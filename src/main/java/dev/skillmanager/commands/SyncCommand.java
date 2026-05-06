package dev.skillmanager.commands;

import dev.skillmanager.app.SyncUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.Skill;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code sync [name] [--from <dir>] [--git-latest] [--merge]} — pull
 * upstream changes for git-tracked installs and re-run the install
 * side-effects pipeline (tools, CLI deps, MCP register, agent symlinks).
 *
 * <p>Every side effect (the merge, the prompt + diff for {@code --from},
 * the post-update tail, orphan-detection) lives in the {@link SyncUseCase}
 * program — the command just resolves targets and hands off.
 */
@Command(name = "sync",
        description = "Pull upstream + re-run install side effects for git-tracked skills.")
public final class SyncCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Skill name to sync (default: all installed)")
    public String name;

    @Option(names = "--from",
            description = "Local directory to pull skill content from (must contain SKILL.md). "
                    + "Without --merge: shows diff and prompts before overwriting. "
                    + "With --merge and a git-backed source: 3-way merge against the source's HEAD. "
                    + "Requires <name>.")
    public Path fromDir;

    @Option(names = {"-y", "--yes"},
            description = "Skip the approval prompt for --from.")
    public boolean yes;

    @Option(names = "--merge",
            description = "Allow a 3-way merge against the resolved upstream when local edits exist. "
                    + "Conflicts leave the working tree in conflicted state and set MERGE_CONFLICT until resolved.")
    public boolean merge;

    @Option(names = "--git-latest",
            description = "Skip the registry; fetch the install-time gitRef (branch / tag) instead of the "
                    + "server-published version's git_sha.")
    public boolean gitLatest;

    @Option(names = "--registry",
            description = "Registry URL override for this invocation (persisted).")
    public String registryUrl;

    @Option(names = "--skip-agents",
            description = "Don't refresh agent symlinks or MCP-config entries.")
    public boolean skipAgents;

    @Option(names = "--skip-mcp",
            description = "Don't re-register MCP servers with the gateway.")
    public boolean skipMcp;

    @Option(names = "--dry-run",
            description = "Print the effects the program would run without mutating filesystem, "
                    + "gateway, or registry.")
    public boolean dryRun;

    @Option(names = "--lock",
            description = "Reconcile against a vendored units.lock.toml at <path>. "
                    + "Walks the diff against the live state and runs sync over the listed units; "
                    + "running twice yields the same disk state.")
    public Path lockPath;

    @Option(names = "--refresh",
            description = "Re-write units.lock.toml from the live install set. Useful after "
                    + "out-of-band changes to sources or to bootstrap a lock from an existing install.")
    public boolean refresh;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();

        // --refresh is a one-shot lockfile rewrite; it doesn't run the
        // sync pipeline at all.
        if (refresh) {
            return runRefresh(store);
        }

        // --lock <path> swaps the implicit "all installed" target list
        // for the units listed in the lock at <path>. Acts as the
        // primary reconciliation entry point per ticket 10.
        if (lockPath != null) {
            return runFromLock(store, lockPath);
        }

        if (fromDir != null && (name == null || name.isBlank())) {
            Log.error("--from requires a skill name");
            return 2;
        }

        GatewayConfig gw = GatewayConfig.resolve(store, null);

        // A user-named skill that isn't installed is an error (exit 3),
        // distinct from the empty-store case (no targets, exit 0 with a
        // warning).
        if (name != null && !name.isBlank() && !store.contains(name)) {
            Log.error("not installed: %s", name);
            return 3;
        }

        List<SyncUseCase.Target> targets = resolveTargets(store);
        if (targets.isEmpty()) {
            Log.warn("no skills installed");
            return 0;
        }

        SyncUseCase.Options opts = new SyncUseCase.Options(
                registryUrl, gitLatest, merge, !skipMcp, !skipAgents, yes);
        dev.skillmanager.effects.StagedProgram<SyncUseCase.Report> program =
                SyncUseCase.buildProgram(store, gw, opts, targets);
        SyncUseCase.Report report;
        if (dryRun) {
            report = new DryRunInterpreter(store).runStaged(program);
        } else {
            dev.skillmanager.effects.Executor.Outcome<SyncUseCase.Report> outcome =
                    new dev.skillmanager.effects.Executor(store, gw).runStaged(program);
            report = outcome.result();
            if (outcome.rolledBack()) {
                Log.warn("sync rolled back %d effect(s) — store + gateway state restored",
                        outcome.applied().size());
            }
        }
        // worstRc reflects the SyncGit fact severity (refused=7, conflicted=8,
        // sync-failed=1). errorCount picks up the post-update tail's failures
        // (gateway unreachable, MCP register errors, agent sync errors) — these
        // wouldn't otherwise surface as a non-zero exit.
        int worst = report.worstRc();
        if (worst != 0) return worst;
        return report.errorCount() > 0 ? 1 : 0;
    }

    private List<SyncUseCase.Target> resolveTargets(SkillStore store) throws IOException {
        if (name != null && !name.isBlank()) {
            return fromDir != null
                    ? List.of(new SyncUseCase.Target.FromDir(name, fromDir))
                    : List.of(new SyncUseCase.Target.Git(name));
        }
        List<SyncUseCase.Target> out = new ArrayList<>();
        for (Skill s : store.listInstalled()) out.add(new SyncUseCase.Target.Git(s.name()));
        return out;
    }

    /**
     * {@code --refresh}: re-write units.lock.toml from the live install
     * set without running the sync pipeline. Useful after out-of-band
     * changes (manual edits to skills/, legacy installs from before
     * UpdateUnitsLock landed) and for bootstrapping a lock the first
     * time.
     */
    private static int runRefresh(SkillStore store) throws IOException {
        dev.skillmanager.lock.UnitsLock live = LockCommand.readLiveState(store);
        java.nio.file.Path lockPath = dev.skillmanager.lock.UnitsLockReader.defaultPath(store);
        dev.skillmanager.lock.UnitsLockWriter.atomicWrite(live, lockPath);
        Log.ok("units.lock.toml refreshed (%d unit(s)) → %s", live.units().size(), lockPath);
        return 0;
    }

    /**
     * {@code --lock <path>}: reconcile disk toward the supplied lock.
     * Walks the diff between the target lock and the live state, then
     * sync's the units that need attention. Idempotent — a second run
     * against the same lock is a no-op once disk converged.
     *
     * <p>For 10c, the reconciliation strategy is conservative:
     * <ul>
     *   <li>Missing-from-disk units present in the lock: log a warning
     *       and recommend {@code skill-manager install}. Reverse-
     *       engineering an install coord from a {@link
     *       dev.skillmanager.lock.LockedUnit} row's origin/ref is
     *       implementable but adds Resolver-level surface area better
     *       gated behind its own ticket.</li>
     *   <li>In-disk units missing from the lock: log a warning and
     *       recommend {@code skill-manager uninstall} or
     *       {@code skill-manager sync --refresh} (if the user wants
     *       to keep the install but adopt the live state into the
     *       lock).</li>
     *   <li>Bumped (sha drift): emit a regular {@code SyncGit} target
     *       for each, fetching the recorded ref. The git handler will
     *       advance the working tree.</li>
     * </ul>
     *
     * <p>Convergence: after one run, drift is reported but nothing
     * disappears or appears. After a manual install/uninstall + a
     * second {@code --lock}, the bumped paths converge. Idempotence
     * holds in the steady state.
     */
    private int runFromLock(SkillStore store, Path target) throws Exception {
        dev.skillmanager.lock.UnitsLock targetLock;
        try {
            targetLock = dev.skillmanager.lock.UnitsLockReader.read(target);
        } catch (IOException io) {
            Log.error("could not read lock at %s: %s", target, io.getMessage());
            return 2;
        }
        dev.skillmanager.lock.UnitsLock liveLock = LockCommand.readLiveState(store);
        dev.skillmanager.lock.LockDiff diff =
                dev.skillmanager.lock.LockDiff.between(liveLock, targetLock);

        if (diff.isEmpty()) {
            Log.ok("disk state matches %s — no reconciliation needed", target);
            return 0;
        }

        for (var u : diff.added()) {
            Log.warn("not installed: %s — run `skill-manager install %s%s` to converge",
                    u.name(), u.name(),
                    u.version() == null ? "" : "@" + u.version());
        }
        for (var u : diff.removed()) {
            Log.warn("on disk but not in lock: %s — run `skill-manager uninstall %s` "
                    + "or `skill-manager sync --refresh` to adopt the live state",
                    u.name(), u.name());
        }

        if (diff.bumped().isEmpty()) {
            // Reported drift but nothing actionable from --lock alone.
            return diff.added().isEmpty() && diff.removed().isEmpty() ? 0 : 1;
        }

        // Re-sync the bumped units. SyncGit fetches the recorded ref and
        // the lock will follow via the existing UpdateUnitsLock effect at
        // the end of the sync program.
        GatewayConfig gw = GatewayConfig.resolve(store, null);
        List<SyncUseCase.Target> targets = new ArrayList<>();
        for (var b : diff.bumped()) targets.add(new SyncUseCase.Target.Git(b.before().name()));
        SyncUseCase.Options opts = new SyncUseCase.Options(
                registryUrl, /*gitLatest=*/false, merge, !skipMcp, !skipAgents, yes);
        dev.skillmanager.effects.StagedProgram<SyncUseCase.Report> program =
                SyncUseCase.buildProgram(store, gw, opts, targets);
        SyncUseCase.Report report;
        if (dryRun) {
            report = new DryRunInterpreter(store).runStaged(program);
        } else {
            dev.skillmanager.effects.Executor.Outcome<SyncUseCase.Report> outcome =
                    new dev.skillmanager.effects.Executor(store, gw).runStaged(program);
            report = outcome.result();
            if (outcome.rolledBack()) {
                Log.warn("sync --lock rolled back %d effect(s)", outcome.applied().size());
            }
        }
        int worst = report.worstRc();
        if (worst != 0) return worst;
        return report.errorCount() > 0 ? 1 : 0;
    }
}
