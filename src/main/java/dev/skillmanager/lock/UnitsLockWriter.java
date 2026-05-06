package dev.skillmanager.lock;

import dev.skillmanager.shared.util.Fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Writes {@link UnitsLock} to {@code units.lock.toml} deterministically:
 * rows are sorted alphabetically by name and fields land in a fixed
 * order, so two writes of the same lock produce identical bytes. This
 * matters for reproducibility — the lock is meant to be vendored and
 * diffed against by reviewers.
 *
 * <p>{@link #atomicWrite} stages the new content in a sibling
 * {@code .tmp} file and {@code Files.move(REPLACE_EXISTING, ATOMIC_MOVE)}s
 * it into place. The lock is the source-of-truth for vendored installs;
 * a half-written lock left behind by a crash mid-write would be worse
 * than an unchanged lock.
 */
public final class UnitsLockWriter {

    private UnitsLockWriter() {}

    /** Render the lock as TOML text. Pure — no IO. */
    public static String render(UnitsLock lock) {
        StringBuilder sb = new StringBuilder();
        sb.append("schema_version = ").append(lock.schemaVersion()).append('\n');

        List<LockedUnit> sorted = new ArrayList<>(lock.units());
        sorted.sort(Comparator.comparing(LockedUnit::name, String.CASE_INSENSITIVE_ORDER));
        for (LockedUnit u : sorted) {
            sb.append('\n');
            sb.append("[[units]]\n");
            sb.append("name = ").append(quote(u.name())).append('\n');
            sb.append("kind = ").append(quote(u.kind().name().toLowerCase())).append('\n');
            if (u.version() != null) sb.append("version = ").append(quote(u.version())).append('\n');
            sb.append("install_source = ").append(quote(u.installSource().name())).append('\n');
            if (u.origin() != null) sb.append("origin = ").append(quote(u.origin())).append('\n');
            if (u.ref() != null) sb.append("ref = ").append(quote(u.ref())).append('\n');
            if (u.resolvedSha() != null) sb.append("resolved_sha = ").append(quote(u.resolvedSha())).append('\n');
        }
        return sb.toString();
    }

    /**
     * Write {@code lock} to {@code path} atomically: stage in a tempfile,
     * rename into place. The parent directory is created if missing.
     */
    public static void atomicWrite(UnitsLock lock, Path path) throws IOException {
        Fs.ensureDir(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        Files.writeString(tmp, render(lock));
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            // Some filesystems (notably Windows in odd configs) don't support
            // ATOMIC_MOVE across the same dir; fall back to a non-atomic
            // replace. Still better than writing in-place.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String quote(String s) {
        // TOML basic strings: \\ and \" need escaping; control chars excluded.
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
