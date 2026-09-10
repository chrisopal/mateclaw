package vip.mate.semantic.ontology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import vip.mate.semantic.core.ontology.*;
import vip.mate.semantic.core.policy.BusinessPolicySet;
import vip.mate.semantic.web.OntologyDtos.*;
import vip.mate.semantic.web.SemanticApiException;

/** HTTP/document boundary. Complete standard documents are the only writable ontology model. */
@Component
public class OntologyWireMapper {
    public static final int MAX_DOCUMENT_BYTES = 1_048_576;
    private final ObjectMapper json;
    private final OntologyDocumentPort documents;
    private final OwlRevisionDocumentMapper stored;

    public OntologyWireMapper(ObjectMapper json, OntologyDocumentPort documents, OwlRevisionDocumentMapper stored) {
        this.json = json; this.documents = documents; this.stored = stored;
    }

    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot encode semantic state", exception); }
    }

    public <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot decode semantic state", exception); }
    }

    public DocumentInput empty(String ontologyId) {
        return new DocumentInput(OntologyDocument.MODEL_SCHEMA, OntologyDocumentSyntax.FUNCTIONAL,
                "Ontology(<urn:mateclaw:ontology:" + ontologyId + ">)", List.of(),
                new BusinessPolicySet("1", List.of()));
    }

    public ParsedOntologyDocument parse(String ontologyId, String revisionId, DocumentInput input) {
        if (input == null || !OntologyDocument.MODEL_SCHEMA.equals(input.modelSchema())
                || input.syntax() == null || input.documentText() == null || input.imports() == null || input.policy() == null)
            throw new SemanticApiException(422, "INVALID_OWL_DOCUMENT", "A complete owl-document-v1 document is required");
        long bytes = input.documentText().getBytes(StandardCharsets.UTF_8).length;
        for (LockedImport item : input.imports()) {
            if (item == null) throw new SemanticApiException(422, "INVALID_IMPORT_LOCK", "Null import lock entry");
            bytes += item.documentText().getBytes(StandardCharsets.UTF_8).length;
        }
        if (bytes > MAX_DOCUMENT_BYTES || input.imports().size() > 50)
            throw new SemanticApiException(413, "DOCUMENT_TOO_LARGE", "Ontology and pinned imports exceed configured limits");
        try { return documents.parse(ontologyId, revisionId, input.documentText(), input.syntax(), input.imports()); }
        catch (OntologyDocumentException | IllegalArgumentException exception) {
            throw new SemanticApiException(422, "INVALID_OWL_DOCUMENT", exception.getMessage());
        }
    }

    public void store(OntologyRevisionRow row, DocumentInput input) {
        var parsed = parse(row.getOntologyId(), row.getId(), input);
        if (parsed.document().syntax() != OntologyDocumentSyntax.FUNCTIONAL) {
            String canonical = new String(documents.export(parsed, OntologyDocumentSyntax.FUNCTIONAL), StandardCharsets.UTF_8);
            parsed = documents.parse(row.getOntologyId(), row.getId(), canonical, OntologyDocumentSyntax.FUNCTIONAL, input.imports());
        }
        stored.write(row, parsed, input.policy());
    }

    public byte[] export(OntologyRevisionRow row, OntologyDocumentSyntax syntax) {
        return documents.export(stored.read(row), syntax);
    }

    public void edit(OntologyRevisionRow row, List<AxiomEdit> edits) {
        if (edits == null || edits.isEmpty() || edits.size() > 1000)
            throw new SemanticApiException(422, "INVALID_AXIOM_EDIT", "One to 1000 axiom edits required");
        try {
        long editBytes = 0;
        var commands = new ArrayList<OntologyAxiomChange>();
        for (var edit : edits) {
            if (edit == null || edit.kind() == null) throw new SemanticApiException(422, "INVALID_AXIOM_EDIT", "Edit kind required");
            if (edit.functionalSyntax()!=null) editBytes += edit.functionalSyntax().getBytes(StandardCharsets.UTF_8).length;
            if (editBytes > MAX_DOCUMENT_BYTES) throw new SemanticApiException(413,"DOCUMENT_TOO_LARGE","Axiom edits exceed document budget");
            switch (edit.kind()) {
                case "ADD" -> commands.add(new OntologyAxiomChange.Add(edit.functionalSyntax()));
                case "REMOVE" -> commands.add(new OntologyAxiomChange.Remove(edit.axiomId()));
                default -> throw new SemanticApiException(422, "INVALID_AXIOM_EDIT", "Unsupported edit kind");
            }
        }
            var changed = documents.applyAxiomChanges(stored.read(row), commands);
            var checked = parse(row.getOntologyId(),row.getId(),new DocumentInput(OntologyDocument.MODEL_SCHEMA,
                changed.document().syntax(),changed.document().documentText(),changed.lockedImports(),stored.policy(row)));
            stored.write(row, checked, stored.policy(row));
        } catch (OntologyDocumentException | IllegalArgumentException | NullPointerException exception) {
            throw new SemanticApiException(422, "INVALID_AXIOM_EDIT", exception.getMessage());
        }
    }

    public java.util.Set<String> classIris(ParsedOntologyDocument parsed) { return documents.classIris(parsed); }

    public java.util.Set<String> classIris(OntologyRevisionRow row) { return documents.classIris(stored.read(row)); }

    public java.util.Map<String,java.util.List<String>> termKinds(OntologyRevisionRow row) { return documents.termKinds(stored.read(row)); }

    public ParsedOntologyDocument parsed(OntologyRevisionRow row) { return stored.read(row); }
    public BusinessPolicySet policy(OntologyRevisionRow row) { return stored.policy(row); }

    public DocumentView document(OntologyRevisionRow row) {
        var parsed = stored.read(row);
        var value = parsed.document();
        return new DocumentView(new DocumentInput(value.modelSchema(), value.syntax(), value.documentText(),
                parsed.lockedImports(), stored.policy(row)), parsed.parsedOntologyIri(),
                parsed.parsedVersionIri().orElse(null), value.documentDigest(), value.importLockDigest(), parsed.axioms());
    }

    public List<Violation> violations(OntologyRevisionRow row) { return violations(parsed(row)); }
    public List<Violation> violations(DocumentInput input) { return violations(parse("preview", "preview", input)); }
    public List<Violation> violations(ParsedOntologyDocument parsed) {
        return documents.validateDl(parsed).violations().stream()
                .map(v -> new Violation(v.code(), v.path(), v.message(), v.severity().name())).toList();
    }

    public void metadata(String name, String description) {
        List<Violation> errors = new ArrayList<>();
        text(name, "name", 128, true, errors); text(description, "description", 1000, false, errors); reject(errors);
    }
    public void text(String value, String path, int max, boolean nonblank, List<Violation> errors) {
        if (value == null || (nonblank && value.isBlank())) errors.add(new Violation("REQUIRED", path, "Value required", "ERROR"));
        else if (value.codePointCount(0, value.length()) > max) errors.add(new Violation("FIELD_TOO_LONG", path, "Maximum length exceeded", "ERROR"));
    }
    public void reject(List<Violation> errors) {
        if (errors.stream().anyMatch(v -> "ERROR".equals(v.severity())))
            throw new SemanticApiException(422, "VALIDATION_FAILED", "Ontology validation failed", errors);
    }
    public OntologyView ontology(OntologyRow row) {
        return new OntologyView(row.getId(), row.getWorkspaceId().toString(), row.getName(), row.getDescription(),
                row.getLatestVersion(), row.getLatestRevisionId(), row.getDraftId()!=null,
                row.getUpdatedAt().toInstant(java.time.ZoneOffset.UTC));
    }
    public DraftView draft(OntologyRevisionRow row) {
        return new DraftView(row.getId(), row.getOntologyId(), row.getBaseRevisionId(), row.getVersion(),
                row.getDraftVersion(), row.getName(), row.getDescription(), document(row));
    }
    public RevisionView revision(OntologyRevisionRow row) {
        return new RevisionView(row.getId(), row.getOntologyId(), row.getVersion(), row.getName(), row.getDescription(),
                document(row), row.getAvailableForNewBindings(),
                row.getPublishedAt().toInstant(java.time.ZoneOffset.UTC), row.getPublishedBy(),
                row.getPublicationNote(), row.getBaseRevisionId());
    }
}
