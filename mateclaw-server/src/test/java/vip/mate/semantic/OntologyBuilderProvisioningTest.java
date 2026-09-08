package vip.mate.semantic;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.binding.repository.*;
import vip.mate.agent.binding.model.*;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.model.SkillEntity;
import vip.mate.semantic.authoring.OntologyBuilderProvisioning;
import vip.mate.semantic.web.SemanticApiException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OntologyBuilderProvisioningTest {
    @Test void createsOnceWithBothBindingsAndPreservesAdministratorEdits() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:builder_"+UUID.randomUUID(),"sa","");
        ds.setUrl(ds.getUrl()+";DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE mate_workspace(id BIGINT PRIMARY KEY,deleted INT)");
        jdbc.update("INSERT INTO mate_workspace VALUES(1,0)");
        var agents=mock(AgentMapper.class);var skillBindings=mock(AgentSkillBindingMapper.class);
        var toolBindings=mock(AgentToolBindingMapper.class);var skills=mock(SkillMapper.class);
        var skill=new SkillEntity();skill.setId(20L);when(skills.selectOne(any())).thenReturn(skill);
        var stored=new ArrayList<AgentEntity>();
        when(agents.selectList(any())).thenAnswer(i->new ArrayList<>(stored));
        doAnswer(i->{AgentEntity a=i.getArgument(0);a.setId(30L);stored.add(a);return 1;}).when(agents).insert(any(AgentEntity.class));
        var sb=new ArrayList<AgentSkillBinding>();var tb=new ArrayList<AgentToolBinding>();
        when(skillBindings.selectOne(any())).thenAnswer(i->sb.isEmpty()?null:sb.getFirst());
        when(toolBindings.selectOne(any())).thenAnswer(i->tb.isEmpty()?null:tb.getFirst());
        doAnswer(i->{sb.add(i.getArgument(0));return 1;}).when(skillBindings).insert(any(AgentSkillBinding.class));
        doAnswer(i->{tb.add(i.getArgument(0));return 1;}).when(toolBindings).insert(any(AgentToolBinding.class));
        var beans=new DefaultListableBeanFactory();
        beans.registerSingleton("agents",agents);beans.registerSingleton("sb",skillBindings);beans.registerSingleton("tb",toolBindings);beans.registerSingleton("skills",skills);
        beans.registerSingleton("jdbc",jdbc);beans.registerSingleton("tx",new DataSourceTransactionManager(ds));
        var service=new OntologyBuilderProvisioning(beans.getBeanProvider(AgentMapper.class),beans.getBeanProvider(AgentSkillBindingMapper.class),beans.getBeanProvider(AgentToolBindingMapper.class),beans.getBeanProvider(SkillMapper.class),beans.getBeanProvider(JdbcTemplate.class),beans.getBeanProvider(PlatformTransactionManager.class));
        assertEquals("30",service.ensure("1").agentId());
        assertEquals("OntologyAuthoringTool",tb.getFirst().getToolName());
        assertEquals(20L,sb.getFirst().getSkillId());
        stored.getFirst().setName("专家自定义名称");stored.getFirst().setEnabled(false);sb.getFirst().setEnabled(false);tb.getFirst().setEnabled(false);
        assertEquals("30",service.ensure("1").agentId());
        assertEquals(1,stored.size());assertEquals(1,sb.size());assertEquals(1,tb.size());
        assertEquals("专家自定义名称",stored.getFirst().getName());assertFalse(stored.getFirst().getEnabled());assertFalse(sb.getFirst().getEnabled());assertFalse(tb.getFirst().getEnabled());
        assertThrows(SemanticApiException.class,()->service.ensure("2"));
        stored.clear();var unrelated=new AgentEntity();when(agents.selectOne(any())).thenReturn(unrelated);
        assertThrows(SemanticApiException.class,()->service.ensure("1"));
    }
}
