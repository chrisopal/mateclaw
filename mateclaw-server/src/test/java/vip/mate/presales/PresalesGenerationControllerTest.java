package vip.mate.presales;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class PresalesGenerationControllerTest {
 @Test void validatesAgainAndPersistsFailedDraftWhenSourceChanges() throws Exception {
  var json=new ObjectMapper();var service=mock(PresalesService.class);var access=mock(PresalesAccess.class);
  var contexts=mock(PresalesContextProvider.class);var model=mock(PresalesEmployeeRuntime.class);
  var state=new AtomicReference<>((ObjectNode)json.readTree("{\"id\":\"p\",\"version\":1,\"tasks\":[]}"));
  when(service.get("w","p")).thenAnswer(i->state.get().deepCopy());
  var employee=new vip.mate.agent.model.AgentEntity(); employee.setId(7L);employee.setName("售前员工");
  when(model.require(anyString(),anyString())).thenReturn(employee);
  when(access.require("w","member")).thenReturn("1");
  when(contexts.snapshot(anyString(),any(),anyString(),anyString())).thenReturn(json.createObjectNode().put("projectVersion",1));
  org.mockito.stubbing.Answer<ObjectNode> save=i->{
   PresalesDtos.Command c=i.getArgument(2);assertEquals(state.get().path("version").asInt(),c.expectedVersion());
   var p=state.get().deepCopy();var task=c.payload().deepCopy();task.put("id","task");p.putArray("tasks").add(task);p.put("version",c.expectedVersion()+1);state.set(p);return p.deepCopy();
  };
  when(service.command(anyString(),anyString(),any())).thenAnswer(save);
  when(service.saveEmployeeTask(anyString(),anyString(),any())).thenAnswer(save);
  when(service.find(any(),eq("tasks"),eq("task"))).thenAnswer(i->(ObjectNode)((ObjectNode)i.getArgument(0)).path("tasks").get(0));
  when(model.execute(anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn(json.createObjectNode().put("schemaVersion",1));
  doThrow(PresalesModelAdapter.error(409,"SOURCE_CHANGED")).when(contexts).revalidate(anyString(),any(),any());
  var controller=new PresalesGenerationController(service,access,contexts,model,json);
  var input=new PresalesGenerationController.Generate(1,"operation","S1","理解需求");
  controller.generate("w","p",input);
  assertEquals("FAILED",state.get().path("tasks").get(0).path("status").asText());
  assertEquals("SOURCE_CHANGED",state.get().path("tasks").get(0).path("error").asText());
  assertFalse(state.get().path("tasks").get(0).has("result"));
  controller.generate("w","p",input);verify(model,times(1)).execute(anyString(),anyString(),anyString(),anyString(),anyString(),any());
 }
 @Test void noModelDoesNotCreatePretendTask() {
  var service=mock(PresalesService.class);var json=new ObjectMapper();when(service.get("w","p")).thenReturn(json.createObjectNode().put("version",1));
  var model=mock(PresalesEmployeeRuntime.class);when(model.require(anyString(),anyString())).thenThrow(PresalesModelAdapter.error(409,"EMPLOYEE_UNAVAILABLE"));
  var controller=new PresalesGenerationController(service,mock(PresalesAccess.class),mock(PresalesContextProvider.class),model,json);
  assertThrows(vip.mate.semantic.web.SemanticApiException.class,()->controller.generate("w","p",new PresalesGenerationController.Generate(1,"op","S1","分析")));
  verify(service,never()).command(any(),any(),any());
 }
}
