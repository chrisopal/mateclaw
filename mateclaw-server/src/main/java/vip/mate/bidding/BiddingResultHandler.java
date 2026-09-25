package vip.mate.bidding;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Skill-specific validation and candidate revision creation for accepted task output. */
public interface BiddingResultHandler {
    Set<String> skillIds();
    BiddingTypes.Ref accept(BiddingTypes.Claim claim,ObjectNode payload);
}
