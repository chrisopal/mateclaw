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
    @Test
    void timeFilterUsesHalfOpenIntervalsAndExcludesUnknown() {
        var statements=mock(StatementApplicationService.class);
        var query=new SemanticQueryService(mock(JdbcTemplate.class),mock(GraphApplicationService.class),mock(SemanticAccessService.class),statements,mock(SupportEvaluator.class),mock(vip.mate.agent.repository.AgentMapper.class),mock(vip.mate.wiki.service.WikiKnowledgeBaseService.class));
        Instant from=Instant.parse("2026-01-01T00:00:00Z"), to=from.plusSeconds(60);
        when(statements.trusted("w","g")).thenReturn(List.of(
                new StatementView("f","g",1,"o","e","PROPERTY","voltage","TEXT","220",null,null,"INTERVAL",from,to,"ACCEPTED","SUPPORTED",List.of("evidence"),"actor",from),
                new StatementView("u","g",1,"o","e","PROPERTY","voltage","TEXT","380",null,null,"UNKNOWN",null,null,"ACCEPTED","SUPPORTED",List.of("evidence"),"actor",from)));
        assertEquals(2,query.search("w","g",new SearchRequest("",10,null)).facts().size());
        assertEquals(List.of("f"),query.search("w","g",new SearchRequest("",10,from)).facts().stream().map(StatementView::id).toList());
        assertTrue(query.search("w","g",new SearchRequest("",10,to)).facts().isEmpty());
        assertTrue(query.search("w","g",new SearchRequest("",10,from.minusNanos(1))).facts().isEmpty());
    }
    @Test
    void searchCanFindFactsBeyondFirstListPageAndReportsLimit() {
        var statements=mock(StatementApplicationService.class);
        var query=new SemanticQueryService(mock(JdbcTemplate.class),mock(GraphApplicationService.class),mock(SemanticAccessService.class),statements,mock(SupportEvaluator.class),mock(vip.mate.agent.repository.AgentMapper.class),mock(vip.mate.wiki.service.WikiKnowledgeBaseService.class));
        List<StatementView> facts=new ArrayList<>();
        for(int i=0;i<102;i++) facts.add(new StatementView("f"+i,"g",1,"o","e","PROPERTY","voltage","TEXT",i>=100?"needle":"other",null,null,"INTERVAL",null,null,"ACCEPTED","SUPPORTED",List.of("evidence"),"actor",Instant.now()));
        when(statements.trusted("w","g")).thenReturn(facts);
        var result=query.search("w","g",new SearchRequest("needle",1,null));
        assertEquals("f100",result.facts().getFirst().id());
        assertTrue(result.truncated());
    }
}
