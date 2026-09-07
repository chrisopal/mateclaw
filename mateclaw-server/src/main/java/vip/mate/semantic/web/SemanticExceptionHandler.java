package vip.mate.semantic.web;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import vip.mate.common.result.R;

import java.util.*;

@RestControllerAdvice(
        basePackageClasses = {OntologyController.class, SemanticStatusController.class})
@Order(-100)
public class SemanticExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SemanticExceptionHandler.class);
    @ExceptionHandler(SemanticApiException.class)
    public ResponseEntity<R<Object>> semantic(SemanticApiException e) {
        return error(e.status(), e.code(), e.getMessage(), e.fieldErrors());
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        ServletRequestBindingException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<R<Object>> malformed(Exception e) {
        return error(
                400,
                "INVALID_REQUEST",
                "Invalid request structure",
                List.of(
                        new OntologyDtos.Violation(
                                "INVALID_REQUEST", "$", "Invalid request structure", "ERROR")));
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<R<Object>> duplicate(org.springframework.dao.DuplicateKeyException e) {
        return error(
                409,
                "OPERATION_CONFLICT",
                "Operation id already committed by a concurrent request",
                List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Object>> unexpected(Exception e) {
        log.error("Semantic operation failed", e);
        return error(500, "INTERNAL_ERROR", "Semantic operation failed", List.of());
    }

    @ExceptionHandler({org.springframework.dao.QueryTimeoutException.class, org.springframework.transaction.TransactionTimedOutException.class})
    public ResponseEntity<R<Object>> timeout(Exception e) {
        return error(504, "QUERY_TIMEOUT", "Semantic query exceeded its deadline; narrow the query and retry", List.of());
    }

    private ResponseEntity<R<Object>> error(
            int status, String code, String message, Object fields) {
        R<Object> body = R.fail(status, message);
        body.setData(
                Map.of(
                        "code",
                        code,
                        "fieldErrors",
                        fields,
                        "traceId",
                        UUID.randomUUID().toString()));
        return ResponseEntity.status(status).body(body);
    }
}
