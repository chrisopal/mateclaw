package vip.mate.agent.execution;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProjectExecutionRevalidatorDispatcherTest {
    private final ProjectExecutionOptions options =
            new ProjectExecutionOptions(
                    "attempt",
                    "model",
                    "config",
                    "skill",
                    "digest",
                    Map.of(),
                    Set.of(),
                    (name, arguments) -> {},
                    0,
                    false,
                    false,
                    1);

    @Test
    void rejectsMissingProvider() {
        var dispatcher = new ProjectExecutionRevalidatorDispatcher(List.of());
        assertThrows(IllegalStateException.class, () -> dispatcher.requireActive(options));
    }

    @Test
    void delegatesToExactlyOneMatchingProvider() {
        var other = mock(ProjectExecutionRevalidationProvider.class);
        var matching = mock(ProjectExecutionRevalidationProvider.class);
        when(matching.supports(options)).thenReturn(true);
        var dispatcher = new ProjectExecutionRevalidatorDispatcher(List.of(other, matching));

        dispatcher.requireActive(options);

        verify(matching).requireActive(options);
        verify(other, times(0)).requireActive(options);
    }

    @Test
    void rejectsAmbiguousProvidersWithoutDelegating() {
        var first = mock(ProjectExecutionRevalidationProvider.class);
        var second = mock(ProjectExecutionRevalidationProvider.class);
        when(first.supports(options)).thenReturn(true);
        when(second.supports(options)).thenReturn(true);
        var dispatcher = new ProjectExecutionRevalidatorDispatcher(List.of(first, second));

        assertThrows(IllegalStateException.class, () -> dispatcher.requireActive(options));
        verify(first, times(0)).requireActive(options);
        verify(second, times(0)).requireActive(options);
    }
}
