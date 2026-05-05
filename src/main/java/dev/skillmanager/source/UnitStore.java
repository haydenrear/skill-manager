package dev.skillmanager.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Reads and writes per-unit metadata under {@code installed/<name>.json}.
 * Replaces {@code SkillSourceStore}; the on-disk JSON shape is unchanged
 * for legacy fields and gains an optional {@code unitKind} field that
 * defaults to {@code "SKILL"} when absent.
 *
 * <p>The directory move from {@code sources/} → {@code installed/} is
 * handled by {@link #migrateFromLegacy(SkillStore)}, called from
 * {@link dev.skillmanager.lifecycle.SkillReconciler} on startup. It is
 * idempotent: subsequent runs find no legacy files and exit cleanly.
 */
public final class UnitStore {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final SkillStore store;

    public UnitStore(SkillStore store) {
        this.store = store;
    }

    public Path file(String unitName) {
        return store.installedDir().resolve(unitName + ".json");
    }

    public void write(InstalledUnit unit) throws IOException {
        Fs.ensureDir(store.installedDir());
        JSON.writerWithDefaultPrettyPrinter().writeValue(file(unit.name()).toFile(), unit);
    }

    public Optional<InstalledUnit> read(String unitName) {
        Path f = file(unitName);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(f.toFile(), InstalledUnit.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void delete(String unitName) throws IOException {
        Path f = file(unitName);
        if (Files.exists(f)) Files.delete(f);
    }

    public void addError(String unitName, InstalledUnit.ErrorKind kind, String message) throws IOException {
        Optional<InstalledUnit> cur = read(unitName);
        if (cur.isEmpty()) return;
        write(cur.get().withErrorAdded(new InstalledUnit.UnitError(kind, message, nowIso())));
    }

    public void clearError(String unitName, InstalledUnit.ErrorKind kind) throws IOException {
        Optional<InstalledUnit> cur = read(unitName);
        if (cur.isEmpty() || !cur.get().hasError(kind)) return;
        write(cur.get().withErrorRemoved(kind));
    }

    public static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    // ----------------------------------------------------------- migration

    /**
     * One-time migration: copy every {@code <root>/sources/<name>.json}
     * into {@code <root>/installed/<name>.json} (preserving content
     * verbatim — Jackson re-parses each record and writes it back, so
     * any legacy field absent from {@link InstalledUnit} is dropped and
     * the new {@code unitKind} field is added with default {@link
     * dev.skillmanager.model.UnitKind#SKILL}). Source files are deleted
     * after a successful copy. If the legacy directory doesn't exist or
     * is empty, the call is a no-op. Idempotent.
     *
     * @return number of files migrated this call (0 on subsequent runs).
     */
    public static int migrateFromLegacy(SkillStore store) {
        Path legacy = store.root().resolve("sources");
        if (!Files.isDirectory(legacy)) return 0;
        int moved = 0;
        try {
            Fs.ensureDir(store.installedDir());
        } catch (IOException e) {
            Log.warn("could not create installed/ for migration: %s", e.getMessage());
            return 0;
        }
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(legacy, "*.json")) {
            for (Path src : dir) {
                Path target = store.installedDir().resolve(src.getFileName());
                if (Files.exists(target)) {
                    // Already migrated for this unit; drop the legacy file
                    // so we don't keep finding it.
                    try { Files.delete(src); } catch (IOException ignored) {}
                    continue;
                }
                try {
                    InstalledUnit unit = JSON.readValue(src.toFile(), InstalledUnit.class);
                    JSON.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), unit);
                    Files.delete(src);
                    moved++;
                    Log.info("migrated installed-unit record: %s", src.getFileName());
                } catch (IOException e) {
                    Log.warn("failed to migrate %s: %s", src.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            Log.warn("could not list legacy sources/ directory: %s", e.getMessage());
        }
        // Best-effort cleanup of an empty legacy dir.
        try {
            if (Files.isDirectory(legacy) && !Files.list(legacy).findAny().isPresent()) {
                Files.delete(legacy);
            }
        } catch (IOException ignored) {}
        return moved;
    }
}
