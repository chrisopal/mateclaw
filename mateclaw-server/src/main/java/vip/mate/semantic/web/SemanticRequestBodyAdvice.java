package vip.mate.semantic.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Local JSON boundary: do not let Jackson coerce numbers into names, enums or CAS tokens. */
@ControllerAdvice(assignableTypes = OntologyController.class)
public class SemanticRequestBodyAdvice extends RequestBodyAdviceAdapter {
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    private final ObjectMapper json;

    public SemanticRequestBodyAdvice(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public boolean supports(
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType instanceof Class<?> type
                && type.getEnclosingClass() == OntologyDtos.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage input,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        byte[] body = input.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES)
            throw new SemanticApiException(
                    413, "REQUEST_TOO_LARGE", "Ontology request exceeds 16 MiB");
        JsonNode value;
        try {
            value = json.readTree(body);
        } catch (IOException e) {
            throw invalid("$");
        }
        verify(value, targetType, "$");
        return new HttpInputMessage() {
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }

    private void verify(JsonNode node, Type expected, String path) {
        if (node == null || node.isNull()) return;
        if (expected instanceof ParameterizedType list && list.getRawType() == List.class) {
            if (!node.isArray()) throw invalid(path);
            for (int i = 0; i < node.size(); i++)
                verify(node.get(i), list.getActualTypeArguments()[0], path + "[" + i + "]");
            return;
        }
        if (!(expected instanceof Class<?> type)) throw invalid(path);
        if (type == String.class) {
            if (!node.isTextual()) throw invalid(path);
            return;
        }
        if (type.isEnum()) {
            if (!node.isTextual()
                    || Arrays.stream(type.getEnumConstants())
                            .noneMatch(value -> ((Enum<?>) value).name().equals(node.textValue())))
                throw invalid(path);
            return;
        }
        if (type == Long.class) {
            if (!node.isIntegralNumber() || !node.canConvertToLong()) throw invalid(path);
            return;
        }
        if (type == Boolean.class) {
            if (!node.isBoolean()) throw invalid(path);
            return;
        }
        if (!type.isRecord() || !node.isObject()) throw invalid(path);
        Set<String> names = new HashSet<>();
        for (RecordComponent field : type.getRecordComponents()) {
            names.add(field.getName());
            verify(node.get(field.getName()), field.getGenericType(), path + "." + field.getName());
        }
        node.fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!names.contains(name)) throw invalid(path + "." + name);
                        });
    }

    private SemanticApiException invalid(String path) {
        return new SemanticApiException(
                400,
                "INVALID_REQUEST",
                "Invalid JSON field type or unknown field",
                List.of(
                        new OntologyDtos.Violation(
                                "INVALID_REQUEST",
                                path,
                                "Invalid JSON field type or unknown field",
                                "ERROR")));
    }
}
