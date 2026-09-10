package vip.mate.semantic.core.ontology;

public class OntologyDocumentException extends RuntimeException {
    public enum Kind { PARSE_ERROR, IMPORT_MISSING, IMPORT_DIGEST_MISMATCH, CHANGE_ERROR, EXPORT_ERROR }

    private final Kind kind;

    public OntologyDocumentException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public OntologyDocumentException(Kind kind, String message) {
        this(kind, message, null);
    }

    public Kind kind() {
        return kind;
    }
}
