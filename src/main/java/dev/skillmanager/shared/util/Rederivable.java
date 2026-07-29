package dev.skillmanager.shared.util;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One definition of <b>re-derivable</b>: content nobody authored, that a tool
 * writes again on demand, and that therefore belongs to no home.
 *
 * <h2>Why this is one class</h2>
 *
 * <p>Two callers ask a question about the same property and used to answer it
 * separately — which is issue #24's shape, and this time the drift had a cost.
 *
 * <ul>
 *   <li>{@code HomeCloner} asks <em>"should the copy carry these bytes?"</em>
 *       and has asked it since the first clone: a {@code .pyc} embeds its
 *       {@code co_filename} and a Gradle {@code executionHistory.bin} embeds
 *       task input paths, both inside binary formats where a length-changing
 *       substitution would corrupt the file. Copying them guarantees a leak.</li>
 *   <li>{@code ChildHomeMaterializer} asks <em>"are these bytes unit CONTENT,
 *       which a reconcile owns?"</em> — and did not ask it at all. That is
 *       issue #41. Running {@code discover.py} once in a ticket worktree, which
 *       is the thing a worktree exists to do, wrote
 *       {@code project_sdk_sources/build-logic/.gradle/**} and
 *       {@code scripts/__pycache__/*.pyc} inside a unit. The unit's digest
 *       moved, so it reported {@code conflicted}; the remedy it printed
 *       ({@code home sync --merge  (then resolve: executionHistory.bin, …)})
 *       exited 1 without clearing the gate; and {@code home close-out} could
 *       never be satisfied again. Measured on three real repositories — 6, 5
 *       and 5 files each — plus a built jar
 *       ({@code validation-graph-build-logic-0.1.0.jar}) that a merge carried
 *       worktree → project as though it were somebody's work.</li>
 * </ul>
 *
 * <h2 id="not-git">{@code .git} IS DELIBERATELY NOT HERE. DO NOT ADD IT.</h2>
 *
 * <p>A {@code .git} directory rewrites itself on every read-only command, so it
 * looks exactly like the entries below and every instinct that put
 * {@code .gradle} here says to put it here too. It is the opposite kind of
 * thing, and skipping it is a <b>data-loss defect</b>, recorded as issue #29:
 *
 * <p>a unit an agent <em>committed</em> work in holds those commits in
 * {@code .git} and nowhere else. Excluded from the digest, such a unit reads as
 * unmodified — the worktree files genuinely are unchanged, because the work is
 * in the object store — so {@code home close-out} clears the teardown, the
 * worktree is removed, and the commits stop existing. Everything below is
 * re-derivable; git history is the least re-derivable thing in a home.
 * {@code CHECKOUT} units are judged by
 * {@code ChildHomeMaterializer.checkoutIsModified}, which asks git instead of a
 * digest, for exactly this reason.
 *
 * <p>The same argument bars anything that <em>holds</em> state rather than
 * caching it — {@code .svn}, {@code .hg}, a lock file recording what is
 * installed. The membership test is not "does it churn" but "can a tool write
 * it again from content that is still here".
 *
 * <h2>Two scopes, and the one place they genuinely differ</h2>
 *
 * <p>{@link #CACHES} is shared by both callers. {@link #OUTPUT_ROOTS} is read
 * by the reconcile only, and the asymmetry is deliberate rather than an
 * oversight:
 *
 * <ul>
 *   <li>A <b>clone</b> has to hand over a home that WORKS. It carries
 *       {@code node_modules/} and in-unit {@code .venv/} and re-anchors their
 *       absolute references per copy, because nothing on the destination
 *       regenerates them automatically and
 *       {@code node_modules/<pkg>/build/Release/*.node} is a prebuilt native
 *       binary no command will rebuild. Skipping those in a clone would hand
 *       over broken tools with no report saying so.</li>
 *   <li>A <b>reconcile</b> moves authored unit content between two homes that
 *       each provision their own tooling, and it has no re-anchoring pass at
 *       all. A {@code .venv} it copied would arrive in the destination with
 *       console-script shebangs naming the SOURCE home — a path the kernel
 *       resolves literally — so the destination would receive a tool that is
 *       broken the moment the source home is removed. That is the very leak
 *       class {@code home clone} exists to prevent, reintroduced through the
 *       other door.</li>
 * </ul>
 *
 * <p>The names in {@link #CACHES} are tool-private and reserved; the names in
 * {@link #OUTPUT_ROOTS} are ordinary words used by convention. That is also why
 * only the first set is safe to apply to a byte-for-byte copy.
 */
public final class Rederivable {

    private Rederivable() {}

    /**
     * Tool-private cache names that can only ever be caches, skipped by every
     * caller wherever they appear.
     *
     * <p>{@code .lark_cache.bin} is a FILE rather than a directory, which is
     * why an enumeration of directory names missed it: lark writes an absolute
     * grammar path into that binary blob, and cloning the operator's real home
     * this single 504 KB file under {@code skills/deploy-helm/.venv/.../hcl2/}
     * was the only leak — enough to make {@code home clone} unusable on the one
     * home that matters.
     */
    public static final Set<String> CACHES = Set.of(
            "__pycache__", ".gradle", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".tox",
            ".lark_cache.bin");

    /**
     * Build-output and provisioned-dependency roots. Not unit content, so a
     * reconcile neither compares them, copies them, nor destroys them — see
     * {@code ChildHomeMaterializer.carryOverUnownedTrees}. Not consulted by
     * {@code HomeCloner}, per the scope note above.
     *
     * <ul>
     *   <li>{@code build}, {@code target} — the Gradle and Maven output roots.
     *       The jar a merge carried worktree → project lived under the first.</li>
     *   <li>{@code node_modules} — regenerated by {@code npm ci} from a
     *       lockfile the unit does carry.</li>
     *   <li>{@code .venv}, {@code venv} — regenerated from
     *       {@code pyproject.toml} / {@code requirements.txt}, and unmovable
     *       between homes for the shebang reason above.</li>
     * </ul>
     */
    public static final Set<String> OUTPUT_ROOTS = Set.of(
            "build", "target", "node_modules", ".venv", "venv");

    private static final Set<String> DERIVED = derived();

    private static Set<String> derived() {
        Set<String> all = new LinkedHashSet<>(CACHES);
        all.addAll(OUTPUT_ROOTS);
        return Set.copyOf(all);
    }

    /** Whether one path segment names a tool-private cache. */
    public static boolean isCacheName(String name) {
        return name != null && (CACHES.contains(name) || isCompiledPython(name));
    }

    /** Whether ANY segment of a relative path names a tool-private cache. */
    public static boolean isCache(String rel) {
        return anySegment(rel, true);
    }

    /**
     * Whether one path segment names something a reconcile does not own: a
     * cache, a build-output root, or a provisioned dependency tree.
     */
    public static boolean isDerivedName(String name) {
        return name != null && (DERIVED.contains(name) || isCompiledPython(name));
    }

    /**
     * Whether ANY segment of a relative path is {@link #isDerivedName}.
     *
     * <p>Segment-wise rather than prefix-wise on purpose: the reported defect
     * was {@code project_sdk_sources/build-logic/.gradle/**}, four levels down
     * inside a unit, and a rule anchored at the top of the tree would not have
     * seen it.
     */
    public static boolean isDerived(String rel) {
        return anySegment(rel, false);
    }

    private static boolean isCompiledPython(String name) {
        return name.endsWith(".pyc") || name.endsWith(".pyo");
    }

    private static boolean anySegment(String rel, boolean cachesOnly) {
        if (rel == null || rel.isEmpty()) return false;
        for (String segment : rel.replace(File.separatorChar, '/').split("/")) {
            if (cachesOnly ? isCacheName(segment) : isDerivedName(segment)) return true;
        }
        return false;
    }
}
