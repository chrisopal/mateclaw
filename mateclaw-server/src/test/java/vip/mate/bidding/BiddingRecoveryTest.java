package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

class BiddingRecoveryTest extends BiddingHttpFixture {
    @Autowired BiddingRepository repository;

    @BeforeEach void clearOtherRunningClaims() { jdbc.update("UPDATE mate_bidding_task SET status='FAILED',active_attempt_id=NULL WHERE status='RUNNING'"); }

    @Test void isolatedH2MigrationBackupAndRestorePreservesBiddingRows() throws Exception {
        Path directory = Files.createTempDirectory("bidding-recovery-");
        String sourceUrl = "jdbc:h2:file:" + directory.resolve("source").toAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        String restoredUrl = "jdbc:h2:file:" + directory.resolve("restored").toAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        Path backup = directory.resolve("snapshot.sql");
        try {
            Flyway.configure().dataSource(sourceUrl, "sa", "")
                    .locations("classpath:db/migration/h2").placeholderReplacement(false).load().migrate();
            try (var connection = java.sql.DriverManager.getConnection(sourceUrl, "sa", "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO mate_bidding_project(id,workspace_id,project_id,owner_id,version,name,lot_name,stage,body_json,created_at,updated_at) "
                        + "VALUES('recovery-project','recovery-workspace','recovery-project','owner',1,'隔离演练','一标段','SOURCE','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                statement.execute("SCRIPT TO '" + backup.toString().replace("'", "''") + "'");
                statement.execute("SHUTDOWN");
            }
            try (var connection = java.sql.DriverManager.getConnection(restoredUrl, "sa", "");
                 var statement = connection.createStatement()) {
                statement.execute("RUNSCRIPT FROM '" + backup.toString().replace("'", "''") + "'");
                try (var rows = statement.executeQuery("SELECT name,workspace_id FROM mate_bidding_project WHERE id='recovery-project'")) {
                    assertTrue(rows.next());
                    assertEquals("隔离演练", rows.getString("name"));
                    assertEquals("recovery-workspace", rows.getString("workspace_id"));
                }
                try (var rows = statement.executeQuery("SELECT MAX(CAST(version AS INT)) FROM flyway_schema_history WHERE success=TRUE")) {
                    assertTrue(rows.next());
                    assertTrue(rows.getInt(1) >= 214, "the isolated restore should reach the latest H2 migration");
                }
            }
        } finally {
            try (var connection = java.sql.DriverManager.getConnection(sourceUrl, "sa", "");
                 var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (Exception ignored) { }
            try (var connection = java.sql.DriverManager.getConnection(restoredUrl, "sa", "");
                 var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (Exception ignored) { }
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    @Test void previousBootRunningAttemptBecomesUnknownAndCannotComplete() {
        String task=UUID.randomUUID().toString(), attempt=UUID.randomUUID().toString(), project=UUID.randomUUID().toString(), isolatedWorkspace=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(),isolatedWorkspace,project,"skill-1","v1","a".repeat(64),"{}");
        jdbc.update("INSERT INTO mate_bidding_task(id,workspace_id,project_id,actor_id,agent_id,skill_package_id,config_digest,input_json,input_refs_json,status,active_attempt_id,deadline_at,cycle_no,cycle_attempt,attempt_count,boot_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'RUNNING',?,DATEADD('SECOND',300,CURRENT_TIMESTAMP),0,1,1,'old-boot',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                task,isolatedWorkspace,project,"42","employee",jdbc.queryForObject("SELECT id FROM mate_bidding_skill_package WHERE workspace_id=? AND project_id=?",String.class,isolatedWorkspace,project),"b".repeat(64),"{}","[]",attempt);
        String token=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_bidding_attempt(id,workspace_id,project_id,task_id,attempt_no,token,state,tool_receipts_json,started_at) VALUES(?,?,?,?,1,?,'RUNNING','[]',CURRENT_TIMESTAMP)",
                attempt,isolatedWorkspace,project,task,token);

        assertEquals(1,repository.recoverInterrupted("new-boot",Instant.now()));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,task));
        assertNull(jdbc.queryForObject("SELECT deadline_at FROM mate_bidding_task WHERE id=?",java.sql.Timestamp.class,task));
        assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM mate_bidding_attempt WHERE id=?",String.class,attempt));
        assertTrue(jdbc.queryForObject("SELECT error_json FROM mate_bidding_attempt WHERE id=?",String.class,attempt).contains("\"resultUnknown\":true"));
        assertEquals(0,repository.recoverInterrupted("new-boot",Instant.now()));
    }

    @Test void expiredDeadlineOnCurrentBootFailsClosedWithoutTouchingSibling() {
        String task=UUID.randomUUID().toString(), attempt=UUID.randomUUID().toString();
        String workspace=UUID.randomUUID().toString(), project=UUID.randomUUID().toString();
        String sibling=UUID.randomUUID().toString(), siblingWorkspace=UUID.randomUUID().toString();
        String siblingProject=UUID.randomUUID().toString();
        String packageId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                packageId,workspace,project,"skill-1","v1","a".repeat(64),"{}");
        jdbc.update("INSERT INTO mate_bidding_skill_package(id,workspace_id,project_id,skill_id,version,digest,files_json,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(),siblingWorkspace,siblingProject,"skill-1","v1","c".repeat(64),"{}");
        jdbc.update("INSERT INTO mate_bidding_task(id,workspace_id,project_id,actor_id,agent_id,skill_package_id,config_digest,input_json,input_refs_json,status,active_attempt_id,deadline_at,cycle_no,cycle_attempt,attempt_count,boot_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'RUNNING',?,DATEADD('SECOND',-1,CURRENT_TIMESTAMP),0,1,1,'same-boot',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                task,workspace,project,"42","employee",packageId,"b".repeat(64),"{}","[]",attempt);
        jdbc.update("INSERT INTO mate_bidding_task(id,workspace_id,project_id,actor_id,agent_id,skill_package_id,config_digest,input_json,input_refs_json,status,cycle_no,cycle_attempt,attempt_count,boot_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',0,1,0,'same-boot',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sibling,siblingWorkspace,siblingProject,"43","employee",UUID.randomUUID().toString(),"d".repeat(64),"{}","[]");
        jdbc.update("INSERT INTO mate_bidding_attempt(id,workspace_id,project_id,task_id,attempt_no,token,state,tool_receipts_json,started_at) VALUES(?,?,?,?,1,?,'RUNNING','[]',CURRENT_TIMESTAMP)",
                attempt,workspace,project,task,UUID.randomUUID().toString());

        assertEquals(1,repository.recoverInterrupted("same-boot",Instant.now()));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,task));
        assertNull(jdbc.queryForObject("SELECT deadline_at FROM mate_bidding_task WHERE id=?",java.sql.Timestamp.class,task));
        assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM mate_bidding_attempt WHERE id=?",String.class,attempt));
        assertTrue(jdbc.queryForObject("SELECT error_json FROM mate_bidding_attempt WHERE id=?",String.class,attempt).contains("EXECUTION_INTERRUPTED"));
        assertEquals("QUEUED",jdbc.queryForObject("SELECT status FROM mate_bidding_task WHERE id=?",String.class,sibling));
    }
}
