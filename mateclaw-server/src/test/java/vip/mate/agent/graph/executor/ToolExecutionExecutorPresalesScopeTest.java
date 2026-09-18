package vip.mate.agent.graph.executor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.presales.PresalesToolPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.tool.guard.ToolGuardResult;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ToolExecutionExecutorPresalesScopeTest {
 @Test void projectPolicyAllowsBoundReadOnlyWikiAndRejectsCrossProjectKb() {
  JdbcTemplate jdbc = mock(JdbcTemplate.class);
  when(jdbc.queryForObject(startsWith("SELECT body_json"), eq(String.class), eq("p"), eq("1")))
      .thenReturn("{\"agentId\":\"7\",\"materials\":[{\"kbId\":\"42\"}],"
          + "\"tasks\":[{\"runId\":\"run\",\"status\":\"RUNNING\",\"contextSnapshot\":{\"sources\":[{\"sourceRef\":\"101\"}]}}]}" );
  when(jdbc.queryForObject(startsWith("SELECT COUNT(*)"), eq(Integer.class), eq("42"), eq("1")))
      .thenReturn(1);
  when(jdbc.queryForObject(startsWith("SELECT wiki_disabled"), eq(Boolean.class), eq("7"), eq("1")))
      .thenReturn(false);
  var policy = new PresalesToolPolicy(jdbc, new ObjectMapper());
  var origin = ChatOrigin.web("presales:1:p:run", "1", 1L, null).withAgent(7L);
  assertTrue(policy.evaluate("wiki_search_pages",
      "{\"agentId\":\"7\",\"query\":\"scope\",\"kbIdParam\":\"42\"}", origin).allowed());
  assertFalse(policy.evaluate("wiki_search_pages",
      "{\"agentId\":\"7\",\"query\":\"scope\",\"kbIdParam\":\"99\"}", origin).allowed());
  assertTrue(policy.evaluate("project_document_read",
      "{\"sourceRef\":\"101\",\"kbId\":\"42\"}", origin).allowed());
  assertFalse(policy.evaluate("project_document_read",
      "{\"sourceRef\":\"101\",\"kbId\":\"99\"}", origin).allowed());
  assertFalse(policy.evaluate("project_document_read",
      "{\"sourceRef\":\"999\",\"kbId\":\"42\"}", origin).allowed());
  assertTrue(policy.evaluate("web_search", "{\"query\":\"latest\"}", origin).allowed());
  assertFalse(policy.evaluate("extract_document_text", "{\"filePath\":\"/tmp/a.pdf\"}", origin).allowed());
 }

 @Test void projectPolicyFailsClosedWhenRunOrSnapshotIsMissing() {
  JdbcTemplate jdbc = mock(JdbcTemplate.class);
  when(jdbc.queryForObject(startsWith("SELECT body_json"), eq(String.class), eq("p"), eq("1")))
      .thenReturn("{\"agentId\":\"7\",\"materials\":[{\"kbId\":\"42\"}],\"tasks\":[]}");
  var policy = new PresalesToolPolicy(jdbc, new ObjectMapper());
  var origin = ChatOrigin.web("presales:1:p:run", "1", 1L, null).withAgent(7L);
  assertFalse(policy.evaluate("project_document_read",
      "{\"sourceRef\":\"101\",\"kbId\":\"42\"}", origin).allowed());
  assertFalse(policy.evaluate("wiki_search_pages",
      "{\"agentId\":\"7\",\"query\":\"scope\",\"kbIdParam\":\"42\"}", origin).allowed());
 }

 @Test void projectCatalogContainsOnlyReadOnlyRetrievalAndBoundSkillTools() {
  assertTrue(PresalesToolPolicy.isProjectVisibleTool("web_search"));
  assertTrue(PresalesToolPolicy.isProjectVisibleTool("load_skill"));
  assertTrue(PresalesToolPolicy.isProjectVisibleTool("readSkillFile"));
  assertTrue(PresalesToolPolicy.isProjectVisibleTool("project_document_read"));
  assertTrue(PresalesToolPolicy.isProjectVisibleTool("wiki_read_page"));
  assertFalse(PresalesToolPolicy.isProjectVisibleTool("wiki_create_page"));
  assertFalse(PresalesToolPolicy.isProjectVisibleTool("extract_document_text"));
  assertFalse(PresalesToolPolicy.isProjectVisibleTool("execute_shell_command"));
 }

 @Test void mandatoryProjectBoundaryDeniesEvenWhenGuardAllowsAndApprovalWasGranted() {
  for(String name:List.of("execute_shell_command","read_file","wiki_search","delegate_agent","tool_call","load_skill")) {
   ToolCallback cb=mock(ToolCallback.class);
   String callbackName="tool_call".equals(name)?"execute_shell_command":name;
   when(cb.getToolDefinition()).thenReturn(ToolDefinition.builder().name(callbackName).description("test").inputSchema("{}").build());
   when(cb.getToolMetadata()).thenReturn(ToolMetadata.builder().returnDirect(false).build());
   var executor=new ToolExecutionExecutor(AgentToolSet.fromCallbacks(List.of(),List.of(cb)),(n,a)->ToolGuardResult.allow(),null,null);
   String arguments="tool_call".equals(name)?"{\"toolName\":\"execute_shell_command\",\"arguments\":{}}":"{}";
   var call=new AssistantMessage.ToolCall("c","function",name,arguments);
   var result=executor.execute(List.of(call),"presales:1:project:run","7",true,"1",null,ChatOrigin.web("presales:1:project:run","1",1L,null));
   assertFalse(result.awaitingApproval());
   assertTrue(result.responses().getFirst().responseData().contains("PRESALES_PROJECT_SCOPE"));
   verify(cb,never()).call(anyString());verify(cb,never()).call(anyString(),any());
  }
 }
}
