import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public class LegacyColumnRetirementGuardTest {
    public static void main(String[] args) throws Exception {
        try (var c = DriverManager.getConnection(System.getenv().getOrDefault("RETIREMENT_TEST_URL","jdbc:h2:mem:retirement-guard"),System.getenv().getOrDefault("SEMANTIC_RESET_DB_USER","sa"),System.getenv().getOrDefault("SEMANTIC_RESET_DB_PASSWORD",""))) {
            boolean mysql=c.getMetaData().getDatabaseProductName().equals("MySQL");
            c.createStatement().execute(("CREATE TABLE mate_semantic_ontology_revision(id VARCHAR(128) PRIMARY KEY,definition_json CLOB,model_schema VARCHAR(2048),document_text CLOB,document_syntax VARCHAR(2048),document_digest VARCHAR(2048),ontology_iri VARCHAR(2048),import_lock_digest VARCHAR(2048),imports_json CLOB,policy_json CLOB,version_iri VARCHAR(2048))").replace("CLOB",mysql?"LONGTEXT":System.getenv().getOrDefault("RETIREMENT_H2_TEXT_TYPE","CLOB")));
            if (LegacyColumnRetirementGuard.inspect(c).revisions() != 0) throw new AssertionError();
            String text = "Ontology(<urn:test:guard>)";
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            try (var p=c.prepareStatement("INSERT INTO mate_semantic_ontology_revision VALUES('r',NULL,'owl-document-v1',?,'FUNCTIONAL',?,'urn:test:guard',?,'[]','{\"version\":\"1\",\"rules\":[]}',NULL)")) {
                p.setString(1,text);p.setString(2,hash);p.setString(3,vip.mate.semantic.core.ontology.LockedImport.digest(java.util.List.of()));p.executeUpdate();
            }
            var before=LegacyColumnRetirementGuard.inspect(c);
            if(before.revisions()!=1)throw new AssertionError();
            reject(c,"definition_json='{}'","LEGACY_COLUMN_IN_USE");
            c.createStatement().execute("UPDATE mate_semantic_ontology_revision SET definition_json=NULL");
            reject(c,"model_schema=NULL","LEGACY_REVISION_REMAINS");
            c.createStatement().execute("UPDATE mate_semantic_ontology_revision SET model_schema='owl-document-v1'");
            reject(c,"document_syntax='UNKNOWN'","INVALID_OWL_SYNTAX");
            c.createStatement().execute("UPDATE mate_semantic_ontology_revision SET document_syntax='FUNCTIONAL'");
            reject(c,"document_text='Ontology()'","OWL_DIGEST_MISMATCH");
            try(var p=c.prepareStatement("UPDATE mate_semantic_ontology_revision SET document_text=?")){p.setString(1,text);p.executeUpdate();}
            reject(c,"import_lock_digest='wrong-lock'","OWL_VALIDATION_FAILED");
            try(var p=c.prepareStatement("UPDATE mate_semantic_ontology_revision SET import_lock_digest=?")){
                p.setString(1,vip.mate.semantic.core.ontology.LockedImport.digest(java.util.List.of()));p.executeUpdate();
            }
            reject(c,"ontology_iri='urn:test:wrong'","OWL_VALIDATION_FAILED");
            c.createStatement().execute("UPDATE mate_semantic_ontology_revision SET ontology_iri='urn:test:guard'");
            reject(c,"policy_json='not-json'","OWL_VALIDATION_FAILED");
            c.createStatement().execute("UPDATE mate_semantic_ontology_revision SET policy_json='{\"version\":\"1\",\"rules\":[]}'");
            String malformed = "Ontology(<urn:test:guard> Declaration(Class()))";
            try(var p=c.prepareStatement("UPDATE mate_semantic_ontology_revision SET document_text=?,document_digest=?")){
                p.setString(1,malformed);p.setString(2,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(malformed.getBytes(StandardCharsets.UTF_8))));p.executeUpdate();
            }
            try{LegacyColumnRetirementGuard.inspect(c);throw new AssertionError("Malformed OWL accepted with valid hash");}
            catch(SQLException invalid){if(!invalid.getMessage().startsWith("OWL_VALIDATION_FAILED"))throw invalid;}
            try(var p=c.prepareStatement("UPDATE mate_semantic_ontology_revision SET document_text=?,document_digest=?")){
                p.setString(1,text);p.setString(2,hash);p.executeUpdate();
            }
            if(!before.equals(LegacyColumnRetirementGuard.inspect(c)))throw new AssertionError("Read-only guard altered data");
            var manifest=java.nio.file.Files.createTempDirectory("retirement-ddl-test-").resolve("plan.properties");
            c.createStatement().execute("ALTER TABLE mate_semantic_ontology_revision ADD CONSTRAINT legacy_check CHECK (definition_json IS NULL)");
            try{LegacyColumnRetirement.run(c,manifest,"plan");throw new AssertionError("CHECK dependency accepted");}
            catch(SQLException expected){if(!expected.getMessage().startsWith("LEGACY_COLUMN_CHECK_CONSTRAINT"))throw expected;}
            c.createStatement().execute("ALTER TABLE mate_semantic_ontology_revision DROP "+(mysql?"CHECK ":"CONSTRAINT ")+"legacy_check");
            LegacyColumnRetirement.run(c,manifest,"plan");
            if(!"CONFIRMED_OFFLINE_STOPPED".equals(System.getenv("SEMANTIC_RESET_WRITER_FENCE"))){
                try{LegacyColumnRetirement.run(c,manifest,"apply");throw new AssertionError("Missing writer fence accepted");}
                catch(IllegalStateException expected){if(!expected.getMessage().startsWith("WRITERS_NOT_STOPPED"))throw expected;}
                if(!LegacyColumnRetirement.columnPresent(c))throw new AssertionError("Rejected operation removed column");
                System.out.println("Missing writer fence rejected with column unchanged");return;
            }

            c.createStatement().execute("UPDATE mate_semantic_ontology_revision SET policy_json='{\"version\":\"2\",\"rules\":[]}'");
            try{LegacyColumnRetirement.run(c,manifest,"apply");throw new AssertionError("Changed data accepted");}
            catch(IllegalStateException expected){if(!expected.getMessage().equals("DOCUMENTS_CHANGED"))throw expected;}
            c.createStatement().execute("UPDATE mate_semantic_ontology_revision SET policy_json='{\"version\":\"1\",\"rules\":[]}'");
            LegacyColumnRetirement.run(c,manifest,"apply");
            if(LegacyColumnRetirement.columnPresent(c))throw new AssertionError("Column still present");
            LegacyColumnRetirement.run(c,manifest,"apply");
            LegacyColumnRetirement.run(c,manifest,"restore");
            LegacyColumnRetirement.run(c,manifest,"restore");
            if(!before.equals(LegacyColumnRetirementGuard.inspect(c)))throw new AssertionError("DDL restore changed documents");
            var recovery=manifest.resolveSibling("recovery.properties");
            LegacyColumnRetirement.run(c,recovery,"plan");
            state(recovery,"APPLYING");
            c.createStatement().execute("ALTER TABLE mate_semantic_ontology_revision DROP COLUMN definition_json");
            LegacyColumnRetirement.run(c,recovery,"apply");
            state(recovery,"RESTORING");
            c.createStatement().execute("ALTER TABLE mate_semantic_ontology_revision ADD COLUMN definition_json "+(mysql?"LONGTEXT":System.getenv().getOrDefault("RETIREMENT_H2_TEXT_TYPE","CLOB"))+" NULL");
            LegacyColumnRetirement.run(c,recovery,"restore");
            if(!before.equals(LegacyColumnRetirementGuard.inspect(c)))throw new AssertionError("Interrupted DDL recovery changed documents");

            System.out.println("Retirement guard: empty/new rows accepted; retained legacy, missing schema, invalid syntax and digest drift rejected; data unchanged");
        }
    }
    static void state(java.nio.file.Path file,String status)throws Exception{
        var p=new java.util.Properties();try(var in=java.nio.file.Files.newInputStream(file)){p.load(in);}
        p.setProperty("status",status);try(var out=java.nio.file.Files.newOutputStream(file)){p.store(out,"Injected interruption state");}
    }
    static void reject(Connection c,String assignment,String code)throws Exception {
        c.createStatement().execute("UPDATE mate_semantic_ontology_revision SET "+assignment);
        try {LegacyColumnRetirementGuard.inspect(c);throw new AssertionError("Accepted "+code);}
        catch(SQLException e){if(!e.getMessage().startsWith(code))throw e;}
    }
}
