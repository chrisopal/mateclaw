package vip.mate.semantic;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.TestPropertySource;

/** Only use a disposable database. Never point these tests at a user database. */
@EnabledIfEnvironmentVariable(named="SEMANTIC_MYSQL_TEST_URL",matches=".+")
@TestPropertySource(properties={
    "spring.datasource.url=${SEMANTIC_MYSQL_TEST_URL}",
    "spring.datasource.username=${SEMANTIC_MYSQL_TEST_USER}",
    "spring.datasource.password=${SEMANTIC_MYSQL_TEST_PASSWORD}",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
    "spring.flyway.locations=classpath:db/migration/mysql"
})
class SemanticMySqlIntegrationTest extends SemanticEndToEndIntegrationTest {
    @org.junit.jupiter.api.Test
    @EnabledIfEnvironmentVariable(named="SEMANTIC_MYSQL_UPGRADE_URL",matches=".+")
    void upgradesAnIsolatedVersion190Database() throws Exception {
        String url=System.getenv("SEMANTIC_MYSQL_UPGRADE_URL");
        String user=System.getenv("SEMANTIC_MYSQL_TEST_USER"), password=System.getenv("SEMANTIC_MYSQL_TEST_PASSWORD");
        org.flywaydb.core.Flyway.configure().dataSource(url,user,password)
                .locations("classpath:db/migration/mysql").placeholderReplacement(false).target("190").load().migrate();
        var flyway=org.flywaydb.core.Flyway.configure().dataSource(url,user,password)
                .locations("classpath:db/migration/mysql").placeholderReplacement(false).load();
        flyway.migrate(); flyway.validate();
        org.junit.jupiter.api.Assertions.assertEquals("207",flyway.info().current().getVersion().getVersion());
        try(var connection=java.sql.DriverManager.getConnection(url,user,password);
            var statement=connection.createStatement();
            var rows=statement.executeQuery("SELECT enabled FROM mate_tool WHERE bean_name='semanticTool'")) {
            org.junit.jupiter.api.Assertions.assertTrue(rows.next());
            org.junit.jupiter.api.Assertions.assertFalse(rows.getBoolean(1));
        }
    }
}
