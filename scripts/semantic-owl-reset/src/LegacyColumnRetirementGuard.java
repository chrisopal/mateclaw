import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import vip.mate.semantic.owl.OwlDocumentAdapter;
import vip.mate.semantic.core.ontology.LockedImport;
import vip.mate.semantic.core.ontology.OntologyDocumentSyntax;
import vip.mate.semantic.core.policy.BusinessPolicySet;

/** Read-only prerequisite for retiring the nullable legacy definition column. */
public final class LegacyColumnRetirementGuard {
    public record Snapshot(long revisions, String documentFingerprint) {}

    public static Snapshot inspect(Connection connection) throws Exception {
        return inspect(connection, false);
    }

    static Snapshot inspect(Connection connection, boolean columnAbsent) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long rows = 0;
        var json = new ObjectMapper().findAndRegisterModules();
        var adapter = new OwlDocumentAdapter();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT id," + (columnAbsent ? "NULL" : "definition_json") + ",model_schema,document_text,document_syntax,document_digest,"
                + "ontology_iri,import_lock_digest,imports_json,policy_json,version_iri "
                + "FROM mate_semantic_ontology_revision ORDER BY id")) {
            while (result.next()) {
                rows++;
                if (result.getString(2) != null)
                    throw new SQLException("LEGACY_COLUMN_IN_USE: retained definition data prevents retirement");
                if (!"owl-document-v1".equals(result.getString(3)))
                    throw new SQLException("LEGACY_REVISION_REMAINS: reset legacy revisions before retirement");
                for (int column = 4; column <= 10; column++) {
                    String value = result.getString(column);
                    if (value == null || value.isBlank())
                        throw new SQLException("INCOMPLETE_OWL_DOCUMENT: required OWL fields are absent");
                }
                String syntax = result.getString(5);
                if (!"FUNCTIONAL".equals(syntax) && !"RDF_XML".equals(syntax))
                    throw new SQLException("INVALID_OWL_SYNTAX: unsupported document syntax");
                String textHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(result.getString(4).getBytes(StandardCharsets.UTF_8)));
                if (!textHash.equals(result.getString(6)))
                    throw new SQLException("OWL_DIGEST_MISMATCH: saved document differs from digest");
                try {
                    List<LockedImport> imports = json.readValue(result.getString(9), new TypeReference<List<LockedImport>>() {});
                    if (!LockedImport.digest(imports).equals(result.getString(8)))
                        throw new IllegalArgumentException("Import lock digest mismatch");
                    var parsed = adapter.parse("retirement-preflight", result.getString(1), result.getString(4),
                            OntologyDocumentSyntax.valueOf(syntax), imports);
                    if (!java.util.Objects.equals(result.getString(7), parsed.parsedOntologyIri())
                            || !java.util.Objects.equals(result.getString(11), parsed.parsedVersionIri().orElse(null)))
                        throw new IllegalArgumentException("Stored ontology identity mismatch");
                    if (!adapter.validateDl(parsed).valid())
                        throw new IllegalArgumentException("Document is outside OWL 2 DL");
                    if (json.readValue(result.getString(10), BusinessPolicySet.class) == null)
                        throw new IllegalArgumentException("Policy is absent");
                } catch (Exception invalid) {
                    throw new SQLException("OWL_VALIDATION_FAILED: document, imports, identity or policy is invalid", invalid);
                }
                for (int column = 1; column <= 11; column++) {
                    String value = result.getString(column);
                    byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
        }
        return new Snapshot(rows, HexFormat.of().formatHex(digest.digest()));
    }
    private LegacyColumnRetirementGuard() {}
}
