package vip.mate.bidding;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

@Service
public class BiddingCommandService {
    private final BiddingProjectService projects;
    public BiddingCommandService(BiddingProjectService projects) { this.projects=projects; }
    public ObjectNode execute(BiddingTypes.Scope scope,BiddingTypes.Command command) { return projects.execute(scope,command); }
}
