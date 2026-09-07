package vip.mate.semantic.support;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.*;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import vip.mate.auth.controller.AuthController;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;
import vip.mate.config.JwtAuthFilter;
import vip.mate.config.MybatisPlusConfig;
import vip.mate.i18n.I18nService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.service.WorkspaceService;

import java.util.*;

@SpringBootTest(
        classes = SemanticHttpFixture.App.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:semantic_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "mateclaw.semantic.enabled=true",
            "spring.profiles.active=semantic-test",
            "spring.flyway.locations=classpath:db/migration/h2",
            "spring.flyway.placeholder-replacement=false",
            "mybatis-plus.configuration.map-underscore-to-camel-case=true"
        })
@AutoConfigureMockMvc
public abstract class SemanticHttpFixture {
    @Configuration
    @EnableWebSecurity
    @ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration.class,
        WebMvcAutoConfiguration.class
    })
    @ComponentScan("vip.mate.semantic")
    @MapperScan({
        "vip.mate.auth.repository",
        "vip.mate.workspace.core.repository",
        "vip.mate.workspace.conversation.repository",
        "vip.mate.agent.repository",
        "vip.mate.semantic.ontology.repository",
        "vip.mate.semantic.statement.repository"
    })
    @Import({
        AuthController.class,
        AuthService.class,
        WorkspaceService.class,
        JwtAuthFilter.class,
        MybatisPlusConfig.class,
        vip.mate.config.JacksonConfig.class,
        vip.mate.config.WorkspaceAccessInterceptor.class
    })
    public static class App {
        @Bean
        org.springframework.web.servlet.config.annotation.WebMvcConfigurer
                legacyWorkspaceInterceptor(vip.mate.config.WorkspaceAccessInterceptor interceptor) {
            return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
                @Override
                public void addInterceptors(
                        org.springframework.web.servlet.config.annotation.InterceptorRegistry
                                registry) {
                    registry.addInterceptor(interceptor);
                }
            };
        }

        @Bean
        BCryptPasswordEncoder encoder() {
            return new BCryptPasswordEncoder(4);
        }

        @Bean
        PersonalAccessTokenService pats() {
            return mock(PersonalAccessTokenService.class);
        }

        @Bean
        WikiKnowledgeBaseService wiki() {
            return mock(WikiKnowledgeBaseService.class);
        }

        @Bean
        I18nService i18n() {
            return mock(I18nService.class);
        }

        @Bean
        SecurityFilterChain chain(HttpSecurity http, JwtAuthFilter jwt) throws Exception {
            return http.csrf(c -> c.disable())
                    .authorizeHttpRequests(
                            a ->
                                    a.requestMatchers("/api/v1/auth/login")
                                            .permitAll()
                                            .anyRequest()
                                            .authenticated())
                    .exceptionHandling(
                            e -> e.authenticationEntryPoint((r, s, x) -> s.setStatus(401)))
                    .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected AuthService auth;
    @Autowired protected WorkspaceService workspaces;
    @Autowired protected org.springframework.jdbc.core.JdbcTemplate jdbc;
    protected String workspace, otherWorkspace;
    protected Map<String, String> tokens;

    @BeforeEach
    void initializeFixture() throws Exception {
        tokens = new HashMap<>();
        Map<String, Long> ids = new HashMap<>();
        for (String role : List.of("owner", "admin", "member", "viewer", "global")) {
            String name = "semantic_" + UUID.randomUUID();
            String password = UUID.randomUUID().toString();
            UserEntity user = new UserEntity();
            user.setUsername(name);
            user.setPassword(password);
            user.setRole(role.equals("global") ? "admin" : "user");
            user.setDeleted(0);
            auth.createUser(user);
            ids.put(role, user.getId());
            var login =
                    mvc.perform(
                                    post("/api/v1/auth/login")
                                            .contentType("application/json")
                                            .content(
                                                    json.writeValueAsString(
                                                            Map.of(
                                                                    "username",
                                                                    name,
                                                                    "password",
                                                                    password))))
                            .andReturn()
                            .getResponse();
            assertEquals(200, login.getStatus(), login.getContentAsString());
            tokens.put(
                    role,
                    "Bearer "
                            + json.readTree(login.getContentAsString())
                                    .path("data")
                                    .path("token")
                                    .asText());
        }
        WorkspaceEntity first = new WorkspaceEntity();
        first.setName("Semantic " + UUID.randomUUID());
        first.setDeleted(0);
        workspace = workspaces.create(first, ids.get("owner")).getId().toString();
        for (String role : List.of("admin", "member", "viewer"))
            workspaces.addMember(Long.valueOf(workspace), ids.get(role), role);
        WorkspaceEntity other = new WorkspaceEntity();
        other.setName("Other " + UUID.randomUUID());
        other.setDeleted(0);
        otherWorkspace = workspaces.create(other, ids.get("owner")).getId().toString();
    }

    protected JsonNode call(
            String method, String path, String role, String scope, Object body, int status)
            throws Exception {
        var response = request(method, path, role, scope, body);
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return response.getContentAsString().isEmpty()
                ? json.nullNode()
                : json.readTree(response.getContentAsString()).path("data");
    }

    protected org.springframework.mock.web.MockHttpServletResponse request(
            String method, String path, String role, String scope, Object body) throws Exception {
        MockHttpServletRequestBuilder req =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                org.springframework.http.HttpMethod.valueOf(method),
                                "/api/v1/semantic" + path)
                        .contentType("application/json");
        if (role != null) req.header("Authorization", tokens.get(role));
        if (scope != null) req.header("X-Workspace-Id", scope);
        if (body != null)
            req.content(new ObjectMapper().findAndRegisterModules().writeValueAsString(body));
        return mvc.perform(req).andReturn().getResponse();
    }

    protected String create() throws Exception {
        return call(
                        "POST",
                        "/ontologies",
                        "member",
                        workspace,
                        Map.of("name", "Equipment", "description", "initial"),
                        200)
                .path("id")
                .asText();
    }

    protected JsonNode draft(String id) throws Exception {
        return call("POST", "/ontologies/" + id + "/draft", "member", workspace, Map.of(), 200);
    }

    protected Map<String, Object> definition() {
        return Map.of(
                "types",
                List.of(Map.of("key", "Equipment", "label", "Equipment", "description", "")),
                "properties",
                List.of(
                        Map.of(
                                "key",
                                "voltage",
                                "label",
                                "Voltage",
                                "description",
                                "",
                                "ownerTypeKey",
                                "Equipment",
                                "valueType",
                                "DECIMAL",
                                "multiplicity",
                                "SINGLE",
                                "fixedUnit",
                                "V")),
                "relations",
                List.of());
    }

    protected Map<String, Object> saveBody(long version, Object definition) {
        return Map.of(
                "expectedDraftVersion",
                version,
                "name",
                "Equipment",
                "description",
                "initial",
                "definition",
                definition);
    }

    protected JsonNode save(String id, long version) throws Exception {
        return call(
                "PUT",
                "/ontologies/" + id + "/draft",
                "member",
                workspace,
                saveBody(version, definition()),
                200);
    }

    protected Map<String, Object> publishBody(long version, String op) {
        return Map.of("expectedDraftVersion", version, "operationId", op, "note", "reviewed");
    }

    protected JsonNode publish(String id, long version, String op) throws Exception {
        return call(
                "POST",
                "/ontologies/" + id + "/draft/publish",
                "owner",
                workspace,
                publishBody(version, op),
                200);
    }
}
