package vip.mate.semantic.ontology;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.type.LogicalType;

import vip.mate.semantic.web.OntologyDtos.*;

import java.io.IOException;
import java.util.*;

/**
 * One format boundary for request, persisted and historical definitions. Never rewrites storage.
 */
public final class OntologyDefinitionCodec extends JsonDeserializer<Definition> {
    private static final ObjectMapper STRICT = strictMapper(new JsonFactory());

    public static ObjectMapper strictMapper(JsonFactory factory) {
        ObjectMapper mapper =
                new ObjectMapper(factory)
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    @Override
    public Definition deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode node = context.readTree(parser);
        try {
            if (!node.isObject())
                throw new IllegalArgumentException("Definition must be an object");
            Set<String> fields =
                    Set.of("types", "properties", "relations", "definitionFormatVersion");
            node.fieldNames()
                    .forEachRemaining(
                            key -> {
                                if (!fields.contains(key))
                                    throw new IllegalArgumentException(
                                            "Unknown definition field: " + key);
                            });
            JsonNode format = node.get("definitionFormatVersion");
            if (format != null && (!format.isIntegralNumber() || !format.canConvertToInt()))
                throw new IllegalArgumentException("definitionFormatVersion must be an integer");
            int version = format == null ? 1 : format.intValue();
            if (version != 1 && version != 2)
                throw new IllegalArgumentException("Unsupported definition format");
            List<Type> types = list(node, "types", Type.class);
            List<Property> properties = list(node, "properties", Property.class);
            List<Relation> relations = list(node, "relations", Relation.class);
            Definition result = new Definition(types, properties, relations, version);
            if (version == 1
                    && (stream(types).anyMatch(t -> !t.aliases().isEmpty() || t.deprecated())
                            || stream(properties)
                                    .anyMatch(
                                            p ->
                                                    !p.aliases().isEmpty()
                                                            || p.deprecated()
                                                            || p.constraints() != null)
                            || stream(relations)
                                    .anyMatch(r -> !r.aliases().isEmpty() || r.deprecated())))
                throw new IllegalArgumentException(
                        "New ontology fields require definitionFormatVersion 2");
            return result;
        } catch (IllegalArgumentException e) {
            throw JsonMappingException.from(parser, e.getMessage(), e);
        }
    }

    private static <T> java.util.stream.Stream<T> stream(List<T> values) {
        return values == null
                ? java.util.stream.Stream.empty()
                : values.stream().filter(Objects::nonNull);
    }

    private static <T> List<T> list(JsonNode node, String field, Class<T> type) {
        JsonNode values = node.get(field);
        if (values == null || values.isNull()) return null;
        if (!values.isArray())
            throw new IllegalArgumentException("Invalid definition collection: " + field);
        List<T> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (value.isNull()) {
                result.add(null);
                continue;
            }
            if (!value.isObject())
                throw new IllegalArgumentException("Definition entries must be objects");
            if (value.has("deprecated") && !value.get("deprecated").isBoolean())
                throw new IllegalArgumentException("deprecated must be boolean");
            result.add(STRICT.convertValue(value, type));
        }
        return Collections.unmodifiableList(result);
    }
}
