package vip.mate.bidding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class BiddingSourceService {
    private static final long MAX_FILE = 25L * 1024 * 1024, MAX_PROJECT = 100L * 1024 * 1024;
    private final BiddingRepository repository;
    private final BiddingAccess access;
    private final BiddingSourceReader reader;
    private final BiddingDependencies dependencies;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final BiddingProperties properties;

    public BiddingSourceService(BiddingRepository repository,BiddingAccess access,BiddingSourceReader reader,BiddingDependencies dependencies,
        ObjectMapper json,PlatformTransactionManager transactionManager,BiddingProperties properties) {
        this.repository=repository; this.access=access; this.reader=reader; this.dependencies=dependencies; this.json=json; this.properties=properties;
        this.transactions=new TransactionTemplate(transactionManager);
    }

    public ObjectNode upload(BiddingTypes.Scope scope,String operationId,String sourceKind,BiddingTypes.Ref supersedes,byte[] bytes,String filename) {
        access.requireActor(scope,scope.actorId());
        if (repository.findProject(scope.workspaceId(),scope.projectId())==null) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        if (bytes==null || bytes.length==0) throw BiddingAccess.error(400,"SOURCE_EMPTY","Source file is empty");
        if (bytes.length>MAX_FILE) throw new BiddingApiException(413,"SOURCE_FILE_LIMIT","文件超过25MiB上限");
        if (filename==null || filename.isBlank()) throw BiddingAccess.error(400,"INVALID_FILENAME","Filename is required");
        filename=filename.replace('\\','/'); filename=filename.substring(filename.lastIndexOf('/')+1);
        if(filename.isBlank() || filename.length()>512 || filename.chars().anyMatch(Character::isISOControl)) throw BiddingAccess.error(400,"INVALID_FILENAME","Filename is invalid");
        final String storedFilename=filename;
        if (sourceKind==null || !sourceKind.matches("[A-Z][A-Z0-9_]{0,63}")) throw BiddingAccess.error(400,"INVALID_SOURCE_KIND","Invalid source kind");
        if (operationId==null || operationId.isBlank() || operationId.length()>128) throw BiddingAccess.error(400,"INVALID_REQUEST","operationId is required");
        String contentDigest=sha256(bytes);
        String requestDigest=sha256((sourceKind+"\n"+storedFilename+"\n"+contentDigest+"\n"+Objects.toString(supersedes, "")).getBytes(StandardCharsets.UTF_8));
        try { return transactions.execute(tx -> uploadInTransaction(scope,operationId,sourceKind,supersedes,bytes,storedFilename,contentDigest,requestDigest)); }
        catch(org.springframework.dao.DuplicateKeyException race) {
            var stored=repository.findOperation(scope.workspaceId(),scope.actorId(),operationId);
            if(stored!=null && stored.digest().equals(requestDigest)) return stored.result();
            throw BiddingAccess.error(409,"OPERATION_CONFLICT","operationId was already used with a different source");
        }
    }

    private ObjectNode uploadInTransaction(BiddingTypes.Scope scope,String operationId,String kind,BiddingTypes.Ref supersedes,byte[] bytes,String filename,String digest,String requestDigest) {
        if(!repository.lockProject(scope.workspaceId(),scope.projectId())) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        var previous=repository.findOperation(scope.workspaceId(),scope.actorId(),operationId);
        if(previous!=null) {
            if(!previous.digest().equals(requestDigest)) throw BiddingAccess.error(409,"OPERATION_CONFLICT","operationId was already used with a different source");
            return previous.result();
        }
        String sourceId; long version;
        if(supersedes==null) { sourceId=UUID.randomUUID().toString(); version=1; }
        else {
            if(!"source".equals(supersedes.kind())) throw BiddingAccess.error(400,"INVALID_SOURCE_REF","supersedesRef must reference a source");
            var old=repository.source(scope.workspaceId(),scope.projectId(),supersedes.id(),supersedes.version());
            if(old==null || !old.digest().equals(supersedes.digest())) throw BiddingAccess.error(404,"NOT_FOUND","Superseded source not found");
            sourceId=supersedes.id(); version=repository.maxSourceVersion(scope,sourceId)+1;
        }
        String rowId=UUID.randomUUID().toString(); java.sql.Timestamp now=now();
        ObjectNode ref=sourceRef(sourceId,version,digest);
        ObjectNode result=json.createObjectNode(); result.put("id",sourceId); result.set("ref",ref); result.put("filename",filename);
        result.put("bytes",bytes.length); result.put("sha256",digest); result.put("readStatus","PENDING"); result.put("sourceKind",kind);
        repository.insertOperation(scope.workspaceId(),scope.actorId(),operationId,requestDigest,"{}",now);
        repository.insertSource(rowId,scope.workspaceId(),scope.projectId(),sourceId,version,kind,digest,bytes,filename,now());
        repository.insertSourceHead(scope,sourceId,version,write(ref));
        repository.updateOperation(scope.workspaceId(),scope.actorId(),operationId,write(result));
        dependencies.invalidate(scope,new BiddingTypes.Ref("source",sourceId,version,digest));
        return result;
    }

    public BiddingTypes.ReadBlock evidence(BiddingTypes.Scope scope,String sourceId,long version,String blockId) {
        requireReader(scope);
        if(repository.findProject(scope.workspaceId(),scope.projectId())==null) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        var source=repository.source(scope.workspaceId(),scope.projectId(),sourceId,version);
        if(source==null) throw BiddingAccess.error(404,"NOT_FOUND","Evidence not found");
        var blocks=parseBlocks(source.blocks());
        return blocks.stream().filter(block -> block.id().equals(blockId)).findFirst().orElseThrow(()->BiddingAccess.error(404,"NOT_FOUND","Evidence not found"));
    }

    public byte[] content(BiddingTypes.Scope scope,String sourceId,long version) {
        requireReader(scope);
        if(repository.findProject(scope.workspaceId(),scope.projectId())==null) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        var source=repository.source(scope.workspaceId(),scope.projectId(),sourceId,version);
        if(source==null) throw BiddingAccess.error(404,"NOT_FOUND","Source not found"); return source.content().clone();
    }
    public SourceContent readContent(BiddingTypes.Scope scope,String sourceId,long version) {
        requireReader(scope);
        if(repository.findProject(scope.workspaceId(),scope.projectId())==null) throw BiddingAccess.error(404,"NOT_FOUND","Source not found");
        var source=repository.source(scope.workspaceId(),scope.projectId(),sourceId,version);
        if(source==null) throw BiddingAccess.error(404,"NOT_FOUND","Source not found"); return new SourceContent(source.content().clone(),source.filename());
    }

    public List<ObjectNode> list(BiddingTypes.Scope scope) {
        requireReader(scope);
        if(repository.findProject(scope.workspaceId(),scope.projectId())==null) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        return repository.sources(scope.workspaceId(),scope.projectId()).stream().map(source -> {
            ObjectNode item=json.createObjectNode(); item.put("id",source.sourceId()); item.put("version",source.version()); item.put("kind",source.kind());
            item.put("filename",source.filename()); item.put("sha256",source.digest()); item.put("readStatus",source.status());
            item.set("problems",parseArray(source.problems())); item.set("blocks",parseArray(source.blocks())); return item;
        }).toList();
    }

    public ObjectNode sourceSetHead(BiddingTypes.Scope scope) {
        requireReader(scope);
        if(repository.findProject(scope.workspaceId(),scope.projectId())==null) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        ObjectNode result=json.createObjectNode();
        var head=repository.sourceSetHead(scope);
        if(head==null) result.putNull("ref"); else {
            ObjectNode ref=json.createObjectNode(); ref.put("kind",head.kind()); ref.put("id",head.id()); ref.put("version",head.version()); ref.put("digest",head.digest());
            result.set("ref",ref);
        }
        return result;
    }

    /** Claims at most two rows atomically, then parses outside each claim transaction. */
    public int readPending(int limit) {
        int max=Math.min(2,Math.max(0,limit)), completed=0;
        for(var source:repository.pendingSources(max)) {
            String token=UUID.randomUUID().toString();
            int claimed=transactions.execute(tx -> repository.claimSource(source.rowId(),token,now()));
            if(claimed!=1) continue;
            String status, quality; List<String> problems; List<BiddingTypes.ReadBlock> blocks;
            try {
                var extraction=reader.read(source.content(),source.filename()); blocks=extraction.blocks(); problems=extraction.problems();
                status=extraction.complete()?"READY":"NEEDS_REVIEW"; quality=status;
            } catch(BiddingApiException e) {
                blocks=List.of(); problems=List.of(e.code()); status="FAILED"; quality="FAILED";
            } catch(Exception e) {
                blocks=List.of(); problems=List.of("SOURCE_READ_FAILED"); status="FAILED"; quality="FAILED";
            }
            final String finalStatus=status, finalQuality=quality, blocksJson=write(blocks), problemsJson=write(problems), rowId=source.rowId();
            transactions.execute(tx -> repository.finishSource(rowId,token,finalStatus,blocksJson,finalQuality,problemsJson,now())); completed++;
        }
        return completed;
    }

    /** Production poller; both module and worker switches must be enabled. */
    @Scheduled(fixedDelayString="${mateclaw.bidding.source-read-delay-ms:1000}")
    public void scheduledReadPending() {
        if(properties.isEnabled() && properties.isSchedulerEnabled()) readPending(2);
    }

    public int recoverReading() { return repository.recoverReadingSources(now()); }
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedReaders() { if(properties.isEnabled() && properties.isSchedulerEnabled()) recoverReading(); }

    @Transactional
    public ObjectNode retryRead(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        access.requireActor(scope,scope.actorId());
        validateExpected(scope,command);
        String digest=commandDigest(command);
        var previous=repository.findOperation(scope.workspaceId(),scope.actorId(),command.operationId());
        if(previous!=null) return replay(previous,digest);
        var ref=sourceRefFrom(command.payload().path("sourceRef"));
        var row=repository.source(scope.workspaceId(),scope.projectId(),ref.id(),ref.version());
        if(row==null || !row.digest().equals(ref.digest())) throw BiddingAccess.error(404,"NOT_FOUND","Source not found");
        if(repository.sourceWasConfirmed(scope,ref)) throw BiddingAccess.error(409,"SOURCE_ALREADY_CONFIRMED","A source referenced by a confirmed source set cannot be reread");
        repository.insertOperation(scope.workspaceId(),scope.actorId(),command.operationId(),digest,"{}",now());
        if(repository.retrySource(scope.workspaceId(),scope.projectId(),ref.id(),ref.version())!=1) throw BiddingAccess.error(409,"SOURCE_NOT_RETRYABLE","Only failed or review-needed sources can be retried");
        ObjectNode result=json.createObjectNode(); result.set("sourceRef",json.valueToTree(ref)); result.put("readStatus","PENDING");
        repository.updateOperation(scope.workspaceId(),scope.actorId(),command.operationId(),write(result)); return result;
    }

    @Transactional
    public ObjectNode confirmSet(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        access.requireActor(scope,scope.actorId());
        access.requireApprover(scope);
        validateExpected(scope,command);
        String commandDigest=commandDigest(command);
        var previous=repository.findOperation(scope.workspaceId(),scope.actorId(),command.operationId());
        if(previous!=null) return replay(previous,commandDigest);
        JsonNode refsNode=command.payload().path("sourceRefs"), exclusionsNode=command.payload().path("exclusions");
        if(!refsNode.isArray() || refsNode.isEmpty() || !exclusionsNode.isArray()) throw BiddingAccess.error(422,"SOURCE_SET_INCOMPLETE","sourceRefs and exclusions are required");
        if(!command.payload().has("expectedSourceSetRef")) throw BiddingAccess.error(400,"SOURCE_SET_HEAD_REQUIRED","expectedSourceSetRef must be supplied, using null when no set is current");
        JsonNode expectedHeadNode=command.payload().path("expectedSourceSetRef");
        BiddingTypes.Ref expectedHead=expectedHeadNode.isNull()?null:json.convertValue(expectedHeadNode,BiddingTypes.Ref.class);
        if(expectedHead!=null && (!"sourceSet".equals(expectedHead.kind()) || !"current".equals(expectedHead.id()) || expectedHead.version()<1 || expectedHead.digest()==null))
            throw BiddingAccess.error(400,"SOURCE_SET_HEAD_INVALID","expectedSourceSetRef is invalid");
        var currentHead=repository.sourceSetHead(scope);
        if(!Objects.equals(currentHead,expectedHead)) throw BiddingAccess.error(409,"SOURCE_SET_HEAD_CONFLICT","The confirmed source set changed; reload before confirming");
        if(!exclusionsNode.isEmpty()) access.requireApprover(scope);
        List<BiddingTypes.Ref> refs=new ArrayList<>(); Set<String> refKeys=new HashSet<>();
        refsNode.forEach(node -> { var ref=sourceRefFrom(node); String key=ref.id(); if(!refKeys.add(key)) throw BiddingAccess.error(422,"SOURCE_SET_DUPLICATE","Select only one version of each source"); refs.add(ref); });
        ensureProjectCapacity(effectiveSourceBytes(scope,refs),0);
        Set<String> excluded=new HashSet<>(); ObjectNode payload=json.createObjectNode(); payload.set("sourceRefs",json.valueToTree(refs));
        var exclusions=json.createArrayNode();
        for(JsonNode item:exclusionsNode) {
            var sourceRef=sourceRefFrom(item.path("sourceRef")); String blockId=item.path("blockId").asText(), reason=item.path("reason").asText();
            if(refs.stream().noneMatch(ref->ref.equals(sourceRef))) throw BiddingAccess.error(422,"SOURCE_EXCLUSION_NOT_ALLOWED","An excluded block must belong to a selected source");
            if(blockId.isBlank() || reason.isBlank() || reason.codePointCount(0,reason.length())>500) throw BiddingAccess.error(422,"EXCLUSION_REASON_REQUIRED","Each excluded page or block needs a reason");
            var source=repository.source(scope.workspaceId(),scope.projectId(),sourceRef.id(),sourceRef.version());
            if(source==null || !source.digest().equals(sourceRef.digest())) throw BiddingAccess.error(404,"NOT_FOUND","Source not found");
            var block=parseBlocks(source.blocks()).stream().filter(b -> b.id().equals(blockId)).findFirst().orElseThrow(()->BiddingAccess.error(404,"NOT_FOUND","Evidence not found"));
            if(!"EMPTY_PAGE".equals(block.kind())) throw BiddingAccess.error(422,"SOURCE_EXCLUSION_NOT_ALLOWED","Unreadable substantive content cannot be excluded");
            if(!excluded.add(source.sourceId()+":"+source.version()+":"+blockId)) throw BiddingAccess.error(422,"SOURCE_EXCLUSION_DUPLICATE","A block can be excluded only once");
            ObjectNode exclusion=json.createObjectNode(); exclusion.set("sourceRef",json.valueToTree(sourceRef)); exclusion.put("blockId",blockId); exclusion.put("reason",reason.trim()); exclusions.add(exclusion);
        }
        var coverage=json.createArrayNode();
        for(var ref:refs) {
            var source=repository.source(scope.workspaceId(),scope.projectId(),ref.id(),ref.version());
            if(source==null || !source.digest().equals(ref.digest())) throw BiddingAccess.error(404,"NOT_FOUND","Source not found");
            var problems=parseArray(source.problems());
            if("FAILED".equals(source.status()) || "PENDING".equals(source.status()) || "READING".equals(source.status())) throw BiddingAccess.error(422,"SOURCE_NOT_READY","Source reading is incomplete");
            for(JsonNode problem:problems) if(problem.asText().startsWith("EMPTY_PDF_PAGE:")) {
                int page=Integer.parseInt(problem.asText().substring("EMPTY_PDF_PAGE:".length()));
                var pageBlock=parseBlocks(source.blocks()).stream().filter(b -> Objects.equals(b.pdfPage(),page) && "EMPTY_PAGE".equals(b.kind())).findFirst().orElse(null);
                if(pageBlock==null || !excluded.contains(source.sourceId()+":"+source.version()+":"+pageBlock.id())) throw BiddingAccess.error(422,"SOURCE_SET_INCOMPLETE","Every unreadable page needs an explicit permitted exclusion");
            } else throw BiddingAccess.error(422,"SOURCE_SET_INCOMPLETE","Substantive unreadable source content cannot be excluded");
            for(var block:parseBlocks(source.blocks())) {
                ObjectNode item=json.createObjectNode(); item.set("sourceRef",json.valueToTree(ref)); item.put("blockId",block.id()); item.put("locator",block.locator());
                boolean isExcluded=excluded.contains(source.sourceId()+":"+source.version()+":"+block.id());
                item.put("disposition",isExcluded?"EXCLUDED":"IN_SCOPE");
                if(isExcluded) exclusionsNode.forEach(exclusion->{ if(block.id().equals(exclusion.path("blockId").asText())) item.put("reason",exclusion.path("reason").asText()); });
                coverage.add(item);
            }
        }
        payload.set("exclusions",exclusions);
        payload.set("coverage",coverage);
        String canonical=write(payload), digest=sha256(canonical.getBytes(StandardCharsets.UTF_8));
        String id=UUID.randomUUID().toString(); long version=repository.maxRevisionVersion(scope,"sourceSet","current")+1; java.sql.Timestamp now=now();
        repository.insertOperation(scope.workspaceId(),scope.actorId(),command.operationId(),commandDigest,"{}",now);
        repository.insertRevision(id,scope.workspaceId(),scope.projectId(),"sourceSet","current",version,canonical,write(refs),"CONFIRMED",digest,now);
        ObjectNode sourceSetRef=json.createObjectNode(); sourceSetRef.put("kind","sourceSet"); sourceSetRef.put("id","current"); sourceSetRef.put("version",version); sourceSetRef.put("digest",digest);
        if(!repository.advanceSourceSetHead(scope,expectedHead,json.convertValue(sourceSetRef,BiddingTypes.Ref.class),write(sourceSetRef)))
            throw BiddingAccess.error(409,"SOURCE_SET_HEAD_CONFLICT","The confirmed source set changed; reload before confirming");
        for(var ref:refs) dependencies.validate(scope,List.of(ref));
        repository.invalidateSourceSet(scope);
        ObjectNode result=json.createObjectNode(); result.set("ref",sourceSetRef); result.set("sourceSet",payload);
        repository.updateOperation(scope.workspaceId(),scope.actorId(),command.operationId(),write(result)); return result;
    }

    private void validateExpected(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        if(command==null || command.operationId()==null || command.operationId().isBlank() || command.operationId().length()>128 || command.expected()==null || command.payload()==null || command.action()==null || command.action().isBlank())
            throw BiddingAccess.error(400,"INVALID_REQUEST","Command operationId, expected ref and payload are required");
        if(!repository.lockProject(scope.workspaceId(),scope.projectId())) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        var project=repository.findProject(scope.workspaceId(),scope.projectId());
        if(project==null) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        var expected=command.expected();
        if(!"project".equals(expected.kind()) || !scope.projectId().equals(expected.id()) || expected.version()!=project.path("version").asLong()
            || !expected.digest().equals(project.path("ref").path("digest").asText())) throw BiddingAccess.error(409,"VERSION_CONFLICT","Project has changed; reload before saving");
    }
    private String commandDigest(BiddingTypes.Command command) { return sha256(write(canonical(json.valueToTree(Map.of("action",command.action(),"expected",command.expected(),"payload",command.payload())))).getBytes(StandardCharsets.UTF_8)); }
    private JsonNode canonical(JsonNode node) {
        if(node.isObject()) { ObjectNode sorted=json.createObjectNode(); var names=new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add); for(String name:names) sorted.set(name,canonical(node.get(name))); return sorted; }
        if(node.isArray()) { var array=json.createArrayNode(); node.forEach(value->array.add(canonical(value))); return array; }
        return node;
    }
    private ObjectNode replay(BiddingRepository.StoredOperation operation,String digest) {
        if(!operation.digest().equals(digest)) throw BiddingAccess.error(409,"OPERATION_CONFLICT","operationId was already used with a different command");
        return operation.result();
    }
    private List<BiddingTypes.ReadBlock> parseBlocks(String text) { try { return json.readerForListOf(BiddingTypes.ReadBlock.class).readValue(text); } catch(Exception e) { throw new IllegalStateException(e); } }
    private JsonNode parseArray(String text) { try { return json.readTree(text); } catch(Exception e) { throw new IllegalStateException(e); } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    private ObjectNode sourceRef(String id,long version,String digest) { ObjectNode ref=json.createObjectNode(); ref.put("kind","source"); ref.put("id",id); ref.put("version",version); ref.put("digest",digest); return ref; }
    private BiddingTypes.Ref sourceRefFrom(JsonNode node) {
        var ref=json.convertValue(node,BiddingTypes.Ref.class);
        if(ref==null || !"source".equals(ref.kind()) || ref.id()==null || ref.version()<1 || ref.digest()==null) throw BiddingAccess.error(400,"INVALID_SOURCE_REF","Invalid source reference");
        return ref;
    }
    private void requireReader(BiddingTypes.Scope scope) {
        String actor=access.require(scope.workspaceId(),"viewer");
        if(!actor.equals(scope.actorId())) throw BiddingAccess.error(404,"NOT_FOUND","Source not found");
    }
    private static java.sql.Timestamp now() { return java.sql.Timestamp.from(Instant.now()); }
    public record SourceContent(byte[] bytes,String filename) {}
    static void ensureProjectCapacity(long activeBytes,long incomingBytes) {
        if(activeBytes<0 || incomingBytes<0 || activeBytes>MAX_PROJECT-incomingBytes) throw new BiddingApiException(413,"PROJECT_SOURCE_LIMIT","项目来源总量超过100MiB上限");
    }
    long effectiveSourceBytes(BiddingTypes.Scope scope,List<BiddingTypes.Ref> refs) {
        if(refs==null) throw BiddingAccess.error(422,"SOURCE_SET_INCOMPLETE","Selected source references are required");
        Set<String> ids=new HashSet<>(); long total=0;
        for(var ref:refs) {
            if(ref==null || !ids.add(ref.id())) throw BiddingAccess.error(422,"SOURCE_SET_DUPLICATE","Select only one version of each source");
            var source=repository.source(scope.workspaceId(),scope.projectId(),ref.id(),ref.version());
            if(source==null || !source.digest().equals(ref.digest())) throw BiddingAccess.error(404,"NOT_FOUND","Source not found");
            total=Math.addExact(total,source.content().length);
        }
        return total;
    }
    private static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(Exception e) { throw new IllegalStateException(e); } }
}
