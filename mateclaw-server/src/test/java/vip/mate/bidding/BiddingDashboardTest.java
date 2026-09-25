package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

class BiddingDashboardTest extends BiddingHttpFixture {
    @Test void dashboardUsesTheSameWorkspaceNameStageAndOwnerFiltersAsTheProjectList() throws Exception {
        JsonNode alpha=api("POST","/projects","member",workspace,Map.of("operationId","dashboard-alpha-"+System.nanoTime(),"name","Alpha Tender","lotName","Lot A"),200);
        Long workspaceOwner=jdbc.queryForObject("SELECT owner_id FROM mate_workspace WHERE id=?",Long.class,Long.valueOf(workspace));
        JsonNode beta=api("POST","/projects","member",workspace,Map.of("operationId","dashboard-beta-"+System.nanoTime(),"name","Beta Tender","lotName","Lot B","ownerId",workspaceOwner.toString()),200);

        JsonNode page=api("GET","/projects?name=Alpha&stage=SETUP&ownerId="+alpha.path("ownerId").asText()+"&page=1&pageSize=20","member",workspace,null,200);
        JsonNode dashboard=api("GET","/dashboard?name=Alpha&stage=SETUP&ownerId="+alpha.path("ownerId").asText(),"member",workspace,null,200);
        assertEquals(1,page.path("total").asInt());
        assertEquals(page.path("total").asInt(),dashboard.path("inProgress").asInt());
        assertEquals(1,dashboard.path("unknownDeadlines").asInt());
        assertEquals(0,dashboard.path("dueWithin7Days").asInt());
        assertEquals(0,dashboard.path("overdueDeadlines").asInt());
        assertEquals(0,dashboard.path("failedTasks").asInt());
        assertEquals(0,api("GET","/dashboard?name=missing&stage=SETUP","member",workspace,null,200).path("inProgress").asInt());
        assertNotEquals(alpha.path("ownerId").asText(),beta.path("ownerId").asText());
    }
}
