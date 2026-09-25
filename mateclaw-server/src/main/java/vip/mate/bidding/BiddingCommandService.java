package vip.mate.bidding;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class BiddingCommandService {
    private final BiddingProjectService projects;
    private final BiddingSourceService sources;
    private final ObjectProvider<BiddingEmployeeBindings> employees;
    private final ObjectProvider<BiddingTaskService> tasks;
    public BiddingCommandService(BiddingProjectService projects,BiddingSourceService sources,ObjectProvider<BiddingEmployeeBindings> employees,ObjectProvider<BiddingTaskService> tasks) { this.projects=projects; this.sources=sources; this.employees=employees; this.tasks=tasks; }
    public ObjectNode execute(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        if(command==null || command.action()==null) return projects.execute(scope,command);
        return switch(command.action()) {
            case "CONFIRM_SOURCE_SET" -> sources.confirmSet(scope,command);
            case "RETRY_SOURCE_READ" -> sources.retryRead(scope,command);
            case "ASSIGN_EMPLOYEES" -> employees.getObject().assign(scope,command);
            case "RETRY_TASK" -> tasks.getObject().retry(scope,command);
            case "CANCEL_TASK" -> tasks.getObject().cancel(scope,command);
            default -> projects.execute(scope,command);
        };
    }
}
