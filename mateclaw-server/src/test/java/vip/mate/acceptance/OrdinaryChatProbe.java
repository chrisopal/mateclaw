package vip.mate.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.llm.model.CreateCustomProviderRequest;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.tool.model.ToolEntity;
import vip.mate.tool.service.ToolService;

/** Local external model protocol fixture; the application and its tool executor remain real. */
final class OrdinaryChatProbe {
    private static final String MODEL = "ac01-probe";
    private static final String PROVIDER = "ac01-local";
    private static final String PRIMARY_PROVIDER = "ac01-primary";
    private static final String PRIMARY_MODEL = "ac01-primary-model";
    private static final String PROMPT = "AC01 clock probe";
    private static final String ANSWER = "AC01_CLOCK_OK";
    private static final String CALL = "call_ac01";
    private static final String TOOL = "getCurrentDateTime";

    static Map<String, Object> verify(
            ConfigurableApplicationContext context,
            HttpClient client,
            ObjectMapper json,
            int port,
            String token,
            String workspace,
            Long owner,
            String variant)
            throws Exception {
        var model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var primary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var primaryCalls = new AtomicInteger();
        var calls = new AtomicInteger();
        var probes = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var advertised = new AtomicReference<List<String>>();
        model.createContext(
                "/",
                exchange -> {
                    boolean headersSent = false;
                    try {
                        if (exchange.getRequestMethod().equals("GET")
                                && exchange.getRequestURI().getPath().equals("/v1/models")) {
                            probes.incrementAndGet();
                            var catalog = json.createObjectNode().put("object", "list");
                            catalog.putArray("data")
                                    .addObject()
                                    .put("id", MODEL)
                                    .put("object", "model")
                                    .put("created", 1)
                                    .put("owned_by", "ac01-fixture");
                            byte[] bytes = json.writeValueAsBytes(catalog);
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, bytes.length);
                            headersSent = true;
                            exchange.getResponseBody().write(bytes);
                            return;
                        }
                        require(
                                exchange.getRequestMethod().equals("POST")
                                        && exchange.getRequestURI()
                                                .getPath()
                                                .equals("/v1/chat/completions"),
                                "Unexpected model route "
                                        + exchange.getRequestMethod()
                                        + " "
                                        + exchange.getRequestURI().getPath());
                        var input = json.readTree(exchange.getRequestBody());
                        require(MODEL.equals(input.path("model").asText()), "Unexpected model");
                        require(
                                input.path("stream").asBoolean(),
                                "Expected streaming model request");
                        int round = calls.incrementAndGet();
                        ObjectNode delta = json.createObjectNode().put("role", "assistant");
                        if (round == 1) {
                            var names = new ArrayList<String>();
                            input.path("tools")
                                    .forEach(
                                            tool ->
                                                    names.add(
                                                            tool.path("function")
                                                                    .path("name")
                                                                    .asText()));
                            require(names.contains("tool_call"), "Progressive tool bridge missing");
                            require(
                                    input.path("messages").toString().contains(TOOL),
                                    "Clock absent from progressive catalog");
                            require(
                                    !names.contains("project_document_read")
                                            && names.stream()
                                                    .noneMatch(
                                                            name ->
                                                                    name.startsWith(
                                                                            "semantic_ontology_")),
                                    "Project-only tool leaked");
                            String catalogMessages = input.path("messages").toString();
                            require(
                                    !catalogMessages.contains("project_document_read")
                                            && !catalogMessages.contains("semantic_ontology_"),
                                    "Project-only tool leaked through progressive catalog");
                            advertised.set(names.stream().sorted().toList());
                            require(
                                    input.path("messages").toString().contains(PROMPT),
                                    "User prompt missing from model input");
                            delta.putArray("tool_calls")
                                    .addObject()
                                    .put("index", 0)
                                    .put("id", CALL)
                                    .put("type", "function")
                                    .putObject("function")
                                    .put("name", "tool_call")
                                    .put(
                                            "arguments",
                                            "{\"toolName\":\"getCurrentDateTime\",\"arguments\":{}}");
                        } else {
                            require(round == 2, "Unexpected extra model request");
                            boolean actualResult = false;
                            for (var message : input.path("messages")) {
                                if ("tool".equals(message.path("role").asText())
                                        && CALL.equals(message.path("tool_call_id").asText())) {
                                    actualResult =
                                            message.path("content")
                                                    .asText()
                                                    .matches(
                                                            ".*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*");
                                }
                            }
                            require(actualResult, "Real clock tool result missing");
                            delta.put("content", ANSWER);
                        }
                        String wire =
                                chunk(json, delta, null)
                                        + chunk(
                                                json,
                                                json.createObjectNode(),
                                                round == 1 ? "tool_calls" : "stop")
                                        + "data: [DONE]\n\n";
                        byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, bytes.length);
                        headersSent = true;
                        exchange.getResponseBody().write(bytes);
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        if (!headersSent) exchange.sendResponseHeaders(500, -1);
                    } finally {
                        exchange.close();
                    }
                });
        model.start();
        primary.createContext(
                "/",
                exchange -> {
                    try {
                        if (exchange.getRequestMethod().equals("GET")
                                && exchange.getRequestURI().getPath().equals("/v1/models")) {
                            var models = json.createObjectNode().put("object", "list");
                            models.putArray("data")
                                    .addObject()
                                    .put("id", PRIMARY_MODEL)
                                    .put("object", "model")
                                    .put("created", 1)
                                    .put("owned_by", "ac01-primary-fixture");
                            byte[] catalog = json.writeValueAsBytes(models);
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, catalog.length);
                            exchange.getResponseBody().write(catalog);
                        } else {
                            require(
                                    exchange.getRequestMethod().equals("POST")
                                            && exchange.getRequestURI()
                                                    .getPath()
                                                    .equals("/v1/chat/completions"),
                                    "Unexpected primary model route");
                            var input = json.readTree(exchange.getRequestBody());
                            require(
                                    PRIMARY_MODEL.equals(input.path("model").asText()),
                                    "Unexpected primary model name");
                            primaryCalls.incrementAndGet();
                            byte[] body =
                                    json.writeValueAsBytes(
                                            Map.of(
                                                    "error",
                                                    Map.of(
                                                            "message",
                                                            "AC01 deterministic primary outage",
                                                            "type",
                                                            "authentication_error")));
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(401, body.length);
                            exchange.getResponseBody().write(body);
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        try {
                            exchange.sendResponseHeaders(500, -1);
                        } catch (Exception ignored) {
                        }
                    } finally {
                        exchange.close();
                    }
                });
        primary.start();
        try {
            var primaryProvider = new CreateCustomProviderRequest();
            primaryProvider.setId(PRIMARY_PROVIDER);
            primaryProvider.setName("AC01 primary failure fixture");
            primaryProvider.setProtocol("openai-compatible");
            primaryProvider.setDefaultBaseUrl("http://127.0.0.1:" + primary.getAddress().getPort());
            primaryProvider.setRequireApiKey(false);
            context.getBean(ModelProviderService.class).createCustomProvider(primaryProvider);
            var provider = new CreateCustomProviderRequest();
            provider.setId(PROVIDER);
            provider.setName("AC01 local protocol fixture");
            provider.setProtocol("openai-compatible");
            provider.setDefaultBaseUrl("http://127.0.0.1:" + model.getAddress().getPort());
            provider.setRequireApiKey(false);
            context.getBean(ModelProviderService.class).createCustomProvider(provider);
            var config = new ModelConfigEntity();
            config.setName("AC01 fallback model");
            config.setProvider(PROVIDER);
            config.setModelName(MODEL);
            config.setModelType("chat");
            config.setEnabled(true);
            config.setIsDefault(false);
            config.setMaxTokens(128);
            config.setMaxInputTokens(8192);
            config.setRequestTimeoutSeconds(10);
            context.getBean(ModelConfigService.class).createModel(config);
            var primaryConfig = new ModelConfigEntity();
            primaryConfig.setName("AC01 configured primary");
            primaryConfig.setProvider(PRIMARY_PROVIDER);
            primaryConfig.setModelName(PRIMARY_MODEL);
            primaryConfig.setModelType("chat");
            primaryConfig.setEnabled(true);
            primaryConfig.setIsDefault(true);
            primaryConfig.setMaxTokens(128);
            primaryConfig.setMaxInputTokens(8192);
            primaryConfig.setRequestTimeoutSeconds(10);
            context.getBean(ModelConfigService.class).createModel(primaryConfig);
            var fallbackPriority = new vip.mate.llm.model.ProviderConfigRequest();
            fallbackPriority.setBaseUrl("http://127.0.0.1:" + model.getAddress().getPort());
            fallbackPriority.setProtocol("openai-compatible");
            fallbackPriority.setRequireApiKey(false);
            fallbackPriority.setFallbackPriority(1);
            context.getBean(ModelProviderService.class)
                    .updateProviderConfig(PROVIDER, fallbackPriority);
            var agent = new AgentEntity();
            agent.setName("AC01 ordinary chat");
            agent.setWorkspaceId(Long.valueOf(workspace));
            agent.setCreatorUserId(owner);
            agent.setModelName(PRIMARY_MODEL);
            agent.setMaxIterations(4);
            agent.setSkillsDisabled(true);
            agent.setWikiDisabled(true);
            context.getBean(AgentService.class).createAgent(agent);
            var clock = new ToolEntity();
            clock.setName(TOOL);
            clock.setDisplayName("AC01 clock");
            clock.setToolType("builtin");
            clock.setBeanName("dateTimeTool");
            clock.setDisclosureTier("core");
            clock.setDeleted(0);
            context.getBean(ToolService.class).createTool(clock);
            context.getBean(AgentBindingService.class)
                    .setToolBindings(agent.getId(), List.of(TOOL));
            var jdbc = context.getBean(JdbcTemplate.class);
            var projectTables =
                    jdbc.queryForList(
                            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='public' AND (LOWER(TABLE_NAME) LIKE '%presales%' OR LOWER(TABLE_NAME) LIKE '%bidding%')",
                            String.class);
            require(
                    projectTables.contains("mate_presales_project")
                            && projectTables.contains("mate_bidding_project"),
                    "Project schema inventory incomplete");
            resetObservation(jdbc);
            jdbc.queryForObject("SELECT COUNT(*) FROM mate_presales_project", Integer.class);
            jdbc.queryForObject("SELECT COUNT(*) FROM mate_bidding_project", Integer.class);
            var control = observedSql(jdbc);
            require(
                    control.stream()
                            .anyMatch(
                                    sql ->
                                            sql.toLowerCase(Locale.ROOT)
                                                    .contains("mate_presales_project")),
                    "Presales query observation control failed");
            require(
                    control.stream()
                            .anyMatch(
                                    sql ->
                                            sql.toLowerCase(Locale.ROOT)
                                                    .contains("mate_bidding_project")),
                    "Bidding query observation control failed");
            resetObservation(jdbc);
            String conversation = "ac01-" + variant;
            var payload =
                    json.createObjectNode()
                            .put("agentId", agent.getId().toString())
                            .put("conversationId", conversation)
                            .put("message", PROMPT)
                            .put("modelProvider", PRIMARY_PROVIDER)
                            .put("modelName", PRIMARY_MODEL)
                            .put("thinkingLevel", "off");
            var response =
                    send(
                            client,
                            request(port, "/api/v1/chat/stream", token, workspace)
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "text/event-stream")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    json.writeValueAsString(payload)))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (failure.get() != null)
                throw new IllegalStateException("Local model fixture failed", failure.get());
            require(response.statusCode() == 200, "Chat HTTP status " + response.statusCode());
            var events = parseEvents(json, response.body());
            require(!events.containsKey("error"), "Chat error event");
            require(
                    events.containsKey("tool_call_started")
                            && events.containsKey("tool_call_completed"),
                    "Tool SSE events missing");
            var done = events.get("done");
            require(done != null && done.size() == 1, "Exactly one done event required");
            require(
                    "completed".equals(done.getFirst().path("status").asText())
                            && done.getFirst().path("persisted").asBoolean(),
                    "Chat did not complete and persist");
            StringBuilder content = new StringBuilder();
            events.getOrDefault("content_delta", List.of())
                    .forEach(event -> content.append(event.path("delta").asText()));
            require(ANSWER.contentEquals(content), "SSE answer mismatch");
            var historyResponse =
                    send(
                            client,
                            request(
                                            port,
                                            "/api/v1/conversations/" + conversation + "/messages",
                                            token,
                                            workspace)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            require(historyResponse.statusCode() == 200, "History HTTP status");
            var messages = json.readTree(historyResponse.body()).path("data");
            require(messages.isArray(), "History response must contain messages");
            int users = 0, assistants = 0;
            for (var message : messages) {
                if ("user".equals(message.path("role").asText())) {
                    users++;
                    require(PROMPT.equals(message.path("content").asText()), "Saved user content");
                }
                if ("assistant".equals(message.path("role").asText())) {
                    assistants++;
                    require(
                            ANSWER.equals(message.path("content").asText()),
                            "Saved assistant content");
                    require(
                            "completed".equals(message.path("status").asText()),
                            "Saved assistant status");
                    require(
                            MODEL.equals(message.path("runtimeModel").asText())
                                    && PROVIDER.equals(message.path("runtimeProvider").asText()),
                            "Saved fallback model attribution");
                    require(
                            message.path("id")
                                    .asText()
                                    .equals(done.getFirst().path("assistantMessageId").asText()),
                            "Done/history message identity mismatch");
                    JsonNode metadata = message.path("metadata");
                    if (metadata.isTextual()) metadata = json.readTree(metadata.asText());
                    boolean savedTool = false;
                    for (var tool : metadata.path("toolCalls")) {
                        if (TOOL.equals(tool.path("name").asText())
                                && tool.path("success").asBoolean()
                                && "completed".equals(tool.path("status").asText()))
                            savedTool = true;
                    }
                    require(savedTool, "Successful tool metadata not persisted");
                }
            }
            require(users == 1 && assistants == 1, "Unexpected persisted message counts");
            require(calls.get() == 2, "Expected two actual fallback reasoning requests");
            require(primaryCalls.get() == 1, "Primary must fail once before fallback succeeds");
            var sql = settledSql(jdbc);
            require(
                    !sql.isEmpty() && sql.size() < 10000,
                    "SQL observation empty or near retention limit");
            require(
                    sql.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("mate_message")),
                    "Conversation SQL not observed");
            for (String statement : sql)
                for (String table : projectTables) {
                    require(
                            !java.util.regex.Pattern.compile(
                                            "\\b" + java.util.regex.Pattern.quote(table) + "\\b",
                                            java.util.regex.Pattern.CASE_INSENSITIVE)
                                    .matcher(statement)
                                    .find(),
                            "Ordinary chat accessed project table: " + table);
                }
            if (failure.get() != null)
                throw new IllegalStateException("Late local model fixture failure", failure.get());
            require(calls.get() == 2, "Unexpected late model request");
            var hashes = new ArrayList<String>();
            for (String statement : sql)
                hashes.add(
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(
                                                        statement.getBytes(
                                                                StandardCharsets.UTF_8))));
            jdbc.execute("SET QUERY_STATISTICS FALSE");
            return Map.ofEntries(
                    Map.entry("model_fixture", "loopback OpenAI-compatible synthetic"),
                    Map.entry("primary_provider", PRIMARY_PROVIDER),
                    Map.entry("primary_failed_requests", primaryCalls.get()),
                    Map.entry("fallback_provider", PROVIDER),
                    Map.entry("fallback_model", MODEL),
                    Map.entry("reasoning_requests", calls.get()),
                    Map.entry("provider_catalog_requests", probes.get()),
                    Map.entry("tool", TOOL),
                    Map.entry("tool_transport", "tool_call progressive bridge"),
                    Map.entry("advertised_tools", advertised.get()),
                    Map.entry("project_table_inventory", projectTables),
                    Map.entry("persisted_user_messages", users),
                    Map.entry("persisted_assistant_messages", assistants),
                    Map.entry("project_query_positive_controls", 2),
                    Map.entry("project_table_queries", 0),
                    Map.entry("observed_sql_shapes", sql.size()),
                    Map.entry("sql_shape_sha256", hashes));
        } finally {
            model.stop(0);
            primary.stop(0);
        }
    }

    private static String chunk(ObjectMapper json, ObjectNode delta, String finish)
            throws Exception {
        var body =
                json.createObjectNode()
                        .put("id", "ac01-completion")
                        .put("object", "chat.completion.chunk")
                        .put("created", 1)
                        .put("model", MODEL);
        var choice = body.putArray("choices").addObject().put("index", 0);
        choice.set("delta", delta);
        if (finish == null) choice.putNull("finish_reason");
        else choice.put("finish_reason", finish);
        return "data: " + json.writeValueAsString(body) + "\n\n";
    }

    private static HttpResponse<String> send(
            HttpClient client, HttpRequest request, HttpResponse.BodyHandler<String> handler)
            throws Exception {
        var pending = client.sendAsync(request, handler);
        try {
            return pending.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pending.cancel(true);
        }
    }

    private static HttpRequest.Builder request(
            int port, String path, String token, String workspace) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("X-Workspace-Id", workspace);
    }

    private static Map<String, List<JsonNode>> parseEvents(ObjectMapper json, String wire)
            throws Exception {
        var events = new LinkedHashMap<String, List<JsonNode>>();
        for (String frame : wire.split("\\r?\\n\\r?\\n")) {
            String event = null;
            StringBuilder data = new StringBuilder();
            for (String line : frame.split("\\r?\\n")) {
                if (line.startsWith("event:")) event = line.substring(6).trim();
                if (line.startsWith("data:")) data.append(line.substring(5).trim());
            }
            if (event != null && !data.isEmpty())
                events.computeIfAbsent(event, ignored -> new ArrayList<>())
                        .add(json.readTree(data.toString()));
        }
        return events;
    }

    private static void resetObservation(JdbcTemplate jdbc) {
        jdbc.execute("SET QUERY_STATISTICS FALSE");
        jdbc.execute("SET QUERY_STATISTICS_MAX_ENTRIES 100000");
        jdbc.execute("SET QUERY_STATISTICS TRUE");
    }

    private static List<String> settledSql(JdbcTemplate jdbc) throws Exception {
        Map<String, Long> previous = sqlCounts(jdbc);
        int stable = 0;
        for (int i = 0; i < 5; i++) {
            Thread.sleep(1000);
            Map<String, Long> current = sqlCounts(jdbc);
            stable = current.equals(previous) ? stable + 1 : 0;
            if (stable == 2) return List.copyOf(current.keySet());
            previous = current;
        }
        throw new IllegalStateException("SQL observation did not settle in five seconds");
    }

    private static Map<String, Long> sqlCounts(JdbcTemplate jdbc) {
        var counts = new LinkedHashMap<String, Long>();
        jdbc.query(
                "SELECT SQL_STATEMENT, EXECUTION_COUNT FROM INFORMATION_SCHEMA.QUERY_STATISTICS",
                row -> {
                    String sql = row.getString(1);
                    if (!sql.toUpperCase(Locale.ROOT)
                            .contains("INFORMATION_SCHEMA.QUERY_STATISTICS"))
                        counts.put(sql, row.getLong(2));
                });
        return counts;
    }

    private static List<String> observedSql(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT SQL_STATEMENT FROM INFORMATION_SCHEMA.QUERY_STATISTICS", String.class);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
