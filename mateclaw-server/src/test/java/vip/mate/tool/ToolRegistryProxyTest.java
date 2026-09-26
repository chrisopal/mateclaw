package vip.mate.tool;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.bidding.BiddingDependencies;
import vip.mate.bidding.BiddingEmployeeRuntime;
import vip.mate.bidding.BiddingReadTool;
import vip.mate.bidding.BiddingRepository;
import vip.mate.agent.AgentToolSet;
import vip.mate.i18n.I18nService;
import vip.mate.tool.repository.ToolMapper;

class ToolRegistryProxyTest {

    @Test
    void cglibProxiedTransactionalToolBeanIsIncludedInEnabledToolSet() {
        BiddingReadTool target = new BiddingReadTool(
                mock(BiddingEmployeeRuntime.class), mock(BiddingRepository.class),
                mock(BiddingDependencies.class), mock(JdbcTemplate.class), new ObjectMapper());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        Object proxied = proxyFactory.getProxy();

        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansWithAnnotation(org.springframework.stereotype.Component.class))
                .thenReturn(Map.of("biddingReadTool", proxied));
        ToolMapper mapper = mock(ToolMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        I18nService i18n = mock(I18nService.class);

        AgentToolSet tools = new ToolRegistry(context, mapper, i18n).getEnabledToolSet();

        assertTrue(tools.callbackByName().containsKey("bidding_read_source"));
        assertTrue(tools.callbackByName().containsKey("bidding_read_sources"));
        assertTrue(tools.withAllowedToolsOnly(java.util.Set.of("biddingReadTool"))
                .callbackByName().containsKey("bidding_read_sources"));
    }
}
