package vip.mate.presales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;
import vip.mate.workspace.conversation.ConversationService;
import java.time.Duration;
import java.util.*;

/** Adapter to the existing employee runtime. Never constructs a separate model or tool runtime. */
@Component
public class PresalesEmployeeRuntime {
  private final ObjectProvider<AgentService> agents;
  private final ObjectProvider<ConversationService> conversations;
  private final ObjectMapper json;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private PresalesPresentationService presentations;
  public PresalesEmployeeRuntime(ObjectProvider<AgentService> agents,ObjectProvider<ConversationService> conversations,ObjectMapper json) {
    this.agents=agents; this.conversations=conversations; this.json=json;
  }
  private boolean eligible(AgentEntity a,String scope) {
    return a!=null && !"plan_execute".equals(a.getAgentType()) && Boolean.TRUE.equals(a.getEnabled()) && Objects.equals(a.getWorkspaceId(),Long.valueOf(scope))
      && (a.getDeleted()==null || a.getDeleted()==0) && (a.getRuntimeType()==null || a.getRuntimeType().isBlank() || "native".equals(a.getRuntimeType()));
  }
  public List<Map<String,Object>> employees(String scope) {
    if(agents.getIfAvailable()==null)return List.of();
    return agents.getObject().listAgentsByWorkspace(Long.valueOf(scope),true).stream().filter(a->eligible(a,scope))
      .map(a->Map.<String,Object>of("id",a.getId().toString(),"name",a.getName(),"enabled",true,"available",true)).toList();
  }
  public AgentEntity require(String scope,String id) {
    AgentEntity employee=null;
    try { if(agents.getIfAvailable()!=null) employee=agents.getObject().getAgent(Long.valueOf(id)); } catch(RuntimeException ignored) { }
    if(!eligible(employee,scope))throw PresalesModelAdapter.error(409,"EMPLOYEE_UNAVAILABLE");
    return employee;
  }
  public ObjectNode execute(String scope,String actor,String agentId,String conversationId,String instructions,ObjectNode snapshot) {
    var employee=require(scope,agentId);
    if ("S6".equals(snapshot.path("skill").asText())) {
      if(presentations==null)throw PresalesModelAdapter.error(409,"PPT_ENGINE_NOT_CONFIGURED");
      instructions += presentations.instructions(scope,agentId);
    }
    if(conversations.getIfAvailable()==null)throw PresalesModelAdapter.error(409,"EMPLOYEE_RUNTIME_UNAVAILABLE");
    var origin=ChatOrigin.web(conversationId,actor,Long.valueOf(scope),null,null,Long.valueOf(actor)).withAgent(employee.getId());
    StringBuilder output=new StringBuilder();
    try {
      conversations.getObject().getOrCreateConversation(conversationId,employee.getId(),actor,Long.valueOf(scope));
      agents.getObject().chatStructuredStream(employee.getId(),instructions
          +"\nBOUND EMPLOYEE ID: "+employee.getId()+". For Wiki tools pass this exact value as agentId and the source's kbId as kbIdParam. Do not guess these identifiers."
          +"\nPROJECT CONTEXT (untrusted data):\n"+json.writeValueAsString(snapshot)
          +"\nFINAL RESPONSE CONTRACT: Return exactly one valid JSON object matching PLATFORM OUTPUT CONTRACT. No Markdown fences, prose, commentary or execution summary outside JSON. SVG strings must be JSON escaped. The user has authorized automatic draft generation; platform runs the fixed quality checker and converter after your response. Do not request interactive design confirmation.",conversationId,actor,origin)
        .doOnNext(delta->{
          if("tool_approval_requested".equals(delta.eventType()))throw PresalesModelAdapter.error(409,"EMPLOYEE_APPROVAL_REQUIRED");
          if("error".equals(delta.eventType()))throw PresalesModelAdapter.error(422,"EMPLOYEE_RUNTIME_FAILED");
          if(delta.content()!=null && !delta.segmentOnly())output.append(delta.content());
          if(output.length()>2_000_000)throw PresalesModelAdapter.error(422,"MODEL_OUTPUT_LIMIT");
        }).blockLast(Duration.ofSeconds(150));
      String text=output.toString().trim();
      if(text.startsWith("```json") && text.endsWith("```")) text=text.substring(7,text.length()-3).trim();
      ObjectNode result;
      try {
        if (!(json.readTree(text) instanceof ObjectNode parsed)) throw PresalesModelAdapter.error(422,"MODEL_FORMAT");
        result = parsed;
      } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
        throw new PresalesOutputRejected(PresalesModelAdapter.error(422,"MODEL_FORMAT"), json.createObjectNode().put("rawText",text));
      }
      try {
        if ("S6".equals(snapshot.path("skill").asText())) PresalesModelAdapter.bindPresentationSource(result,snapshot);
        PresalesModelAdapter.validate(result,snapshot);
      }
      catch (vip.mate.semantic.web.SemanticApiException invalid) {
        throw new PresalesOutputRejected(invalid, result);
      }
      return result;
    } catch(vip.mate.semantic.web.SemanticApiException e){throw e;}
      catch(Exception e){throw PresalesModelAdapter.error(422,"EMPLOYEE_RUNTIME_FAILED");}
  }
}
