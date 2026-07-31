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

import dev.skillmanager.store.HomePaths;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared {@link ObjectMapper} for binding-ledger I/O. Provides
 * {@link Path} ↔ JSON-string conversion so {@link Binding} and
 * {@link Projection} can keep {@code Path}-typed fields without
 * Jackson's default {@code Iterable<Path>}-via-{@link Path} confusion.
 *
 * <p>{@link #MAPPER} writes paths verbatim. {@link #mapperFor(Path)}
 * returns a mapper anchored at one home root: paths that point into
 * that home serialize as {@code $SKILL_MANAGER_HOME/...} and both forms
 * deserialize. Everything persisted <em>inside</em> a home — the
 * projection ledgers under {@code installed/} above all — uses the
 * anchored mapper so the home stays relocatable. {@link #MAPPER} is
 * kept for records that live outside a home (a harness instance lock in
 * a sandbox directory), where there is no home to be relative to.
 */
public final class BindingJson {

    private BindingJson() {}

    public static final ObjectMapper MAPPER = create(null);

    private static final Map<Path, ObjectMapper> ANCHORED = new ConcurrentHashMap<>();

    /**
     * Mapper whose {@link Path} fields are stored relative to
     * {@code homeRoot}. Cached per root — mappers are thread-safe once
     * configured, and stores are created per command, not per call.
     */
    public static ObjectMapper mapperFor(Path homeRoot) {
        if (homeRoot == null) return MAPPER;
        HomePaths paths = HomePaths.of(homeRoot);
        return ANCHORED.computeIfAbsent(paths.homeRoot(), root -> create(paths));
    }

    private static ObjectMapper create(HomePaths paths) {
        SimpleModule m = new SimpleModule("skill-manager-bindings");
        m.addSerializer(Path.class, new JsonSerializer<Path>() {
            @Override
            public void serialize(Path value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(paths == null ? value.toString() : paths.encode(value));
            }
        });
        m.addDeserializer(Path.class, new JsonDeserializer<Path>() {
            @Override
            public Path deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                String raw = p.getValueAsString();
                return paths == null ? Path.of(raw) : paths.decode(raw);
            }
        });
        return new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .registerModule(m);
    }
}
