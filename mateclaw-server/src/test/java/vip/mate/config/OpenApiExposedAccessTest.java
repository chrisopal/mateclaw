package vip.mate.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Local/dev posture: with {@code mateclaw.openapi.expose-ui=true} (the base {@code application.yml}
 * default) the Swagger UI / OpenAPI document stays anonymously reachable so developers can browse
 * and debug without a login.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.locations=classpath:db/migration/h2",
            "mateclaw.openapi.expose-ui=true"
        })
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:openapi_exposed_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver"
        })
class OpenApiExposedAccessTest {

    @Autowired private TestRestTemplate rest;

    @Test
    @DisplayName("Anonymous OpenAPI JSON is reachable (200) when expose-ui=true")
    void anonymousApiDocsReachable() {
        ResponseEntity<String> resp = rest.getForEntity("/v3/api-docs", String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }
}
