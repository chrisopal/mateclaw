package vip.mate.bidding;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

@Service
public class BiddingCommandService {
    private final BiddingProjectService projects;
    private final BiddingRepository repository;
    private final BiddingDependencies dependencies;
    private final BiddingSourceService sources;
    private final ObjectProvider<BiddingEmployeeBindings> employees;
    private final ObjectProvider<BiddingTaskService> tasks;
    private final ObjectProvider<BiddingAnalysisService> analysis;
    private final ObjectProvider<BiddingHandoffService> handoffs;
    private final ObjectProvider<BiddingMaterials> materials;
    private final ObjectProvider<BiddingOutlineService> outlines;
    private final ObjectProvider<BiddingWritingService> writing;
    private final ObjectProvider<BiddingReviewService> reviews;
    private final ObjectProvider<BiddingArtifactService> artifacts;
    private final ObjectProvider<BiddingApprovalService> approvals;
    private final TransactionTemplate commandTransactions;
    public BiddingCommandService(BiddingProjectService projects,BiddingRepository repository,BiddingSourceService sources,BiddingDependencies dependencies,ObjectProvider<BiddingEmployeeBindings> employees,
            ObjectProvider<BiddingTaskService> tasks,ObjectProvider<BiddingAnalysisService> analysis,
            ObjectProvider<BiddingHandoffService> handoffs,ObjectProvider<BiddingMaterials> materials,ObjectProvider<BiddingOutlineService> outlines,ObjectProvider<BiddingWritingService> writing,ObjectProvider<BiddingReviewService> reviews,ObjectProvider<BiddingArtifactService> artifacts,ObjectProvider<BiddingApprovalService> approvals,PlatformTransactionManager transactionManager) {
        this.projects=projects; this.repository=repository; this.sources=sources; this.dependencies=dependencies; this.employees=employees; this.tasks=tasks; this.analysis=analysis; this.handoffs=handoffs; this.materials=materials; this.outlines=outlines; this.writing=writing; this.reviews=reviews;this.artifacts=artifacts;this.approvals=approvals;
        this.commandTransactions=new TransactionTemplate(transactionManager);
    }
    public ObjectNode execute(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        if(command==null || command.action()==null || "UPDATE_PROJECT".equals(command.action()) || "ARCHIVE_PROJECT".equals(command.action()) || "DISPATCH_REVIEW".equals(command.action()))
            return route(scope,command);
        return commandTransactions.execute(status->{
            if(!repository.lockProject(scope.workspaceId(),scope.projectId()))throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
            return route(scope,command);
        });
    }
    private ObjectNode route(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        if(command==null || command.action()==null) return projects.execute(scope,command);
        return switch(command.action()) {
            case "CONFIRM_SOURCE_SET" -> sources.confirmSet(scope,command);
            case "RETRY_SOURCE_READ" -> sources.retryRead(scope,command);
            case "ASSIGN_EMPLOYEES" -> employees.getObject().assign(scope,command);
            case "RETRY_TASK" -> tasks.getObject().retry(scope,command);
            case "CANCEL_TASK" -> tasks.getObject().cancel(scope,command);
            case "DISPATCH_ANALYSIS" -> analysis.getObject().dispatch(scope,command);
            case "EDIT_ANALYSIS_ITEM" -> analysis.getObject().edit(scope,command);
            case "CONFIRM_ANALYSIS" -> analysis.getObject().confirm(scope,command);
            case "RECEIVE_HANDOFF" -> handoffs.getObject().receive(scope,command);
            case "BIND_MATERIAL" -> materials.getObject().bind(scope,command);
            case "DISPATCH_OUTLINE" -> outlines.getObject().dispatch(scope,command);
            case "SAVE_OUTLINE" -> outlines.getObject().save(scope,command);
            case "CONFIRM_OUTLINE" -> outlines.getObject().confirm(scope,command);
            case "DISPATCH_WRITING" -> writing.getObject().dispatch(scope,command);
            case "EDIT_CHAPTER" -> writing.getObject().edit(scope,command);
            case "ADOPT_CHAPTER" -> writing.getObject().adopt(scope,command);
            case "ASSEMBLE_MANUSCRIPT" -> writing.getObject().assemble(scope,command);
            case "DISPATCH_REVIEW" -> reviews.getObject().dispatch(scope,command);
            case "RESOLVE_FINDING" -> reviews.getObject().resolve(scope,command);
            case "RESOLVE_HUMAN_TODO" -> reviews.getObject().resolveHumanTodo(scope,command);
            case "CLASSIFY_HUMAN_TODO" -> reviews.getObject().classifyHumanTodo(scope,command);
            case "REVISE_CHAPTER" -> writing.getObject().revise(scope,command);
            case "DISPATCH_EXPORT" -> artifacts.getObject().dispatch(scope,command);
            case "PREPARE_EXPORT" -> artifacts.getObject().prepareExport(scope,command);
            case "PREPARE_PREVIEW" -> artifacts.getObject().preparePreview(scope,command);
            case "APPROVE_ARTIFACT" -> approvals.getObject().approve(scope,command);
            case "SAVE_FORMAT_REQUIREMENTS" -> artifacts.getObject().saveFormatRequirements(scope,command);
            case "CONFIRM_CHANGE_IMPACT" -> dependencies.reconfirm(scope,command);
            default -> projects.execute(scope,command);
        };
    }
}
