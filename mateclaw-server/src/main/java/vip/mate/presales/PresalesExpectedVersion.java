package vip.mate.presales;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Keeps optimistic-lock versions exact at the HTTP boundary. */
final class PresalesExpectedVersion extends StdDeserializer<Integer> {
    PresalesExpectedVersion() {
        super(Integer.class);
    }

    static Integer read(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(
                    "expectedVersion must be a JSON integer within int range");
        }
        return value.intValue();
    }

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        try {
            return read(parser.readValueAsTree());
        } catch (IllegalArgumentException invalid) {
            throw JsonMappingException.from(parser, invalid.getMessage(), invalid);
        }
    }
}
