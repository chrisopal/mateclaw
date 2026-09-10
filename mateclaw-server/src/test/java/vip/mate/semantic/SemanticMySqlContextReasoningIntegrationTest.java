package vip.mate.semantic;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.TestPropertySource;

/** Runs context and reasoning continuation contracts on a disposable MySQL database. */
@EnabledIfEnvironmentVariable(named = "SEMANTIC_MYSQL_TEST_URL", matches = ".+")
@TestPropertySource(
        properties = {
            "spring.datasource.url=${SEMANTIC_MYSQL_TEST_URL}",
            "spring.datasource.username=${SEMANTIC_MYSQL_TEST_USER}",
            "spring.datasource.password=${SEMANTIC_MYSQL_TEST_PASSWORD}",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.flyway.locations=classpath:db/migration/mysql"
        })
class SemanticMySqlContextReasoningIntegrationTest extends SemanticContextReasoningIntegrationTest {}
