package vip.mate.presales;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import vip.mate.semantic.web.SemanticApiException;

/** Exercises the real advice mapping; it does not replace Workspace/authorization integration. */
class PresalesErrorContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void businessStatusesKeepExactEnvelopeAndUnknownCode() throws Exception {
        for (int status : List.of(400, 401, 403, 404, 409, 422, 500)) {
            String code = "FUTURE_CODE_9007199254740993001";
            String message = "不可用 \"原消息\"\nsecond line";
            var failure = new SemanticApiException(status, code, message);
            var mvc =
                    MockMvcBuilders.standaloneSetup(new ErrorController(failure))
                            .setControllerAdvice(new PresalesExceptionHandler())
                            .build();
            var response = mvc.perform(get("/errors/failure")).andReturn().getResponse();
            assertEquals(status, response.getStatus());
            assertEquals(
                    expected(status, code, message),
                    json.readTree(response.getContentAsByteArray()));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 401, 403, 404, 409, 422, 500})
    void domainRejectionKeepsTheSameHttpEnvelope(int status) throws Exception {
        String code = "FUTURE_CODE_9007199254740993001";
        String message = "领域拒绝 原消息";
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new ErrorController(new PresalesRejected(status, code, message)))
                        .setControllerAdvice(new PresalesExceptionHandler())
                        .build();
        var response = mvc.perform(get("/errors/failure")).andReturn().getResponse();
        assertEquals(status, response.getStatus());
        assertEquals(
                expected(status, code, message), json.readTree(response.getContentAsByteArray()));
    }

    @Test
    void semanticFieldErrorsAndRejectedModelOutputAreNotExposed() throws Exception {
        var failure =
                new SemanticApiException(422, "MODEL_FORMAT", "MODEL_FORMAT") {
                    @Override
                    public List<vip.mate.semantic.web.OntologyDtos.Violation> fieldErrors() {
                        throw new AssertionError(
                                "Presales must not serialize semantic field errors");
                    }
                };
        var rejected = json.createObjectNode().put("secret", "untrusted result");
        for (var error : List.of(failure, new PresalesOutputRejected(failure, rejected))) {
            var mvc =
                    MockMvcBuilders.standaloneSetup(new ErrorController(error))
                            .setControllerAdvice(new PresalesExceptionHandler())
                            .build();
            var response = mvc.perform(get("/errors/failure")).andReturn().getResponse();
            assertEquals(422, response.getStatus());
            assertEquals(
                    expected(422, "MODEL_FORMAT", "MODEL_FORMAT"),
                    json.readTree(response.getContentAsByteArray()));
        }
    }

    @Test
    void duplicateKeyKeepsConflictWithoutDatabaseExceptionDetails() throws Exception {
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new ErrorController(
                                        new DuplicateKeyException("secret database detail")))
                        .setControllerAdvice(new PresalesExceptionHandler())
                        .build();
        var response = mvc.perform(get("/errors/failure")).andReturn().getResponse();
        assertEquals(409, response.getStatus());
        assertEquals(
                expected(
                        409,
                        "OPERATION_CONFLICT",
                        "Concurrent operation; retry with same operationId"),
                json.readTree(response.getContentAsByteArray()));
    }

    @Test
    void malformedJsonAndMissingHeaderKeepMalformedRequestEnvelope() throws Exception {
        var mvc =
                MockMvcBuilders.standaloneSetup(new ErrorController(null))
                        .setControllerAdvice(new PresalesExceptionHandler())
                        .build();
        for (var request :
                List.of(
                        post("/errors/command")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{"),
                        get("/errors/workspace"))) {
            var response = mvc.perform(request).andReturn().getResponse();
            assertEquals(400, response.getStatus());
            assertEquals(
                    expected(400, "INVALID_REQUEST", "Malformed request"),
                    json.readTree(response.getContentAsByteArray()));
        }
    }

    @Test
    void nullMessageAndNullCodeKeepExistingAdapterSemantics() throws Exception {
        var handler = new PresalesExceptionHandler();
        var response = handler.api(new SemanticApiException(409, "CUSTOM", null));
        assertEquals(409, response.getStatusCode().value());
        assertEquals(expected(409, "CUSTOM", null), json.valueToTree(response.getBody()));
        assertThrows(
                NullPointerException.class,
                () -> handler.api(new SemanticApiException(409, null, "message")));
    }

    private com.fasterxml.jackson.databind.JsonNode expected(
            int status, String code, String message) {
        var expected = json.createObjectNode().put("code", status).put("msg", message);
        expected.putObject("data").put("code", code);
        return expected;
    }

    @RestController
    static class ErrorController {
        private final RuntimeException failure;

        ErrorController(RuntimeException failure) {
            this.failure = failure;
        }

        @GetMapping("/errors/failure")
        Object failure() {
            throw failure;
        }

        @PostMapping("/errors/command")
        Object command(@RequestBody PresalesDtos.Command command) {
            return command;
        }

        @GetMapping("/errors/workspace")
        Object workspace(@RequestHeader("X-Workspace-Id") String workspace) {
            return workspace;
        }
    }
}
