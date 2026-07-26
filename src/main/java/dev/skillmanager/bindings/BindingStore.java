package dev.skillmanager.bindings;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes per-unit binding ledgers under
 * {@code installed/<name>.projections.json}.
 *
 * <p>Mirrors {@link dev.skillmanager.source.UnitStore} for the unit
 * record but holds its own ObjectMapper so {@link Path}-typed fields
 * round-trip cleanly. One file per unit so concurrent operations on
 * different units never contend; per-unit JSON edits are read-modify-
 * write (callers serialize via the executor — there's no in-process
 * locking here).
 */
public final class BindingStore {

    private final SkillStore store;

    public BindingStore(SkillStore store) {
        this.store = store;
    }

    /**
     * Ledgers live inside the home they describe, so their store-side
     * paths are self-references and are persisted relative to it. See
     * {@link dev.skillmanager.store.HomePaths}.
     */
    private ObjectMapper mapper() {
        return BindingJson.mapperFor(store.root());
    }

    public Path file(String unitName) {
        return store.installedDir().resolve(unitName + ".projections.json");
    }

    public ProjectionLedger read(String unitName) {
        Path f = file(unitName);
        if (!Files.isRegularFile(f)) return ProjectionLedger.empty(unitName);
        try {
            return mapPaths(mapper().readValue(f.toFile(), ProjectionLedger.class), false);
        } catch (IOException e) {
            return ProjectionLedger.empty(unitName);
        }
    }

    /**
     * Apply the home encoding to {@link Projection#backupOf()}, which the
     * mapper cannot reach: it is a {@code String}, so
     * {@link BindingJson}'s {@code Path} serializer never sees it. It holds
     * the destination a {@link ConflictPolicy#RENAME_EXISTING} moved out of
     * the way, so it is normally external — but nothing structurally
     * prevents it naming a path in the home, and unbind moves the backup
     * back to it.
     */
    private ProjectionLedger mapPaths(ProjectionLedger ledger, boolean encoding) {
        dev.skillmanager.store.HomePaths paths =
                dev.skillmanager.store.HomePaths.of(store.root());
        List<Binding> bindings = new ArrayList<>(ledger.bindings().size());
        for (Binding b : ledger.bindings()) {
            List<Projection> projections = new ArrayList<>(b.projections().size());
            for (Projection p : b.projections()) {
                String backupOf = p.backupOf() == null ? null
                        : encoding ? paths.encode(p.backupOf()) : paths.decodeToString(p.backupOf());
                projections.add(new Projection(p.bindingId(), p.sourcePath(), p.destPath(),
                        p.kind(), backupOf, p.boundHash()));
            }
            bindings.add(b.withProjections(projections));
        }
        return new ProjectionLedger(ledger.unitName(), bindings);
    }

    public void write(ProjectionLedger ledger) throws IOException {
        Fs.ensureDir(store.installedDir());
        // Empty ledger → drop the file to keep installed/ tidy.
        if (ledger.bindings().isEmpty()) {
            delete(ledger.unitName());
            return;
        }
        mapper().writerWithDefaultPrettyPrinter()
                .writeValue(file(ledger.unitName()).toFile(), mapPaths(ledger, true));
    }

    public void delete(String unitName) throws IOException {
        Path f = file(unitName);
        if (Files.exists(f)) Files.delete(f);
    }

    /**
     * Walk every ledger in {@code installed/} and surface every binding.
     * Used by {@code bindings list} when no {@code --unit} filter is set.
     */
    public List<Binding> listAll() {
        List<Binding> out = new ArrayList<>();
        Path dir = store.installedDir();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.projections.json")) {
            for (Path f : stream) {
                try {
                    ProjectionLedger l = mapPaths(
                            mapper().readValue(f.toFile(), ProjectionLedger.class), false);
                    out.addAll(l.bindings());
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return out;
    }

    /** Find a binding across all units by its id; first match wins. */
    public Optional<LocatedBinding> findById(String bindingId) {
        Path dir = store.installedDir();
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.projections.json")) {
            for (Path f : stream) {
                try {
                    ProjectionLedger l = mapPaths(
                            mapper().readValue(f.toFile(), ProjectionLedger.class), false);
                    for (Binding b : l.bindings()) {
                        if (b.bindingId().equals(bindingId)) {
                            return Optional.of(new LocatedBinding(l.unitName(), b));
                        }
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return Optional.empty();
    }

    // -------------------------------------------------------- helpers

    public static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    /** A binding plus the unit name whose ledger holds it. Result of {@link #findById}. */
    public record LocatedBinding(String unitName, Binding binding) {}
}
