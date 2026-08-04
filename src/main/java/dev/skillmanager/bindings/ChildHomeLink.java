package dev.skillmanager.bindings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one answer to "is the home at {@code child} a child home of the store at
 * {@code parent}".
 *
 * <h2>Why a predicate exists at all</h2>
 *
 * <p>{@code home verify}'s isolation rule (#49) forbids <em>any</em> path in a
 * home resolving into another home, and it was written before child homes
 * existed. {@link ChildHomeMaterializer#mirrorExistingShim} then made exactly
 * such a path <b>on purpose</b>: a child home's {@code bin/cli/<dep>} is a
 * symlink at the PARENT store's entry, so the child shares the toolchain the
 * parent provisioned instead of installing a second copy of it. Its
 * {@link ChildHomeMaterializer.ShimOutcome#MIRRORED} is documented as "the
 * child now links at the parent's entry", and
 * {@link ChildHomeMaterializer.ShimOutcome#KEPT_LOCAL} already names the
 * collision out loud — "it puts a live symlink into the parent home in a
 * directory that exists to reach no other home — the very thing {@code home
 * verify} refuses".
 *
 * <p>Measured on the {@code harness-smoke} graph: every child home carrying one
 * CLI dep failed its own {@code home verify} with
 * {@code ✗ FOREIGN_HOME bin/cli/pycowsay … resolves into the home at <parent>}.
 * {@code project-child-home} passed only because its fixtures declare no CLI
 * deps.
 *
 * <p>So the isolation rule is over-broad rather than wrong: a foreign home's
 * path leaking in is a real defect, and a child's sanctioned link at its own
 * parent's store is not the same thing. Telling them apart needs an answer to
 * this question, and the answer has to come from evidence on disk rather than
 * from the SHAPE of the link — the shape is all a copied home's stale link has
 * too.
 *
 * <h2>Two pieces of evidence, and why one is not enough</h2>
 *
 * <ol>
 *   <li><b>The parent's claim.</b>
 *       {@code <parent>/child-homes/<id>/child-home.json} names
 *       {@code parentHome} and {@code childHome}; it is written by
 *       {@link ProjectChildHomeScaffolder} and {@link ChildHomeHarnessInstaller}
 *       and by nothing else. This is the authoritative record while the child
 *       home is live.</li>
 *   <li><b>The child's own provenance.</b>
 *       {@code <child>/.materialization/<kind>/<unit>.json} records the
 *       {@code source} each unit was materialized from — a path inside the
 *       parent store. Written by {@link ChildHomeMaterializer}, in the child
 *       home, and by nothing else.</li>
 * </ol>
 *
 * <p>The parent's claim alone is <em>not</em> sufficient, and that is measured
 * rather than assumed: {@code harness rm} deliberately deletes the child-home
 * record while deliberately keeping the child store — the {@code harness-smoke}
 * graph asserts both, as {@code child_home_registry_removed} and
 * {@code child_store_survives_teardown}. A rule reading only the parent side
 * therefore calls a surviving child home a foreign-home leak the moment its
 * harness instance is torn down, which is the exact state the fixpoint law
 * caught. The child's own provenance is what outlives that teardown, because
 * the units it describes outlive it too.
 *
 * <p>Neither piece is inferred from the other, and either one on its own is
 * positive evidence that this home was built out of that store. A home that is
 * not a child of {@code parent} has neither.
 */
public final class ChildHomeLink {

    private ChildHomeLink() {}

    /**
     * True when {@code child} is recorded — on either side — as a child home
     * of the Skill Manager store at {@code parent}.
     *
     * <p>Never true for {@code child.equals(parent)}: the degenerate
     * same-home layout has no cross-home link to sanction, and answering
     * "yes" there would let a home sanction paths into itself, which is a
     * question this predicate is not being asked.
     */
    public static boolean isChildOf(Path child, Path parent) {
        if (child == null || parent == null) return false;
        Path c = child.toAbsolutePath().normalize();
        Path p = parent.toAbsolutePath().normalize();
        if (samePath(c, p)) return false;
        return parentClaims(p, c) || childWasMaterializedFrom(c, p);
    }

    /**
     * The parent side: any {@code child-homes/<id>/child-home.json} in
     * {@code parent} whose {@code childHome} is {@code child}.
     *
     * <p>Read with the raw mapper rather than {@link ChildHomeRegistry#read},
     * because the id is not known here and {@code childHome} is stored
     * verbatim — the only encoded field is {@code parentHome}, which this
     * question does not use. Same read {@link ChildHomeRegistry#childHomesClaiming}
     * performs.
     */
    private static boolean parentClaims(Path parent, Path child) {
        Path root = parent.resolve(ChildHomeRegistry.DIR);
        if (!Files.isDirectory(root)) return false;
        try (var stream = Files.list(root)) {
            for (Path dir : (Iterable<Path>) stream::iterator) {
                Path file = dir.resolve(ChildHomeRegistry.FILENAME);
                if (!Files.isRegularFile(file)) continue;
                try {
                    ChildHomeRegistry.ChildHomeRecord record = BindingJson.MAPPER
                            .readValue(file.toFile(), ChildHomeRegistry.ChildHomeRecord.class);
                    String claimed = record.childHome();
                    if (claimed == null || claimed.isBlank()) continue;
                    if (samePath(Path.of(claimed).toAbsolutePath().normalize(), child)) return true;
                } catch (IOException | RuntimeException unreadable) {
                    // A record this program cannot parse is not a claim. Never
                    // a reason to sanction: a downgrade needs positive
                    // evidence, and not being able to tell is not that.
                }
            }
        } catch (IOException cannotList) {
            return false;
        }
        return false;
    }

    /**
     * The child side: any materialization record in {@code child} whose
     * {@code source} is inside {@code parent}.
     *
     * <p>Both sides go through {@link #realized} before they are compared, and
     * that is load-bearing rather than defensive. The parent path this is asked
     * about arrives from {@code HomeCloner}'s link resolution, so it is already
     * the {@code /private/var/…} spelling; the recorded {@code source} is
     * whatever the scaffolder held, which on macOS is the {@code /var/…} one.
     * Measured: a plain {@code HomePaths.isInsideHome} said no on exactly the
     * child home this predicate exists for, because that method deliberately
     * never resolves the candidate — right for a link target, wrong for a
     * recorded source. The source need not still exist, so the resolution stops
     * at the deepest ancestor that does.
     */
    private static boolean childWasMaterializedFrom(Path child, Path parent) {
        Path records = child.resolve(ChildHomeMaterializer.RECORDS_DIR);
        if (!Files.isDirectory(records)) return false;
        Path parentReal = realized(parent);
        try (var stream = Files.walk(records, 3)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                if (!file.getFileName().toString().endsWith(".json")) continue;
                try {
                    ChildHomeMaterializer.MaterializationRecord record = BindingJson.MAPPER
                            .readValue(file.toFile(),
                                    ChildHomeMaterializer.MaterializationRecord.class);
                    String source = record.source();
                    if (source == null || source.isBlank()) continue;
                    if (realized(Path.of(source)).startsWith(parentReal)) return true;
                } catch (IOException | RuntimeException unreadable) {
                    // As above: unreadable is not evidence.
                }
            }
        } catch (IOException cannotWalk) {
            return false;
        }
        return false;
    }

    /** Equality across the {@code /var} — {@code /private/var} pair. */
    private static boolean samePath(Path a, Path b) {
        if (a.equals(b)) return true;
        return realized(a).equals(realized(b));
    }

    /**
     * {@code path} with its deepest EXISTING ancestor resolved and the missing
     * tail re-appended.
     *
     * <p>{@code toRealPath} alone answers nothing for a path that has been
     * deleted — a unit uninstalled from the parent since the child was built —
     * and these records are statements about the past, so a missing directory
     * must not silently turn into "not my parent".
     */
    private static Path realized(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        java.util.Deque<Path> tail = new java.util.ArrayDeque<>();
        Path existing = abs;
        while (existing != null && !Files.exists(existing)) {
            Path name = existing.getFileName();
            if (name != null) tail.addFirst(name);
            existing = existing.getParent();
        }
        if (existing == null) return abs;
        Path out = realOrSame(existing);
        for (Path segment : tail) out = out.resolve(segment);
        return out;
    }

    private static Path realOrSame(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException notResolvable) {
            return path;
        }
    }
}
