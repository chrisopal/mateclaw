package vip.mate.presales;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;
import vip.mate.workspace.conversation.ConversationService;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class PresalesEmployeeRuntimeTest {
 @Test void actualRuntimeEntryReceivesAuthenticatedWorkspaceContextAndValidatesOutput() {
  AgentService agents=mock(AgentService.class);ConversationService conversations=mock(ConversationService.class);
  ObjectProvider<AgentService> ap=mock(ObjectProvider.class);when(ap.getObject()).thenReturn(agents);when(ap.getIfAvailable()).thenReturn(agents);
  ObjectProvider<ConversationService> cp=mock(ObjectProvider.class);when(cp.getObject()).thenReturn(conversations);when(cp.getIfAvailable()).thenReturn(conversations);
  var employee=new AgentEntity();employee.setId(7L);employee.setWorkspaceId(1L);employee.setEnabled(true);when(agents.getAgent(7L)).thenReturn(employee);
  var origin=org.mockito.ArgumentCaptor.forClass(ChatOrigin.class);
  when(agents.chatStructuredStream(eq(7L),anyString(),eq("presales:1:p:run"),eq("9"),origin.capture())).thenReturn(Flux.just(
      AgentService.StreamDelta.segmentOnly("先读取项目资料。",null),
      AgentService.StreamDelta.finalAnswer("{\"schemaVersion\":1,\"needsHumanReview\":true,\"items\":[],\"unknowns\":[],\"assumptions\":[]}",true)));
  var runtime=new PresalesEmployeeRuntime(ap,cp,new ObjectMapper());
  var result=runtime.execute("1","9","7","presales:1:p:run","instructions",new ObjectMapper().createObjectNode());
  assertTrue(result.path("needsHumanReview").asBoolean());assertEquals(1L,origin.getValue().workspaceId());assertEquals(9L,origin.getValue().requesterUserId());
  verify(conversations).getOrCreateConversation("presales:1:p:run",7L,"9",1L);
  verify(agents).chatStructuredStream(eq(7L),contains("BOUND EMPLOYEE ID: 7."),eq("presales:1:p:run"),eq("9"),any(ChatOrigin.class));
  assertThrows(vip.mate.semantic.web.SemanticApiException.class,()->runtime.require("2","7"));
 }
}
