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
        description = "Pull upstream + re-run install side effects for git-tracked units (skills "
                + "and plugins). For plugins this also regenerates the plugin marketplace at "
                + "`$SKILL_MANAGER_HOME/plugin-marketplace/` and re-registers each plugin with "
                + "Claude/Codex via their CLIs (uninstall+reinstall, so hooks reload).")
public final class SyncCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1",
            description = "Unit name to sync — skill or plugin (default: all installed)")
    public String name;

    @Option(names = "--from",
            description = "Local directory to pull unit content from (must contain SKILL.md "
                    + "for a skill or .claude-plugin/plugin.json for a plugin). "
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

    @Option(names = "--force",
            description = "Doc-repo sync only: clobber locally-edited and conflict destinations. "
                    + "Lost edits are surfaced as warnings.")
    public boolean force;

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

        // A user-named unit that isn't installed is an error (exit 3),
        // distinct from the empty-store case (no targets, exit 0 with a
        // warning). Kind-agnostic check — sync handles both skills and
        // plugins.
        if (name != null && !name.isBlank() && !store.containsUnit(name)) {
            Log.error("not installed: %s", name);
            return 3;
        }

        List<SyncUseCase.Target> targets = resolveTargets(store);
        if (targets.isEmpty()) {
            Log.warn("no units installed");
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
            // Kind-aware dispatch:
            //   SKILL / PLUGIN  → git pull or --from dir
            //   DOC             → SyncDocRepo (four-state matrix)
            //   HARNESS         → fan out one SyncHarness per known
            //                     instance of that template
            dev.skillmanager.model.UnitKind kind = kindOf(store, name);
            if (kind == dev.skillmanager.model.UnitKind.DOC) {
                if (fromDir != null) {
                    Log.warn("--from is not supported for doc-repos — ignoring");
                }
                return List.of(new SyncUseCase.Target.DocRepo(name, force));
            }
            if (kind == dev.skillmanager.model.UnitKind.HARNESS) {
                if (fromDir != null) {
                    Log.warn("--from is not supported for harness templates — ignoring");
                }
                return harnessInstanceTargets(store, name);
            }
            return fromDir != null
                    ? List.of(new SyncUseCase.Target.FromDir(name, fromDir))
                    : List.of(new SyncUseCase.Target.Git(name));
        }
        // Walk every installed unit so a no-arg sync re-runs the
        // post-update tail (or doc-repo drift sweep, or harness
        // reconcile) over the full set rather than missing kinds.
        List<SyncUseCase.Target> out = new ArrayList<>();
        for (var u : store.listInstalledUnits()) {
            switch (u.kind()) {
                case DOC -> out.add(new SyncUseCase.Target.DocRepo(u.name(), force));
                case HARNESS -> out.addAll(harnessInstanceTargets(store, u.name()));
                case SKILL, PLUGIN -> out.add(new SyncUseCase.Target.Git(u.name()));
            }
        }
        return out;
    }

    /**
     * Discover every live instance of {@code templateName} from the
     * binding ledger and emit one {@link SyncUseCase.Target.Harness}
     * per instance. Instances are identified by the
     * {@code harness:<instanceId>:} prefix on harness-source bindings.
     */
    private static List<SyncUseCase.Target> harnessInstanceTargets(SkillStore store, String templateName) {
        dev.skillmanager.bindings.BindingStore bs = new dev.skillmanager.bindings.BindingStore(store);
        java.util.Set<String> instanceIds = new java.util.LinkedHashSet<>();
        for (var b : bs.listAll()) {
            if (b.source() != dev.skillmanager.bindings.BindingSource.HARNESS) continue;
            String id = b.bindingId();
            if (!id.startsWith("harness:")) continue;
            String rest = id.substring("harness:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) continue;
            instanceIds.add(rest.substring(0, colon));
        }
        if (instanceIds.isEmpty()) {
            Log.warn("harness template %s has no live instances — `skill-manager harness instantiate %s`",
                    templateName, templateName);
            return List.of();
        }
        List<SyncUseCase.Target> out = new ArrayList<>(instanceIds.size());
        for (String iid : instanceIds) {
            out.add(new SyncUseCase.Target.Harness(templateName, iid));
        }
        return out;
    }

    private static dev.skillmanager.model.UnitKind kindOf(SkillStore store, String unitName) {
        return new dev.skillmanager.source.UnitStore(store)
                .read(unitName)
                .map(dev.skillmanager.source.InstalledUnit::unitKind)
                .orElse(dev.skillmanager.model.UnitKind.SKILL);
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
