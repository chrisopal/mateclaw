package vip.mate.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.llm.model.CreateCustomProviderRequest;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.service.WorkspaceService;

/** Three real processes share only an isolated file database and private fixture state. */
final class PresalesRestartProbe {
    private static final String PROVIDER = "ac21-pending-provider";
    private static final String MODEL = "ac21-pending-model";

    static Map<String, Object> verify(
            ConfigurableApplicationContext context, int port, Path root, String phase)
            throws Exception {
        var json = context.getBean(ObjectMapper.class);
        var jdbc = context.getBean(JdbcTemplate.class);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            if (phase.equals("restart-seed")) return seed(context, client, json, jdbc, port, root);
            var state =
                    (ObjectNode) json.readTree(root.resolve("restart-private-state.json").toFile());
            String token = login(client, json, port, state);
            String workspace = state.path("workspace").asText();
            String projectId = state.path("projectId").asText();
            var response =
                    ModuleStartupProbe.request(
                            client,
                            json,
                            port,
                            "/api/v1/presales/projects/" + projectId,
                            token,
                            workspace,
                            null,
                            200,
                            null);
            var project = response.path("data");
            String body = body(jdbc, workspace, projectId);
            require(
                    project.equals(json.readTree(body)),
                    "HTTP read must equal persisted recovered body");
            var task = project.path("tasks").get(0);
            require(task != null && project.path("tasks").size() == 1, "Recovery task cardinality");
            require("FAILED".equals(task.path("status").asText()), "Restart task must be failed");
            require(
                    "INTERRUPTED_BY_RESTART".equals(task.path("error").asText()),
                    "Restart error code");
            require(!task.has("result"), "Interrupted run must have no accepted result");
            Instant.parse(task.path("finishedAt").asText());
            int version = state.path("acceptedVersion").intValue() + 1;
            require(
                    project.path("version").intValue() == version,
                    "Recovery version must increment once");
            var expected = (ObjectNode) json.readTree(state.path("beforeBody").asText());
            expected.put("version", version);
            var expectedTask = (ObjectNode) expected.path("tasks").get(0);
            expectedTask
                    .put("status", "FAILED")
                    .put("error", "INTERRUPTED_BY_RESTART")
                    .put("finishedAt", task.path("finishedAt").asText());
            expectedTask.remove("result");
            require(expected.equals(project), "Recovery must retain all other project/task fields");
            require(
                    receipts(jdbc, workspace) == state.path("receipts").intValue(),
                    "No duplicate receipt");
            require(
                    revisions(jdbc, projectId) == state.path("revisions").intValue(),
                    "No duplicate command revision");
            Path saved = root.resolve("recovered-body.json");
            if (phase.equals("restart-recover")) Files.writeString(saved, body);
            else
                require(
                        Files.readString(saved).equals(body),
                        "Second restart must preserve exact bytes");
            return Map.of(
                    "phase",
                    phase,
                    "project_version",
                    version,
                    "task_status",
                    "FAILED",
                    "task_error",
                    "INTERRUPTED_BY_RESTART",
                    "receipts",
                    receipts(jdbc, workspace),
                    "revisions",
                    revisions(jdbc, projectId),
                    "http_readback",
                    true,
                    "no_duplicate_write",
                    phase.equals("restart-repeat"));
        }
    }

    private static Map<String, Object> seed(
            ConfigurableApplicationContext context,
            HttpClient client,
            ObjectMapper json,
            JdbcTemplate jdbc,
            int port,
            Path root)
            throws Exception {
        var state =
                json.createObjectNode()
                        .put("username", "ac21-owner")
                        .put("password", UUID.randomUUID().toString());
        var owner = new UserEntity();
        owner.setUsername(state.path("username").asText());
        owner.setPassword(state.path("password").asText());
        owner.setRole("user");
        owner.setDeleted(0);
        context.getBean(AuthService.class).createUser(owner);
        var workspace = new WorkspaceEntity();
        workspace.setName("AC21 restart workspace");
        workspace.setSlug("ac21-restart");
        workspace.setDeleted(0);
        context.getBean(WorkspaceService.class).create(workspace, owner.getId());
        String scope = workspace.getId().toString();
        state.put("workspace", scope);
        String token = login(client, json, port, state);
        var received = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        model.createContext(
                "/",
                exchange -> {
                    try {
                        if (exchange.getRequestMethod().equals("GET")
                                && exchange.getRequestURI().getPath().equals("/v1/models")) {
                            byte[] response =
                                    json.writeValueAsBytes(
                                            Map.of(
                                                    "object",
                                                    "list",
                                                    "data",
                                                    java.util.List.of(
                                                            Map.of(
                                                                    "id",
                                                                    MODEL,
                                                                    "object",
                                                                    "model",
                                                                    "owned_by",
                                                                    "ac21"))));
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, response.length);
                            exchange.getResponseBody().write(response);
                            exchange.close();
                            return;
                        }
                        require(
                                exchange.getRequestMethod().equals("POST")
                                        && exchange.getRequestURI()
                                                .getPath()
                                                .equals("/v1/chat/completions"),
                                "Unexpected model request");
                        var request = json.readTree(exchange.getRequestBody());
                        require(
                                MODEL.equals(request.path("model").asText())
                                        && request.path("stream").asBoolean(),
                                "Pinned streaming model request");
                        calls.incrementAndGet();
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody()
                                .write(
                                        ": waiting-for-test-process-halt\n\n"
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                        received.countDown();
                        // The containing isolated JVM is halted with the request still in progress.
                        new CountDownLatch(1).await(120, TimeUnit.SECONDS);
                        exchange.close();
                    } catch (Throwable error) {
                        failure.set(error);
                        received.countDown();
                        exchange.close();
                    }
                });
        model.start();
        var provider = new CreateCustomProviderRequest();
        provider.setId(PROVIDER);
        provider.setName("AC21 pending model fixture");
        provider.setProtocol("openai-compatible");
        provider.setDefaultBaseUrl("http://127.0.0.1:" + model.getAddress().getPort());
        provider.setRequireApiKey(false);
        context.getBean(ModelProviderService.class).createCustomProvider(provider);
        var config = new ModelConfigEntity();
        config.setName(MODEL);
        config.setProvider(PROVIDER);
        config.setModelName(MODEL);
        config.setModelType("chat");
        config.setEnabled(true);
        config.setIsDefault(true);
        config.setMaxTokens(128);
        config.setMaxInputTokens(32768);
        config.setRequestTimeoutSeconds(120);
        context.getBean(ModelConfigService.class).createModel(config);
        var agent = new AgentEntity();
        agent.setName("AC21 restart employee");
        agent.setWorkspaceId(workspace.getId());
        agent.setCreatorUserId(owner.getId());
        agent.setModelName(MODEL);
        agent.setMaxIterations(4);
        agent.setSkillsDisabled(true);
        agent.setWikiDisabled(true);
        context.getBean(AgentService.class).createAgent(agent);
        var created =
                ModuleStartupProbe.request(
                        client,
                        json,
                        port,
                        "/api/v1/presales/projects",
                        token,
                        scope,
                        json.writeValueAsString(
                                Map.of(
                                        "name",
                                        "AC21 project",
                                        "customer",
                                        "Isolated restart fixture",
                                        "agentId",
                                        agent.getId().toString(),
                                        "operationId",
                                        "ac21-create",
                                        "expectedVersion",
                                        0)),
                        200,
                        null);
        String projectId = created.path("data").path("id").asText();
        require(!projectId.isBlank(), "Created project missing");
        int initialVersion = created.path("data").path("version").intValue();
        var submitted =
                ModuleStartupProbe.request(
                        client,
                        json,
                        port,
                        "/api/v1/presales/projects/" + projectId + "/generate",
                        token,
                        scope,
                        json.writeValueAsString(
                                Map.of(
                                        "expectedVersion",
                                        initialVersion,
                                        "operationId",
                                        "ac21-generate",
                                        "skill",
                                        "S1",
                                        "taskGoal",
                                        "Describe the supplied project")),
                        200,
                        null);
        require(received.await(20, TimeUnit.SECONDS), "Model request did not start");
        require(failure.get() == null, "Model protocol fixture failed: " + failure.get());
        require(calls.get() == 1, "Exactly one model request must be pending");
        String body = body(jdbc, scope, projectId);
        var persisted = json.readTree(body);
        require(
                persisted.equals(submitted.path("data")),
                "Generate response must equal committed task body");
        require(persisted.path("tasks").size() == 1, "Single committed task expected");
        require(
                "RUNNING".equals(persisted.path("tasks").get(0).path("status").asText()),
                "Task must be RUNNING at halt");
        require(!persisted.path("tasks").get(0).has("result"), "No result before halt");
        // Only this synthetic provider is disabled, preventing restart auto-probes of a dead port.
        require(
                jdbc.update(
                                "UPDATE mate_model_provider SET enabled=FALSE WHERE provider_id=?",
                                PROVIDER)
                        == 1,
                "Disable fixture provider");
        jdbc.execute("CHECKPOINT SYNC");
        state.put("projectId", projectId)
                .put("beforeBody", body)
                .put("acceptedVersion", persisted.path("version").intValue())
                .put("receipts", receipts(jdbc, scope))
                .put("revisions", revisions(jdbc, projectId));
        json.writeValue(root.resolve("restart-private-state.json").toFile(), state);
        return Map.of(
                "phase",
                "restart-seed",
                "task_status",
                "RUNNING",
                "model_requests",
                calls.get(),
                "project_version",
                persisted.path("version").intValue(),
                "receipts",
                receipts(jdbc, scope),
                "revisions",
                revisions(jdbc, projectId),
                "committed_before_halt",
                true);
    }

    private static String login(HttpClient client, ObjectMapper json, int port, ObjectNode state)
            throws Exception {
        var response =
                ModuleStartupProbe.request(
                        client,
                        json,
                        port,
                        "/api/v1/auth/login",
                        null,
                        null,
                        json.writeValueAsString(
                                Map.of(
                                        "username",
                                        state.path("username").asText(),
                                        "password",
                                        state.path("password").asText())),
                        200,
                        null);
        String token = response.path("data").path("token").asText();
        require(!token.isBlank(), "Login token missing");
        return token;
    }

    private static String body(JdbcTemplate jdbc, String workspace, String project) {
        return jdbc.queryForObject(
                "SELECT body_json FROM mate_presales_project WHERE workspace_id=? AND id=?",
                String.class,
                workspace,
                project);
    }

    private static int receipts(JdbcTemplate jdbc, String workspace) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_presales_operation WHERE workspace_id=?",
                Integer.class,
                workspace);
    }

    private static int revisions(JdbcTemplate jdbc, String project) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_presales_revision WHERE project_id=?",
                Integer.class,
                project);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
