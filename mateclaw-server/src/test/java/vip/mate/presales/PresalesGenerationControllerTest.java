package vip.mate.presales;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class PresalesGenerationControllerTest {
 @Test void acceptsAndQueuesImmediatelyAndRetryDoesNotQueueAgain() throws Exception {
  var json=new ObjectMapper();var service=mock(PresalesService.class);var access=mock(PresalesAccess.class);
  var contexts=mock(PresalesContextProvider.class);var model=mock(PresalesEmployeeRuntime.class);
  var coordinator=mock(PresalesGenerationCoordinator.class);
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
  var controller=new PresalesGenerationController(service,access,contexts,model,json,coordinator);
  var input=new PresalesGenerationController.Generate(1,"operation","S1","理解需求");
  var accepted=controller.generate("w","p",input);
  assertEquals(200,accepted.getCode());
  verify(coordinator).enqueue(any(PresalesGenerationCoordinator.Submission.class));
  controller.generate("w","p",input);
  verify(coordinator,times(1)).enqueue(any(PresalesGenerationCoordinator.Submission.class));
  verify(model,never()).execute(anyString(),anyString(),anyString(),anyString(),anyString(),any());
 }
 @Test void noModelDoesNotCreatePretendTask() {
  var service=mock(PresalesService.class);var json=new ObjectMapper();when(service.get("w","p")).thenReturn(json.createObjectNode().put("version",1));
  var model=mock(PresalesEmployeeRuntime.class);when(model.require(anyString(),anyString())).thenThrow(PresalesModelAdapter.error(409,"EMPLOYEE_UNAVAILABLE"));
  var controller=new PresalesGenerationController(service,mock(PresalesAccess.class),mock(PresalesContextProvider.class),model,json,mock(PresalesGenerationCoordinator.class));
  assertThrows(vip.mate.semantic.web.SemanticApiException.class,()->controller.generate("w","p",new PresalesGenerationController.Generate(1,"op","S1","分析")));
  verify(service,never()).command(any(),any(),any());
 }
}
