package vip.mate.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import vip.mate.bidding.BiddingEmployeeRuntime;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.presales.PresalesAccess;
import vip.mate.presales.PresalesContextProvider;
import vip.mate.presales.PresalesEmployeeRuntime;
import vip.mate.presales.PresalesExecutionRevalidationProvider;
import vip.mate.presales.PresalesService;

class ProjectExecutionWiringTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        ProjectExecutionRevalidatorDispatcher.class,
                        PresalesEmployeeRuntime.class,
                        PresalesExecutionRevalidationProvider.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ModelConfigService.class, () -> mock(ModelConfigService.class))
                .withBean(PresalesAccess.class, () -> mock(PresalesAccess.class))
                .withBean(PresalesService.class, () -> mock(PresalesService.class))
                .withBean(PresalesContextProvider.class, () -> mock(PresalesContextProvider.class));
    }

    @Test
    void presalesEnabledResolvesOneHostRevalidatorWithoutCircularDependency() {
        runner().withPropertyValues("mateclaw.presales.enabled=true")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals(
                                    1,
                                    context.getBeansOfType(ProjectToolPolicy.Revalidator.class)
                                            .size());
                            assertEquals(
                                    1,
                                    context.getBeansOfType(
                                                    PresalesExecutionRevalidationProvider.class)
                                            .size());
                            assertEquals(
                                    1,
                                    context.getBeansOfType(PresalesEmployeeRuntime.class).size());
                        });
    }

    @Test
    void presalesDisabledLeavesBiddingProviderAndHostRevalidatorAvailable() {
        runner().withPropertyValues("mateclaw.presales.enabled=false")
                .withBean(BiddingEmployeeRuntime.class, () -> mock(BiddingEmployeeRuntime.class))
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals(
                                    1,
                                    context.getBeansOfType(ProjectToolPolicy.Revalidator.class)
                                            .size());
                            assertTrue(
                                    context.getBeansOfType(
                                                    PresalesExecutionRevalidationProvider.class)
                                            .isEmpty());
                            assertEquals(
                                    1,
                                    context.getBeansOfType(
                                                    ProjectExecutionRevalidationProvider.class)
                                            .size());
                        });
    }

    @Test
    void bothModulesDisabledKeepOneFailClosedHostRevalidator() {
        runner().withPropertyValues("mateclaw.presales.enabled=false")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals(
                                    1,
                                    context.getBeansOfType(ProjectToolPolicy.Revalidator.class)
                                            .size());
                            assertTrue(
                                    context.getBeansOfType(
                                                    ProjectExecutionRevalidationProvider.class)
                                            .isEmpty());
                        });
    }

    @Test
    void bothProjectProvidersShareExactlyOneHostRevalidator() {
        runner().withPropertyValues("mateclaw.presales.enabled=true")
                .withBean(BiddingEmployeeRuntime.class, () -> mock(BiddingEmployeeRuntime.class))
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals(
                                    1,
                                    context.getBeansOfType(ProjectToolPolicy.Revalidator.class)
                                            .size());
                            assertEquals(
                                    2,
                                    context.getBeansOfType(
                                                    ProjectExecutionRevalidationProvider.class)
                                            .size());
                        });
    }
}
