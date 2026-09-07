package vip.mate.semantic.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;

import java.io.IOException;

/** Semantic version/count numbers keep their wire type despite the host's Long-to-ID serializer. */
public class SemanticCounterSerializer extends StdScalarSerializer<Long> {
    public SemanticCounterSerializer() {
        super(Long.class);
    }

    @Override
    public void serialize(Long value, JsonGenerator generator, SerializerProvider provider)
            throws IOException {
        generator.writeNumber(value);
    }
}
