package dev.skillmanager.commands;

import dev.skillmanager.app.SyncUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code upgrade [name|--all] [--self] [--merge]} — version bump for
 * git-tracked skills, plus optional Homebrew upgrade of the CLI itself.
 *
 * <p>Drives the same {@link SyncUseCase} program as {@code sync} — no
 * command-from-command instantiation. The
 * {@link dev.skillmanager.effects.SkillEffect.SyncGit} handler does the
 * registry lookup / non-downgrade / git fetch+merge per target, then the
 * post-update tail (transitives, tools, CLI, MCP, agents) runs once.
 *
 * <p>Non-git installs are not supported — convert them to a git source first.
 */
@Command(name = "upgrade",
        description = "Upgrade installed git-tracked units — skills and plugins — to the latest "
                + "registry version (and optionally skill-manager itself). Plugins re-register "
                + "with Claude/Codex via their CLIs after the bytes update so hooks reload.")
public final class UpgradeCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1",
            description = "Unit name to upgrade — skill or plugin")
    String name;

    @Option(names = "--all", description = "Upgrade every installed unit (skills + plugins).")
    boolean all;

    @Option(names = {"--self", "--skill-manager"},
            description = "Upgrade the skill-manager CLI via Homebrew.")
    boolean self;

    @Option(names = "--registry",
            description = "Registry URL override for this invocation (persisted).")
    String registryUrl;

    @Option(names = "--merge",
            description = "Run a 3-way merge against the new version's git_sha when local edits exist.")
    boolean merge;

    @Option(names = "--dry-run",
            description = "Print the effects the program would run without executing them.")
    boolean dryRun;

    private final SkillStore store;

    public UpgradeCommand() {
        this(SkillStore.defaultStore());
    }

    public UpgradeCommand(SkillStore store) {
        this.store = store;
    }

    @Override
    public Integer call() throws Exception {
        int selfRc = 0;
        if (self) {
            selfRc = upgradeSkillManager();
            if (!all && (name == null || name.isBlank())) return selfRc;
        }
        if (!all && (name == null || name.isBlank())) {
            Log.error("usage: skill-manager upgrade <name> | --all | --self");
            return 2;
        }

        store.init();
        // Registry override is persisted by the ConfigureRegistry effect that
        // SyncUseCase prepends — no inline RegistryConfig.resolve needed.

        List<AgentUnit> targets;
        if (all) {
            targets = store.listInstalledUnits();
        } else {
            AgentUnit unit = store.loadUnit(name).orElse(null);
            if (unit == null) {
                Log.error("not installed: %s", name);
                return 1;
            }
            targets = List.of(unit);
        }
        if (targets.isEmpty()) {
            Log.warn("no units installed");
            return selfRc;
        }

        // Non-git skills can't be upgraded. For an explicit single target
        // that's a hard error; for --all we skip them with a warning and
        // upgrade the git-tracked ones (so a single bundled/local-dir skill
        // doesn't break upgrade --all entirely).
        List<SyncUseCase.Target> targetList = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (AgentUnit u : targets) {
            if (u.kind() == UnitKind.DOC || u.kind() == UnitKind.HARNESS) {
                String message = u.name() + ": " + u.kind().name().toLowerCase()
                        + " units do not support `upgrade`; use `sync` instead";
                if (all) {
                    skipped.add(message);
                    continue;
                }
                Log.error(message);
                return 5;
            }
            if (!GitOps.isGitRepo(store.unitDir(u.name(), u.kind())) || !GitOps.isAvailable()) {
                if (all) {
                    skipped.add(u.name() + ": not git-tracked");
                    continue;
                }
                Log.error("%s: not git-tracked — only git-tracked skill/plugin installs can be upgraded. "
                        + "Reinstall from a github source or add a git repo.", u.name());
                return 5;
            }
            targetList.add(new SyncUseCase.Target.Git(u.name()));
        }
        for (String message : skipped) {
            Log.warn("%s — skipping", message);
        }
        if (targetList.isEmpty()) {
            Log.warn("no git-tracked skill/plugin units to upgrade");
            return selfRc;
        }

        GatewayConfig gw = GatewayConfig.resolve(store, null);

        SyncUseCase.Options opts = new SyncUseCase.Options(
                registryUrl, /*gitLatest=*/false, merge, true, true, /*yes=*/false);
        dev.skillmanager.effects.StagedProgram<SyncUseCase.Report> program =
                SyncUseCase.buildProgram(store, gw, opts, targetList);
        SyncUseCase.Report report;
        if (dryRun) {
            report = new DryRunInterpreter(store).runStaged(program);
        } else {
            dev.skillmanager.effects.Executor.Outcome<SyncUseCase.Report> outcome =
                    new dev.skillmanager.effects.Executor(store, gw).runStaged(program);
            report = outcome.result();
            if (outcome.rolledBack()) {
                Log.warn("upgrade rolled back %d effect(s) — store + gateway state restored",
                        outcome.applied().size());
            }
        }
        int worst = report.worstRc();
        return worst > 0 ? worst : selfRc;
    }

    private static final String BREW_TAP = "haydenrear/skill-manager";
    private static final String BREW_FORMULA = BREW_TAP + "/skill-manager";

    private static int upgradeSkillManager() {
        Log.step("ensuring brew tap %s is added", BREW_TAP);
        int tapRc = runBrew("tap", BREW_TAP);
        if (tapRc != 0) {
            Log.error("brew tap %s exited %d", BREW_TAP, tapRc);
            return tapRc;
        }
        Log.step("upgrading skill-manager via brew upgrade %s", BREW_FORMULA);
        int upRc = runBrew("upgrade", BREW_FORMULA);
        if (upRc == 0) Log.ok("skill-manager upgraded");
        else Log.error("brew upgrade %s exited %d", BREW_FORMULA, upRc);
        return upRc;
    }

    private static int runBrew(String... brewArgs) {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add("brew");
        for (String a : brewArgs) argv.add(a);
        try {
            Process p = new ProcessBuilder(argv).inheritIO().start();
            return p.waitFor();
        } catch (java.io.IOException e) {
            Log.error("brew not found on PATH: %s", e.getMessage());
            return 6;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }
}
