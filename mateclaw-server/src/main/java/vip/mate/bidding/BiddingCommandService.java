package vip.mate.bidding;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class BiddingCommandService {
    private final BiddingProjectService projects;
    private final BiddingSourceService sources;
    private final ObjectProvider<BiddingEmployeeBindings> employees;
    public BiddingCommandService(BiddingProjectService projects,BiddingSourceService sources,ObjectProvider<BiddingEmployeeBindings> employees) { this.projects=projects; this.sources=sources; this.employees=employees; }
    public ObjectNode execute(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        if(command==null || command.action()==null) return projects.execute(scope,command);
        return switch(command.action()) {
            case "CONFIRM_SOURCE_SET" -> sources.confirmSet(scope,command);
            case "RETRY_SOURCE_READ" -> sources.retryRead(scope,command);
            case "ASSIGN_EMPLOYEES" -> employees.getObject().assign(scope,command);
            default -> projects.execute(scope,command);
        };
    }
}
