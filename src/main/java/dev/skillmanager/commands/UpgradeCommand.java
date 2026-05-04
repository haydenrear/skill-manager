package dev.skillmanager.commands;

import dev.skillmanager.app.PostUpdateUseCase;
import dev.skillmanager.app.SyncUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.Skill;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        description = "Upgrade installed git-tracked skills (and optionally skill-manager itself).")
public final class UpgradeCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Skill name to upgrade")
    String name;

    @Option(names = "--all", description = "Upgrade every installed skill.")
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

        SkillStore store = SkillStore.defaultStore();
        store.init();
        // Registry override is persisted by the ConfigureRegistry effect that
        // SyncUseCase prepends — no inline RegistryConfig.resolve needed.

        List<Skill> targets;
        if (all) {
            targets = store.listInstalled();
        } else {
            if (!store.contains(name)) {
                Log.error("not installed: %s", name);
                return 1;
            }
            targets = List.of(store.load(name).orElseThrow());
        }
        if (targets.isEmpty()) {
            Log.warn("no skills installed");
            return selfRc;
        }

        // Reject non-git skills upfront — the SyncGit handler would skip them
        // and record NEEDS_GIT_MIGRATION, but the user specifically asked
        // to upgrade these so a hard error is the right surface.
        List<String> targetNames = new ArrayList<>();
        for (Skill s : targets) {
            if (!GitOps.isGitRepo(store.skillDir(s.name())) || !GitOps.isAvailable()) {
                Log.error("%s: not git-tracked — only git-tracked installs can be upgraded. "
                        + "Reinstall from a github source.", s.name());
                return 5;
            }
            targetNames.add(s.name());
        }

        GatewayConfig gw = GatewayConfig.resolve(store, null);

        Map<String, Set<String>> preMcpDeps = PostUpdateUseCase.snapshotMcpDeps(store);
        Program<SyncUseCase.Report> program = SyncUseCase.buildProgram(
                store, gw, registryUrl, targetNames, /*gitLatest=*/false, merge,
                true, true, preMcpDeps);
        ProgramInterpreter interpreter = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store, gw);
        SyncUseCase.Report report = interpreter.run(program);
        SyncUseCase.printSyncSummary(report);
        if (!report.agentConfigChanges().isEmpty()) {
            PostUpdateUseCase.printAgentConfigSummary(
                    new PostUpdateUseCase.Report(report.errorCount(),
                            report.agentConfigChanges(), report.orphansUnregistered()),
                    gw.mcpEndpoint().toString());
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
