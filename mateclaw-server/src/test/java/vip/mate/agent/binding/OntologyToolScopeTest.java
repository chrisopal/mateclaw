package vip.mate.agent.binding;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.semantic.authoring.OntologyAuthoringTool;
import vip.mate.semantic.tool.SemanticContextTool;
import static org.junit.jupiter.api.Assertions.*;

class OntologyToolScopeTest {
    private AgentToolSet tools() {
        var author=new OntologyAuthoringTool(null,null,null,null,null,null,null,
            org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),null);
        var context=new SemanticContextTool(null,null);
        return AgentToolSet.fromCallbacks(List.of(author,context),
            java.util.stream.Stream.concat(java.util.Arrays.stream(ToolCallbacks.from(author)),
                java.util.Arrays.stream(ToolCallbacks.from(context))).toList());
    }
    @Test void legacyGlobalDefaultsCannotAdvertiseAuthoringButRetainContext() {
        var all=tools();
        assertTrue(all.allNames().contains("semantic_ontology_create_draft"));
        var scoped=AgentBindingService.applyEffectiveToolScope(all,null);
        assertEquals(Set.of("semantic_context","semantic_context_source"),scoped.callbackByName().keySet());
    }
    @Test void explicitAuthoringBindingStillWorksAndContextOnlyStaysReadOnly() {
        var all=tools();
        var author=AgentBindingService.applyEffectiveToolScope(all,Set.of("OntologyAuthoringTool"));
        assertTrue(author.allNames().contains("semantic_ontology_create_draft"));
        assertTrue(author.allNames().contains("semantic_ontology_save_draft"));
        assertFalse(author.allNames().contains("semantic_context"));
        assertEquals(Set.of("semantic_context","semantic_context_source"),
            AgentBindingService.applyEffectiveToolScope(all,Set.of("SemanticContextTool")).callbackByName().keySet());
    }
}
