package dev.skillmanager.shared.util;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.util.FS;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * One unit's own answer to <em>"is this path content?"</em>, read from the
 * {@code .gitignore} files the unit ships and from what its repository
 * actually tracks.
 *
 * <h2>Why a declaration and not a name list</h2>
 *
 * <p>{@link Rederivable} is a list of names that can only ever mean one thing —
 * {@code __pycache__}, {@code node_modules}. The trees this class exists for
 * are not like that. The test-graph scaffolder writes
 * {@code test_graph/build-logic}, {@code test_graph/sdk} and
 * {@code test_graph/standard-nodes} into a consuming unit as symlinks into the
 * provider's store copy, and in the same pass writes the {@code .gitignore}
 * block that declares all three generated
 * ({@code ensure_provider_binding_ignores},
 * {@code skills/test_graph/scripts/_common.py}). {@code sdk} and
 * {@code standard-nodes} are ordinary words: a unit that genuinely authors a
 * directory called {@code sdk} must keep it, so a global name list is the wrong
 * instrument and adding those names to {@link Rederivable} would be wrong for
 * every unit that is not a test-graph consumer. The unit's own declaration is
 * per-unit by construction, which is the property the name list cannot have.
 *
 * <h2 id="tracked">A TRACKED PATH IS NEVER IGNORED. This is not an optimisation.</h2>
 *
 * <p>Git itself says a {@code .gitignore} rule has no effect on a path already
 * in the index, and here that rule is load-bearing rather than a compatibility
 * detail. Measured across the operator's root store, one unit
 * ({@code spec-double-compiler}) <b>tracks 14 files its own {@code .gitignore}
 * matches</b> — {@code .DS_Store} and twelve generated-then-committed modules
 * under {@code examples/distributed_history/specs/generated/spec_unit/}.
 * Honouring the ignore file without this clause would make committed content
 * invisible to the digest <em>and</em> keep it out of the copy a child home
 * receives: content quietly dropped, which is the failure this exclusion exists
 * to avoid inverted. So the index is consulted, and anything it holds — or
 * holds anything beneath — stays visible.
 *
 * <p>The two readings of "tracked" are not interchangeable, and the difference
 * cost 742 paths when it was got wrong. A path is <em>visible</em> when the
 * index holds it or anything under it, so the walk descends into a directory
 * that merely contains committed files. But a path stops being an
 * <em>ignoring ancestor</em> only when the index holds a blob at exactly that
 * path: a directory git descends through to reach one tracked file is not
 * itself content, and reading it as content un-ignores every untracked sibling
 * beside that file. Both readings are measured in {@link #ignores}'s comments.
 *
 * <p>When the index cannot be read at all, <b>nothing is ignored</b>. Without
 * evidence that a path is untracked there is no evidence it is disposable, and
 * this class fails towards visibility, the same way {@code Disposal} does.
 *
 * <h2>{@code .git} is never ignored either</h2>
 *
 * <p>Independently of any rule. See {@link Rederivable}'s
 * "{@code .git} IS DELIBERATELY NOT HERE" heading (issue #29): a unit whose
 * agent committed work holds it in {@code .git} and nowhere else, so a rule
 * that could reach it is a data-loss defect however it got written.
 *
 * <h2>What is deliberately NOT consulted</h2>
 *
 * <ul>
 *   <li>{@code core.excludesFile} / {@code ~/.gitignore_global} and
 *       {@code .git/info/exclude}. A home's digest must mean the same thing on
 *       every machine that reads it; a record whose contents depend on the
 *       operator's personal global excludes is not comparable with the same
 *       home elsewhere, which is the whole point of recording it.</li>
 *   <li>{@code HomeCloner}. A clone has to hand over a home that WORKS, which
 *       is why it carries {@code node_modules/} and in-unit {@code .venv/} —
 *       both of which a unit routinely gitignores. Applying these rules there
 *       would hand over broken tools with no report saying so. Same asymmetry
 *       {@link Rederivable} already documents between its two sets, for the
 *       same reason.</li>
 * </ul>
 */
public final class GitIgnoreRules {

    /** Rules that ignore nothing — a tree with no unit to ask. */
    public static final GitIgnoreRules NONE = new GitIgnoreRules(null, null);

    /** The one segment git's own state lives under, unreachable by any rule. */
    private static final String GIT_DIR = ".git";

    private final Path root;

    /**
     * Every path the unit's index holds, or {@code null} when the question
     * could not be answered — which disables ignoring entirely.
     */
    private final TreeSet<String> tracked;

    /** {@code .gitignore} per directory, relative to {@link #root}; loaded on demand. */
    private final Map<String, Optional<IgnoreNode>> nodes = new HashMap<>();

    private GitIgnoreRules(Path root, TreeSet<String> tracked) {
        this.root = root;
        this.tracked = tracked;
    }

    /**
     * The rules declared by the unit rooted at {@code unitRoot}.
     *
     * <p><b>NO READABLE INDEX MEANS NOTHING IS IGNORED.</b> One rule for three
     * cases — no {@code .git} at all, a {@code .git} with no {@code index} yet,
     * and an {@code index} that will not parse — because they are one situation:
     * the index is what rescues a path the declaration would hide, and without
     * it there is no rescue to apply. Reading the declaration anyway would hide
     * committed content on the evidence of a file that is not there, which is
     * the opposite of failing towards visibility.
     *
     * <p>Measured before choosing it: the only units in the operator's root
     * store with a {@code .gitignore} and no repository are {@code slm-agent}
     * and {@code tracer-agent}, whose declarations name {@code __pycache__/},
     * {@code *.pyc}, {@code .pytest_cache/} — all of them already
     * {@link Rederivable}'s — and {@code .DS_Store}. The rule costs one
     * {@code .DS_Store} across the whole store, and buys a sentence with no
     * exceptions in it.
     */
    public static GitIgnoreRules forUnit(Path unitRoot) {
        if (unitRoot == null || !Files.isDirectory(unitRoot, LinkOption.NOFOLLOW_LINKS)) {
            return NONE;
        }
        TreeSet<String> tracked = readIndex(gitDirOf(unitRoot));
        if (tracked == null) return NONE;
        return new GitIgnoreRules(unitRoot, tracked);
    }

    /**
     * Whether the unit declares {@code rel} — a unit-relative path, {@code '/'}
     * separated — not to be content.
     *
     * <p>{@code directory} matters: {@code build/} names a directory only, and
     * a symlink is not a directory for this purpose even when it resolves to
     * one, which is exactly the state a scaffolded binding is in before a child
     * home dereferences it.
     */
    public boolean ignores(String rel, boolean directory) {
        if (root == null || rel == null || rel.isEmpty()) return false;
        if (hasGitSegment(rel)) return false;
        if (trackedAtOrUnder(rel)) return false;
        // An ancestor directory that is ignored takes everything with it; git
        // does not let a negation re-include through an ignored parent. Checked
        // explicitly so this predicate means the same thing whether or not the
        // caller happened to walk top-down.
        //
        // AN ANCESTOR THE INDEX HOLDS AT THAT EXACT PATH IS SKIPPED, and the
        // exactness is the whole clause. A test-graph binding a unit COMMITTED
        // is tracked at mode 120000 and matched by the very .gitignore block
        // that declares it generated; the materializer dereferences it into the
        // provider's tree, and every path in that tree then sits under a tracked
        // path. Without this the source walk emitted NOTHING for that subtree
        // while the store's own walk still emitted the tracked LINK -- two
        // sides disagreeing about one path, which is the divergence this class
        // exists to remove wearing a new hat. Measured on the sync-settles
        // fixture, whose own vacuity guard caught it.
        //
        // `contains`, not `trackedAtOrUnder`: a directory git merely DESCENDS
        // THROUGH to reach a tracked file is not itself content, and treating
        // it as content costs everything. Measured on the committed baseline:
        // spec-double-compiler commits twelve files under
        // examples/distributed_history/specs/generated/, and reading that
        // directory as tracked un-ignores 742 of its untracked siblings --
        // 772 excluded paths collapse to 30, and the record stops shrinking at
        // all.
        //
        // So: a path git holds a blob for is content, and so is whatever the
        // walk finds under it. The UNTRACKED generated trees are this class's
        // subject; a TRACKED store link is HIS-4's, on the git surface, already
        // delivered ({@code DereferencedStoreLinks}).
        int cut = rel.indexOf('/');
        while (cut >= 0) {
            String ancestor = rel.substring(0, cut);
            if (!tracked.contains(ancestor) && Boolean.TRUE.equals(decide(ancestor, true))) {
                return true;
            }
            cut = rel.indexOf('/', cut + 1);
        }
        return Boolean.TRUE.equals(decide(rel, directory));
    }

    // ------------------------------------------------------------ internals

    /**
     * The deepest {@code .gitignore} that has an opinion about {@code rel}, or
     * {@code null} when none does. Shallow to deep, last opinion wins — git's
     * own precedence, so a nested {@code .gitignore} can re-include what the
     * unit root excluded.
     */
    private Boolean decide(String rel, boolean directory) {
        Boolean result = null;
        int from = 0;
        while (true) {
            String dir = from == 0 ? "" : rel.substring(0, from - 1);
            IgnoreNode node = nodeAt(dir);
            if (node != null) {
                Boolean one = node.checkIgnored(rel.substring(from), directory);
                if (one != null) result = one;
            }
            int next = rel.indexOf('/', from);
            if (next < 0) return result;
            from = next + 1;
        }
    }

    private IgnoreNode nodeAt(String dirRel) {
        return nodes.computeIfAbsent(dirRel, d -> {
            Path file = (d.isEmpty() ? root : root.resolve(d)).resolve(".gitignore");
            if (!Files.isRegularFile(file)) return Optional.empty();
            IgnoreNode node = new IgnoreNode();
            try (InputStream in = Files.newInputStream(file)) {
                node.parse(file.toString(), in);
            } catch (IOException | RuntimeException unreadable) {
                // A .gitignore we cannot read states nothing, which leaves the
                // paths under it visible. Never the other way round.
                return Optional.empty();
            }
            return Optional.of(node);
        }).orElse(null);
    }

    private boolean trackedAtOrUnder(String rel) {
        if (tracked.isEmpty()) return false;
        if (tracked.contains(rel)) return true;
        String prefix = rel + "/";
        String next = tracked.ceiling(prefix);
        return next != null && next.startsWith(prefix);
    }

    private static boolean hasGitSegment(String rel) {
        for (String segment : rel.split("/")) {
            if (GIT_DIR.equals(segment)) return true;
        }
        return false;
    }

    /**
     * The {@code .git} directory for {@code unitRoot}, following the one-line
     * {@code gitdir:} pointer a worktree or submodule checkout leaves in its
     * place, or {@code null} when there is no repository here.
     */
    private static Path gitDirOf(Path unitRoot) {
        Path dotGit = unitRoot.resolve(GIT_DIR);
        if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) return dotGit;
        if (!Files.isRegularFile(dotGit)) return null;
        try {
            for (String line : Files.readString(dotGit, StandardCharsets.UTF_8).split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("gitdir:")) continue;
                Path target = Path.of(trimmed.substring("gitdir:".length()).trim());
                Path resolved = target.isAbsolute() ? target : unitRoot.resolve(target).normalize();
                return Files.isDirectory(resolved) ? resolved : null;
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return null;
    }

    /**
     * Every path in the index, or {@code null} when it cannot be read — the
     * signal that this unit gets {@link #NONE} rather than a partial answer.
     *
     * <p>Read straight from {@code .git/index} through jgit rather than by
     * shelling out, so it costs no process and cannot touch the index it reads:
     * this runs inside a digest that the whole hold-back rule depends on, and a
     * digest that mutates a repository as a side effect of describing it would
     * be its own defect.
     */
    private static TreeSet<String> readIndex(Path gitDir) {
        if (gitDir == null) return null;
        Path index = gitDir.resolve("index");
        // NOT an empty set. A repository with no index yet tracks nothing YET,
        // and treating that as "nothing is tracked" makes the declaration
        // authoritative on the strength of a missing file: measured, a
        // committed file became IGNORED after `rm .git/index`.
        if (!Files.isRegularFile(index)) return null;
        try {
            DirCache cache = DirCache.read(index.toFile(), FS.DETECTED);
            TreeSet<String> paths = new TreeSet<>();
            for (int i = 0; i < cache.getEntryCount(); i++) {
                DirCacheEntry entry = cache.getEntry(i);
                if (entry != null) paths.add(entry.getPathString());
            }
            return paths;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
