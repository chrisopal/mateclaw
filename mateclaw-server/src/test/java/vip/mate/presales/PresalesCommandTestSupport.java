package vip.mate.presales;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import vip.mate.workspace.core.service.ProjectAuthorityFence;

/** Unit fixtures mock command locks; database concurrency suites exercise the real fence. */
final class PresalesCommandTestSupport {
    private PresalesCommandTestSupport() {}

    static ProjectAuthorityFence fence() {
        var fence = mock(ProjectAuthorityFence.class);
        when(fence.lockForCommand(
                        anyString(),
                        anyCollection(),
                        nullable(String.class),
                        anyCollection(),
                        anyCollection(),
                        anyCollection()))
                .thenReturn(true);
        return fence;
    }
}
