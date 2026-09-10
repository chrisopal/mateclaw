package vip.mate.semantic.extraction;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import vip.mate.semantic.core.fact.*;
import java.io.IOException;
import java.time.Instant;

/** Private persistence codec; explicit closed subtype names, never arbitrary Java class names. */
final class ExtractionJson {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, property="kind")
    @JsonSubTypes({@JsonSubTypes.Type(value=PredicateRef.PropertyRef.class,name="PROPERTY"),
        @JsonSubTypes.Type(value=PredicateRef.RelationRef.class,name="RELATION")})
    interface Predicates {}
    ExtractionJson() {
        mapper.addMixIn(PredicateRef.class,Predicates.class);
        SimpleModule module=new SimpleModule();
        module.addSerializer(Validity.class,new JsonSerializer<>() {
            public void serialize(Validity v,JsonGenerator out,SerializerProvider p)throws IOException {
                out.writeStartObject();out.writeStringField("kind",v.kind().name());
                out.writeObjectField("from",v.fromInclusive());out.writeObjectField("to",v.toExclusive());out.writeEndObject();
            }
        });
        module.addDeserializer(Validity.class,new JsonDeserializer<>() {
            public Validity deserialize(JsonParser in,DeserializationContext c)throws IOException {
                JsonNode n=in.getCodec().readTree(in);
                return "UNKNOWN".equals(n.path("kind").asText())?Validity.unknown():Validity.interval(instant(n.get("from")),instant(n.get("to")));
            }
        });
        mapper.registerModule(module);
    }
    private static Instant instant(JsonNode n){return n==null||n.isNull()?null:Instant.parse(n.asText());}
    String write(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Extraction persistence encoding failed",e);}}
    <T>T read(String value,Class<T> type){try{return mapper.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Extraction persistence decoding failed",e);}}
}
