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
    void searchCanFindFactsBeyondFirstListPageAndReportsLimit() {
        var statements=mock(StatementApplicationService.class);
        var query=new SemanticQueryService(mock(JdbcTemplate.class),mock(GraphApplicationService.class),mock(SemanticAccessService.class),statements,mock(SupportEvaluator.class));
        List<StatementView> facts=new ArrayList<>();
        for(int i=0;i<102;i++) facts.add(new StatementView("f"+i,"g",1,"o","e","PROPERTY","voltage","TEXT",i>=100?"needle":"other",null,null,"INTERVAL",null,null,"ACCEPTED","SUPPORTED",List.of("evidence"),"actor",Instant.now()));
        when(statements.trusted("w","g")).thenReturn(facts);
        var result=query.search("w","g",new SearchRequest("needle",1,null));
        assertEquals("f100",result.facts().getFirst().id());
        assertTrue(result.truncated());
    }
}
