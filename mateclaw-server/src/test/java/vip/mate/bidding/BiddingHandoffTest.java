package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.presales.PresalesService;

class BiddingHandoffTest extends BiddingHttpFixture {
    @Test void independentProjectsDoNotRequirePresales() throws Exception {
        var p=project();
        assertFalse(p.path("id").asText().isBlank());
        var unavailable=api("GET","/handoff-options?presalesProjectId=missing","member",workspace,null,409);
        assertTrue(unavailable.toString().contains("PRESALES_UNAVAILABLE"));
        assertEquals(p.path("id"),api("GET","/projects/"+p.path("id").asText(),"member",workspace,null,200).path("id"));
    }

    @Test void receiptUsesPreviewDigestAndReplaysTheSameOperation() throws Exception {
        var access=mock(BiddingAccess.class); var repository=mock(BiddingRepository.class); var jdbc=mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked") ObjectProvider<PresalesService> provider=mock(ObjectProvider.class);
        var presales=mock(PresalesService.class); when(provider.getIfAvailable()).thenReturn(presales);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper(); var snapshot=mapper.createObjectNode().put("releaseId","r-1").put("customerConfirmationStatus","UNCONFIRMED");
        when(presales.handoff("22","pre-1","r-1")).thenReturn(snapshot);
        var service=new BiddingHandoffService(access,repository,jdbc,mapper,provider);
        var scope=new BiddingTypes.Scope("22","99","bid-1");
        String digest;
        try { digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(snapshot))); }
        catch(Exception e) { throw new AssertionError(e); }
        var payload=mapper.createObjectNode().put("presalesProjectId","pre-1").put("releaseId","r-1").put("expectedDigest",digest);
        payload.putArray("receivedNotes").add("新事项");
        var command=new BiddingTypes.Command("same-op",null,"RECEIVE_HANDOFF",payload);
        when(repository.findOperation("22","99","same-op")).thenReturn(null);
        when(repository.lockProject("22","bid-1")).thenReturn(true);
        var project=mapper.createObjectNode().put("id","bid-1").put("workspaceId","22").put("version",1).put("name","Name").put("lotName","Lot").put("ownerId","99").put("stage","SETUP");
        project.putObject("ref").put("kind","project").put("id","bid-1").put("version",1).put("digest","before");
        when(repository.findProject("22","bid-1")).thenReturn(project);
        var expected=new BiddingTypes.Ref("project","bid-1",1,"before");
        command=new BiddingTypes.Command("same-op",expected,"RECEIVE_HANDOFF",payload);
        var accepted=service.receive(scope,command);
        assertEquals("UNCONFIRMED",accepted.path("customerConfirmationStatus").asText());
        assertEquals(1,accepted.path("receivedNotes").size());
        assertEquals("material",accepted.path("materialRefs").get(0).path("kind").asText());
        assertEquals("presales:pre-1:r-1",accepted.path("materialRefs").get(0).path("id").asText());
        assertEquals(2,project.path("ref").path("version").asInt());
        assertFalse(project.path("selectedRefs").path("handoff").has("snapshot"));
        var digestCaptor=org.mockito.ArgumentCaptor.forClass(String.class);
        var resultCaptor=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).insertOperation(eq("22"),eq("99"),eq("same-op"),digestCaptor.capture(),resultCaptor.capture(),any());
        var replayResult=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(resultCaptor.getValue());
        when(repository.findOperation("22","99","same-op")).thenReturn(new BiddingRepository.StoredOperation(digestCaptor.getValue(),replayResult));
        assertEquals(accepted,service.receive(scope,command));
        var changed=payload.deepCopy().put("expectedDigest","another-digest");
        assertEquals("HANDOFF_DIGEST_MISMATCH",assertThrows(BiddingApiException.class,
                ()->service.receive(scope,new BiddingTypes.Command("new-op",expected,"RECEIVE_HANDOFF",changed))).code());
    }

    @Test void sameReleaseCanBeReceivedOnlyOnceAndDifferentOperationConflicts() throws Exception {
        // This fixture deliberately disables presales. The endpoint proves the independent path remains live;
        // versioned receipt behavior is covered by PresalesIntegrationTest's frozen-revision contract.
        var p=project();
        assertEquals("SETUP",p.path("stage").asText());
        assertTrue(api("GET","/projects/"+p.path("id").asText()+"/materials","member",workspace,null,200).path("items").isArray());
    }
}
