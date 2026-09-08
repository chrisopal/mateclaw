package vip.mate.semantic.application.extraction;

import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import static vip.mate.semantic.application.extraction.ExtractionPorts.*;

/** Fixed-input application lifecycle; model calls occur outside repository transactions. */
public final class ExtractionCoordinator {
    private final SourceContentPort sources;
    private final AccessPolicyPort access;
    private final ContextPort context;
    private final ExtractionTaskRepository repository;
    private final ExtractionModelPort model;
    private final Clock clock;
    private final BooleanSupplier enabled;
    public ExtractionCoordinator(SourceContentPort sources,AccessPolicyPort access,ContextPort context,
            ExtractionTaskRepository repository,ExtractionModelPort model,Clock clock,BooleanSupplier enabled){
        this.sources=sources;this.access=access;this.context=context;this.repository=repository;this.model=model;this.clock=clock;this.enabled=enabled;
    }
    public TaskRef start(Actor actor,StartCommand command){
        if(!enabled.getAsBoolean())throw new ExtractionException(409,"EXTRACTION_DISABLED");
        operation(command.operationId());access.require(actor,command.graphId(),command.sourceRef(),Action.START);
        var ontology=context.ontology(actor,command.graphId());var config=context.configuration(actor,command.graphId(),command.modelConfigId());
        var source=sources.read(actor,command.graphId(),command.sourceRef());try{new SourceChunker().split(source.text());}catch(IllegalArgumentException e){throw new ExtractionException(422,"SOURCE_TOO_LARGE");}
        String hash=hash(List.of(command.graphId(),command.sourceRef(),source.snapshotId(),source.contentHash(),ontology.revisionId().value(),config.configurationHash()).toString());
        Instant now=clock.instant();
        return ref(repository.insertOrReplay(new Task(UUID.randomUUID().toString(),actor,command.graphId(),command.operationId(),hash,source,ontology,hash(ontology.definition().toString()),"semantic-m5-v1",config,TaskStatus.QUEUED,1,false,now,now)));
    }
    public TaskRef cancel(Actor actor,String graph,String id){Task t=require(actor,graph,id);access.require(actor,graph,t.source().sourceRef(),Action.CANCEL);
        if(t.status()!=TaskStatus.QUEUED&&t.status()!=TaskStatus.RUNNING)return ref(t);
        Task next=state(t,TaskStatus.CANCELLED,true);if(!repository.update(next,t.version()))throw new ExtractionException(409,"TASK_VERSION_CONFLICT");return ref(next);
    }
    public TaskRef retry(Actor actor,String graph,String id,String operationId){
        operation(operationId);if(!enabled.getAsBoolean())throw new ExtractionException(409,"EXTRACTION_DISABLED");
        Task t=require(actor,graph,id);access.require(actor,graph,t.source().sourceRef(),Action.RETRY);
        if(!context.ontology(actor,graph).revisionId().equals(t.ontology().revisionId()))throw new ExtractionException(409,"BINDING_CHANGED");
        context.requireSnapshot(actor,graph,t.source());
        if(!context.configuration(actor,graph,t.configuration().modelConfigId()).configurationHash().equals(t.configuration().configurationHash()))throw new ExtractionException(409,"MODEL_CONFIGURATION_CHANGED");
        return ref(repository.requeue(actor,graph,id,operationId,clock.instant()));
    }
    public boolean runNext(String workerId){
        if(!enabled.getAsBoolean())return false;
        var claimed=repository.claim(workerId,clock.instant(),clock.instant().plusSeconds(60),2,4);if(claimed.isEmpty())return false;
        execute(claimed.get());return true;
    }
    public void execute(Attempt attempt){
        Task task=repository.findClaimed(attempt.lease());long input=0,output=0;
        try{
            List<RawSuggestion> all=new ArrayList<>();List<String> explanations=new ArrayList<>();int completedChunks=0;
            for(Chunk chunk:new SourceChunker().split(task.source().text())){
                requireLive(task,attempt);
                ModelResult result=null;
                for(int retry=0;retry<3;retry++){
                    try{if(clock.instant().plusSeconds(90).isAfter(attempt.startedAt().plusSeconds(600)))throw new ExtractionException(422,"TASK_BUDGET_EXCEEDED");result=model.extract(new ModelRequest(task.ontology(),chunk,task.configuration(),task.promptVersion()));break;}
                    catch(ExtractionException e){if(retry==2||!Set.of("TIMEOUT","RATE_LIMIT","TEMPORARY_UNAVAILABLE").contains(e.code()))throw e;
                        long delay=retry==0?2000:8000;if(clock.instant().plusMillis(delay+90000).isAfter(attempt.startedAt().plusSeconds(600)))throw new ExtractionException(422,"TASK_BUDGET_EXCEEDED");
                        Thread.sleep(delay);requireLive(task,attempt);
                    }
                }
                requireLive(task,attempt);input+=result.usage().inputTokens();output+=result.usage().outputTokens();
                for(RawSuggestion raw:result.suggestions()){
                    // Invalid block offsets remain diagnosable and cannot point into a different chunk.
                    var quotes=raw.quotes().stream().map(q->locate(chunk,q)).toList();
                    all.add(new RawSuggestion(raw.subject(),raw.predicate(),raw.value(),raw.target(),raw.validity(),quotes));
                }
                if(all.size()>SuggestionValidator.MAX_SUGGESTIONS)throw new ExtractionException(422,"MODEL_OUTPUT_LIMIT");
                if(result.explanation()!=null&&!result.explanation().isBlank())explanations.add(result.explanation());
                if(!repository.progress(attempt.lease(),++completedChunks,clock.instant()))throw new ExtractionException(409,"LEASE_LOST");
            }
            // Normalize duplicate content within this attempt and preserve distinct exact references.
            Map<String,RawSuggestion> unique=new LinkedHashMap<>();
            for(RawSuggestion raw:all){String key=List.of(raw.subject(),raw.predicate(),Objects.toString(raw.value()),Objects.toString(raw.target()),raw.validity()).toString();
                RawSuggestion old=unique.get(key);if(old==null)unique.put(key,raw);else{var quotes=new LinkedHashSet<>(old.quotes());quotes.addAll(raw.quotes());unique.put(key,new RawSuggestion(raw.subject(),raw.predicate(),raw.value(),raw.target(),raw.validity(),List.copyOf(quotes)));}}
            List<Suggestion> suggestions=new ArrayList<>();var validator=new SuggestionValidator();
            for(RawSuggestion raw:unique.values())suggestions.add(new Suggestion(UUID.randomUUID().toString(),task.taskId(),attempt.attemptId(),raw,null,validator.validateRaw(context.scope(task.actor(),task.graphId()),task.ontology(),raw,task.source().text()).violations(),1,SuggestionStatus.OPEN));
            requireLive(task,attempt);repository.complete(attempt.lease(),finished(attempt,input,output,null),suggestions,clock.instant(),String.join("\n",explanations));
        }catch(Exception e){
            String code=e instanceof ExtractionException x?x.code():"EXTRACTION_FAILED";
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            repository.fail(attempt.lease(),finished(attempt,input,output,new Failure(category(code),code,UUID.randomUUID().toString())),clock.instant());
        }
    }
    private void requireLive(Task t,Attempt a){
        if(Thread.currentThread().isInterrupted())throw new ExtractionException(409,"LEASE_LOST");
        if(!enabled.getAsBoolean())throw new ExtractionException(409,"EXTRACTION_DISABLED");
        if(clock.instant().isAfter(a.startedAt().plusSeconds(600)))throw new ExtractionException(422,"TASK_BUDGET_EXCEEDED");
        access.require(t.actor(),t.graphId(),t.source().sourceRef(),Action.START);
        context.requireSnapshot(t.actor(),t.graphId(),t.source());
        if(!repository.heartbeat(a.lease(),clock.instant().plusSeconds(60)))throw new ExtractionException(409,"LEASE_LOST");
    }
    /** Exact unique substring fallback only; repeated or absent quotations remain diagnostics. */
    static Quote locate(Chunk chunk,Quote quote){
        Quote local=quote;
        if(!new SuggestionValidator().matches(chunk.text(),quote)){
            String exact=quote.exactQuote();int start=exact==null||exact.isEmpty()?-1:chunk.text().indexOf(exact);
            if(start<0||chunk.text().indexOf(exact,start+1)>=0)return new Quote(-1,-1,exact);
            int begin=chunk.text().codePointCount(0,start);local=new Quote(begin,begin+exact.codePointCount(0,exact.length()),exact);
        }
        return new Quote(local.startCodePoint()+chunk.startCodePoint(),local.endCodePoint()+chunk.startCodePoint(),local.exactQuote());
    }
    private static ErrorCategory category(String code){
        if(code.equals("TIMEOUT"))return ErrorCategory.TIMEOUT;
        if(code.equals("RATE_LIMIT"))return ErrorCategory.RATE_LIMIT;
        if(code.equals("TEMPORARY_UNAVAILABLE"))return ErrorCategory.TEMPORARY_UNAVAILABLE;
        if(code.contains("AUTHORIZATION")||code.equals("FORBIDDEN"))return ErrorCategory.AUTHORIZATION;
        if(code.contains("FORMAT"))return ErrorCategory.FORMAT;
        if(code.contains("LIMIT")||code.contains("BUDGET"))return ErrorCategory.LIMIT;
        if(code.contains("CANCEL")||code.equals("LEASE_LOST"))return ErrorCategory.CANCELLED;
        return ErrorCategory.RULE;
    }
    private Attempt finished(Attempt a,long in,long out,Failure failure){return new Attempt(a.attemptId(),a.taskId(),a.number(),a.lease(),a.configuration(),new Usage(in,out),a.startedAt(),clock.instant(),failure);}
    private Task require(Actor a,String g,String id){return repository.find(a,g,id).orElseThrow(()->new ExtractionException(404,"NOT_FOUND"));}
    private Task state(Task t,TaskStatus status,boolean cancel){return new Task(t.taskId(),t.actor(),t.graphId(),t.operationId(),t.requestHash(),t.source(),t.ontology(),t.definitionHash(),t.promptVersion(),t.configuration(),status,t.version()+1,cancel,t.createdAt(),clock.instant());}
    private static TaskRef ref(Task t){return new TaskRef(t.taskId(),t.status().name(),t.version());}
    public static void operation(String value){if(value==null||value.isBlank()||value.length()>128)throw new ExtractionException(400,"OPERATION_REQUIRED");}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
