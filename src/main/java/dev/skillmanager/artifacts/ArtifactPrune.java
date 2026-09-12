package dev.skillmanager.artifacts;

import dev.skillmanager.bindings.ChildHomeRegistry;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a home may delete once the thing that asked for it is gone — decided
 * from the LEDGER, never from a directory walk.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>{@code SkillEffect.PruneCliIfOrphan} removes a unit's lock claim and, when
 * nothing else claims the same backend/tool identity, the <b>declared binary</b>
 * — and only that. {@code CliDependencyCleaner.removeArtifacts} has a
 * {@code venvs/} branch for {@code pip} and a {@code cache/cli-<name>} branch
 * for {@code tar}, and <b>no branch at all for {@code skill-script}</b>. So the
 * {@code cache/skill-script-<unit>-<tool>/} tree the install actually wrote, and
 * the virtualenv under it, survive their owner forever. Marketplace rows,
 * gateway registrations and projection residue are outside that effect
 * entirely.
 *
 * <p>ARTI-05 made every one of those a node with edges. Teardown is therefore a
 * graph walk rather than a list of special cases, and this class is that walk.
 *
 * <h2 id="ledger">The rule that does not bend: the ledger decides, not the disk</h2>
 *
 * <p>{@link dev.skillmanager.shared.util.Rederivable} carries a heading in
 * capitals — "{@code .git} IS DELIBERATELY NOT HERE. DO NOT ADD IT." — because
 * a {@code .git} directory looks exactly like a cache and is the least
 * re-derivable thing in a home: a unit an agent committed work in holds those
 * commits there and nowhere else, and issue #29 is what happened when a
 * disposal decision skipped it. <b>A prune that walks paths rather than the
 * ledger is that same defect with a different entry point</b> — a rule about
 * which directories look derived, applied to deletion, over a tree whose most
 * valuable content is the part that looks most derived.
 *
 * <p>So nothing here decides what to delete by looking at a directory. A path
 * is deletable only if it is an {@code outputs} entry of a
 * {@link ArtifactLedger.Row} — a fact some producer recorded about something it
 * made — and a home with no ledger deletes nothing and says so. Three further
 * guards sit on top of that, each of which would be redundant if the first rule
 * held perfectly and none of which costs anything:
 *
 * <ol>
 *   <li>a target that <b>contains a {@code .git} anywhere below it</b> is
 *       refused by name, with #29's reasoning in the refusal — and so is a
 *       target with a subtree this pass could not read, because a tree it
 *       could not look inside is a tree it cannot rule one out of;</li>
 *   <li>a target that does not resolve strictly inside this home is refused,
 *       decided on the path the KERNEL will use rather than on the string —
 *       an intermediate directory symlink is a way out of a home, and the
 *       operator's home has fourteen directory symlinks in it;</li>
 *   <li>{@link ArtifactKind#UNIT_STORE}, {@link ArtifactKind#UNIT_DIGEST} and
 *       every {@link Artifact.Scope#EXTERNAL} output are out of scope, <b>and
 *       so is any output whose PATH is installed unit content</b>
 *       ({@link dev.skillmanager.store.HomeCloner.Surface#CONTENT}). The kind
 *       is what a row DECLARES; the path is what would be deleted, and a row
 *       declaring {@code provisioned-tree} over {@code skills/<unit>} is not a
 *       hypothetical shape. A unit's bytes are removed by {@code uninstall}
 *       and its agent-side projections by {@code unbind}; both are
 *       ledger-driven already, and a second command that also deletes them is
 *       a second thing that can be wrong about somebody else's checkout.</li>
 * </ol>
 *
 * <h2>What counts as an orphan</h2>
 *
 * <p>An artifact is an orphan when it has an {@link Artifact#owner()}, that
 * owner is not an installed unit any more, and nothing that survives is built
 * from it. Each clause is load-bearing:
 *
 * <ul>
 *   <li><b>an owner is required.</b> An artifact nobody claims is NOT an
 *       orphan, it is unattributed — {@code cache/uv-tools}, {@code pm/node},
 *       {@code npm/} are in that state by construction (#122), and they are
 *       shared roots that several installs write into. "Nothing claims it"
 *       reads as "delete it" only if you have already decided the ledger is
 *       complete, which is the assumption this class refuses to make;</li>
 *   <li><b>a surviving consumer keeps it.</b> {@link ArtifactGraph#feeds}
 *       gives the reverse edges ARTI-05 drew, so a tree two units run out of
 *       survives the removal of one of them without anyone special-casing
 *       it;</li>
 *   <li><b>a registered child home keeps it.</b> {@code HomeCloner}'s
 *       {@code parentStoreShims} makes a child home's {@code bin/cli} entry a
 *       symlink at its PARENT's entry, by design, and {@code home verify}
 *       reports those and refuses to count them. Pruning in the parent has to
 *       ask the same question, or removing a unit from the parent breaks a
 *       child home that never mentioned it. See {@link #claimedByChildHome}.
 *       A child home this pass cannot read — its registry record, or the shim
 *       directory the record points at — stops the pass by name, because "no
 *       answer" and "links at nothing" are the same empty set and only one of
 *       them is a licence to delete.</li>
 * </ul>
 */
public final class ArtifactPrune {

    /**
     * One artifact this command has decided about.
     *
     * @param lockRow the {@code cli-lock.toml} identity this artifact came
     *        from, or null. Carried as a pair rather than parsed back out of
     *        {@link #id}: {@code ArtifactBuild}'s javadoc records why a shim's
     *        identity is never recovered by taking a name apart —
     *        {@code bin/cli/tofu} comes from {@code brew:opentofu} — and
     *        although this id WAS minted from the pair, a second decoder is a
     *        second thing that can be wrong about the same string.
     */
    public record Step(String id, ArtifactKind kind, String owner, Verdict verdict,
                       List<String> paths, String reason, LockRow lockRow) {
        public Step {
            paths = paths == null ? List.of() : List.copyOf(paths);
        }

        public Step(String id, ArtifactKind kind, String owner, Verdict verdict,
                    List<String> paths, String reason) {
            this(id, kind, owner, verdict, paths, reason, null);
        }

        public boolean prunes() { return verdict == Verdict.PRUNE; }
    }

    /** The {@code [backend][tool]} key a {@code cli-lock.toml} row is filed under. */
    public record LockRow(String backend, String tool) {}

    /** What this command intends to do about one artifact. */
    public enum Verdict {
        /** Orphaned, in the ledger, and inside this home: it goes. */
        PRUNE,
        /** Something still claims it. */
        CLAIMED,
        /**
         * It might be an orphan and this command will not act on it — no
         * ledger row, a path it cannot vouch for, or a kind whose teardown
         * belongs to another verb. {@link Step#reason} says which.
         */
        REFUSED;

        public String token() { return name().toLowerCase(Locale.ROOT); }
    }

    /** The whole decision. */
    public record Plan(String home, boolean ledgerPresent, List<Step> steps) {
        public Plan {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public List<Step> prunes() {
            List<Step> out = new ArrayList<>();
            for (Step step : steps) if (step.prunes()) out.add(step);
            return out;
        }

        public List<Step> refusals() {
            List<Step> out = new ArrayList<>();
            for (Step step : steps) if (step.verdict() == Verdict.REFUSED) out.add(step);
            return out;
        }

        public boolean isEmpty() { return steps.isEmpty(); }
    }

    private ArtifactPrune() {}

    /**
     * Decide what is orphaned in {@code store}. Nothing is deleted.
     *
     * @param owners when non-empty, consider only artifacts owned by these
     *               units — the reverse walk an {@code uninstall} runs for the
     *               unit it just removed. Empty means the whole home, which is
     *               what {@code artifacts prune} does for the orphans past
     *               removals already left behind.
     */
    public static Plan of(SkillStore store, List<String> owners) throws IOException {
        ArtifactIndex index = ArtifactIndex.of(store);
        ArtifactGraph graph = ArtifactGraph.of(index);
        ArtifactLedger ledger = ArtifactLedger.load(store);
        Set<String> installed = installedUnitNames(store);
        Set<String> declaredLockKeys = declaredLockKeys(store);
        Path home = store.root().toAbsolutePath().normalize();
        ChildClaims childClaims = childHomeClaims(store, home);
        // Both closures are the SAME walk over the same reverse edges, run
        // from two different seeds, because the two answers are different
        // sentences: "something installed is built from it" and "a home this
        // one scaffolded is running out of it". Folding them into one set
        // would make the second refusal print the first's reason.
        Set<String> claimed = closeOverFeeds(index, graph,
                declaredSeeds(index, installed, declaredLockKeys));
        Set<String> childClaimed = closeOverFeeds(index, graph,
                childClaimSeeds(index, childClaims));

        List<Step> steps = new ArrayList<>();
        for (Artifact artifact : index.artifacts()) {
            if (!owners.isEmpty() && !declaredBy(artifact, owners)) continue;
            Step step = decide(artifact, ledger, installed, declaredLockKeys, claimed,
                    childClaimed, childClaims, home);
            if (step != null) steps.add(step);
        }
        return new Plan(home.toString(), !ledger.isEmpty(), steps);
    }

    /**
     * Every artifact something still installed depends on, transitively.
     *
     * <h2>Why the walk, and why it is not "is the owner installed"</h2>
     *
     * <p>{@link Artifact#owner()} is the FIRST requester of a {@code cli-lock}
     * row and nothing more. Two units asking for one tool produce one row with
     * two {@code requested_by} entries, and removing the first of them leaves
     * an artifact whose {@code owner} is gone and whose tool is still declared
     * — which an owner test alone reads as an orphan and deletes out from under
     * the surviving unit. So a claim is any {@code unit:} input naming an
     * installed unit, on the artifact itself or on anything downstream of it.
     *
     * <p>Iterated to a fixpoint over {@link ArtifactGraph#feeds} rather than
     * recursed, so a cycle the graph tolerates cannot become a stack overflow
     * in the one command that deletes things.
     *
     * <p>The same closure is run from a SECOND seed — the artifacts a
     * registered child home links at, {@link #childClaimSeeds} — because a
     * child home's claim propagates exactly the way an installed unit's does:
     * a child links at the parent's {@code bin/cli/<tool>}, that shim is built
     * from {@code venvs/<x>}, and the tree therefore has a live claimant even
     * though nothing in the registry ever names it. Before ARTI-08's review
     * that propagation did not happen and both were deleted.
     *
     * @param seeds the ids known to be claimed before any edge is walked
     */
    private static Set<String> closeOverFeeds(ArtifactIndex index, ArtifactGraph graph,
                                              Set<String> seeds) {
        Set<String> claimed = new LinkedHashSet<>(seeds);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Artifact artifact : index.artifacts()) {
                if (claimed.contains(artifact.id())) continue;
                for (String consumer : graph.feeds(artifact.id())) {
                    if (claimed.contains(consumer)) {
                        claimed.add(artifact.id());
                        grew = true;
                        break;
                    }
                }
            }
        }
        return claimed;
    }

    /** The artifacts an installed unit declares directly. */
    private static Set<String> declaredSeeds(ArtifactIndex index, Set<String> installed,
                                             Set<String> declaredLockKeys) {
        Set<String> seeds = new LinkedHashSet<>();
        if (declaredLockKeys == null) return seeds;
        for (Artifact artifact : index.artifacts()) {
            if (declaredByInstalled(artifact, installed, declaredLockKeys)) seeds.add(artifact.id());
        }
        return seeds;
    }

    /** The artifacts whose own outputs a registered child home links at. */
    private static Set<String> childClaimSeeds(ArtifactIndex index, ChildClaims claims) {
        Set<String> seeds = new LinkedHashSet<>();
        if (claims.paths().isEmpty()) return seeds;
        for (Artifact artifact : index.artifacts()) {
            if (claims.claimOn(artifact) != null) seeds.add(artifact.id());
        }
        return seeds;
    }

    /**
     * Whether an installed unit still claims this artifact.
     *
     * <h2>A shim is judged by the DECLARATION, not by {@code requested_by}</h2>
     *
     * <p>{@code requested_by} records who asked for a row once, and is
     * re-derived in exactly one place: {@code CliDependencyCleaner.pruneIfOrphan}
     * overwrites it with a freshly computed {@code survivorClaimers} set when a
     * unit is uninstalled with pruning. Nothing else refreshes it — in
     * particular, a unit that merely EDITS its manifest to stop declaring a dep
     * leaves the row naming it forever, and that is the case below. Measured on
     * the operator's
     * project home: {@code cli-lock.toml} carries {@code npm:gemini-cli},
     * {@code npm:google} and {@code npm:google-gemini-cli} beside the real
     * {@code npm:@google/gemini-cli}, all four with live requesters
     * ({@code acp-cdc-ai-python}, {@code hyper-experiments}) — and the two
     * units' manifests declare only the fourth. Those three ARE the "rows
     * declared by no installed unit" this ticket names, and a requester test
     * cannot see any of them.
     *
     * <p>The false-positive direction is bounded by construction rather than by
     * care: if a dep's name and its lock key ever disagree, the same key
     * mismatch already makes {@code ArtifactBackfill} report the shim as
     * {@code bin/cli/<unknown>}, which has no output on disk, which this class
     * never deletes.
     */
    private static boolean declaredByInstalled(Artifact artifact, Set<String> installed,
                                               Set<String> declaredLockKeys) {
        if (artifact.kind() == ArtifactKind.CLI_SHIM) {
            LockRow row = lockRowOf(artifact);
            return row != null && declaredLockKeys.contains(key(row.backend(), row.tool()));
        }
        if (artifact.owner() != null && installed.contains(artifact.owner())) return true;
        for (String unit : declaringUnits(artifact)) {
            if (installed.contains(unit)) return true;
        }
        return false;
    }

    private static String key(String backend, String tool) { return backend + "\0" + tool; }

    /** The {@code [backend][tool]} keys the INSTALLED units' manifests declare today. */
    private static Set<String> declaredLockKeys(SkillStore store) {
        Set<String> keys = new LinkedHashSet<>();
        try {
            for (AgentUnit unit : store.listInstalledUnits().units()) {
                for (dev.skillmanager.model.CliDependency dep : unit.cliDependencies()) {
                    if (dep.name() == null) continue;
                    keys.add(key(dep.backend(), dep.name()));
                }
            }
        } catch (IOException e) {
            Log.warn("artifacts prune: could not read the installed units' CLI declarations (%s)"
                    + " — no shim will be treated as orphaned", e.getMessage());
            return null;
        }
        return keys;
    }

    /** Whether any unit this artifact names is one of {@code owners}. */
    private static boolean declaredBy(Artifact artifact, List<String> owners) {
        if (artifact.owner() != null && owners.contains(artifact.owner())) return true;
        for (String unit : declaringUnits(artifact)) {
            if (owners.contains(unit)) return true;
        }
        return false;
    }

    /** The units named by this artifact's declared {@code unit:} inputs. */
    private static Set<String> declaringUnits(Artifact artifact) {
        Set<String> units = new LinkedHashSet<>();
        for (String input : artifact.inputs()) {
            if (input != null && input.startsWith(UNIT_INPUT)) {
                units.add(input.substring(UNIT_INPUT.length()));
            }
        }
        return units;
    }

    private static final String UNIT_INPUT = "unit:";

    private static Step decide(Artifact artifact, ArtifactLedger ledger,
                               Set<String> installed, Set<String> declaredLockKeys,
                               Set<String> claimed, Set<String> childClaimed,
                               ChildClaims childClaims, Path home) {
        // Kinds whose teardown belongs to another verb, filtered before
        // anything else so they never even appear as candidates.
        if (artifact.kind() == ArtifactKind.UNIT_STORE
                || artifact.kind() == ArtifactKind.UNIT_DIGEST) {
            return null;
        }
        String owner = artifact.owner();
        if (owner == null) {
            // Not an orphan — unattributed. The shared package-manager roots
            // are permanently in this state and several installs write into
            // them; see the class javadoc.
            return null;
        }
        // A record this pass could not read is not a licence to delete — and
        // it is REFUSED rather than skipped, because a silently empty plan and
        // a plan that found nothing print the same thing.
        if (declaredLockKeys == null) {
            return new Step(artifact.id(), artifact.kind(), owner, Verdict.REFUSED, List.of(),
                    "this pass could not read the installed units' CLI declarations, so it "
                            + "cannot tell an orphan from a live dep — nothing is removed while "
                            + "that is true");
        }
        if (!childClaims.unreadable().isEmpty()) {
            return new Step(artifact.id(), artifact.kind(), owner, Verdict.REFUSED, List.of(),
                    "a registered child home could not be read in full ("
                            + childClaims.unreadable().get(0) + "), and a child home's bin/cli "
                            + "entry is a symlink AT THIS HOME's copy by design "
                            + "(parentStoreShims) — a child home this pass cannot read is not "
                            + "the same answer as one that links at nothing");
        }
        if (declaredByInstalled(artifact, installed, declaredLockKeys)) return null;
        if (childClaimed.contains(artifact.id())) {
            String at = childClaims.claimOn(artifact);
            return new Step(artifact.id(), artifact.kind(), owner, Verdict.CLAIMED,
                    at == null ? List.of() : List.of(at),
                    "a registered child home links at "
                            + (at == null ? "something built from it" : at)
                            + " — the parent's copy is shared by design (parentStoreShims)");
        }
        if (claimed.contains(artifact.id())) {
            return new Step(artifact.id(), artifact.kind(), owner, Verdict.CLAIMED, List.of(),
                    "something still installed is built from it");
        }

        ArtifactLedger.Row row = ledger.byId(artifact.id()).orElse(null);
        if (row == null || row.outputs().isEmpty()) {
            return new Step(artifact.id(), artifact.kind(), owner, Verdict.REFUSED, List.of(),
                    "no ledger row names an output for it, and this command deletes only what "
                            + "the ledger recorded — run `skill-manager artifacts record` while "
                            + "the producer is still installed");
        }

        List<String> targets = new ArrayList<>();
        for (String relative : row.outputs()) {
            // Asked of the LEDGER's outputs too, and not only of the index's:
            // the ledger is what `apply` deletes from, and a row naming a path
            // the index did not attribute to this artifact is exactly the case
            // the index cannot rule out.
            String at = childClaims.claimFor(relative);
            if (at != null) {
                return new Step(artifact.id(), artifact.kind(), owner, Verdict.CLAIMED,
                        List.of(relative),
                        "a registered child home links at " + at
                                + " — the parent's copy is shared by design (parentStoreShims)");
            }
            Path target = home.resolve(relative).normalize();
            String refusal = whyUndeletable(target, home, relative);
            if (refusal != null) {
                return new Step(artifact.id(), artifact.kind(), owner, Verdict.REFUSED,
                        List.of(relative), refusal);
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) targets.add(relative);
        }
        if (targets.isEmpty()) {
            // A lock row whose install produced nothing this home still has is
            // STILL an artifact — the row itself. Three of them sit in the
            // operator's project home, and leaving them is the ledger and the
            // disk disagreeing, which is the thing this epic exists to end.
            LockRow lockOnly = lockRowOf(artifact);
            if (lockOnly != null) {
                return new Step(artifact.id(), artifact.kind(), owner, Verdict.PRUNE, List.of(),
                        "no installed unit declares " + lockOnly.backend() + ":" + lockOnly.tool()
                                + " any more, and its install left nothing on disk — only the "
                                + "cli-lock.toml row is left to remove", lockOnly);
            }
            return new Step(artifact.id(), artifact.kind(), owner, Verdict.CLAIMED, List.of(),
                    "its owner is gone and nothing of it is left on disk");
        }
        return new Step(artifact.id(), artifact.kind(), owner, Verdict.PRUNE, targets,
                "declared by " + owner + ", which is not installed here any more",
                lockRowOf(artifact));
    }

    /** The lock key this shim's row is filed under, or null. */
    private static LockRow lockRowOf(Artifact artifact) {
        if (artifact.kind() != ArtifactKind.CLI_SHIM) return null;
        String backend = artifact.recorded().get("backend");
        String tool = artifact.recorded().get("tool");
        return backend == null || tool == null ? null : new LockRow(backend, tool);
    }

    /**
     * Why {@code target} may not be deleted, or null when it may.
     *
     * <p>The {@code .git} clause is the one that matters and it is checked by
     * WALKING for one rather than by testing the target's own name: the
     * dangerous shape is not {@code cache/x/.git}, it is a recorded output that
     * happens to CONTAIN a checkout somebody committed in. Issue #29 is what
     * that costs, and {@link dev.skillmanager.shared.util.Rederivable}'s
     * heading is a standing instruction not to let a disposal decision skip it.
     *
     * <h2>The containment test is asked of the path the KERNEL will use</h2>
     *
     * <p>It used to be asked of the lexically normalized path, while
     * {@link Fs#deleteRecursive} operated on whatever the kernel resolved. An
     * output whose intermediate directory is a symlink out of the home —
     * {@code cache/escape/subdir} where {@code cache/escape} points elsewhere —
     * passed a test about the string and then deleted a tree outside the home.
     * Measured on this branch's review: the outside tree was removed. The
     * operator's home carries fourteen directory symlinks today ({@code
     * pm/*&#47;current}, the {@code test_graph/sdk} cross-unit links), all
     * home-internal, so the blast radius was another unit's source tree — but
     * nothing bounds it to that.
     *
     * <p>Only the PARENT chain is resolved, never the leaf: {@code
     * deleteRecursive} deletes a leaf symlink without following it, so a shim
     * pointing at {@code /opt/homebrew/bin/tofu} is still removable, and
     * resolving the leaf would refuse the ordinary case to fix the dangerous
     * one.
     */
    static String whyUndeletable(Path target, Path home, String relative) {
        Path resolved = withResolvedParents(target);
        Path root = Fs.realOrNormalized(home);
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            return "it resolves to " + resolved + ", which is not inside this home";
        }
        if (relative == null || relative.isBlank() || relative.contains("..")) {
            return "\"" + relative + "\" is not a path this home can vouch for";
        }
        // A PATH-level scope rule, beside the kind-level one in `decide`. The
        // kind is what a ROW DECLARES and a row can declare anything: a
        // `provisioned-tree` row whose outputs name `skills/<unit>` deleted a
        // unit store whenever that store had no `.git` — which is every unit
        // installed from a `file:` coordinate or a tarball, and the `.git` walk
        // was the only thing standing in front of them.
        if (dev.skillmanager.store.HomeCloner.classify(relative)
                == dev.skillmanager.store.HomeCloner.Surface.CONTENT) {
            return "\"" + relative + "\" is installed unit content, which `uninstall` removes and "
                    + "this command does not — whatever kind the row that named it declares";
        }
        GitScan scan = scanForGit(target);
        if (scan != null && scan.unreadable()) {
            return "it contains " + describe(home, scan.at()) + ", which this pass could not"
                    + " read. An unreadable subtree is treated as containing a `.git`: those"
                    + " commits exist nowhere else (issue #29), and a tree this command could"
                    + " not look inside is a tree it cannot say that about";
        }
        if (scan != null) {
            return "it contains " + describe(home, scan.at()) + ". A `.git` holds commits that"
                    + " exist nowhere else (issue #29), so no disposal decision in this program"
                    + " may step over one — inspect it and remove it by hand if it really is"
                    + " spent";
        }
        return null;
    }

    /** {@code p} with its parent directories resolved and its own name left alone. */
    private static Path withResolvedParents(Path p) {
        Path parent = p.getParent();
        if (parent == null) return p.toAbsolutePath().normalize();
        Path real = Fs.realOrNormalized(parent);
        Path name = p.getFileName();
        return name == null ? real : real.resolve(name);
    }

    /** {@code path} relative to the home when it is under it, absolute when it is not. */
    private static String describe(Path home, Path path) {
        try {
            return home.relativize(path).toString();
        } catch (IllegalArgumentException notUnderHome) {
            return path.toString();
        }
    }

    /**
     * Where a scan for a {@code .git} stopped.
     *
     * @param at the {@code .git} found, or the entry that could not be read
     * @param unreadable whether it stopped because it could not read, rather
     *        than because it found one
     */
    private record GitScan(Path at, boolean unreadable) {}

    /** The first {@code .git} at or below {@code target}, or null. */
    private static GitScan scanForGit(Path target) {
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            Path git = target.resolve(".git");
            return Files.exists(git, LinkOption.NOFOLLOW_LINKS) ? new GitScan(git, false) : null;
        }
        final Path[] found = {null};
        final boolean[] unreadable = {false};
        try {
            Files.walkFileTree(target, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(
                        Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (".git".equals(dir.getFileName().toString())) {
                        found[0] = dir;
                        return java.nio.file.FileVisitResult.TERMINATE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(
                        Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    // A `.git` FILE is a worktree pointer at a real repository
                    // elsewhere, which is the shape a worktree home carries and
                    // the shape #29 was measured on.
                    if (".git".equals(file.getFileName().toString())) {
                        found[0] = file;
                        return java.nio.file.FileVisitResult.TERMINATE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException e) {
                    // Unreadable means unvouchable, and this is the visitor
                    // that decides it. Returning CONTINUE here — which it did
                    // — swallowed the per-entry AccessDeniedException that
                    // `walkFileTree` reports for a directory it cannot open,
                    // so the catch below was unreachable for the failure it
                    // was written for and a mode-000 subdirectory holding a
                    // real `.git` scanned as deletable. Measured: a target
                    // holding one was planned for PRUNE.
                    found[0] = file;
                    unreadable[0] = true;
                    return java.nio.file.FileVisitResult.TERMINATE;
                }
            });
        } catch (IOException e) {
            // The walk itself failed — the target's own directory stream, or a
            // `postVisitDirectory` exception rethrown by SimpleFileVisitor.
            return new GitScan(target, true);
        }
        return found[0] == null ? null : new GitScan(found[0], unreadable[0]);
    }

    /**
     * Carry out {@code plan}, and re-record the ledger so it stops declaring
     * what is gone.
     *
     * <h2>One step's failure does not abort the pass, and never skips the
     * re-record</h2>
     *
     * <p>{@link Fs#deleteRecursive} threw an {@code AccessDeniedException}
     * straight out through {@code ArtifactsCommand} on this branch's review:
     * every step after it was skipped, none of their lock rows was dropped,
     * and — the part that matters — the ledger re-save below never ran. The
     * home was left PARTIALLY PRUNED with a ledger still naming what was gone,
     * which is the exact disagreement between the ledger and the disk this
     * epic exists to end, produced by the command that exists to end it.
     *
     * <p>So each step is wrapped, a failure is reported and the pass
     * continues, a step whose deletion failed is NOT reported as pruned and
     * keeps its lock row, and the ledger is re-recorded whenever anything was
     * attempted — from what is actually on disk, so a partial deletion is
     * described accurately rather than optimistically.
     *
     * @return the artifact ids actually pruned
     */
    public static List<String> apply(SkillStore store, Plan plan) throws IOException {
        List<String> pruned = new ArrayList<>();
        Path home = store.root().toAbsolutePath().normalize();
        boolean attempted = false;
        for (Step step : plan.prunes()) {
            boolean any = false;
            boolean failed = false;
            for (String relative : step.paths()) {
                Path target = home.resolve(relative).normalize();
                // Re-asked at the moment of deletion, not merely at plan time.
                // A plan is a statement about a past moment; a delete is not.
                String refusal = whyUndeletable(target, home, relative);
                if (refusal != null) {
                    Log.warn("artifacts prune: not removing %s — %s", relative, refusal);
                    continue;
                }
                // Set BEFORE the call: a delete that throws part-way through a
                // tree has still changed the disk, and that is precisely the
                // state the ledger has to be re-recorded from.
                attempted = true;
                try {
                    Fs.deleteRecursive(target);
                    any = true;
                } catch (IOException | RuntimeException e) {
                    failed = true;
                    Log.warn("artifacts prune: could not remove %s (%s) — the rest of the plan "
                            + "still runs and the ledger is re-recorded from what is on disk",
                            relative, e.toString());
                }
            }
            // A step whose removal failed is not a step that happened: it keeps
            // its cli-lock.toml row, because the row describes an install whose
            // files are still here.
            if (failed) continue;
            // A row-only prune has no path and is still work: `any` is about
            // files, and the lock row is the artifact when there are none.
            if (!any && !step.paths().isEmpty()) continue;
            pruned.add(step.id());
            // The lock ROW is an artifact of the same install and outlives it
            // the same way. `PruneCliIfOrphan` drops it when a removal knows
            // which dep to ask about; the rows already orphaned by past
            // removals have no such caller, which is what `artifacts prune`
            // is for — the live homes carry three of them.
            dropLockRow(store, step);
        }
        if (attempted || !pruned.isEmpty()) {
            try {
                ArtifactLedger.of(ArtifactIndex.of(store).artifacts()).save(store);
            } catch (IOException | RuntimeException e) {
                Log.warn("artifacts prune: removed %d artifact(s) but could not re-record %s (%s)"
                        + " — the ledger now names things this home no longer has; re-run"
                        + " `skill-manager artifacts record`",
                        pruned.size(), ArtifactLedger.FILENAME, e.toString());
            }
        }
        return pruned;
    }

    private static void dropLockRow(SkillStore store, Step step) {
        LockRow row = step.lockRow();
        if (row == null) return;
        try {
            CliLock lock = CliLock.load(store);
            if (lock.get(row.backend(), row.tool()) == null) return;
            lock.remove(row.backend(), row.tool());
            lock.save(store);
        } catch (IOException e) {
            Log.warn("artifacts prune: removed %s but could not drop its cli-lock.toml row (%s)",
                    step.id(), e.getMessage());
        }
    }

    // ------------------------------------------------------------- claimants

    private static Set<String> installedUnitNames(SkillStore store) {
        Set<String> names = new LinkedHashSet<>();
        try {
            for (AgentUnit unit : store.listInstalledUnits().units()) names.add(unit.name());
        } catch (IOException e) {
            // Reading the installed set is how "orphan" is decided. If it
            // cannot be read, nothing is an orphan.
            Log.warn("artifacts prune: could not read the installed units (%s) — nothing will "
                    + "be treated as orphaned", e.getMessage());
            return Set.of();
        }
        return names;
    }

    /**
     * What registered child homes link at inside THIS home, and what this pass
     * could not read.
     *
     * <p>The second field is not decoration. An unreadable registry record and
     * a registry with no records produce the same empty claim set, and the
     * first must not read as "nothing claims this" — see {@code decide}, which
     * refuses everything while it is non-empty.
     *
     * @param paths home-relative paths a child home reaches, every hop
     * @param unreadable what stood between this pass and a full claim set: a
     *                   registry file that would not decode, or a child home's
     *                   shim directory it could not stat
     */
    private record ChildClaims(Set<String> paths, List<String> unreadable) {

        /**
         * The claim covering {@code output}, or null.
         *
         * <h2>Why containment, and why in BOTH directions</h2>
         *
         * <p>The claim is a path a child home reaches; the output is the ROOT
         * of what some artifact produced. Those are different strings in the
         * shape that matters — a claim on {@code venvs/jinja2-cli/bin/jinja2}
         * against the tree artifact's output {@code venvs/jinja2-cli} — and an
         * exact-string test is how both the shim and the tree feeding it came
         * to be planned for deletion while a child home was running out of
         * them.
         *
         * <p>Downward too: a child that links at {@code venvs/x} is broken by
         * deleting {@code venvs/x/bin/tool} just as surely. Deleting either an
         * ancestor or a descendant of something a child home reaches is a
         * hazard, so both are claims.
         *
         * <p>The {@code + "/"} is the same segment-boundary rule
         * {@link ArtifactGraph} states: {@code venvs/jinja2-cli-old} is not
         * inside {@code venvs/jinja2-cli}.
         */
        String claimFor(String output) {
            String path = normalizeRelative(output);
            if (path == null) return null;
            for (String claim : paths) {
                if (claim.equals(path)
                        || claim.startsWith(path + "/")
                        || path.startsWith(claim + "/")) {
                    return claim;
                }
            }
            return null;
        }

        /** The claim covering any of {@code artifact}'s in-home outputs, or null. */
        String claimOn(Artifact artifact) {
            for (Artifact.Output output : artifact.outputs()) {
                if (output.scope() != Artifact.Scope.HOME) continue;
                String claim = claimFor(output.path());
                if (claim != null) return claim;
            }
            return null;
        }
    }

    private static String normalizeRelative(String path) {
        if (path == null || path.isBlank()) return null;
        String out = path.replace('\\', '/');
        while (out.startsWith("./")) out = out.substring(2);
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out.isEmpty() ? null : out;
    }

    /**
     * Home-relative entries in THIS home that a registered child home links at.
     *
     * <p>{@code ChildHomeRegistry} records every child home this home
     * scaffolded, and {@code ChildHomeMaterializer.mirrorExistingShim} makes
     * the child's {@code bin/cli} entry a symlink at the parent's — never a
     * copy — precisely so the two cannot diverge. That makes the parent's copy
     * load-bearing for a home whose name appears nowhere in the parent's
     * installed set, which is exactly the shape an orphan test gets wrong.
     */
    private static ChildClaims childHomeClaims(SkillStore store, Path home) {
        Set<String> claimed = new LinkedHashSet<>();
        List<String> unreadable = new ArrayList<>();
        ChildHomeRegistry.Listing listing = new ChildHomeRegistry(store).listing();
        for (String file : listing.unreadable()) {
            Log.warn("artifacts prune: could not read the child-home record %s — this pass "
                    + "cannot tell what that home links at, so it removes nothing", file);
            unreadable.add(file);
        }
        for (ChildHomeRegistry.ChildHomeRecord record : listing.records()) {
            Path childHome = record.childHome() == null ? null : Path.of(record.childHome());
            if (childHome == null) continue;
            for (String dir : List.of("bin/cli", "bin/mcp")) {
                Path shimDir = childHome.resolve(dir);
                // Asked as two questions rather than one, because
                // `Files.isDirectory` gives the same false to both and they are
                // opposite instructions. "Not there" means that home links at
                // nothing here. "Could not be stat'd" — a mode that bites, a
                // dead mount, a detached volume — means this pass cannot tell
                // WHAT it links at, which is the sentence the unreadable
                // registry records above already earn. Reading the second as
                // the first is how a live child home's tree gets planned for
                // deletion while that home is running out of it.
                boolean isDir;
                try {
                    isDir = Files.readAttributes(shimDir, BasicFileAttributes.class).isDirectory();
                } catch (NoSuchFileException absent) {
                    continue;
                } catch (IOException unstattable) {
                    Log.warn("artifacts prune: could not stat %s (%s) — this pass cannot tell "
                            + "what that child home links at, so it removes nothing. Make the "
                            + "path readable, or unregister that child home, and run again",
                            shimDir, unstattable.getClass().getSimpleName());
                    unreadable.add(shimDir.toString());
                    continue;
                }
                if (!isDir) continue;
                try (var entries = Files.list(shimDir)) {
                    for (Path entry : (Iterable<Path>) entries::iterator) {
                        claimed.addAll(claimedByChildHome(entry, home));
                    }
                } catch (IOException e) {
                    Log.warn("artifacts prune: could not read %s (%s) — treating this home's "
                            + "shims as claimed by it", shimDir, e.getMessage());
                    claimed.add(dir);
                }
            }
        }
        return new ChildClaims(claimed, List.copyOf(unreadable));
    }

    /** How far a link chain is followed before it is treated as a loop. */
    private static final int MAX_LINK_HOPS = 32;

    /**
     * Every home-relative path in {@code home} that {@code entry}'s link chain
     * passes THROUGH, not merely the one it ends at.
     *
     * <h2>The endpoint alone is the wrong answer for the shape that matters</h2>
     *
     * <p>This used to be one {@code entry.toRealPath()}, which resolves the
     * WHOLE chain and returns only where it stops. That is correct exactly
     * when the parent's {@code bin/cli/<tool>} is a regular file — the
     * skill-script wrapper shape, where the resolution terminates one hop
     * early — and wrong for every {@code pip}, {@code brew} and {@code npm}
     * install, where the parent's own entry is itself a symlink:
     *
     * <pre>
     *   child/bin/cli/jinja2 → parent/bin/cli/jinja2 → parent/venvs/jinja2-cli/bin/jinja2
     * </pre>
     *
     * <p>The endpoint {@code venvs/jinja2-cli/bin/jinja2} matches neither the
     * shim artifact's output ({@code bin/cli/jinja2}) nor the tree artifact's
     * ({@code venvs/jinja2-cli}), so an exact-string claim test found neither
     * and BOTH were pruned: the parent's tree deleted, the child home's entry
     * left dangling. Measured against a clone of the operator's project home
     * with a registered child home.
     *
     * <p>So every hop is claimed, and {@link ChildClaims#claimFor} matches by
     * containment rather than equality. Two mechanisms rather than one, on
     * purpose: the hop list catches the parent's own entry, the containment
     * rule catches the tree that entry points into, and the closure in
     * {@link #closeOverFeeds} catches anything further upstream that the
     * edges already know about.
     *
     * <p>Parent directories are resolved at each hop and the leaf is not, so a
     * temp home at {@code /var/folders/…} whose real path is
     * {@code /private/var/folders/…} still compares equal — the reason the
     * original went through {@code toRealPath} at all.
     */
    static Set<String> claimedByChildHome(Path entry, Path home) {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isSymbolicLink(entry)) return out;
        Path root = Fs.realOrNormalized(home);
        Set<Path> seen = new LinkedHashSet<>();
        Path current = entry;
        for (int hop = 0; hop < MAX_LINK_HOPS && current != null && seen.add(current); hop++) {
            Path here = withResolvedParents(current);
            if (here.startsWith(root) && !here.equals(root)) {
                out.add(root.relativize(here).toString().replace('\\', '/'));
            }
            if (!Files.isSymbolicLink(current)) break;
            Path link;
            try {
                link = Files.readSymbolicLink(current);
            } catch (IOException unreadable) {
                break;
            }
            Path parent = current.getParent();
            current = (link.isAbsolute() || parent == null ? link : parent.resolve(link))
                    .normalize();
        }
        return out;
    }
}
