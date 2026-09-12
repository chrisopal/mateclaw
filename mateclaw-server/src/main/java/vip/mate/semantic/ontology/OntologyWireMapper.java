package vip.mate.semantic.ontology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import vip.mate.semantic.core.ontology.*;
import vip.mate.semantic.core.policy.BusinessPolicySet;
import vip.mate.semantic.web.OntologyDtos.*;
import vip.mate.semantic.web.SemanticApiException;

/** HTTP/document boundary. Complete standard documents are the only writable ontology model. */
@Component
public class OntologyWireMapper {
    public static final int MAX_DOCUMENT_BYTES = 1_048_576;
    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final Pattern IRI_SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*");
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{1,8}(?:-[A-Za-z0-9]{1,8})*");
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

    /**
     * Converts the bounded business editor vocabulary into ordinary OWL axiom edits.
     * All identifiers are treated as opaque complete IRIs; functional syntax is built here
     * only after validating and escaping every user supplied value.
     */
    public List<AxiomEdit> modelEdits(OntologyRevisionRow row, String operationId, List<ModelEdit> edits) {
        return compileModelCommands(row, operationId, edits).changes();
    }

    public record ModelCommandBatch(List<AxiomEdit> changes, List<ModelItemResult> items) {}

    public ModelCommandBatch compileModelCommands(OntologyRevisionRow row, String operationId, List<ModelEdit> edits) {
        if (edits == null || edits.isEmpty() || edits.size() > 1000)
            throw new SemanticApiException(422, "INVALID_MODEL_EDIT", "One to 1000 model edits required");
        if (operationId == null || operationId.isBlank() || operationId.length() > 128)
            throw new SemanticApiException(422, "INVALID_MODEL_EDIT", "operationId required, maximum 128 characters");

        ParsedOntologyDocument parsed = stored.read(row);
        Map<String, List<String>> termKinds = documents.termKinds(parsed);
        List<OntologyAxiomDescriptor> axioms = parsed.axioms();
        Map<String, String> generatedKinds = new HashMap<>();
        Map<String, String> references = new HashMap<>();
        Set<String> clientIds = new java.util.HashSet<>();
        for (int index = 0; index < edits.size(); index++) {
            ModelEdit edit = edits.get(index);
            if (edit == null || edit.kind() == null) throw invalidModel("Edit kind required");
            if (edit.clientId() != null && (!edit.clientId().matches("[A-Za-z0-9_-]{1,128}")
                    || !clientIds.add(edit.clientId()))) throw invalidModel("Invalid or duplicate clientId");
            if ("CREATE_TERM".equals(edit.kind())) {
                String target = generatedTermIri(row, operationId, index);
                generatedKinds.put(target, requiredChoice(edit.termKind(), Set.of("OBJECT", "RELATION", "ATTRIBUTE"), "termKind"));
                if (edit.clientId() != null) references.put(edit.clientId(), target);
            }
        }
        List<AxiomEdit> result = new ArrayList<>();
        List<ModelItemResult> items = new ArrayList<>();
        long editBytes = 0;
        for (int index = 0; index < edits.size(); index++) {
            ModelEdit edit = resolveModelEdit(edits.get(index), references);
            int start = result.size();
            switch (edit.kind()) {
                case "CREATE_TERM" -> createTerm(row, operationId, index, edit, result, termKinds, generatedKinds);
                case "REPLACE_DEFINITION" -> replaceDefinition(edit, termKinds, generatedKinds, axioms, result);
                case "REPLACE_RESTRICTION" -> replaceRestriction(edit, termKinds, generatedKinds, axioms, result);
                default -> throw invalidModel("Unsupported model edit kind");
            }
            // Parse only the item's additions to obtain the adapter's canonical axiom IDs.
            String additions = result.subList(start, result.size()).stream()
                    .filter(change -> "ADD".equals(change.kind())).map(AxiomEdit::functionalSyntax)
                    .collect(java.util.stream.Collectors.joining("\n"));
            editBytes += additions.getBytes(StandardCharsets.UTF_8).length;
            if (editBytes > MAX_DOCUMENT_BYTES)
                throw new SemanticApiException(413, "DOCUMENT_TOO_LARGE", "Model edits exceed document budget");
            ParsedOntologyDocument itemDocument;
            try {
                itemDocument = documents.parse(row.getOntologyId(), row.getId(),
                        "Ontology(" + additions + ")", OntologyDocumentSyntax.FUNCTIONAL, List.of());
            } catch (OntologyDocumentException | IllegalArgumentException exception) {
                throw invalidModel(exception.getMessage());
            }
            items.add(new ModelItemResult(edit.clientId(), "CREATE_TERM".equals(edit.kind())
                    ? generatedTermIri(row, operationId, index) : edit.targetId(),
                    itemDocument.axioms().stream().map(OntologyAxiomDescriptor::axiomId).sorted().toList()));
        }
        return new ModelCommandBatch(List.copyOf(result), List.copyOf(items));
    }

    private static ModelEdit resolveModelEdit(ModelEdit edit, Map<String, String> references) {
        String value = Set.of("PARENT", "DOMAIN", "RANGE").contains(edit.field() == null ? "" : edit.field().toUpperCase(Locale.ROOT))
                ? resolveReference(edit.value(), references) : edit.value();
        return new ModelEdit(edit.kind(), edit.termKind(), resolveReference(edit.targetId(), references),
                edit.name(), resolveReference(edit.domainId(), references), resolveReference(edit.rangeId(), references),
                edit.field(), value, edit.language(), edit.operator(), resolveReference(edit.propertyId(), references),
                resolveReference(edit.fillerId(), references), edit.cardinality(), edit.originalAxiomId(), edit.clientId());
    }

    private static String resolveReference(String value, Map<String, String> references) {
        if (value == null || !value.startsWith("$")) return value;
        String target = references.get(value.substring(1));
        if (target == null) throw invalidModel("Unknown temporary reference: " + value);
        return target;
    }

    private void createTerm(
            OntologyRevisionRow row,
            String operationId,
            int index,
            ModelEdit edit,
            List<AxiomEdit> result,
            Map<String, List<String>> termKinds,
            Map<String, String> generatedKinds) {
        String kind = requiredChoice(edit.termKind(), Set.of("OBJECT", "RELATION", "ATTRIBUTE"), "termKind");
        String name = required(edit.name(), "name");
        String iri = generatedTermIri(row, operationId, index);
        generatedKinds.put(iri, kind);
        String declaration = switch (kind) {
            case "OBJECT" -> "Declaration(Class(" + iri(iri) + "))";
            case "RELATION" -> "Declaration(ObjectProperty(" + iri(iri) + "))";
            case "ATTRIBUTE" -> "Declaration(DataProperty(" + iri(iri) + "))";
            default -> throw invalidModel("Unsupported termKind");
        };
        result.add(new AxiomEdit("ADD", null, declaration));
        result.add(new AxiomEdit("ADD", null,
                "AnnotationAssertion(" + iri(RDFS + "label") + " " + iri(iri) + " "
                        + literal(name, edit.language()) + ")"));
        if (kind.equals("RELATION") || kind.equals("ATTRIBUTE")) {
            String domain = validIri(edit.domainId(), "domainId");
            String range = validIri(edit.rangeId(), "rangeId");
            if (kind.equals("RELATION")) {
                requireClass(domain, termKinds, generatedKinds, "domainId");
                requireClass(range, termKinds, generatedKinds, "rangeId");
            } else {
                requireClass(domain, termKinds, generatedKinds, "domainId");
                requireDatatype(range, termKinds, generatedKinds);
            }
            String property = iri(iri);
            result.add(new AxiomEdit("ADD", null,
                    (kind.equals("RELATION") ? "ObjectPropertyDomain(" : "DataPropertyDomain(")
                            + property + " " + iri(domain) + ")"));
            result.add(new AxiomEdit("ADD", null,
                    (kind.equals("RELATION") ? "ObjectPropertyRange(" : "DataPropertyRange(")
                            + property + " " + iri(range) + ")"));
        }
    }

    private void replaceDefinition(
            ModelEdit edit,
            Map<String, List<String>> termKinds,
            Map<String, String> generatedKinds,
            List<OntologyAxiomDescriptor> axioms,
            List<AxiomEdit> result) {
        String target = validIri(edit.targetId(), "targetId");
        String field = requiredChoice(edit.field(), Set.of("NAME", "DESCRIPTION", "PARENT", "DOMAIN", "RANGE"), "field");
        String syntax;
        switch (field) {
            case "NAME" -> syntax = "AnnotationAssertion(" + iri(RDFS + "label") + " " + iri(target) + " "
                    + literal(required(edit.value(), "value"), edit.language()) + ")";
            case "DESCRIPTION" -> syntax = "AnnotationAssertion(" + iri(RDFS + "comment") + " " + iri(target) + " "
                    + literal(required(edit.value(), "value"), edit.language()) + ")";
            case "PARENT" -> {
                requireClass(target, termKinds, generatedKinds, "targetId");
                String parent = validIri(edit.value(), "value");
                requireClass(parent, termKinds, generatedKinds, "value");
                syntax = "SubClassOf(" + iri(target) + " " + iri(parent) + ")";
            }
            case "DOMAIN", "RANGE" -> {
                String propertyKind = propertyKind(target, edit.termKind(), termKinds, generatedKinds);
                String value = validIri(
                        field.equals("DOMAIN") && (edit.value() == null || edit.value().isBlank())
                                ? edit.domainId() : field.equals("RANGE") && (edit.value() == null || edit.value().isBlank())
                                ? edit.rangeId() : edit.value(),
                        "value");
                boolean object = propertyKind.equals("RELATION");
                if (object || field.equals("DOMAIN")) requireClass(value, termKinds, generatedKinds, "value");
                else requireDatatype(value, termKinds, generatedKinds);
                syntax = field.equals("DOMAIN")
                        ? (object ? "ObjectPropertyDomain(" : "DataPropertyDomain(") + iri(target) + " " + iri(value) + ")"
                        : (object ? "ObjectPropertyRange(" : "DataPropertyRange(") + iri(target) + " " + iri(value) + ")";
            }
            default -> throw invalidModel("Unsupported definition field");
        }
        Predicate<String> originalMatches = switch (field) {
            case "NAME" -> rendering -> annotationRendering(rendering, RDFS + "label", target);
            case "DESCRIPTION" -> rendering -> annotationRendering(rendering, RDFS + "comment", target);
            case "PARENT" -> rendering -> rendering.matches("SubClassOf\\(" + Pattern.quote(iri(target)) + " <[^>]+>\\)");
            case "DOMAIN" -> rendering -> namedPropertyAxiomRendering(rendering,
                    "RELATION".equalsIgnoreCase(edit.termKind()) ? "ObjectPropertyDomain" : "DataPropertyDomain", target);
            case "RANGE" -> rendering -> namedPropertyAxiomRendering(rendering,
                    "RELATION".equalsIgnoreCase(edit.termKind()) ? "ObjectPropertyRange" : "DataPropertyRange", target);
            default -> throw invalidModel("Unsupported definition field");
        };
        addReplacement(result, edit.originalAxiomId(), axioms, originalMatches, syntax);
    }

    private void replaceRestriction(
            ModelEdit edit,
            Map<String, List<String>> termKinds,
            Map<String, String> generatedKinds,
            List<OntologyAxiomDescriptor> axioms,
            List<AxiomEdit> result) {
        String subject = validIri(edit.targetId(), "targetId");
        String property = validIri(edit.propertyId(), "propertyId");
        String filler = validIri(edit.fillerId(), "fillerId");
        requireClass(subject, termKinds, generatedKinds, "targetId");
        if (!termKinds.getOrDefault(property, List.of()).contains("ObjectProperty")
                && !generatedKinds.getOrDefault(property, "").equals("RELATION"))
            throw invalidModel("propertyId is not an object property");
        requireClass(filler, termKinds, generatedKinds, "fillerId");
        String operator = requiredChoice(edit.operator(), Set.of("SOME", "ALL", "MIN", "MAX", "EXACT"), "operator");
        String restriction = switch (operator) {
            case "SOME" -> "ObjectSomeValuesFrom(" + iri(property) + " " + iri(filler) + ")";
            case "ALL" -> "ObjectAllValuesFrom(" + iri(property) + " " + iri(filler) + ")";
            case "MIN", "MAX", "EXACT" -> {
                Integer cardinality = edit.cardinality();
                if (cardinality == null || cardinality < 0)
                    throw invalidModel("Non-negative cardinality required for " + operator);
                String name = switch (operator) {
                    case "MIN" -> "ObjectMinCardinality";
                    case "MAX" -> "ObjectMaxCardinality";
                    default -> "ObjectExactCardinality";
                };
                yield name + "(" + cardinality + " " + iri(property) + " " + iri(filler) + ")";
            }
            default -> throw invalidModel("Unsupported restriction operator");
        };
        String syntax = "SubClassOf(" + iri(subject) + " " + restriction + ")";
        addReplacement(result, edit.originalAxiomId(), axioms,
                restrictionRendering(subject), syntax);
    }

    private static Predicate<String> restrictionRendering(String subject) {
        String named = "<[^>]+>";
        String direct = "(?:ObjectSomeValuesFrom\\(" + named + " " + named + "\\)"
                + "|ObjectAllValuesFrom\\(" + named + " " + named + "\\)"
                + "|ObjectMinCardinality\\([0-9]+ " + named + " " + named + "\\)"
                + "|ObjectMaxCardinality\\([0-9]+ " + named + " " + named + "\\)"
                + "|ObjectExactCardinality\\([0-9]+ " + named + " " + named + "\\))";
        return rendering -> rendering.matches(Pattern.quote("SubClassOf(" + iri(subject) + " ")
                + direct + "\\)");
    }

    private static boolean namedPropertyAxiomRendering(String rendering, String axiomType, String target) {
        return rendering.matches(Pattern.quote(axiomType + "(" + iri(target) + " ") + "<[^>]+>\\)");
    }

    private static void addReplacement(
            List<AxiomEdit> result,
            String originalAxiomId,
            List<OntologyAxiomDescriptor> axioms,
            Predicate<String> originalMatches,
            String syntax) {
        if (originalAxiomId != null) {
            String id = validAxiomId(originalAxiomId);
            OntologyAxiomDescriptor original = axioms.stream()
                    .filter(axiom -> axiom.axiomId().equals(id))
                    .findFirst()
                    .orElseThrow(() -> invalidModel("originalAxiomId is not in this draft"));
            if (!originalMatches.test(original.rendering()))
                throw invalidModel("originalAxiomId does not match the requested replacement");
            result.add(new AxiomEdit("REMOVE", id, null));
        }
        result.add(new AxiomEdit("ADD", null, syntax));
    }

    private static String propertyKind(
            String iri, String requestedKind, Map<String, List<String>> termKinds, Map<String, String> generatedKinds) {
        String requested = requiredChoice(requestedKind, Set.of("RELATION", "ATTRIBUTE"), "termKind");
        String generated = generatedKinds.get(iri);
        if (generated != null) {
            if (generated.equals(requested)) return generated;
            throw invalidModel("targetId is not a property");
        }
        List<String> kinds = termKinds.getOrDefault(iri, List.of());
        if (requested.equals("RELATION") && kinds.contains("ObjectProperty")) return requested;
        if (requested.equals("ATTRIBUTE") && kinds.contains("DataProperty")) return requested;
        throw invalidModel("targetId is not a declared property");
    }

    private static void requireDatatype(String value, Map<String, List<String>> termKinds,
            Map<String, String> generatedKinds) {
        if (generatedKinds.containsKey(value) || termKinds.getOrDefault(value, List.of()).stream()
                .anyMatch(kind -> !"Datatype".equals(kind)))
            throw invalidModel("rangeId is not a datatype");
    }

    private static void requireClass(
            String iri, Map<String, List<String>> termKinds, Map<String, String> generatedKinds, String field) {
        if (!termKinds.getOrDefault(iri, List.of()).contains("Class")
                && !generatedKinds.getOrDefault(iri, "").equals("OBJECT"))
            throw invalidModel(field + " is not a declared class");
    }

    private static boolean annotationRendering(String rendering, String property, String target) {
        return rendering.startsWith("AnnotationAssertion(" + iri(property) + " " + iri(target) + " ")
                || (property.equals(RDFS + "label")
                        && rendering.startsWith("AnnotationAssertion(rdfs:label " + iri(target) + " "))
                || (property.equals(RDFS + "comment")
                        && rendering.startsWith("AnnotationAssertion(rdfs:comment " + iri(target) + " "));
    }

    private static String generatedTermIri(OntologyRevisionRow row, String operationId, int index) {
        String digest = OntologyDocument.sha256(row.getOntologyIri() + "\u0000" + operationId + "\u0000" + index);
        return row.getOntologyIri() + "/model-term/" + digest;
    }

    private static String requiredChoice(String value, Set<String> choices, String field) {
        String normalized = required(value, field).toUpperCase(Locale.ROOT);
        if (!choices.contains(normalized)) throw invalidModel("Unsupported " + field);
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw invalidModel(field + " required");
        return value;
    }

    private static String validIri(String value, String field) {
        String candidate = required(value, field);
        if (candidate.length() > 2048 || candidate.chars().anyMatch(Character::isWhitespace)
                || candidate.chars().anyMatch(ch -> ch < 0x20)
                || candidate.indexOf('<') >= 0 || candidate.indexOf('>') >= 0
                || candidate.indexOf('"') >= 0 || candidate.indexOf('{') >= 0
                || candidate.indexOf('}') >= 0 || candidate.indexOf('|') >= 0
                || candidate.indexOf('^') >= 0 || candidate.indexOf('`') >= 0
                || candidate.indexOf('\\') >= 0)
            throw invalidModel("Malformed " + field);
        int colon = candidate.indexOf(':');
        if (colon <= 0 || !IRI_SCHEME.matcher(candidate.substring(0, colon)).matches())
            throw invalidModel("Malformed " + field);
        try {
            URI parsed = new URI(candidate);
            if (parsed.getScheme() == null) throw new URISyntaxException(candidate, "missing scheme");
        } catch (URISyntaxException exception) {
            throw invalidModel("Malformed " + field);
        }
        return candidate;
    }

    private static String validAxiomId(String value) {
        if (!value.matches("[0-9a-fA-F]{64}")) throw invalidModel("Malformed originalAxiomId");
        return value;
    }

    private static String iri(String value) {
        return "<" + value + ">";
    }

    private static String literal(String value, String language) {
        StringBuilder escaped = new StringBuilder(value.length() + 2);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) throw invalidModel("value contains an unsupported control character");
                    escaped.append(ch);
                }
            }
        }
        if (language == null || language.isBlank()) return "\"" + escaped + "\"";
        if (!LANGUAGE.matcher(language).matches()) throw invalidModel("Malformed language");
        return "\"" + escaped + "\"@" + language;
    }

    private static SemanticApiException invalidModel(String message) {
        return new SemanticApiException(422, "INVALID_MODEL_EDIT", message);
    }

    public java.util.Set<String> classIris(ParsedOntologyDocument parsed) { return documents.classIris(parsed); }

    public java.util.Set<String> classIris(OntologyRevisionRow row) { return documents.classIris(stored.read(row)); }

    public java.util.Map<String,java.util.List<String>> termKinds(OntologyRevisionRow row) { return documents.termKinds(stored.read(row)); }

    public ParsedOntologyDocument parsed(OntologyRevisionRow row) { return stored.read(row); }
    public OntologyDisplayProjection project(OntologyRevisionRow row, int limit) {
        return documents.project(stored.read(row), limit);
    }
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
                row.getUpdatedAt().toInstant(java.time.ZoneOffset.UTC), row.isArchived());
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
