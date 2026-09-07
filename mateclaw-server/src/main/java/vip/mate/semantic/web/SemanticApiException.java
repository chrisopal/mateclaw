package vip.mate.semantic.web;

import java.util.List;

public class SemanticApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<OntologyDtos.Violation> fieldErrors;

    public SemanticApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public SemanticApiException(
            int status, String code, String message, List<OntologyDtos.Violation> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(errors);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<OntologyDtos.Violation> fieldErrors() {
        return fieldErrors;
    }
}
