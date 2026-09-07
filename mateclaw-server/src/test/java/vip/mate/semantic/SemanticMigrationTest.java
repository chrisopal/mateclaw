package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

class SemanticMigrationTest {
    @Test
    void dialectDdlAndSingleDraftConstraint() throws Exception {
        for (String dialect : java.util.List.of("h2", "mysql", "kingbase")) {
            String mode = dialect.equals("kingbase") ? "PostgreSQL" : "MySQL";
            String url =
                    "jdbc:h2:mem:ddl_"
                            + UUID.randomUUID()
                            + ";MODE="
                            + mode
                            + ";DATABASE_TO_LOWER=TRUE";
            try (var connection = DriverManager.getConnection(url, "sa", "")) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
                        connection,
                        new org.springframework.core.io.ClassPathResource(
                                "db/migration/" + dialect + "/V191__semantic_ontology.sql"));
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(
                            "INSERT INTO"
                                + " mate_semantic_ontology(id,workspace_id,name,description,updated_at)"
                                + " VALUES('1',1,'name','',CURRENT_TIMESTAMP)");
                    statement.executeUpdate(
                            "INSERT INTO"
                                + " mate_semantic_ontology_revision(id,ontology_id,version,draft_version,revision_state,draft_slot,name,description,definition_json,available_for_new_bindings)"
                                + " VALUES('2','1',1,1,'DRAFT',1,'name','','{}',FALSE)");
                    assertThrows(
                            java.sql.SQLException.class,
                            () ->
                                    statement.executeUpdate(
                                            "INSERT INTO"
                                                + " mate_semantic_ontology_revision(id,ontology_id,version,draft_version,revision_state,draft_slot,name,description,definition_json,available_for_new_bindings)"
                                                + " VALUES('3','1',2,1,'DRAFT',1,'name','','{}',FALSE)"));
                }
            }
        }
    }

    @Test
    void emptyDatabaseAndUpgradeFrom190() throws Exception {
        for (boolean upgrade : new boolean[] {false, true}) {
            String url =
                    "jdbc:h2:mem:migration_"
                            + UUID.randomUUID()
                            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
            if (upgrade)
                Flyway.configure()
                        .dataSource(url, "sa", "")
                        .locations("classpath:db/migration/h2")
                        .placeholderReplacement(false)
                        .target("190")
                        .load()
                        .migrate();
            Flyway.configure()
                    .dataSource(url, "sa", "")
                    .locations("classpath:db/migration/h2")
                    .placeholderReplacement(false)
                    .load()
                    .migrate();
            try (var c = DriverManager.getConnection(url, "sa", "");
                    var s = c.createStatement()) {
                var r =
                        s.executeQuery(
                                "select count(*) from information_schema.tables where table_name in"
                                    + " ('mate_semantic_ontology','mate_semantic_ontology_revision','mate_semantic_command_record','mate_semantic_governance_record')");
                r.next();
                assertEquals(4, r.getInt(1));
            }
        }
    }
}
