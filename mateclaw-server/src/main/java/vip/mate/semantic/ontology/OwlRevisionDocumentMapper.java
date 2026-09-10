package vip.mate.semantic.ontology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vip.mate.semantic.core.ontology.*;
import vip.mate.semantic.core.policy.BusinessPolicySet;
import vip.mate.semantic.web.SemanticApiException;

/** New document persistence mapping. No legacy conversion or fallback path. */
public final class OwlRevisionDocumentMapper {
    private static final TypeReference<List<LockedImport>> IMPORTS = new TypeReference<>() {};
    private final ObjectMapper json;
    private final OntologyDocumentPort documents;

    public OwlRevisionDocumentMapper(ObjectMapper json, OntologyDocumentPort documents) {
        this.json = Objects.requireNonNull(json);
        this.documents = Objects.requireNonNull(documents);
    }

    public ParsedOntologyDocument read(OntologyRevisionRow row) {
        if (!OntologyDocument.MODEL_SCHEMA.equals(row.getModelSchema())) {
            throw new SemanticApiException(409, "LEGACY_ONTOLOGY_RETIRED",
                    "This revision must be rebuilt as an OWL document");
        }
        final OntologyDocument document;
        final List<LockedImport> imports;
        try {
            imports = json.readValue(row.getImportsJson(), IMPORTS);
            if (!LockedImport.digest(imports).equals(row.getImportLockDigest()))
                throw new IllegalArgumentException("Import lock digest mismatch");
            document = new OntologyDocument(row.getOntologyId(), row.getId(), row.getOntologyIri(),
                    Optional.ofNullable(row.getVersionIri()), OntologyDocumentSyntax.valueOf(row.getDocumentSyntax()),
                    row.getDocumentText(), row.getDocumentDigest(), row.getImportLockDigest(), row.getModelSchema());
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("Stored ontology document integrity check failed", exception);
        }
        return documents.parse(document, document.syntax(), imports);
    }

    public BusinessPolicySet policy(OntologyRevisionRow row) {
        try {
            return Objects.requireNonNull(json.readValue(row.getPolicyJson(), BusinessPolicySet.class), "policy");
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("Stored business policy cannot be decoded", exception);
        }
    }

    /** Caller persists this row in the existing draft CAS transaction. */
    public void write(OntologyRevisionRow row, ParsedOntologyDocument parsed, BusinessPolicySet policy) {
        Objects.requireNonNull(policy, "policy");
        var document = parsed.document();
        if (!row.getId().equals(document.revisionId()) || !row.getOntologyId().equals(document.ontologyId()))
            throw new IllegalArgumentException("Document belongs to another ontology revision");
        if (!LockedImport.digest(parsed.lockedImports()).equals(document.importLockDigest()))
            throw new IllegalArgumentException("Document import lock digest mismatch");
        if (document.syntax() != OntologyDocumentSyntax.FUNCTIONAL)
            throw new IllegalArgumentException("Persist normalized Functional Syntax only");
        final String importsJson;
        final String policyJson;
        try {
            importsJson = json.writeValueAsString(parsed.lockedImports());
            policyJson = json.writeValueAsString(policy);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot encode OWL revision metadata", exception);
        }
        row.setDocumentText(document.documentText());
        row.setDocumentSyntax(document.syntax().name());
        row.setDocumentDigest(document.documentDigest());
        row.setOntologyIri(document.ontologyIri());
        row.setVersionIri(document.versionIri().orElse(null));
        row.setImportLockDigest(document.importLockDigest());
        row.setModelSchema(OntologyDocument.MODEL_SCHEMA);
        row.setImportsJson(importsJson);
        row.setPolicyJson(policyJson);
    }
}
