package dev.skillmanager.bindings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parent-side registry for child Skill Manager homes.
 */
public final class ChildHomeRegistry {

    public static final String DIR = "child-homes";
    public static final String FILENAME = "child-home.json";

    private final SkillStore store;

    public ChildHomeRegistry(SkillStore store) {
        this.store = store;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChildHomeRecord(
            String id,
            String parentHome,
            String childHome,
            String harnessName,
            List<String> units,
            String createdAt
    ) {
        public ChildHomeRecord {
            units = units == null ? List.of() : List.copyOf(units);
        }
    }

    public void write(ChildHomeRecord record) throws IOException {
        Path file = file(record.id());
        Fs.ensureDir(file.getParent());
        BindingJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), record);
    }

    public List<String> childHomesClaiming(String unitName) throws IOException {
        Path root = root();
        if (!Files.isDirectory(root)) return List.of();
        List<String> out = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path dir : (Iterable<Path>) stream::iterator) {
                Path file = dir.resolve(FILENAME);
                if (!Files.isRegularFile(file)) continue;
                try {
                    ChildHomeRecord record = BindingJson.MAPPER.readValue(file.toFile(), ChildHomeRecord.class);
                    if (record.units().contains(unitName)) out.add(record.id());
                } catch (IOException ignored) {}
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public Path file(String id) {
        return root().resolve(safeId(id)).resolve(FILENAME);
    }

    private Path root() {
        return store.root().resolve(DIR);
    }

    private static String safeId(String id) {
        if (id == null || id.isBlank()) return "child";
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
