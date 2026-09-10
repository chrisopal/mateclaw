package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.semantic.graph.GraphApplicationService;
import vip.mate.semantic.query.SemanticQueryService;
import vip.mate.semantic.query.SemanticQueryDtos.SearchRequest;
import vip.mate.semantic.security.SemanticAccessService;
import vip.mate.semantic.statement.StatementApplicationService;
import vip.mate.semantic.statement.SupportEvaluator;
import vip.mate.semantic.web.StatementDtos.StatementView;
import java.time.Instant;
import java.util.*;

class SemanticQueryWindowTest {
    private SemanticQueryService query(StatementApplicationService statements) {
        var domain=mock(vip.mate.semantic.statement.SemanticDomainMapper.class);
        var ontology=mock(vip.mate.semantic.core.ontology.OntologyRevision.class);
        when(domain.ontology(any())).thenReturn(ontology);
        var port=mock(vip.mate.semantic.core.ontology.OntologyDocumentPort.class);
        when(port.termLabels(any())).thenReturn(Map.of());
        return new SemanticQueryService(mock(JdbcTemplate.class),mock(GraphApplicationService.class),mock(SemanticAccessService.class),
            statements,mock(SupportEvaluator.class),mock(vip.mate.agent.repository.AgentMapper.class),
            mock(vip.mate.wiki.service.WikiKnowledgeBaseService.class),domain,port);
    }
    private vip.mate.semantic.core.fact.AssertionPayload assertion(String value) {
        return new vip.mate.semantic.owl.OwlAssertionAdapter().parse("DataPropertyAssertion(<urn:test:voltage> <urn:test:entity> \""+value+"\")");
    }
    @Test
    void timeFilterUsesHalfOpenIntervalsAndExcludesUnknown() {
        var statements=mock(StatementApplicationService.class);
        var query=query(statements);
        Instant from=Instant.parse("2026-01-01T00:00:00Z"), to=from.plusSeconds(60);
        when(statements.trusted("w","g")).thenReturn(List.of(
                new StatementView("f","g",1,"o","e",assertion("220"),"INTERVAL",from,to,"ACCEPTED","SUPPORTED",List.of("evidence"),"actor",from),
                new StatementView("u","g",1,"o","e",assertion("380"),"UNKNOWN",null,null,"ACCEPTED","SUPPORTED",List.of("evidence"),"actor",from)));
        assertEquals(2,query.search("w","g",new SearchRequest("",10,null)).facts().size());
        assertEquals(List.of("f"),query.search("w","g",new SearchRequest("",10,from)).facts().stream().map(StatementView::id).toList());
        assertTrue(query.search("w","g",new SearchRequest("",10,to)).facts().isEmpty());
        assertTrue(query.search("w","g",new SearchRequest("",10,from.minusNanos(1))).facts().isEmpty());
    }
    @Test
    void searchCanFindFactsBeyondFirstListPageAndReportsLimit() {
        var statements=mock(StatementApplicationService.class);
        var query=query(statements);
        List<StatementView> facts=new ArrayList<>();
        for(int i=0;i<102;i++) facts.add(new StatementView("f"+i,"g",1,"o","e",assertion(i>=100?"needle":"other"),"INTERVAL",null,null,"ACCEPTED","SUPPORTED",List.of("evidence"),"actor",Instant.now()));
        when(statements.trusted("w","g")).thenReturn(facts);
        var result=query.search("w","g",new SearchRequest("needle",1,null));
        assertEquals("f100",result.facts().getFirst().id());
        assertTrue(result.truncated());
    }
}
