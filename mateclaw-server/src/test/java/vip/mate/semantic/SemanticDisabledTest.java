package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import vip.mate.semantic.support.SemanticHttpFixture;

@TestPropertySource(
        properties = {
            "mateclaw.semantic.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:semantic_disabled;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        })
class SemanticDisabledTest extends SemanticHttpFixture {
    @Test
    void statusIsAuthenticatedWithoutWorkspaceAndBusinessIsAbsent() throws Exception {
        assertFalse(call("GET", "/status", "viewer", null, null, 200).path("enabled").asBoolean());
        call("GET", "/status", null, null, null, 401);
        call("GET", "/ontologies", "owner", workspace, null, 404);
    }
}
