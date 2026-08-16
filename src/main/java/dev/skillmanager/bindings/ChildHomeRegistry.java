package dev.skillmanager.bindings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.HomePaths;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parent-side registry for child Skill Manager homes.
 *
 * <p>{@code parentHome} names the home this registry lives in — a
 * self-reference, so it is persisted as {@code $SKILL_MANAGER_HOME} and
 * the record survives the home being copied elsewhere. {@code childHome}
 * points at a project checkout somewhere else on disk and is stored
 * verbatim. Reads accept either spelling of {@code parentHome}, so
 * records written before this encoding existed still load.
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
        BindingJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), encode(record));
    }

    /** Read one record with {@code parentHome} resolved back to a real path. */
    public Optional<ChildHomeRecord> read(String id) {
        Path file = file(id);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(decode(
                    BindingJson.MAPPER.readValue(file.toFile(), ChildHomeRecord.class)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private ChildHomeRecord encode(ChildHomeRecord record) {
        HomePaths paths = HomePaths.of(store.root());
        return new ChildHomeRecord(
                record.id(),
                paths.encode(record.parentHome()),
                record.childHome(),
                record.harnessName(),
                record.units(),
                record.createdAt());
    }

    private ChildHomeRecord decode(ChildHomeRecord record) {
        HomePaths paths = HomePaths.of(store.root());
        return new ChildHomeRecord(
                record.id(),
                paths.decodeToString(record.parentHome()),
                record.childHome(),
                record.harnessName(),
                record.units(),
                record.createdAt());
    }

    public void delete(String id) throws IOException {
        Path dir = file(id).getParent();
        if (Files.exists(dir)) Fs.deleteRecursive(dir);
    }

    public boolean exists(String id) {
        return Files.isRegularFile(file(id));
    }

    /**
     * Every child home this home has registered, decoded.
     *
     * <p>{@link #childHomesClaiming} already opens this directory and reads the
     * same files, and returns only ids — enough to REFUSE a removal, and not
     * enough to answer the question a teardown has to ask instead: which of
     * <em>this</em> home's own files a child home is depending on. A child's
     * {@code bin/cli} entry is a symlink at the parent's by design
     * ({@code ChildHomeMaterializer.mirrorExistingShim}), so pruning in the
     * parent without reading the children breaks a home whose name appears
     * nowhere in the parent's installed set.
     *
     * <p>Records are {@link #decode}d, unlike {@link #childHomesClaiming},
     * which reads them raw and leaves {@code parentHome} token-encoded — that
     * is invisible while only {@code units()} is read and wrong the moment a
     * caller looks at a path.
     *
     * <p>Never throws. A registry that cannot be read is reported and treated
     * as empty by the caller, which is the conservative direction for a reader
     * and the dangerous one for a deleter — so the deleter
     * ({@link dev.skillmanager.artifacts.ArtifactPrune}) handles its own
     * failure rather than inferring it from an empty list.
     */
    public List<ChildHomeRecord> list() {
        Path root = root();
        if (!Files.isDirectory(root)) return List.of();
        List<ChildHomeRecord> out = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path dir : (Iterable<Path>) stream::iterator) {
                Path file = dir.resolve(FILENAME);
                if (!Files.isRegularFile(file)) continue;
                try {
                    out.add(decode(BindingJson.MAPPER.readValue(file.toFile(),
                            ChildHomeRecord.class)));
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {
            return List.copyOf(out);
        }
        out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.id(), b.id()));
        return List.copyOf(out);
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
