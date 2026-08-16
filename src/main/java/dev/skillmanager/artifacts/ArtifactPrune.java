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
import java.nio.file.Path;
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
 *       refused by name, with #29's reasoning in the refusal;</li>
 *   <li>a target that does not resolve strictly inside this home is refused;</li>
 *   <li>{@link ArtifactKind#UNIT_STORE}, {@link ArtifactKind#UNIT_DIGEST} and
 *       every {@link Artifact.Scope#EXTERNAL} output are out of scope. A unit's
 *       bytes are removed by {@code uninstall} and its agent-side projections by
 *       {@code unbind}; both are ledger-driven already, and a second command
 *       that also deletes them is a second thing that can be wrong about
 *       somebody else's checkout.</li>
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
 *       child home that never mentioned it. See {@link #claimedByChildHome}.</li>
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
        Set<String> childClaims = childHomeClaims(store);
        Path home = store.root().toAbsolutePath().normalize();
        Set<String> claimed = claimedIds(index, graph, installed, declaredLockKeys);

        List<Step> steps = new ArrayList<>();
        for (Artifact artifact : index.artifacts()) {
            if (!owners.isEmpty() && !declaredBy(artifact, owners)) continue;
            Step step = decide(artifact, ledger, installed, declaredLockKeys, claimed,
                    childClaims, home);
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
     */
    private static Set<String> claimedIds(ArtifactIndex index, ArtifactGraph graph,
                                          Set<String> installed, Set<String> declaredLockKeys) {
        Set<String> claimed = new LinkedHashSet<>();
        if (declaredLockKeys == null) return claimed;
        for (Artifact artifact : index.artifacts()) {
            if (declaredByInstalled(artifact, installed, declaredLockKeys)) claimed.add(artifact.id());
        }
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

    /**
     * Whether an installed unit still claims this artifact.
     *
     * <h2>A shim is judged by the DECLARATION, not by {@code requested_by}</h2>
     *
     * <p>{@code requested_by} records who asked for a row once; it is not
     * re-derived, so it goes on naming an installed unit after that unit's
     * manifest has stopped declaring the dep. Measured on the operator's
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
                               Set<String> claimed, Set<String> childClaims, Path home) {
        // A registry this pass could not read is not a licence to delete.
        if (declaredLockKeys == null) return null;
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
        if (declaredByInstalled(artifact, installed, declaredLockKeys)) return null;
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
            if (childClaims.contains(relative)) {
                return new Step(artifact.id(), artifact.kind(), owner, Verdict.CLAIMED,
                        List.of(relative),
                        "a registered child home links at " + relative
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
     */
    static String whyUndeletable(Path target, Path home, String relative) {
        if (!target.startsWith(home) || target.equals(home)) {
            return "it resolves to " + target + ", which is not inside this home";
        }
        if (relative.isBlank() || relative.contains("..")) {
            return "\"" + relative + "\" is not a path this home can vouch for";
        }
        Path git = findGitDir(target);
        if (git != null) {
            return "it contains " + home.relativize(git) + ". A `.git` holds commits that exist"
                    + " nowhere else (issue #29), so no disposal decision in this program may"
                    + " step over one — inspect it and remove it by hand if it really is spent";
        }
        return null;
    }

    /** The first {@code .git} at or below {@code target}, or null. */
    private static Path findGitDir(Path target) {
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            return Files.exists(target.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                    ? target.resolve(".git") : null;
        }
        final Path[] found = {null};
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
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Unreadable means unvouchable: report a `.git` we cannot rule out
            // rather than deleting a tree we could not read.
            return target;
        }
        return found[0];
    }

    /**
     * Carry out {@code plan}, and re-record the ledger so it stops declaring
     * what is gone.
     *
     * @return the artifact ids actually pruned
     */
    public static List<String> apply(SkillStore store, Plan plan) throws IOException {
        List<String> pruned = new ArrayList<>();
        Path home = store.root().toAbsolutePath().normalize();
        for (Step step : plan.prunes()) {
            boolean any = false;
            for (String relative : step.paths()) {
                Path target = home.resolve(relative).normalize();
                // Re-asked at the moment of deletion, not merely at plan time.
                // A plan is a statement about a past moment; a delete is not.
                String refusal = whyUndeletable(target, home, relative);
                if (refusal != null) {
                    Log.warn("artifacts prune: not removing %s — %s", relative, refusal);
                    continue;
                }
                Fs.deleteRecursive(target);
                any = true;
            }
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
        if (!pruned.isEmpty()) {
            ArtifactLedger.of(ArtifactIndex.of(store).artifacts()).save(store);
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
     * Home-relative entries in THIS home that a registered child home links at.
     *
     * <p>{@code ChildHomeRegistry} records every child home this home
     * scaffolded, and {@code ChildHomeMaterializer.mirrorExistingShim} makes
     * the child's {@code bin/cli} entry a symlink at the parent's — never a
     * copy — precisely so the two cannot diverge. That makes the parent's copy
     * load-bearing for a home whose name appears nowhere in the parent's
     * installed set, which is exactly the shape an orphan test gets wrong.
     */
    private static Set<String> childHomeClaims(SkillStore store) {
        Set<String> claimed = new LinkedHashSet<>();
        Path home = store.root().toAbsolutePath().normalize();
        for (ChildHomeRegistry.ChildHomeRecord record : new ChildHomeRegistry(store).list()) {
            Path childHome = record.childHome() == null ? null : Path.of(record.childHome());
            if (childHome == null) continue;
            for (String dir : List.of("bin/cli", "bin/mcp")) {
                Path shimDir = childHome.resolve(dir);
                if (!Files.isDirectory(shimDir)) continue;
                try (var entries = Files.list(shimDir)) {
                    for (Path entry : (Iterable<Path>) entries::iterator) {
                        String relative = claimedByChildHome(entry, home);
                        if (relative != null) claimed.add(relative);
                    }
                } catch (IOException e) {
                    Log.warn("artifacts prune: could not read %s (%s) — treating this home's "
                            + "shims as claimed by it", shimDir, e.getMessage());
                    claimed.add(dir);
                }
            }
        }
        return claimed;
    }

    /**
     * The home-relative path in {@code home} that {@code entry} links at, or
     * null when it links somewhere else.
     */
    static String claimedByChildHome(Path entry, Path home) {
        try {
            if (!Files.isSymbolicLink(entry)) return null;
            // BOTH sides through toRealPath. A temp home is `/var/folders/…`
            // and its real path is `/private/var/folders/…` on this platform;
            // comparing a resolved link against an unresolved root answers
            // "not mine" for a link that is very much mine, and the cost of
            // that answer is deleting a file a child home is running out of.
            Path resolved = entry.toRealPath();
            Path root = home.toRealPath();
            if (!resolved.startsWith(root)) return null;
            return root.relativize(resolved).toString().replace('\\', '/');
        } catch (IOException e) {
            return null;
        }
    }
}
