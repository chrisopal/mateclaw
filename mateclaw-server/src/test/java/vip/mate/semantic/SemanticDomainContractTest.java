package vip.mate.semantic;

import org.junit.jupiter.api.Test;
import vip.mate.semantic.graph.GraphRow;
import vip.mate.semantic.graph.repository.GraphMapper;
import vip.mate.semantic.ontology.OntologyWireMapper;
import vip.mate.semantic.statement.SemanticDomainMapper;
import vip.mate.semantic.owl.OwlAssertionAdapter;
import vip.mate.semantic.web.SemanticApiException;
import vip.mate.semantic.web.StatementDtos.ProposeRequest;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SemanticDomainContractTest {
    private final SemanticDomainMapper mapper=new SemanticDomainMapper(mock(GraphMapper.class),mock(OntologyWireMapper.class),new OwlAssertionAdapter());
    private final GraphRow graph=graph();
    private static final String TEXT="DataPropertyAssertion(<urn:test:confirmed> <urn:test:entity> \"true\"^^<http://www.w3.org/2001/XMLSchema#boolean>)";
    @Test void assertionAndTimeCannotSilentlyChangeMeaning(){
        for(String syntax:new String[]{null,"","Ontology(<urn:test:o>)","UnknownAssertion(<urn:test:x>)"})
            assertThrows(SemanticApiException.class,()->mapper.content(graph,request(syntax,"UNKNOWN",null,List.of())));
        for(String validity:new String[]{null,"","UNKOWN"})
            assertThrows(SemanticApiException.class,()->mapper.content(graph,request(TEXT,validity,null,List.of())));
        assertThrows(SemanticApiException.class,()->mapper.content(graph,request(TEXT,"UNKNOWN",Instant.now(),List.of())));
        var content=mapper.content(graph,request(TEXT,"UNKNOWN",null,List.of()));
        assertEquals("urn:test:confirmed",content.assertion().predicateIri().orElseThrow());
        assertEquals("true",content.assertion().literal().orElseThrow().lexicalValue());
    }
    @Test void rejectsDuplicateEvidenceAndMultipleAxioms(){
        assertThrows(SemanticApiException.class,()->mapper.content(graph,request(TEXT,"UNKNOWN",null,List.of("6","6"))));
        assertThrows(SemanticApiException.class,()->mapper.content(graph,request(TEXT+" "+TEXT.replace("true","false"),"UNKNOWN",null,List.of())));
    }
    private ProposeRequest request(String assertion,String validity,Instant from,List<String> evidence){return new ProposeRequest("op","4",assertion,validity,from,null,evidence);}
    private static GraphRow graph(){GraphRow row=new GraphRow();row.setId("2");row.setKbId(1L);row.setWorkspaceId(1L);row.setOntologyRevisionId("3");return row;}
}
