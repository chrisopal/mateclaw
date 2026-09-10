package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Dialect smoke tests use H2 emulation; they do not certify native MySQL/Kingbase. */
class SemanticOwlSchemaTest {
    @ParameterizedTest
    @ValueSource(strings = {"h2", "mysql", "kingbase"})
    void expansionPreservesLegacyDataAndAllowsNewDocumentWithoutLegacyJson(String dialect)
            throws Exception {
        try (Connection connection = connection(dialect)) {
            migrate(connection, dialect, "V191__semantic_ontology.sql");
            migrate(connection, dialect, "V192__semantic_graph.sql");
            execute(connection, "INSERT INTO mate_semantic_ontology(id,workspace_id,name,description,updated_at) VALUES('o',1,'test','',CURRENT_TIMESTAMP)");
            execute(connection, "INSERT INTO mate_semantic_ontology_revision(id,ontology_id,version,draft_version,revision_state,draft_slot,name,description,definition_json,available_for_new_bindings) VALUES('old','o',0,1,'DRAFT',1,'test','','{}',false)");
            migrate(connection, dialect, "V199__semantic_owl_document.sql");
            try (var result = connection.createStatement().executeQuery("SELECT definition_json,document_text,model_schema FROM mate_semantic_ontology_revision WHERE id='old'")) {
                assertTrue(result.next());
                assertEquals("{}", result.getString(1));
                assertNull(result.getString(2));
                assertNull(result.getString(3), "legacy rows must not be falsely marked as OWL");
            }
            execute(connection, "INSERT INTO mate_semantic_ontology_revision(id,ontology_id,version,draft_version,revision_state,name,description,available_for_new_bindings,document_text,document_syntax,document_digest,model_schema,imports_json,policy_json) VALUES('new','o',1,2,'PUBLISHED','test','',true,'Ontology(<urn:test>)','FUNCTIONAL','" + "a".repeat(64) + "','owl-document-v1','[]','{}')");
            try (var result = connection.createStatement().executeQuery("SELECT definition_json,document_text FROM mate_semantic_ontology_revision WHERE id='new'")) {
                assertTrue(result.next());
                assertNull(result.getString(1));
                assertEquals("Ontology(<urn:test>)", result.getString(2));
            }
            execute(connection, "INSERT INTO mate_semantic_graph(id,workspace_id,kb_id,ontology_revision_id,enabled,created_at,updated_at) VALUES('g',1,1,'new',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            execute(connection, "INSERT INTO mate_semantic_entity(id,graph_id,iri,display_name,status,created_by,created_at) VALUES('e','g','urn:test:e','test','ACTIVE','u',CURRENT_TIMESTAMP)");
            assertThrows(SQLException.class, () -> execute(connection, "INSERT INTO mate_semantic_ontology_axiom(revision_id,axiom_id,axiom_kind,axiom_text,signature_json) VALUES('missing','a','SubClassOf','x','[]')"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "mysql", "kingbase"})
    void axiomIdentityAndImportArtifactsAreScoped(String dialect) throws Exception {
        try (Connection connection = connection(dialect)) {
            migrate(connection, dialect, "V191__semantic_ontology.sql");
            migrate(connection, dialect, "V192__semantic_graph.sql");
            migrate(connection, dialect, "V199__semantic_owl_document.sql");
            String digest = "b".repeat(64);
            execute(connection, "INSERT INTO mate_semantic_import_artifact(id,workspace_id,document_digest,document_syntax,document_text,created_at) VALUES('i1',1,'"+digest+"','FUNCTIONAL','Ontology()',CURRENT_TIMESTAMP)");
            execute(connection, "INSERT INTO mate_semantic_import_artifact(id,workspace_id,document_digest,document_syntax,document_text,created_at) VALUES('i2',2,'"+digest+"','FUNCTIONAL','Ontology()',CURRENT_TIMESTAMP)");
            assertThrows(SQLException.class, () -> execute(connection, "INSERT INTO mate_semantic_import_artifact(id,workspace_id,document_digest,document_syntax,document_text,created_at) VALUES('i3',1,'"+digest+"','FUNCTIONAL','Ontology()',CURRENT_TIMESTAMP)"));
        }
    }

    private static Connection connection(String dialect) throws SQLException {
        String mode = dialect.equals("mysql") ? ";MODE=MySQL" : dialect.equals("kingbase") ? ";MODE=PostgreSQL" : "";
        return DriverManager.getConnection("jdbc:h2:mem:owl-schema-" + UUID.randomUUID() + mode);
    }

    private static void migrate(Connection connection, String dialect, String file) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/"+dialect+"/"+file));
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
