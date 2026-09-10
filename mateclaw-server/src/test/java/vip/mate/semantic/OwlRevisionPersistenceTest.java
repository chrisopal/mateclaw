package vip.mate.semantic;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import vip.mate.semantic.ontology.OntologyRevisionRow;
import vip.mate.semantic.ontology.repository.OntologyMapper;

class OwlRevisionPersistenceTest {
    @Test
    void mapperWritesOnlyOwlAuthorityAndPreservesCas() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:owl-store-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = source.getConnection()) {
            for (String name : new String[]{"V191__semantic_ontology.sql", "V192__semantic_graph.sql", "V199__semantic_owl_document.sql"})
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/h2/"+name));
            connection.createStatement().execute("INSERT INTO mate_semantic_ontology(id,workspace_id,name,description,updated_at) VALUES('o',1,'test','',CURRENT_TIMESTAMP)");
        }
        var configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(OntologyMapper.class);
        var factory = new SqlSessionFactoryBuilder().build(configuration);
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(OntologyMapper.class);
            var row = new OntologyRevisionRow();
            row.setId("r"); row.setOntologyId("o"); row.setVersion(1); row.setDraftVersion(1L);
            row.setName("test"); row.setDescription("");
            var adapter = new vip.mate.semantic.owl.OwlDocumentAdapter();
            var documents = new vip.mate.semantic.ontology.OwlRevisionDocumentMapper(
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), adapter);
            var parsed = adapter.parse("o", "r", "Ontology(<urn:test:o> <urn:test:v1> Declaration(Class(<urn:test:A>)) Declaration(Class(<urn:test:B>)) SubClassOf(<urn:test:A> <urn:test:B>))",
                    vip.mate.semantic.core.ontology.OntologyDocumentSyntax.FUNCTIONAL, java.util.List.of());
            documents.write(row, parsed, new vip.mate.semantic.core.policy.BusinessPolicySet("v1", java.util.List.of()));
            assertEquals(1, mapper.insertDraft(row));
            session.commit(); session.clearCache();
            var stored = mapper.revision("r", "o");
            assertEquals(row.getDocumentText(), stored.getDocumentText());
            assertEquals(row.getImportLockDigest(), stored.getImportLockDigest());
            assertEquals(row.getPolicyJson(), stored.getPolicyJson());
            assertEquals("urn:test:v1", stored.getVersionIri());
            var reloaded = documents.read(stored);
            assertTrue(adapter.validateDl(reloaded).valid());
            assertTrue(reloaded.axioms().stream().anyMatch(a -> a.axiomType().equals("SubClassOf")));
            var changed = adapter.parse("o", "r", "Ontology(<urn:test:o> <urn:test:v2>)",
                    vip.mate.semantic.core.ontology.OntologyDocumentSyntax.FUNCTIONAL, java.util.List.of());
            documents.write(row, changed, new vip.mate.semantic.core.policy.BusinessPolicySet("v2", java.util.List.of()));
            assertEquals(1, mapper.saveDraft(row, 1));
            assertEquals(0, mapper.saveDraft(row, 1), "stale draft must not overwrite new document");
            session.commit(); session.clearCache();
            assertEquals(2, mapper.revision("r", "o").getDraftVersion());
            assertEquals(row.getDocumentText(), mapper.revision("r", "o").getDocumentText());
        }
    }
}
