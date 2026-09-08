package vip.mate.semantic.application.extraction;

import static vip.mate.semantic.application.extraction.ExtractionContracts.*;
import static vip.mate.semantic.application.extraction.ExtractionPorts.*;

/** A durable immutable submission intent closes the edit/propose/receipt failure window. */
public final class SuggestionSubmissionService {
    private final AccessPolicyPort access;private final ContextPort context;private final ExtractionTaskRepository repository;private final KnowledgeSubmissionPort submission;
    public SuggestionSubmissionService(AccessPolicyPort access,ContextPort context,ExtractionTaskRepository repository,KnowledgeSubmissionPort submission){this.access=access;this.context=context;this.repository=repository;this.submission=submission;}
    public SubmissionRef submit(Actor actor,String graph,SubmitCommand command){
        ExtractionCoordinator.operation(command.operationId());
        Suggestion s=repository.suggestion(actor,graph,command.suggestionId()).orElseThrow(()->new ExtractionException(404,"NOT_FOUND"));
        Task task=repository.find(actor,graph,s.taskId()).orElseThrow(()->new ExtractionException(404,"NOT_FOUND"));
        access.require(actor,graph,task.source().sourceRef(),Action.SUBMIT);
        context.requireSnapshot(actor,graph,task.source());
        if(!context.ontology(actor,graph).revisionId().equals(task.ontology().revisionId()))throw new ExtractionException(409,"BINDING_CHANGED");
        if(s.editVersion()!=command.expectedVersion())throw new ExtractionException(409,"SUGGESTION_VERSION_CONFLICT");
        var receipt=repository.receipt(actor,graph,s.suggestionId(),s.editVersion());
        if(receipt.isPresent()){
            if(!receipt.get().operationId().equals(command.operationId()))throw new ExtractionException(409,"OPERATION_CONFLICT");
            return receipt.get().submission();
        }
        if(s.status()!=SuggestionStatus.OPEN||s.mappedContent()==null)throw new ExtractionException(422,"OBJECT_SELECTION_REQUIRED");
        if(!s.diagnostics().isEmpty())throw new ExtractionException(422,s.diagnostics().getFirst().code());
        var report=new SuggestionValidator().validate(context.scope(actor,graph),task.ontology(),s.mappedContent(),context.entities(actor,graph),task.source().text(),s.content().quotes());
        if(!report.valid())throw new ExtractionException(422,report.violations().getFirst().code());
        String hash=ExtractionCoordinator.hash(s.mappedContent().toString()+s.content().quotes());
        repository.reserveSubmission(actor,graph,s,command.operationId(),hash);
        String stable="m5-"+ExtractionCoordinator.hash(graph+":"+s.suggestionId()+":"+s.editVersion()+":"+command.operationId());
        SubmissionRef result=submission.submit(actor,graph,s.mappedContent(),task.source(),s.content().quotes(),stable);
        repository.saveReceipt(actor,graph,new Receipt(s.suggestionId(),s.editVersion(),command.operationId(),hash,result));return result;
    }
}
