package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BiddingOutlineValidatorTest {
    @Test void rejectsCyclesUnknownParentsAndExcessiveDepth() {
        var cycle=assertThrows(BiddingApiException.class,()->BiddingOutlineService.requireAcyclic(Map.of("a","b","b","a")));
        assertEquals("OUTLINE_CYCLE",cycle.code());
        var parent=assertThrows(BiddingApiException.class,()->BiddingOutlineService.requireAcyclic(Map.of("a","missing")));
        assertEquals("OUTLINE_PARENT",parent.code());
        Map<String,String> deep=new java.util.LinkedHashMap<>();
        for(int i=0;i<7;i++) deep.put("n"+i,i==6?null:"n"+(i+1));
        var depth=assertThrows(BiddingApiException.class,()->BiddingOutlineService.requireAcyclic(deep));
        assertEquals("OUTLINE_DEPTH",depth.code());
    }
}
