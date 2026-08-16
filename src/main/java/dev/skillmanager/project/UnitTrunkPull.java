package dev.skillmanager.project;

import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.bindings.MaterializationMode;
import dev.skillmanager.effects.EffectContext;
import dev.skillmanager.effects.SyncGitHandler;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls each of a project's units up to its own trunk.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code project sync} used to be a teardown and a re-resolve: it produced
 * the same content it already had, from the same local store, and called that a
 * sync. Nothing in it ever asked the unit's repository whether the unit had
 * moved. So "sync the project" and "get the current version of these skills"
 * were different operations, and only one of them existed.
 *
 * <h2>Where the merge happens is per unit</h2>
 *
 * <p>A {@link MaterializationMode#CHECKOUT} unit has its own clone in the child
 * home and that clone is where the agent's commits are, so that is what gets
 * merged. Everything else lives once, in the parent store, and the child home
 * holds a copy that a later materialization refreshes. Merging into the parent
 * for those is what makes the pull visible to every project that shares the
 * unit; merging into a copy would leave the store stale and the next resolve
 * would either revert the pull or hold the unit back forever.
 *
 * <h2>Never destroy</h2>
 *
 * <p>Two rules, and both are the reason this class does not simply call
 * {@code git pull}:
 *
 * <ul>
 *   <li>A unit with uncommitted local changes is <b>held back</b> unless the
 *       caller passed {@code --merge}. The default for a routine sync is to skip
 *       and report, exactly as {@code sync} does — because "your edit was
 *       three-way merged into something you did not ask for" and "your edit is
 *       gone" are indistinguishable to whoever made the edit.</li>
 *   <li>With {@code --merge}, the merge runs through
 *       {@link SyncGitHandler#runMerge} — stash, fetch, merge, pop — so a
 *       conflict leaves the local work at {@code stash@{0}} and reports rc 8
 *       rather than resolving in upstream's favour.</li>
 * </ul>
 */
public final class UnitTrunkPull {

    /** The trunk this pulls from when nothing more specific is recorded. */
    public static final String DEFAULT_TRUNK = "main";

    private final SkillStore store;
    private final GatewayConfig gateway;

    public UnitTrunkPull(SkillStore store, GatewayConfig gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    /**
     * @param merge fold the pull into a unit that has local changes, rather than
     *        holding it back
     * @param trunk branch to pull; null means {@link #DEFAULT_TRUNK}
     * @param gitLatest pull the ref the unit was installed from instead of the
     *        trunk — the same meaning {@code sync --git-latest} has
     * @param fromRoot a directory holding {@code <unit-name>} checkouts to use as
     *        the upstream instead of {@code origin}, mirroring {@code sync --from}
     */
    public record Options(boolean merge, String trunk, boolean gitLatest, Path fromRoot) {
        public static Options defaults() { return new Options(false, null, false, null); }

        public String trunkOr() {
            return trunk == null || trunk.isBlank() ? DEFAULT_TRUNK : trunk.trim();
        }
    }

    public enum Status {
        /** Upstream content was merged in. */
        MERGED,
        /** Already at (or ahead of) the trunk. */
        UP_TO_DATE,
        /** Local changes present and {@code --merge} was not given. */
        HELD_BACK,
        /** The merge conflicted; local work preserved, nothing resolved for you. */
        CONFLICTED,
        /** Not a git checkout — a file/local install cannot be pulled. */
        NOT_GIT_TRACKED,
        /** Git-tracked but with no upstream to pull from. */
        NO_UPSTREAM,
        /** git fetch/merge failed and was rolled back. */
        FAILED
    }

    public record UnitPull(
            String unitName,
            UnitKind unitKind,
            Status status,
            Path repo,
            String upstream,
            String ref,
            String head,
            List<String> conflictedFiles,
            String detail
    ) {
        public UnitPull {
            conflictedFiles = conflictedFiles == null ? List.of() : List.copyOf(conflictedFiles);
        }

        public String label() { return unitKind.name().toLowerCase() + ":" + unitName; }

        public boolean changed() { return status == Status.MERGED; }

        public boolean problem() {
            return status == Status.CONFLICTED || status == Status.FAILED;
        }
    }

    public record Report(List<UnitPull> pulls) {
        public Report { pulls = pulls == null ? List.of() : List.copyOf(pulls); }

        public List<UnitPull> changed() { return pulls.stream().filter(UnitPull::changed).toList(); }

        public List<UnitPull> heldBack() {
            return pulls.stream().filter(p -> p.status() == Status.HELD_BACK).toList();
        }

        public List<UnitPull> problems() { return pulls.stream().filter(UnitPull::problem).toList(); }
    }

    /**
     * Pull every unit in {@code units}. The child home is consulted so a
     * {@code CHECKOUT} unit is merged where its commits actually are.
     */
    public Report pull(List<SkillProjectLock.ResolvedUnit> units, SkillStore childStore,
                       Options options) throws IOException {
        Options opts = options == null ? Options.defaults() : options;
        List<UnitPull> out = new ArrayList<>();
        if (units == null || units.isEmpty()) return new Report(out);
        if (!GitOps.isAvailable()) {
            for (SkillProjectLock.ResolvedUnit unit : units) {
                out.add(new UnitPull(unit.name(), unit.kind(), Status.NOT_GIT_TRACKED, null,
                        null, opts.trunkOr(), null, List.of(), "git is not on PATH"));
            }
            return new Report(out);
        }
        UnitStore sources = new UnitStore(store);
        ChildHomeMaterializer materializer = childStore == null
                ? null
                : new ChildHomeMaterializer(store, childStore);
        EffectContext ctx = new EffectContext(store, gateway);
        for (SkillProjectLock.ResolvedUnit unit : units) {
            out.add(pullOne(unit, sources, childStore, materializer, ctx, opts));
        }
        return new Report(out);
    }

    private UnitPull pullOne(SkillProjectLock.ResolvedUnit unit, UnitStore sources,
                             SkillStore childStore, ChildHomeMaterializer materializer,
                             EffectContext ctx, Options opts) throws IOException {
        Path repo = pullTarget(unit, childStore, materializer);
        if (!GitOps.isGitRepo(repo)) {
            return new UnitPull(unit.name(), unit.kind(), Status.NOT_GIT_TRACKED, repo, null,
                    opts.trunkOr(), null, List.of(),
                    "not a git checkout — installed from a local path, so there is no trunk to pull");
        }
        InstalledUnit record = sources.read(unit.name()).orElse(null);
        String upstream = upstreamFor(unit, repo, record, opts);
        if (upstream == null || upstream.isBlank()) {
            return new UnitPull(unit.name(), unit.kind(), Status.NO_UPSTREAM, repo, null,
                    opts.trunkOr(), GitOps.headHash(repo), List.of(),
                    "git-tracked but no origin remote is configured");
        }
        String ref = refFor(repo, record, upstream, opts);

        if (GitOps.hasWorktreeChanges(repo) && !opts.merge()) {
            // The refusal, and the whole reason this is not `git pull`. The edit
            // stays exactly as the agent left it.
            Log.warn("%s has uncommitted local changes — not pulled from %s %s "
                            + "(re-run with --merge to fold them together)",
                    unit.name(), upstream, ref);
            return new UnitPull(unit.name(), unit.kind(), Status.HELD_BACK, repo, upstream, ref,
                    GitOps.headHash(repo), List.of(),
                    "local changes in " + repo + "; re-run with --merge to three-way merge them");
        }

        String before = GitOps.headHash(repo);
        SyncGitHandler.BaselineWatch watch =
                SyncGitHandler.BaselineWatch.before(store, unit.name(), unit.kind());
        SyncGitHandler.MergeResult result =
                SyncGitHandler.runMerge(ctx, repo, upstream, ref, unit.name());
        String after = GitOps.headHash(repo);
        if (result.rc() == 0 && before != null && !before.equals(after)) watch.afterUpstreamMove();
        return switch (result.rc()) {
            case 0 -> {
                boolean moved = before != null && !before.equals(after);
                yield new UnitPull(unit.name(), unit.kind(),
                        moved ? Status.MERGED : Status.UP_TO_DATE, repo, upstream, ref, after,
                        List.of(), moved ? "merged " + upstream + " " + ref : "already current");
            }
            case 8 -> {
                Log.warn("%s: merge of %s %s conflicted — local work preserved, nothing resolved "
                        + "automatically", unit.name(), upstream, ref);
                yield new UnitPull(unit.name(), unit.kind(), Status.CONFLICTED, repo, upstream, ref,
                        after, result.conflictedFiles(),
                        "merge conflict; resolve in " + repo + " (local work is in the worktree "
                                + "or at stash@{0})");
            }
            default -> new UnitPull(unit.name(), unit.kind(), Status.FAILED, repo, upstream, ref,
                    after, List.of(), "git fetch/merge failed and was rolled back");
        };
    }

    /**
     * Where this unit's history lives: its own checkout in the child home when it
     * was materialized as one, otherwise the parent store.
     */
    private Path pullTarget(SkillProjectLock.ResolvedUnit unit, SkillStore childStore,
                            ChildHomeMaterializer materializer) {
        if (childStore != null && materializer != null && isCheckout(materializer, unit)) {
            return childStore.unitDir(unit.name(), unit.kind()).toAbsolutePath().normalize();
        }
        return store.unitDir(unit.name(), unit.kind()).toAbsolutePath().normalize();
    }

    private static boolean isCheckout(ChildHomeMaterializer materializer,
                                     SkillProjectLock.ResolvedUnit unit) {
        return materializer.readRecord(unit.name(), unit.kind())
                .map(r -> MaterializationMode.CHECKOUT.name().equals(r.mode()))
                .orElse(false);
    }

    /**
     * {@code --from <dir>} wins when it holds a checkout for this unit, then the
     * install record's origin, then the repository's own {@code origin}.
     */
    private static String upstreamFor(SkillProjectLock.ResolvedUnit unit, Path repo,
                                      InstalledUnit record, Options opts) {
        if (opts.fromRoot() != null) {
            Path local = opts.fromRoot().resolve(unit.name()).toAbsolutePath().normalize();
            if (Files.isDirectory(local) && GitOps.isGitRepo(local)) return local.toString();
        }
        if (record != null && record.origin() != null && !record.origin().isBlank()) {
            return record.origin();
        }
        return GitOps.originUrl(repo);
    }

    /**
     * The trunk, unless {@code --git-latest} asked for the ref the unit was
     * installed from. When the requested trunk does not exist on the remote the
     * remote's own default branch is used, so a repository whose trunk is
     * {@code master} (or anything else) is pulled rather than reported as failed.
     */
    private static String refFor(Path repo, InstalledUnit record, String upstream, Options opts) {
        if (opts.gitLatest()) {
            String tracked = record == null ? null : record.gitRef();
            if (tracked != null && !tracked.isBlank()) return tracked;
        }
        String trunk = opts.trunkOr();
        if (GitOps.remoteBranchHash(repo, upstream, trunk) != null) return trunk;
        String remoteDefault = GitOps.remoteDefaultBranch(repo, upstream);
        return remoteDefault != null && !remoteDefault.isBlank() ? remoteDefault : trunk;
    }
}
