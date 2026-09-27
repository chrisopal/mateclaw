package vip.mate.bidding;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class BiddingMigrationTest {
    @Test void biddingIsDisabledByDefaultInApplicationConfiguration() throws Exception {
        MockEnvironment environment=new MockEnvironment();
        var sources=new YamlPropertySourceLoader().load("app",new ClassPathResource("application.yml"));
        sources.forEach(environment.getPropertySources()::addFirst);
        assertFalse(Binder.get(environment).bind("mateclaw.bidding",Bindable.of(BiddingProperties.class)).get().isEnabled());
    }

    @Test void v212PreservesPreexistingPresalesAndSemanticRowsAndCreatesIsolatedTables() throws Exception {
        String url="jdbc:h2:mem:bidding_migration_"+System.nanoTime()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway flyway=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration/h2")
            .placeholderReplacement(false).target(MigrationVersion.fromVersion("211")).load();
        flyway.migrate();
        try(var connection=DriverManager.getConnection(url,"sa",""); var statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO mate_presales_project(id,workspace_id,version,name,status,body_json) VALUES('legacy-pre','7',3,'保留售前','ACTIVE','{\"legacy\":true}')");
            statement.executeUpdate("INSERT INTO mate_semantic_ontology(id,workspace_id,name,description,draft_counter,updated_at) VALUES('legacy-sem',7,'保留本体','unchanged',0,CURRENT_TIMESTAMP)");
        }
        flyway=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration/h2").placeholderReplacement(false)
            .target(MigrationVersion.fromVersion("212")).load();
        flyway.migrate();
        byte[] legacySource = new byte[]{4, 2, 9};
        try(var connection=DriverManager.getConnection(url,"sa",""); var insert=connection.prepareStatement(
            "INSERT INTO mate_bidding_source(id,workspace_id,project_id,source_id,version,kind,digest,content,blocks_json,quality,created_at) VALUES('legacy-source','7','project','legacy-source-id',1,'TENDER','sha',?,'[]','PENDING',CURRENT_TIMESTAMP)")) {
            insert.setBytes(1,legacySource); insert.executeUpdate();
        }
        try(var connection=DriverManager.getConnection(url,"sa",""); var insert=connection.prepareStatement(
            "INSERT INTO mate_bidding_task(id,workspace_id,project_id,agent_id,skill_package_id,config_digest,input_json,input_refs_json,status,cycle_no,cycle_attempt,attempt_count,created_at,updated_at) VALUES('legacy-task','7','project','11','22','config','{}','[]','QUEUED',0,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
            insert.executeUpdate();
        }
        flyway=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration/h2").placeholderReplacement(false).load();
        flyway.migrate();
        try(var connection=DriverManager.getConnection(url,"sa",""); var statement=connection.createStatement()) {
            try(var rows=statement.executeQuery("SELECT id,body_json,version FROM mate_presales_project WHERE id='legacy-pre'")) {
                assertTrue(rows.next()); assertEquals("{\"legacy\":true}",rows.getString("body_json")); assertEquals(3,rows.getInt("version"));
            }
            try(var rows=statement.executeQuery("SELECT id,name FROM mate_semantic_ontology WHERE id='legacy-sem'")) {
                assertTrue(rows.next()); assertEquals("保留本体",rows.getString("name"));
            }
            assertEquals(0,scalar(statement,"SELECT COUNT(*) FROM mate_bidding_project"));
            assertEquals(12,scalar(statement,"SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) LIKE 'mate_bidding_%'"));
            try(var rows=statement.executeQuery("SELECT content,read_status,problems_json FROM mate_bidding_source WHERE id='legacy-source'")) {
                assertTrue(rows.next()); assertArrayEquals(legacySource,rows.getBytes("content"));
                assertEquals("PENDING",rows.getString("read_status")); assertEquals("[]",rows.getString("problems_json"));
            }
            assertEquals(1,scalar(statement,"SELECT COUNT(*) FROM mate_bidding_task WHERE id='legacy-task' AND actor_id IS NULL AND status='QUEUED'"));
            statement.executeUpdate("INSERT INTO mate_bidding_task(id,workspace_id,project_id,agent_id,skill_package_id,config_digest,input_json,input_refs_json,status,cycle_no,cycle_attempt,attempt_count,actor_id,created_at,updated_at) VALUES('new-task','7','project','11','22','config','{}','[]','QUEUED',0,0,0,'42',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            assertEquals(1,scalar(statement,"SELECT COUNT(*) FROM mate_bidding_task WHERE id='new-task' AND actor_id='42'"));
            byte[] largeContent=new byte[512*1024]; new java.util.Random(17).nextBytes(largeContent);
            try(var insert=connection.prepareStatement("INSERT INTO mate_bidding_source(id,workspace_id,project_id,source_id,version,kind,digest,content,blocks_json,quality,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)")) {
                insert.setString(1,"source-row"); insert.setString(2,"7"); insert.setString(3,"project"); insert.setString(4,"source");
                insert.setInt(5,1); insert.setString(6,"TENDER"); insert.setString(7,"digest"); insert.setBytes(8,largeContent);
                insert.setString(9,"{\"blocks\":[]}"); insert.setString(10,"COMPLETE"); insert.executeUpdate();
            }
            try(var rows=statement.executeQuery("SELECT content,blocks_json FROM mate_bidding_source WHERE id='source-row'")) {
                assertTrue(rows.next()); assertArrayEquals(largeContent,rows.getBytes("content")); assertEquals("{\"blocks\":[]}",rows.getString("blocks_json"));
            }
            byte[] artifactBytes=new byte[]{0,1,2,13,10,(byte)0xff,(byte)0x80,42};
            String artifactDigest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(artifactBytes));
            try(var insert=connection.prepareStatement("INSERT INTO mate_bidding_artifact(workspace_id,project_id,id,manuscript_ref_json,template_ref_json,format_ref_json,mode,format,digest,byte_size,content,checks_json,generator_attempt_id,status,decision_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)")) {
                insert.setString(1,"7"); insert.setString(2,"project"); insert.setString(3,"artifact-row");
                insert.setString(4,"{\"kind\":\"manuscript\",\"version\":3}"); insert.setString(5,"{\"kind\":\"template\",\"version\":1}");
                insert.setString(6,"{\"kind\":\"formatRequirements\",\"version\":2}"); insert.setString(7,"candidate"); insert.setString(8,"docx");
                insert.setString(9,artifactDigest); insert.setLong(10,artifactBytes.length); insert.setBytes(11,artifactBytes);
                insert.setString(12,"{\"structural\":\"PASS\"}"); insert.setString(13,"attempt-row"); insert.setString(14,"CANDIDATE"); insert.setString(15,null);
                assertEquals(1,insert.executeUpdate());
            }
            try(var rows=statement.executeQuery("SELECT manuscript_ref_json,template_ref_json,format_ref_json,digest,byte_size,content,status FROM mate_bidding_artifact WHERE workspace_id='7' AND project_id='project' AND id='artifact-row'")) {
                assertTrue(rows.next()); assertEquals("{\"kind\":\"manuscript\",\"version\":3}",rows.getString("manuscript_ref_json"));
                assertEquals("{\"kind\":\"template\",\"version\":1}",rows.getString("template_ref_json"));
                assertEquals("{\"kind\":\"formatRequirements\",\"version\":2}",rows.getString("format_ref_json"));
                byte[] readBack=rows.getBytes("content"); assertArrayEquals(artifactBytes,readBack); assertEquals((long)artifactBytes.length,rows.getLong("byte_size"));
                assertEquals(artifactDigest,rows.getString("digest")); assertEquals("CANDIDATE",rows.getString("status"));
            }
        }
    }
    private int scalar(java.sql.Statement s,String sql) throws Exception { try(var r=s.executeQuery(sql)) { assertTrue(r.next()); return r.getInt(1); } }
}
