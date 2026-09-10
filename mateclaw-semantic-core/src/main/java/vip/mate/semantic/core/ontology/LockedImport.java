package vip.mate.semantic.core.ontology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** An import artifact pinned by its requested IRI and content digest. */
public record LockedImport(
        String requestedIri,
        String resolvedOntologyIri,
        Optional<String> versionIri,
        OntologyDocumentSyntax syntax,
        String documentText,
        String contentDigest,
        String artifactId) {

    public LockedImport {
        requestedIri = requireText(requestedIri, "requestedIri");
        resolvedOntologyIri = requireText(resolvedOntologyIri, "resolvedOntologyIri");
        versionIri = versionIri == null ? Optional.empty() : versionIri.filter(value -> !value.isBlank());
        syntax = Objects.requireNonNull(syntax, "syntax");
        documentText = Objects.requireNonNull(documentText, "documentText");
        contentDigest = requireText(contentDigest, "contentDigest").toLowerCase();
        String actualDigest = OntologyDocument.sha256(documentText);
        if (!actualDigest.equals(contentDigest)) {
            throw new IllegalArgumentException("contentDigest does not match import document UTF-8 bytes");
        }
        artifactId = requireText(artifactId, "artifactId");
    }

    public static LockedImport fromText(
            String requestedIri,
            String resolvedOntologyIri,
            Optional<String> versionIri,
            OntologyDocumentSyntax syntax,
            String documentText,
            String artifactId) {
        return new LockedImport(
                requestedIri,
                resolvedOntologyIri,
                versionIri,
                syntax,
                documentText,
                OntologyDocument.sha256(documentText),
                artifactId);
    }

    /** Digest of the ordered lock metadata and bytes, suitable for the document envelope. */
    public static String digest(List<LockedImport> imports) {
        Objects.requireNonNull(imports, "imports");
        String canonical = imports.stream()
                .sorted(java.util.Comparator.comparing(LockedImport::requestedIri))
                .map(value -> String.join("\u0000",
                        value.requestedIri(), value.resolvedOntologyIri(), value.versionIri().orElse(""),
                        value.syntax().name(), value.contentDigest(), value.artifactId()))
                .collect(Collectors.joining("\u0001"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
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
