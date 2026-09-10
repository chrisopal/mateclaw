package vip.mate.agent.binding;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.tool.ToolRegistry;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=MateClawApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties={
    "spring.datasource.url=jdbc:h2:mem:ontology_tool_registry_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.dashscope.api-key=test-key",
    "spring.main.web-application-type=none",
    "mateclaw.semantic.enabled=true"
})
class OntologyAgentToolRegistryIntegrationTest {
    @Autowired AgentBindingService bindings;
    @Autowired ToolRegistry registry;
    @Autowired JdbcTemplate jdbc;

    @Test void databaseBindingControlsRealEnabledCallbacksAndRevocation() {
        long agentId=9_990_991L;
        jdbc.update("INSERT INTO mate_agent(id,name,agent_type,system_prompt,max_iterations,enabled,workspace_id,create_time,update_time,deleted) VALUES (?,?,'react','',10,TRUE,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",agentId,"ontology-tool-scope-acceptance");
        jdbc.update("UPDATE mate_tool SET enabled=TRUE WHERE bean_name IN ('ontologyAuthoringTool','semanticContextTool')");
        registry.invalidateEnabledToolSetCache("ontology-scope-test");
        var all=registry.getEnabledToolSet();
        assertTrue(all.callbackByName().containsKey("semantic_ontology_create_draft"),"Exercise an enabled real authoring bean");
        assertTrue(all.callbackByName().containsKey("semantic_context"));
        assertNull(bindings.getEffectiveToolNames(agentId));
        var ordinary=AgentBindingService.applyEffectiveToolScope(all,bindings.getEffectiveToolNames(agentId));
        assertTrue(ordinary.callbackByName().containsKey("semantic_context"));
        assertFalse(ordinary.callbackByName().keySet().stream().anyMatch(n->n.startsWith("semantic_ontology_")));

        bindings.setToolBindings(agentId,List.of("OntologyAuthoringTool","SemanticContextTool"));
        var builder=AgentBindingService.applyEffectiveToolScope(all,bindings.getEffectiveToolNames(agentId));
        assertTrue(builder.callbackByName().containsKey("semantic_ontology_create_draft"));
        assertTrue(builder.callbackByName().containsKey("semantic_ontology_save_draft"));
        bindings.unbindTool(agentId,"OntologyAuthoringTool");
        var revoked=AgentBindingService.applyEffectiveToolScope(all,bindings.getEffectiveToolNames(agentId));
        assertTrue(revoked.callbackByName().containsKey("semantic_context"));
        assertFalse(revoked.callbackByName().keySet().stream().anyMatch(n->n.startsWith("semantic_ontology_")));
    }
}
