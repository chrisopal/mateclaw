package vip.mate.bidding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BiddingProjectService {
    private final BiddingRepository repository;
    private final BiddingAccess access;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public BiddingProjectService(BiddingRepository repository,BiddingAccess access,ObjectMapper json,PlatformTransactionManager transactionManager) {
        this.repository=repository; this.access=access; this.json=json;
        this.transactions=new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public ObjectNode create(BiddingTypes.Scope scope,BiddingTypes.NewProject request) {
        if(request==null) throw BiddingAccess.error(400,"INVALID_REQUEST","Project body is required");
        validateText(request.name(),"name"); validateText(request.lotName(),"lotName");
        if(request.operationId()==null || request.operationId().isBlank() || request.operationId().length()>128)
            throw BiddingAccess.error(400,"INVALID_REQUEST","operationId is required");
        String owner=request.ownerId()==null || request.ownerId().isBlank()?scope.actorId():request.ownerId();
        String digest=digest(Map.of("name",request.name().trim(),"lotName",request.lotName().trim(),"ownerId",owner));
        try {
            return transactions.execute(status -> createInTransaction(scope,request,owner,digest));
        } catch(DuplicateKeyException race) {
            return replayAfterRollback(scope,request.operationId(),digest,"Project creation conflicted with another operation");
        }
    }

    private ObjectNode createInTransaction(BiddingTypes.Scope scope,BiddingTypes.NewProject request,String owner,String digest) {
        access.requireOwner(scope.workspaceId(),owner);
        var previous=repository.findOperation(scope.workspaceId(),scope.actorId(),request.operationId());
        if(previous!=null) return replay(previous,digest);
        String id=UUID.randomUUID().toString(); java.sql.Timestamp now=now();
        repository.insertOperation(scope.workspaceId(),scope.actorId(),request.operationId(),digest,"{}",now);
        ObjectNode project=project(id,scope.workspaceId(),owner,request.name().trim(),request.lotName().trim(),1,"SETUP");
        repository.insertProject(id,scope.workspaceId(),owner,request.name().trim(),request.lotName().trim(),write(project),1,project.path("ref").path("digest").asText(),now);
        repository.updateOperation(scope.workspaceId(),scope.actorId(),request.operationId(),write(project));
        return project;
    }

    public ObjectNode get(BiddingTypes.Scope scope) {
        ObjectNode project=repository.findProject(scope.workspaceId(),scope.projectId());
        if(project==null) throw BiddingAccess.error(404,"NOT_FOUND","Project not found"); return project;
    }

    public BiddingTypes.Page<ObjectNode> list(BiddingTypes.Scope scope,String query,String stage,String ownerId,int page,int pageSize) {
        if(page<1) throw BiddingAccess.error(400,"INVALID_PAGE","page must be at least 1");
        if(pageSize<1) pageSize=20; if(pageSize>100) pageSize=100;
        return repository.list(scope.workspaceId(),query,stage,ownerId,page,pageSize);
    }
    public ObjectNode dashboard(BiddingTypes.Scope scope,String query,String stage,String ownerId) { return repository.dashboard(scope.workspaceId(),query,stage,ownerId); }

    public ObjectNode execute(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        if(command==null || command.operationId()==null || command.operationId().isBlank() || command.expected()==null)
            throw BiddingAccess.error(400,"INVALID_REQUEST","Command operationId and expected ref are required");
        if(command.operationId().length()>128 || command.action()==null || command.expected().kind()==null || command.expected().id()==null
            || command.expected().version()<1 || command.expected().digest()==null || command.expected().digest().isBlank())
            throw BiddingAccess.error(400,"INVALID_REQUEST","Command fields are invalid");
        String digest=digest(Map.of("action",Objects.toString(command.action(),""),"expected",command.expected(),"payload",command.payload()==null?json.createObjectNode():command.payload()));
        try {
            return transactions.execute(status -> executeInTransaction(scope,command,digest));
        } catch(DuplicateKeyException race) {
            return replayAfterRollback(scope,command.operationId(),digest,"Command conflicted with another operation");
        }
    }

    private ObjectNode executeInTransaction(BiddingTypes.Scope scope,BiddingTypes.Command command,String digest) {
        if(!Set.of("UPDATE_PROJECT","ARCHIVE_PROJECT").contains(command.action())) throw BiddingAccess.error(400,"UNKNOWN_ACTION","Unsupported project action");
        ObjectNode current=get(scope);
        access.requireApprover(scope);
        var previous=repository.findOperation(scope.workspaceId(),scope.actorId(),command.operationId());
        if(previous!=null) return replay(previous,digest);
        if("ARCHIVED".equals(current.path("stage").asText()))
            throw BiddingAccess.error(409,"PROJECT_ARCHIVED","Archived projects cannot be changed");
        repository.insertOperation(scope.workspaceId(),scope.actorId(),command.operationId(),digest,"{}",now());
        BiddingTypes.Ref expected=command.expected();
        if(!"project".equals(expected.kind()) || !scope.projectId().equals(expected.id())) throw BiddingAccess.error(409,"VERSION_CONFLICT","Expected project ref does not match target");
        if(expected.version()!=current.path("version").asLong() || !expected.digest().equals(current.path("ref").path("digest").asText()))
            throw BiddingAccess.error(409,"VERSION_CONFLICT","Project has changed; reload before saving");
        access.requireOwner(scope.workspaceId(),current.path("ownerId").asText());
        ObjectNode payload=command.payload()==null?json.createObjectNode():command.payload();
        String name=current.path("name").asText(), lot=current.path("lotName").asText(), owner=current.path("ownerId").asText();
        String stage=current.path("stage").asText();
        if("UPDATE_PROJECT".equals(command.action())) {
            if(payload.has("name")) name=payload.path("name").asText();
            if(payload.has("lotName")) lot=payload.path("lotName").asText();
            if(payload.has("ownerId")) owner=payload.path("ownerId").asText();
            validateText(name,"name"); validateText(lot,"lotName"); access.requireOwner(scope.workspaceId(),owner);
        } else stage="ARCHIVED";
        ObjectNode updated=project(scope.projectId(),scope.workspaceId(),owner,name.trim(),lot.trim(),Math.toIntExact(expected.version()+1),stage);
        // Project edits own the descriptive fields; employee bindings and selected
        // revision pointers are independent selections and must survive the edit.
        updated.set("bindings",current.path("bindings").deepCopy());
        updated.set("selectedRefs",current.path("selectedRefs").deepCopy());
        int count=repository.updateProject(scope.workspaceId(),scope.projectId(),owner,name.trim(),lot.trim(),stage,write(updated),Math.toIntExact(expected.version()),now());
        if(count!=1) throw BiddingAccess.error(409,"VERSION_CONFLICT","Project has changed; reload before saving");
        ObjectNode result=json.createObjectNode(); result.set("ref",updated.path("ref")); result.set("result",updated);
        repository.updateOperation(scope.workspaceId(),scope.actorId(),command.operationId(),write(result));
        return result;
    }

    private ObjectNode replay(BiddingRepository.StoredOperation operation,String digest) {
        if(!operation.digest().equals(digest)) throw BiddingAccess.error(409,"OPERATION_CONFLICT","operationId was already used with a different payload");
        return operation.result();
    }
    private ObjectNode replayAfterRollback(BiddingTypes.Scope scope,String operationId,String digest,String conflictMessage) {
        BiddingRepository.StoredOperation stored=transactions.execute(status -> repository.findOperation(scope.workspaceId(),scope.actorId(),operationId));
        if(stored!=null) return replay(stored,digest);
        throw BiddingAccess.error(409,"OPERATION_CONFLICT",conflictMessage);
    }
    private ObjectNode project(String id,String workspace,String owner,String name,String lot,int version,String stage) {
        ObjectNode body=json.createObjectNode(); body.put("id",id); body.put("workspaceId",workspace); body.put("name",name);
        body.put("lotName",lot); body.put("ownerId",owner); body.put("version",version); body.put("stage",stage);
        body.set("bindings",json.createObjectNode()); body.set("selectedRefs",json.createObjectNode());
        ObjectNode ref=body.putObject("ref"); ref.put("kind","project"); ref.put("id",id); ref.put("version",version); ref.put("digest",digest(Map.of("workspaceId",workspace,"name",name,"lotName",lot,"ownerId",owner,"version",version,"stage",stage)));
        return body;
    }
    private static void validateText(String value,String field) {
        if(value==null || value.isBlank() || value.trim().codePointCount(0,value.trim().length())>200) throw BiddingAccess.error(400,"INVALID_"+field.toUpperCase(Locale.ROOT),field+" must contain 1 to 200 characters");
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    private static java.sql.Timestamp now() { return java.sql.Timestamp.from(Instant.now()); }
    private String digest(Object value) {
        try { byte[] bytes=json.writeValueAsBytes(canonical(json.valueToTree(value))); return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private com.fasterxml.jackson.databind.JsonNode canonical(com.fasterxml.jackson.databind.JsonNode node) {
        if(node.isObject()) {
            ObjectNode sorted=json.createObjectNode();
            java.util.TreeSet<String> names=new java.util.TreeSet<>(); node.fieldNames().forEachRemaining(names::add);
            for(String name:names) sorted.set(name,canonical(node.get(name)));
            return sorted;
        }
        if(node.isArray()) { var array=json.createArrayNode(); node.forEach(value->array.add(canonical(value))); return array; }
        return node;
    }
}
