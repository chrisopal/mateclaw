package vip.mate.semantic.core.ontology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * The immutable, versioned OWL document envelope. The text digest is over the
 * exact UTF-8 document bytes; it is deliberately not a semantic equivalence hash.
 */
public record OntologyDocument(
        String ontologyId,
        String revisionId,
        String ontologyIri,
        Optional<String> versionIri,
        OntologyDocumentSyntax syntax,
        String documentText,
        String documentDigest,
        String importLockDigest,
        String modelSchema) {

    public static final String MODEL_SCHEMA = "owl-document-v1";

    public OntologyDocument {
        ontologyId = requireText(ontologyId, "ontologyId");
        revisionId = requireText(revisionId, "revisionId");
        ontologyIri = requireText(ontologyIri, "ontologyIri");
        versionIri = versionIri == null ? Optional.empty() : versionIri.filter(value -> !value.isBlank());
        syntax = Objects.requireNonNull(syntax, "syntax");
        documentText = Objects.requireNonNull(documentText, "documentText");
        documentDigest = requireText(documentDigest, "documentDigest").toLowerCase();
        String actualDigest = sha256(documentText);
        if (!actualDigest.equals(documentDigest)) {
            throw new IllegalArgumentException("documentDigest does not match documentText UTF-8 bytes");
        }
        importLockDigest = requireText(importLockDigest, "importLockDigest").toLowerCase();
        modelSchema = requireText(modelSchema, "modelSchema");
        if (!MODEL_SCHEMA.equals(modelSchema)) {
            throw new IllegalArgumentException("unsupported ontology document modelSchema: " + modelSchema);
        }
    }

    public static OntologyDocument fromText(
            String ontologyId,
            String revisionId,
            String ontologyIri,
            Optional<String> versionIri,
            OntologyDocumentSyntax syntax,
            String documentText,
            String importLockDigest) {
        return new OntologyDocument(
                ontologyId,
                revisionId,
                ontologyIri,
                versionIri,
                syntax,
                documentText,
                sha256(documentText),
                importLockDigest,
                MODEL_SCHEMA);
    }

    /** Builds an envelope before parsing when the source does not provide an IRI hint. */
    public static OntologyDocument fromUnboundText(
            String ontologyId,
            String revisionId,
            OntologyDocumentSyntax syntax,
            String documentText,
            String importLockDigest) {
        return fromText(
                ontologyId,
                revisionId,
                "urn:mateclaw:unbound:" + sha256(documentText),
                Optional.empty(),
                syntax,
                documentText,
                importLockDigest);
    }

    public OntologyDocument withOntologyIri(String newOntologyIri, Optional<String> newVersionIri) {
        return new OntologyDocument(
                ontologyId, revisionId, newOntologyIri, newVersionIri, syntax,
                documentText, documentDigest, importLockDigest, modelSchema);
    }

    public static String sha256(String text) {
        Objects.requireNonNull(text, "text");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JDK", exception);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
