package dev.skillmanager.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Read / write {@link SkillSource} records as JSON under
 * {@code <store>/sources/<name>.json}. One file per installed skill,
 * created by {@code skill-manager install} and read by sync / upgrade.
 */
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
        Path out = file(source.name());
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), source);
    }

    /** Read the source record for {@code skillName}, or empty if absent / unreadable. */
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
}
