package dev.skillmanager.bindings;

import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
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

    public Path file(String unitName) {
        return store.installedDir().resolve(unitName + ".projections.json");
    }

    public ProjectionLedger read(String unitName) {
        Path f = file(unitName);
        if (!Files.isRegularFile(f)) return ProjectionLedger.empty(unitName);
        try {
            return BindingJson.MAPPER.readValue(f.toFile(), ProjectionLedger.class);
        } catch (IOException e) {
            return ProjectionLedger.empty(unitName);
        }
    }

    public void write(ProjectionLedger ledger) throws IOException {
        Fs.ensureDir(store.installedDir());
        // Empty ledger → drop the file to keep installed/ tidy.
        if (ledger.bindings().isEmpty()) {
            delete(ledger.unitName());
            return;
        }
        BindingJson.MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(file(ledger.unitName()).toFile(), ledger);
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
                    ProjectionLedger l = BindingJson.MAPPER.readValue(f.toFile(), ProjectionLedger.class);
                    out.addAll(l.bindings());
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return out;
    }

    /** Find a binding across all units by its id; first match wins (ids are ULIDs, unique). */
    public Optional<LocatedBinding> findById(String bindingId) {
        Path dir = store.installedDir();
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.projections.json")) {
            for (Path f : stream) {
                try {
                    ProjectionLedger l = BindingJson.MAPPER.readValue(f.toFile(), ProjectionLedger.class);
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

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] B32 =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    /**
     * Generate a 26-character ULID-shaped binding id: 10 chars of
     * Crockford-base32 timestamp + 16 chars of randomness. Stable,
     * sortable by creation time, no dashes.
     */
    public static String newBindingId() {
        long ts = System.currentTimeMillis();
        char[] out = new char[26];
        for (int i = 9; i >= 0; i--) {
            out[i] = B32[(int) (ts & 0x1F)];
            ts >>>= 5;
        }
        for (int i = 10; i < 26; i++) {
            out[i] = B32[RNG.nextInt(32)];
        }
        return new String(out);
    }

    public static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    /** A binding plus the unit name whose ledger holds it. Result of {@link #findById}. */
    public record LocatedBinding(String unitName, Binding binding) {}
}
