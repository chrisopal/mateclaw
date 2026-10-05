package vip.mate.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.MateClawApplication;
import vip.mate.agent.AgentService;
import vip.mate.agent.execution.ProjectExecutionRevalidatorDispatcher;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.bidding.BiddingProperties;
import vip.mate.presales.PresalesController;
import vip.mate.presales.PresalesProjectQueryService;
import vip.mate.semantic.config.SemanticProperties;
import vip.mate.semantic.web.OntologyController;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.service.WorkspaceService;

/** Executed only in a child JVM with production class directories and this probe's classes. */
public final class ModuleStartupProbe {
    private static ConfigurableApplicationContext readyContext;

    public static final class ReadyListener implements ApplicationListener<ApplicationReadyEvent> {
        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            readyContext = event.getApplicationContext();
        }
    }

    public static void main(String[] args) {
        try {
            runProbe(args);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void runProbe(String[] args) throws Exception {
        String bits = args[0];
        if (!bits.matches("[01]{3}")) throw new IllegalArgumentException("Invalid module variant");
        Path root = Path.of(args[1]).toAbsolutePath();
        String phase = args.length > 2 ? args[2] : "ordinary";
        require(
                java.util.Set.of("ordinary", "restart-seed", "restart-recover", "restart-repeat")
                        .contains(phase),
                "Unknown phase");
        boolean restart = !phase.equals("ordinary");
        boolean initialized = phase.equals("restart-recover") || phase.equals("restart-repeat");
        String database =
                restart
                        ? "jdbc:h2:file:" + root.resolve("restart-data")
                        : "jdbc:h2:mem:ac05_" + bits;
        boolean semantic = bits.charAt(0) == '1';
        boolean presales = bits.charAt(1) == '1';
        boolean bidding = bits.charAt(2) == '1';
        var properties = new LinkedHashMap<String, String>();
        properties.put("spring.config.location", "classpath:/application.yml");
        properties.put("spring.profiles.active", "ac05-isolated");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "0");
        properties.put(
                "spring.datasource.url",
                database
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;WRITE_DELAY=0");
        properties.put("spring.datasource.driver-class-name", "org.h2.Driver");
        properties.put("spring.datasource.username", "sa");
        properties.put("spring.datasource.password", "");
        properties.put("spring.flyway.locations", "classpath:db/migration/h2");
        properties.put("spring.ai.dashscope.api-key", "ac05-not-a-real-key");
        properties.put("mateclaw.semantic.enabled", Boolean.toString(semantic));
        properties.put("mateclaw.presales.enabled", Boolean.toString(presales));
        properties.put("mateclaw.bidding.enabled", Boolean.toString(bidding));
        properties.put("mateclaw.bidding.scheduler-enabled", "false");
        properties.put("mateclaw.semantic.extraction.scheduler-enabled", "false");
        properties.put("mateclaw.plugin.enabled", "false");
        // Keep wall-clock cron jobs outside this ordinary-chat observation fixture.
        properties.put("mate.memory.fact.projection-rebuild-cron", "-");
        properties.put("mate.wiki.chunk-token-backfill-cron", "-");
        properties.put("mateclaw.setup.await-language-selection", "true");
        properties.put("mateclaw.skill.workspace.root", root.resolve("skills").toString());
        properties.put("mateclaw.workspace.sandbox.root", root.resolve("workspace").toString());
        properties.put("mateclaw.chat.upload.base-dir", root.resolve("chat-uploads").toString());
        properties.put("mate.wiki.upload-dir", root.resolve("wiki-uploads").toString());
        properties.put("mateclaw.workflow.payload.fs.root", root.resolve("payloads").toString());
        var arguments = new ArrayList<String>();
        properties.forEach((key, value) -> arguments.add("--" + key + "=" + value));
        // Execute the production entry point, including its JDK HTTP defaults.
        MateClawApplication.main(arguments.toArray(String[]::new));
        require(readyContext != null, "ApplicationReadyEvent not observed");
        var report = new LinkedHashMap<String, Object>();
        try (var context = readyContext) {
            require(context.isActive(), "Context inactive");
            require(
                    context.getBean(SemanticProperties.class).isEnabled() == semantic,
                    "Semantic flag");
            require(
                    context.getBean(BiddingProperties.class).isEnabled() == bidding,
                    "Bidding flag");
            require(
                    context.getBeansOfType(OntologyController.class).size() == (semantic ? 1 : 0),
                    "Ontology controller");
            require(
                    context.getBeansOfType(PresalesController.class).size() == (presales ? 1 : 0),
                    "Presales controller");
            require(
                    context.getBeansOfType(PresalesProjectQueryService.class).size()
                            == (presales ? 1 : 0),
                    "Project query service");
            context.getBean(AgentService.class);
            context.getBean(ProjectExecutionRevalidatorDispatcher.class);
            var source = context.getBean(DataSource.class);
            try (var connection = source.getConnection()) {
                require(
                        connection.getMetaData().getURL().equals(database),
                        "Unexpected datasource");
                require(
                        connection.getMetaData().getDatabaseProductName().equals("H2"),
                        "Unexpected database engine");
                report.put("database", connection.getMetaData().getURL());
            }
            var flyway = context.getBean(Flyway.class);
            flyway.validate();
            require(flyway.info().pending().length == 0, "Pending migrations");
            require(flyway.info().current() != null, "No current migration");
            var jdbc = context.getBean(JdbcTemplate.class);
            var enabled = new LinkedHashMap<String, Integer>();
            for (String table :
                    new String[] {
                        "mate_cron_job", "mate_channel", "mate_mcp_server", "mate_model_provider"
                    }) {
                int count =
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM " + table + " WHERE enabled=TRUE",
                                Integer.class);
                require(count == 0, "Unexpected enabled startup work in " + table);
                enabled.put(table, count);
            }
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            require(port > 0, "No bound HTTP port");
            try (var client =
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                var response =
                        client.send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + port
                                                                + "/api/v1/setup/status"))
                                        .timeout(Duration.ofSeconds(5))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                require(response.statusCode() == 200, "Setup HTTP status " + response.statusCode());
                var body = context.getBean(ObjectMapper.class).readTree(response.body());
                require(
                        body.path("data").has("initialized")
                                && body.path("data").path("initialized").asBoolean() == initialized,
                        "Setup state");
            }
            report.put(
                    "http_contracts",
                    restart
                            ? PresalesRestartProbe.verify(context, port, root, phase)
                            : verifyBusinessHttp(context, port, semantic, presales, bidding, bits));
            report.put("variant", bits);
            report.put("phase", phase);
            report.put("pid", ProcessHandle.current().pid());
            report.put("ready_event", true);
            report.put("port", port);
            report.put("setup_initialized", initialized);
            report.put("current_migration", flyway.info().current().getVersion().toString());
            report.put("enabled_external_work", enabled);
            report.put(
                    "module_flags",
                    Map.of("semantic", semantic, "presales", presales, "bidding", bidding));
            if (phase.equals("restart-seed")) {
                report.put("context_closed", false);
                report.put("exit_kind", "halt_after_committed_running_task");
                new ObjectMapper().writeValue(root.resolve("result.json").toFile(), report);
                Runtime.getRuntime().halt(23);
            }
        }
        report.put("context_closed", true);
        new ObjectMapper().writeValue(root.resolve("result.json").toFile(), report);
        System.exit(0);
    }

    private static Map<String, Object> verifyBusinessHttp(
            org.springframework.context.ConfigurableApplicationContext context,
            int port,
            boolean semantic,
            boolean presales,
            boolean bidding,
            String variant)
            throws Exception {
        var json = context.getBean(ObjectMapper.class);
        String password = java.util.UUID.randomUUID().toString();
        var user = new UserEntity();
        user.setUsername("ac05-owner");
        user.setPassword(password);
        user.setRole("user");
        user.setDeleted(0);
        context.getBean(AuthService.class).createUser(user);
        var workspace = new WorkspaceEntity();
        workspace.setName("AC05 isolated workspace");
        workspace.setSlug("ac05-isolated");
        workspace.setDeleted(0);
        context.getBean(WorkspaceService.class).create(workspace, user.getId());
        var checks = new LinkedHashMap<String, Object>();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var login =
                    request(
                            client,
                            json,
                            port,
                            "/api/v1/auth/login",
                            null,
                            null,
                            json.writeValueAsString(
                                    Map.of("username", user.getUsername(), "password", password)),
                            200,
                            null);
            String token = login.path("data").path("token").asText();
            require(!token.isBlank(), "Login token missing");
            String scope = workspace.getId().toString();
            var capabilities =
                    request(
                            client,
                            json,
                            port,
                            "/api/v1/bidding/capabilities",
                            token,
                            scope,
                            null,
                            bidding ? 200 : 404,
                            bidding ? null : "BIDDING_DISABLED");
            if (bidding)
                require(
                        capabilities.path("data").path("enabled").asBoolean(),
                        "Bidding enabled capability");
            checks.put("bidding", bidding ? "200_ENABLED" : "404_BIDDING_DISABLED");
            String projectId = "unused-when-presales-disabled";
            if (presales) {
                var payload =
                        json.createObjectNode()
                                .put("name", "AC05 project")
                                .put("customer", "Isolated fixture")
                                .put("expectedVersion", 0)
                                .put("operationId", "ac05-create-project");
                var created =
                        request(
                                client,
                                json,
                                port,
                                "/api/v1/presales/projects",
                                token,
                                scope,
                                json.writeValueAsString(payload),
                                200,
                                null);
                projectId = created.path("data").path("id").asText();
                require(!projectId.isBlank(), "Created project id missing");
                var saved =
                        request(
                                client,
                                json,
                                port,
                                "/api/v1/presales/projects/" + projectId,
                                token,
                                scope,
                                null,
                                200,
                                null);
                require(
                        projectId.equals(saved.path("data").path("id").asText()),
                        "Project read-back identity");
                var statements =
                        request(
                                client,
                                json,
                                port,
                                "/api/v1/presales/projects/" + projectId + "/statements",
                                token,
                                scope,
                                null,
                                semantic ? 200 : 409,
                                semantic ? null : "SEMANTIC_DISABLED");
                if (semantic)
                    require(
                            statements.path("data").isArray() && statements.path("data").isEmpty(),
                            "Empty project statements");
                checks.put("presales_project", "CREATE_READ_BACK_200");
                checks.put(
                        "semantic_dependency",
                        semantic ? "200_EMPTY_STATEMENTS" : "409_SEMANTIC_DISABLED");
            }
            if (bidding) {
                var options =
                        request(
                                client,
                                json,
                                port,
                                "/api/v1/bidding/handoff-options?presalesProjectId=" + projectId,
                                token,
                                scope,
                                null,
                                presales ? 200 : 409,
                                presales ? null : "PRESALES_UNAVAILABLE");
                if (presales)
                    require(
                            options.path("data").isArray() && options.path("data").isEmpty(),
                            "Empty published releases");
                checks.put(
                        "presales_dependency",
                        presales ? "200_EMPTY_OPTIONS" : "409_PRESALES_UNAVAILABLE");
            }
            checks.put(
                    "ordinary_chat",
                    OrdinaryChatProbe.verify(
                            context, client, json, port, token, scope, user.getId(), variant));
        }
        return checks;
    }

    static JsonNode request(
            HttpClient client,
            ObjectMapper json,
            int port,
            String path,
            String token,
            String workspace,
            String payload,
            int expectedStatus,
            String expectedCode)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (workspace != null) builder.header("X-Workspace-Id", workspace);
        if (payload == null) builder.GET();
        else
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        require(
                response.statusCode() == expectedStatus,
                path + " HTTP status " + response.statusCode() + " expected " + expectedStatus);
        var body = json.readTree(response.body());
        if (expectedCode != null)
            require(
                    expectedCode.equals(body.path("data").path("code").asText()),
                    path + " error code");
        return body;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
