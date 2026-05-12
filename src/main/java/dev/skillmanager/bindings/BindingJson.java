package dev.skillmanager.bindings;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Shared {@link ObjectMapper} for binding-ledger I/O. Provides
 * {@link Path} ↔ JSON-string conversion so {@link Binding} and
 * {@link Projection} can keep {@code Path}-typed fields without
 * Jackson's default {@code Iterable<Path>}-via-{@link Path} confusion.
 */
public final class BindingJson {

    private BindingJson() {}

    public static final ObjectMapper MAPPER = create();

    private static ObjectMapper create() {
        SimpleModule m = new SimpleModule("skill-manager-bindings");
        m.addSerializer(Path.class, new JsonSerializer<Path>() {
            @Override
            public void serialize(Path value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.toString());
            }
        });
        m.addDeserializer(Path.class, new JsonDeserializer<Path>() {
            @Override
            public Path deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                return Path.of(p.getValueAsString());
            }
        });
        return new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .registerModule(m);
    }
}
