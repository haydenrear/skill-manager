package dev.skillmanager.commands;

import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.app.ResolveContextUseCase;
import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.EffectContext;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.effects.StagedProgram;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * One-shot onboarding for a fresh checkout / install. Drives the new
 * {@link SkillEffect.BuildResolveGraphFromBundledSkills} effect from
 * inside a {@link StagedProgram}: stage 1 does preflight + resolve +
 * commit + run, stage 2 does the post-commit tail (agent sync, harness
 * plugin marketplace, orphan unregister, lock flip) over the unit list
 * read from {@link EffectContext#resolvedGraph()} after stage 1.
 *
 * <p>By default the bundled skills are fetched directly from their
 * github repos. {@code --install-dir} (or {@code SKILL_MANAGER_INSTALL_DIR}
 * pointing at a tree containing every bundled skill dir) switches to a
 * local install — the in-tree dev / test path.
 */
@Command(
        name = "onboard",
        description = "Install the bundled skills (one shared install program) and start the gateway."
)
public final class OnboardCommand implements Callable<Integer> {

    /**
     * Bundled skills onboard installs — directory name (in-tree),
     * published skill name (matches {@code [skill].name} in the
     * manifest, also the SkillStore directory), and the github coord
     * for the default remote-fetch path.
     */
    private record BundledSkill(String dirName, String skillName, String githubCoord) {}

    private static final List<BundledSkill> BUNDLED_SKILLS = List.of(
            new BundledSkill("skill-manager-skill", "skill-manager",
                    "github:haydenrear/skill-manager-skill"),
            new BundledSkill("skill-publisher-skill", "skill-publisher",
                    "github:haydenrear/skill-publisher-skill")
    );

    @Option(names = "--install-dir",
            description = "Install the bundled skills from local directories under this "
                    + "root instead of cloning from github. Used by tests and in-tree dev "
                    + "to exercise uncommitted edits. Defaults to $SKILL_MANAGER_INSTALL_DIR "
                    + "if it points at a tree containing both bundled skill dirs; otherwise "
                    + "onboard fetches from github.")
    Path installDir;

    @Option(names = "--registry",
            description = "Registry URL override (forwarded to the install program).")
    String registryUrl;

    @Option(names = "--skip-gateway",
            description = "Install skills only, don't ensure the gateway is up.")
    boolean skipGateway;

    @Option(names = "--dry-run",
            description = "Print the effects the program would run without executing them.")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        Path root = resolveInstallRoot();
        if (root != null) {
            Log.step("onboarding from %s (local install dir)", root);
        } else {
            Log.step("onboarding from github (no local install dir found — "
                    + "pass --install-dir to install from a working tree)");
        }

        SkillStore store = SkillStore.defaultStore();
        store.init();
        Policy.writeDefaultIfMissing(store);
        GatewayConfig gw = GatewayConfig.resolve(store, null);

        // Pre-validate --install-dir's on-disk shape so the user gets a
        // typed exit code instead of a mid-program failure receipt. The
        // BundledSkillSpec list is what
        // BuildResolveGraphFromBundledSkills consumes — its handler does
        // the per-spec discovery facts + resolve.
        List<SkillEffect.BuildResolveGraphFromBundledSkills.BundledSkillSpec> specs = new ArrayList<>();
        for (BundledSkill bundled : BUNDLED_SKILLS) {
            if (root != null) {
                Path skillDir = root.resolve(bundled.dirName());
                if (!Files.isDirectory(skillDir) || !Files.isRegularFile(skillDir.resolve("SKILL.md"))) {
                    Log.error("bundled skill %s not found at %s", bundled.dirName(), skillDir);
                    return 2;
                }
            }
            specs.add(new SkillEffect.BuildResolveGraphFromBundledSkills.BundledSkillSpec(
                    bundled.dirName(), bundled.skillName(), bundled.githubCoord()));
        }

        StagedProgram<OnboardReport> program = buildOnboardProgram(store, gw, root, specs);
        OnboardReport report;
        int rc = 0;
        if (dryRun) {
            report = new DryRunInterpreter(store).runStaged(program);
        } else {
            Executor.Outcome<OnboardReport> outcome = new Executor(store, gw).runStaged(program);
            report = outcome.result();
            if (outcome.rolledBack()) {
                Log.warn("onboard rolled back %d effect(s) — no partial state retained",
                        outcome.applied().size());
            }
            if (report.exitCode() != 0) return report.exitCode();
            if (report.errorCount() > 0) rc = 1;
        }

        System.out.println();
        System.out.println("onboard summary: installed=" + report.installed()
                + " skipped=" + report.skipped()
                + (skipGateway ? "" : " gateway=" + gatewayStatus(store)));
        return rc;
    }

    /**
     * Build the staged onboard program. Stage 1 = preflight + resolve
     * via {@link SkillEffect.BuildResolveGraphFromBundledSkills} +
     * commit + audit + provenance + summary + run. Stage 2's builder
     * reads {@link EffectContext#resolvedGraph()} for the unit list,
     * then emits agent sync, harness plugin marketplace, orphan
     * unregister, and lock flip — same shape InstallUseCase's stage 2
     * uses.
     */
    private StagedProgram<OnboardReport> buildOnboardProgram(
            SkillStore store, GatewayConfig gw, Path installRoot,
            List<SkillEffect.BuildResolveGraphFromBundledSkills.BundledSkillSpec> specs) {
        String operationId = "onboard-" + UUID.randomUUID();

        List<SkillEffect> stage1Effects = new ArrayList<>(
                ResolveContextUseCase.preflight(gw, registryUrl, !skipGateway && !dryRun));

        // Bundled-skill discovery + resolve as a single effect — emits
        // BundledSkillFound / BundledSkillFromGithub /
        // BundledSkillAlreadyInstalled / BundledSkillMissing per spec,
        // halts the program with a HaltWithExitCode on any resolve
        // failure (same behavior the old pre-Program path had).
        stage1Effects.add(new SkillEffect.BuildResolveGraphFromBundledSkills(installRoot, specs));

        stage1Effects.add(new SkillEffect.SnapshotMcpDeps());
        stage1Effects.add(new SkillEffect.BuildInstallPlan());

        if (!dryRun) {
            stage1Effects.add(new SkillEffect.CommitUnitsToStore());
            stage1Effects.add(new SkillEffect.RecordAuditPlan("onboard"));
            stage1Effects.add(new SkillEffect.RecordSourceProvenance());
            stage1Effects.add(new SkillEffect.PrintInstalledSummary());
        }

        stage1Effects.add(new SkillEffect.RunInstallPlan(gw));

        Program<?> stage1 = new Program<>(operationId + "-stage1", stage1Effects, receipts -> null)
                .withFinally(new SkillEffect.CleanupResolvedGraph());

        java.util.function.Function<EffectContext, Program<?>> stage2Builder = ctx -> {
            ResolvedGraph graph = ctx.resolvedGraph().orElse(new ResolvedGraph());
            List<AgentUnit> tailUnits = graph.units();
            List<SkillEffect> stage2Effects = new ArrayList<>();
            stage2Effects.add(new SkillEffect.SyncAgents(tailUnits, gw));
            if (!dryRun) {
                stage2Effects.add(SkillEffect.RefreshHarnessPlugins.reinstallAll(pluginNames(tailUnits)));
            }
            stage2Effects.add(new SkillEffect.UnregisterMcpOrphans(gw));
            if (!dryRun) {
                stage2Effects.add(buildLockUpdate(store, graph));
            }
            return new Program<>(operationId + "-stage2", stage2Effects, receipts -> null);
        };

        return new StagedProgram<>(operationId, stage1, stage2Builder, OnboardCommand::decode);
    }

    private static SkillEffect.UpdateUnitsLock buildLockUpdate(SkillStore store, ResolvedGraph graph) {
        Path lockPath = dev.skillmanager.lock.UnitsLockReader.defaultPath(store);
        try {
            dev.skillmanager.lock.UnitsLock current = dev.skillmanager.lock.UnitsLockReader.read(lockPath);
            dev.skillmanager.lock.UnitsLock target = current;
            for (var r : graph.resolved()) {
                InstalledUnit.InstallSource src = mapSourceKind(r.sourceKind());
                target = target.withUnit(new dev.skillmanager.lock.LockedUnit(
                        r.name(), r.unit().kind(), r.version(), src,
                        null, null, r.sha256()));
            }
            return new SkillEffect.UpdateUnitsLock(target, lockPath);
        } catch (IOException io) {
            return new SkillEffect.UpdateUnitsLock(
                    dev.skillmanager.lock.UnitsLock.empty(), lockPath);
        }
    }

    private static InstalledUnit.InstallSource mapSourceKind(ResolvedGraph.SourceKind k) {
        return switch (k) {
            case REGISTRY -> InstalledUnit.InstallSource.REGISTRY;
            case GIT -> InstalledUnit.InstallSource.GIT;
            case LOCAL -> InstalledUnit.InstallSource.LOCAL_FILE;
        };
    }

    private static List<String> pluginNames(List<AgentUnit> units) {
        List<String> out = new ArrayList<>();
        for (var u : units) {
            if (u.kind() == UnitKind.PLUGIN) out.add(u.name());
        }
        return out;
    }

    private static OnboardReport decode(List<EffectReceipt> receipts) {
        int committed = 0;
        int skipped = 0;
        int errorCount = 0;
        int exitCode = 0;
        for (EffectReceipt r : receipts) {
            if (r.status() == EffectStatus.FAILED || r.status() == EffectStatus.PARTIAL) errorCount++;
            for (ContextFact f : r.facts()) {
                if (f instanceof ContextFact.SkillCommitted) committed++;
                else if (f instanceof ContextFact.BundledSkillAlreadyInstalled) skipped++;
                else if (f instanceof ContextFact.HaltWithExitCode h && exitCode == 0) exitCode = h.code();
            }
        }
        return new OnboardReport(committed, skipped, errorCount, exitCode);
    }

    /** Decoded onboard report — installed count, already-installed skipped count, error count, and the typed exit code from HaltWithExitCode (0 = no halt). */
    private record OnboardReport(int installed, int skipped, int errorCount, int exitCode) {}

    private static String gatewayStatus(SkillStore store) {
        try {
            GatewayConfig gw = GatewayConfig.resolve(store, null);
            return new GatewayClient(gw).ping() ? "up" : "down";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Locate a working tree containing both bundled skill directories,
     * if one exists. Returns null when no local source is available — in
     * that case onboard falls back to fetching the skills from github.
     */
    private Path resolveInstallRoot() {
        if (installDir != null) {
            Path p = installDir.toAbsolutePath();
            if (!hasBundledSkills(p)) {
                Log.warn("--install-dir %s does not contain both bundled skill directories — "
                        + "falling back to github fetch", p);
                return null;
            }
            return p;
        }
        String env = System.getenv("SKILL_MANAGER_INSTALL_DIR");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env).toAbsolutePath();
            if (hasBundledSkills(p)) return p;
        }
        Path cur = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cur != null) {
            if (hasBundledSkills(cur)) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean hasBundledSkills(Path candidate) {
        for (BundledSkill bundled : BUNDLED_SKILLS) {
            if (!Files.isRegularFile(candidate.resolve(bundled.dirName()).resolve("SKILL.md"))) {
                return false;
            }
        }
        return true;
    }
}
