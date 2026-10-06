package vip.mate.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.ActorResolver;
import vip.mate.delivery.repository.DeliveryHandoffRepository;
import vip.mate.presales.api.PresalesHandoffReader;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.service.WorkspaceAccessService;

class DeliveryHandoffServiceTest {
    private DeliveryHandoffService service;
    private PresalesHandoffReader source;
    private WorkspaceAccessService workspaces;
    private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private PresalesHandoffReader.Handoff handoff;

    @BeforeEach
    void setup() {
        var db = new org.h2.jdbcx.JdbcDataSource();
        db.setURL("jdbc:h2:mem:delivery_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/h2/V224__delivery_handoff.sql"))
                .execute(db);
        jdbc = new JdbcTemplate(db);
        var actors = mock(ActorResolver.class);
        var user = new UserEntity();
        user.setId(1L);
        user.setRole("user");
        when(actors.requireCurrent()).thenReturn(user);
        workspaces = mock(WorkspaceAccessService.class);
        when(workspaces.findActiveWorkspace(10L)).thenReturn(new WorkspaceEntity());
        when(workspaces.hasMinimumRole(10L, 1L, "member")).thenReturn(true);
        when(workspaces.hasMinimumRole(10L, 1L, "viewer")).thenReturn(true);
        source = mock(PresalesHandoffReader.class);
        handoff =
                PresalesHandoffReader.Handoff.from(
                        "p1",
                        "r1",
                        "{\"release\":{\"id\":\"r1\"},\"solution\":{\"title\":\"方案\"}}");
        when(source.read("10", "p1", "r1")).thenReturn(handoff);
        var beans = new StaticListableBeanFactory();
        beans.addBean("source", source);
        service =
                new DeliveryHandoffService(
                        new DeliveryHandoffRepository(jdbc),
                        actors,
                        workspaces,
                        beans.getBeanProvider(PresalesHandoffReader.class),
                        json,
                        new DataSourceTransactionManager(db));
    }

    private DeliveryHandoffService.Receive request(String operation, String digest) {
        return new DeliveryHandoffService.Receive(operation, "p1", "r1", digest);
    }

    @Test
    void receivesExactReleaseAndReplaysWithoutAnotherWrite() {
        var first = service.receive("10", request("op", handoff.digest()));
        var replay = service.receive("10", request("op", handoff.digest()));
        assertEquals(first, replay);
        assertEquals(first, service.get("10", first.id()));
        assertEquals("方案", first.snapshot().path("solution").path("title").asText());
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM mate_delivery_handoff", Integer.class));
    }

    @Test
    void concurrentIdenticalRequestsReplayTheCommittedReceipt() throws Exception {
        var calls = raceSourceReads(false);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.receive("10", request("race", handoff.digest())));
            var second =
                    pool.submit(() -> service.receive("10", request("race", handoff.digest())));
            assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(3, calls.get(), "The losing request must reauthorize the committed source");
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM mate_delivery_handoff", Integer.class));
    }

    @Test
    void concurrentDifferentRequestsKeepOneReceiptAndRejectTheOther() throws Exception {
        raceSourceReads(false);
        var other = PresalesHandoffReader.Handoff.from("p1", "r2", "{\"release\":{\"id\":\"r2\"}}");
        var firstInput = request("race", handoff.digest());
        var secondInput = new DeliveryHandoffService.Receive("race", "p1", "r2", other.digest());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> receiveOutcome(firstInput));
            var second = pool.submit(() -> receiveOutcome(secondInput));
            Object one = first.get(15, TimeUnit.SECONDS);
            Object two = second.get(15, TimeUnit.SECONDS);
            var accepted =
                    one instanceof DeliveryHandoffService.Accepted value
                            ? value
                            : assertInstanceOf(DeliveryHandoffService.Accepted.class, two);
            var rejected =
                    one instanceof DeliveryRejected value
                            ? value
                            : assertInstanceOf(DeliveryRejected.class, two);
            assertEquals("OPERATION_CONFLICT", rejected.code());
            assertEquals(409, rejected.status());
            assertEquals(accepted, service.get("10", accepted.id()));
        }
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM mate_delivery_handoff", Integer.class));
    }

    @Test
    void concurrentReplayRechecksSourceRevocationAfterTheConflict() throws Exception {
        raceSourceReads(true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var input = request("race", handoff.digest());
            var first = pool.submit(() -> receiveOutcome(input));
            var second = pool.submit(() -> receiveOutcome(input));
            Object one = first.get(15, TimeUnit.SECONDS);
            Object two = second.get(15, TimeUnit.SECONDS);
            assertTrue(
                    one instanceof DeliveryHandoffService.Accepted
                            || two instanceof DeliveryHandoffService.Accepted);
            var rejected =
                    one instanceof DeliveryRejected value
                            ? value
                            : assertInstanceOf(DeliveryRejected.class, two);
            assertEquals("SOURCE_UNAVAILABLE", rejected.code());
            assertEquals(403, rejected.status());
        }
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM mate_delivery_handoff", Integer.class));
    }

    private Object receiveOutcome(DeliveryHandoffService.Receive input) {
        try {
            return service.receive("10", input);
        } catch (DeliveryRejected rejected) {
            return rejected;
        }
    }

    private AtomicInteger raceSourceReads(boolean revokeOnReplay) {
        var barrier = new CyclicBarrier(2);
        var calls = new AtomicInteger();
        when(source.read(eq("10"), eq("p1"), anyString()))
                .thenAnswer(
                        invocation -> {
                            int call = calls.incrementAndGet();
                            // Both transactions have observed no operation row before either
                            // inserts.
                            if (call <= 2) barrier.await(10, TimeUnit.SECONDS);
                            else if (revokeOnReplay)
                                throw new PresalesHandoffReader.Unavailable(
                                        403, "SOURCE_UNAVAILABLE");
                            String release = invocation.getArgument(2);
                            return "r1".equals(release)
                                    ? handoff
                                    : PresalesHandoffReader.Handoff.from(
                                            "p1", "r2", "{\"release\":{\"id\":\"r2\"}}");
                        });
        return calls;
    }

    @Test
    void changedDigestCannotReuseOperationOrImportDifferentBytes() {
        service.receive("10", request("op", handoff.digest()));
        assertEquals(
                "OPERATION_CONFLICT",
                assertThrows(
                                DeliveryRejected.class,
                                () -> service.receive("10", request("op", "0".repeat(64))))
                        .code());
        assertEquals(
                "HANDOFF_DIGEST_MISMATCH",
                assertThrows(
                                DeliveryRejected.class,
                                () -> service.receive("10", request("other", "0".repeat(64))))
                        .code());
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM mate_delivery_handoff", Integer.class));
    }

    @Test
    void sourceRevocationAlsoBlocksStoredReadsAndReplay() {
        var first = service.receive("10", request("op", handoff.digest()));
        when(source.read("10", "p1", "r1"))
                .thenThrow(new PresalesHandoffReader.Unavailable(403, "SOURCE_UNAVAILABLE"));
        assertEquals(
                403,
                assertThrows(DeliveryRejected.class, () -> service.get("10", first.id())).status());
        assertEquals(
                403,
                assertThrows(
                                DeliveryRejected.class,
                                () -> service.receive("10", request("op", handoff.digest())))
                        .status());
    }

    @Test
    void viewerCannotImportAndOtherWorkspaceCannotRead() {
        when(workspaces.hasMinimumRole(10L, 1L, "member")).thenReturn(false);
        assertEquals(
                403,
                assertThrows(
                                DeliveryRejected.class,
                                () -> service.receive("10", request("op", handoff.digest())))
                        .status());
        assertEquals(
                404, assertThrows(DeliveryRejected.class, () -> service.get("20", "id")).status());
        verifyNoInteractions(source);
    }

    @Test
    void corruptStoredBytesAndChangedSourceNeverReturnSuccess() {
        var first = service.receive("10", request("op", handoff.digest()));
        when(source.read("10", "p1", "r1"))
                .thenReturn(PresalesHandoffReader.Handoff.from("p1", "r1", "{}"));
        assertEquals(
                "HANDOFF_DIGEST_MISMATCH",
                assertThrows(DeliveryRejected.class, () -> service.get("10", first.id())).code());
        when(source.read("10", "p1", "r1")).thenReturn(handoff);
        jdbc.update("UPDATE mate_delivery_handoff SET snapshot_json='{}' WHERE id=?", first.id());
        assertEquals(
                "HANDOFF_DIGEST_MISMATCH",
                assertThrows(DeliveryRejected.class, () -> service.get("10", first.id())).code());
    }

    @Test
    void httpReceiveReadAndValidationUseActualRepository() throws Exception {
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new DeliveryHandoffController(service))
                        .setControllerAdvice(new DeliveryExceptionHandler())
                        .build();
        String content =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/api/v1/delivery/handoffs")
                                        .header("X-Workspace-Id", "10")
                                        .contentType(
                                                org.springframework.http.MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsBytes(
                                                        request("http-op", handoff.digest()))))
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .status()
                                        .isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String id = json.readTree(content).path("data").path("id").asText();
        assertFalse(id.isBlank());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/v1/delivery/handoffs/" + id)
                                .header("X-Workspace-Id", "10"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.data.digest")
                                .value(handoff.digest()));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/api/v1/delivery/handoffs")
                                .header("X-Workspace-Id", "10")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM mate_delivery_handoff", Integer.class));
    }
}
