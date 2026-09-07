package vip.mate.semantic;

import org.junit.jupiter.api.Test;
import vip.mate.semantic.graph.GraphRow;
import vip.mate.semantic.graph.repository.GraphMapper;
import vip.mate.semantic.ontology.OntologyWireMapper;
import vip.mate.semantic.statement.SemanticDomainMapper;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.StatementDtos.ProposeRequest;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SemanticDomainContractTest {
    private final SemanticDomainMapper mapper = new SemanticDomainMapper(mock(GraphMapper.class), mock(OntologyWireMapper.class));
    private final GraphRow graph = graph();

    @Test void discriminatorsNeverSilentlyChangeMeaning() {
        for (String kind : new String[]{null, "", "PROPRETY"})
            assertThrows(SemanticApiException.class, () -> mapper.content(graph, request(kind, "INTERVAL", "true", null)));
        for (String validity : new String[]{null, "", "UNKOWN"})
            assertThrows(SemanticApiException.class, () -> mapper.content(graph, request("PROPERTY", validity, "true", null)));
        for (String value : new String[]{null, "", "TRUE", "yes", "1", "flase"})
            assertThrows(SemanticApiException.class, () -> mapper.content(graph, request("PROPERTY", "INTERVAL", value, null)));
        assertThrows(SemanticApiException.class, () -> mapper.content(graph, request("PROPERTY", "UNKNOWN", "true", Instant.now())));
        assertDoesNotThrow(() -> mapper.content(graph, request("PROPERTY", "INTERVAL", "false", null)));
        assertDoesNotThrow(() -> mapper.content(graph, request("PROPERTY", "UNKNOWN", "true", null)));
    }

    private ProposeRequest request(String kind, String validity, String value, Instant from) {
        return new ProposeRequest("op", "4", kind, "confirmed", "BOOLEAN", value, null, null, validity, from, null, List.of());
    }
    @Test void contradictoryFieldsCannotChangeHowAValidatedFactIsDisplayed() {
        assertThrows(SemanticApiException.class,()->mapper.content(graph,new ProposeRequest("op","4","RELATION","cause","ENTITY","misleading display value",null,"5","INTERVAL",null,null,List.of())));
        assertThrows(SemanticApiException.class,()->mapper.content(graph,new ProposeRequest("op","4","PROPERTY","confirmed","BOOLEAN","true",null,"5","INTERVAL",null,null,List.of())));
        assertThrows(SemanticApiException.class,()->mapper.content(graph,new ProposeRequest("op","4","PROPERTY","confirmed","BOOLEAN","true","V",null,"INTERVAL",null,null,List.of())));
        assertThrows(SemanticApiException.class,()->mapper.content(graph,new ProposeRequest("op","4","PROPERTY","confirmed","BOOLEAN","true",null,null,"INTERVAL",null,null,List.of("6","6"))));
    }
    private static GraphRow graph() {
        GraphRow row = new GraphRow(); row.setId("2"); row.setKbId(1L); row.setWorkspaceId(1L); row.setOntologyRevisionId("3"); return row;
    }
}
