package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
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

@SpringBootTest(classes = BiddingHttpFixture.App.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:bidding_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "mateclaw.semantic.enabled=false", "mateclaw.presales.enabled=false",
    "mateclaw.bidding.enabled=true", "mateclaw.bidding.scheduler-enabled=false",
    "spring.profiles.active=bidding-test", "spring.flyway.locations=classpath:db/migration/h2",
    "spring.flyway.placeholder-replacement=false", "mybatis-plus.configuration.map-underscore-to-camel-case=true"
})
@AutoConfigureMockMvc
abstract class BiddingHttpFixture {
    @Configuration @Profile("bidding-test") @EnableWebSecurity
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class, org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration.class,
        FlywayAutoConfiguration.class, MybatisPlusAutoConfiguration.class, JacksonAutoConfiguration.class,
        org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class})
    @ComponentScan("vip.mate.bidding")
    @MapperScan({"vip.mate.auth.repository", "vip.mate.workspace.core.repository", "vip.mate.workspace.conversation.repository", "vip.mate.agent.repository"})
    @Import({AuthController.class, AuthService.class, WorkspaceService.class, JwtAuthFilter.class,
        MybatisPlusConfig.class, vip.mate.config.JacksonConfig.class, vip.mate.config.WorkspaceAccessInterceptor.class})
    static class App {
        @Bean BCryptPasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
        @Bean PersonalAccessTokenService pats() { return org.mockito.Mockito.mock(PersonalAccessTokenService.class); }
        @Bean WikiKnowledgeBaseService wiki() { return org.mockito.Mockito.mock(WikiKnowledgeBaseService.class); }
        @Bean I18nService i18n() { return org.mockito.Mockito.mock(I18nService.class); }
        @Bean SecurityFilterChain chain(HttpSecurity http, JwtAuthFilter jwt) throws Exception {
            return http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.requestMatchers("/api/v1/auth/login").permitAll().anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((r,s,x) -> s.setStatus(401)))
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class).build();
        }
        @Bean org.springframework.web.servlet.config.annotation.WebMvcConfigurer workspaceInterceptor(vip.mate.config.WorkspaceAccessInterceptor interceptor) {
            return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
                @Override public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) { registry.addInterceptor(interceptor); }
            };
        }
    }
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected AuthService auth;
    @Autowired protected WorkspaceService workspaces;
    @Autowired protected org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired protected BiddingProperties biddingProperties;
    protected String workspace, otherWorkspace;
    protected Map<String,String> tokens;

    @BeforeEach void initializeFixture() throws Exception {
        biddingProperties.setEnabled(true);
        tokens = new HashMap<>(); Map<String,Long> ids = new HashMap<>();
        for (String role : List.of("owner", "member", "viewer")) {
            String name = "bidding_" + UUID.randomUUID(), password=UUID.randomUUID().toString(); UserEntity user = new UserEntity();
            user.setUsername(name); user.setPassword(password); user.setRole("user"); user.setDeleted(0);
            auth.createUser(user); ids.put(role,user.getId());
            var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType("application/json").content(json.writeValueAsString(Map.of("username",name,"password",password)))).andReturn().getResponse();
            assertEquals(200,response.getStatus(),response.getContentAsString());
            tokens.put(role,"Bearer "+json.readTree(response.getContentAsString()).path("data").path("token").asText());
        }
        WorkspaceEntity first = new WorkspaceEntity(); first.setName("Bidding " + UUID.randomUUID()); first.setDeleted(0);
        workspace=workspaces.create(first,ids.get("owner")).getId().toString();
        for(String role:List.of("member","viewer")) workspaces.addMember(Long.valueOf(workspace),ids.get(role),role);
        WorkspaceEntity other = new WorkspaceEntity(); other.setName("Other " + UUID.randomUUID()); other.setDeleted(0);
        otherWorkspace=workspaces.create(other,ids.get("owner")).getId().toString();
    }

    protected JsonNode api(String method,String path,String role,String scope,Object body,int expectedStatus) throws Exception {
        var response=request(method,path,role,scope,body); String content=response.getContentAsString();
        assertEquals(expectedStatus,response.getStatus(),content);
        JsonNode result=content.isEmpty()?json.nullNode():json.readTree(content);
        if(expectedStatus>=400) { if(!content.isEmpty()) assertTrue(content.contains("code"),content); return result; }
        return result.path("data");
    }
    private org.springframework.mock.web.MockHttpServletResponse request(String method,String path,String role,String scope,Object body) throws Exception {
        MockHttpServletRequestBuilder req=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
            org.springframework.http.HttpMethod.valueOf(method),"/api/v1/bidding"+path).contentType("application/json");
        if(role!=null) req.header("Authorization",tokens.get(role)); if(scope!=null) req.header("X-Workspace-Id",scope);
        if(body!=null) req.content(json.writeValueAsString(body)); return mvc.perform(req).andReturn().getResponse();
    }
    protected JsonNode project() throws Exception { return api("POST","/projects","member",workspace,Map.of("operationId",UUID.randomUUID().toString(),"name","测试项目","lotName","一标段"),200); }
    protected JsonNode command(JsonNode project,BiddingTypes.Ref expected,String action,Map<String,?> payload,String role,int expectedStatus) throws Exception {
        return api("POST","/projects/"+project.path("id").asText()+"/commands",role,workspace,
            Map.of("operationId",UUID.randomUUID().toString(),"expected",expected,"action",action,"payload",payload),expectedStatus);
    }
    protected BiddingTypes.Ref ref(JsonNode object) { return json.convertValue(object.path("ref"),BiddingTypes.Ref.class); }
}
