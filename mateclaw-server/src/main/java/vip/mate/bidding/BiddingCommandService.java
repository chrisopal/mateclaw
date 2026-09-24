package vip.mate.bidding;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

@Service
public class BiddingCommandService {
    private final BiddingProjectService projects;
    private final BiddingSourceService sources;
    public BiddingCommandService(BiddingProjectService projects,BiddingSourceService sources) { this.projects=projects; this.sources=sources; }
    public ObjectNode execute(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        if(command==null || command.action()==null) return projects.execute(scope,command);
        return switch(command.action()) {
            case "CONFIRM_SOURCE_SET" -> sources.confirmSet(scope,command);
            case "RETRY_SOURCE_READ" -> sources.retryRead(scope,command);
            default -> projects.execute(scope,command);
        };
    }
}
