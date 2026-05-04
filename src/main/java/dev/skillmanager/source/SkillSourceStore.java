package dev.skillmanager.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

public final class SkillSourceStore {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final SkillStore store;

    public SkillSourceStore(SkillStore store) {
        this.store = store;
    }

    public Path file(String skillName) {
        return store.sourcesDir().resolve(skillName + ".json");
    }

    public void write(SkillSource source) throws IOException {
        Fs.ensureDir(store.sourcesDir());
        JSON.writerWithDefaultPrettyPrinter().writeValue(file(source.name()).toFile(), source);
    }

    public Optional<SkillSource> read(String skillName) {
        Path f = file(skillName);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(f.toFile(), SkillSource.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void delete(String skillName) throws IOException {
        Path f = file(skillName);
        if (Files.exists(f)) Files.delete(f);
    }

    public void addError(String skillName, SkillSource.ErrorKind kind, String message) throws IOException {
        Optional<SkillSource> cur = read(skillName);
        if (cur.isEmpty()) return;
        write(cur.get().withErrorAdded(new SkillSource.SkillError(kind, message, nowIso())));
    }

    public void clearError(String skillName, SkillSource.ErrorKind kind) throws IOException {
        Optional<SkillSource> cur = read(skillName);
        if (cur.isEmpty() || !cur.get().hasError(kind)) return;
        write(cur.get().withErrorRemoved(kind));
    }

    public static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }
}
