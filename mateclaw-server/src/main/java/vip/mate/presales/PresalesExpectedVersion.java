package vip.mate.presales;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Keeps optimistic-lock versions exact at the HTTP boundary. */
final class PresalesExpectedVersion extends StdDeserializer<Long> {
    PresalesExpectedVersion() {
        super(Long.class);
    }

    static Long read(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < -PresalesProjectRevision.MAX_VALUE
                || value.longValue() > PresalesProjectRevision.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "expectedVersion must be a JSON integer within the safe integer range");
        }
        return value.longValue();
    }

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        try {
            return read(parser.readValueAsTree());
        } catch (IllegalArgumentException invalid) {
            throw JsonMappingException.from(parser, invalid.getMessage(), invalid);
        }
    }

    /** Override the host's Long-as-ID serializer only for project revision numbers. */
    static final class NumericSerializer
            extends com.fasterxml.jackson.databind.ser.std.StdSerializer<Long> {
        public NumericSerializer() {
            super(Long.class);
        }

        @Override
        public void serialize(
                Long value,
                com.fasterxml.jackson.core.JsonGenerator output,
                com.fasterxml.jackson.databind.SerializerProvider provider)
                throws IOException {
            output.writeNumber(value);
        }
    }
}
