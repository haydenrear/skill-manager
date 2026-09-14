package dev.skillmanager.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Every spelling of one directory that a generated file might hold: the one
 * the caller gave, its resolved form, and the ALIAS forms of both through the
 * filesystem's top-level symlinks.
 *
 * <h2>Why aliases, #343</h2>
 *
 * <p>{@code {given, real}} covers a home handed over through a symlink
 * ({@code /var/x} given, {@code /private/var/x} real). It does not cover the
 * reverse: handed {@code /private/var/x}, both entries are
 * {@code /private/var/x}, and a shim holding {@code /var/x/venvs/...} — the
 * spelling {@code java.io.tmpdir} and most tools hand out on macOS — is never
 * scanned. {@code home verify} then printed "every reference resolves" over a
 * shim that exits 127, which is why a local macOS sweep passed
 * {@code home-clone} while Linux CI refused the same planted defect.
 *
 * <p>A symlink cannot be inverted in general (see
 * {@code HomeVerifyPathSpellingTest}'s stated limit), but the ones that make
 * every temp path two-spelled can: they are entries of {@code /} itself
 * ({@code /var -> private/var}, {@code /tmp -> private/tmp},
 * {@code /etc -> private/etc}). Those are read from the disk rather than typed
 * here, so a platform with none (Linux) derives none, and one with a different
 * set gets its own.
 */
final class PathSpellings {

    private PathSpellings() {}

    private static volatile Map<Path, Path> rootAliases;

    /** Given, real, then every alias of either; distinct, in that order. */
    static List<String> of(Path path) {
        return of(path, rootAliases());
    }

    /** {@link #of(Path)} against an explicit alias table ({@code link -> target}). */
    static List<String> of(Path path, Map<Path, Path> aliases) {
        Path given = path.toAbsolutePath().normalize();
        Path real = realOrSame(given);
        Set<Path> out = new LinkedHashSet<>();
        out.add(given);
        out.add(real);
        for (Path spelling : List.copyOf(out)) {
            for (Map.Entry<Path, Path> alias : aliases.entrySet()) {
                Path link = alias.getKey();
                Path target = alias.getValue();
                // Both directions: a target-spelled path gains its link
                // spelling, and a link-spelled one its target spelling (which
                // realOrSame already gives for a path that exists, and does not
                // for one that does not).
                if (spelling.startsWith(target)) out.add(link.resolve(target.relativize(spelling)));
                if (spelling.startsWith(link)) out.add(target.resolve(link.relativize(spelling)));
            }
        }
        List<String> strings = new ArrayList<>();
        for (Path p : out) {
            String s = p.toString();
            if (!strings.contains(s)) strings.add(s);
        }
        return List.copyOf(strings);
    }

    /**
     * Symlinks directly under {@code /} whose resolved target is a directory,
     * as {@code link -> resolved target}. Read once per process: the root of
     * the filesystem does not grow aliases while a command runs.
     */
    static Map<Path, Path> rootAliases() {
        Map<Path, Path> cached = rootAliases;
        if (cached != null) return cached;
        Map<Path, Path> found = new LinkedHashMap<>();
        Path root = Path.of("/");
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (!Files.isSymbolicLink(entry)) continue;
                try {
                    Path target = entry.toRealPath();
                    if (!target.equals(entry) && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                        found.put(entry, target);
                    }
                } catch (IOException | RuntimeException dangling) {
                    // a dangling top-level link names nothing a home can live under
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // no aliases derivable: {given, real} is still correct, just narrower
        }
        rootAliases = java.util.Collections.unmodifiableMap(found);
        return rootAliases;
    }

    private static Path realOrSame(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }
}
