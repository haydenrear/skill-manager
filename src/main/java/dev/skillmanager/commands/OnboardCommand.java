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
     *
     * <p>{@code dirName} is null for a unit this repository does not
     * vendor; see {@link #BUNDLED_SKILLS}.
     */
    private record BundledSkill(String dirName, String skillName, String githubCoord) {
        boolean githubOnly() { return dirName == null; }
    }

    // SI-18. The skt entry is GONE and tla-spec-dev stands in its place.
    //
    // skt is not a plugin any more: it is a contained skill of the
    // tla-spec-dev plugin, at plugins/tla-spec-dev/skills/skt. Onboarding a
    // STANDALONE skt would re-materialise on every fresh home exactly the
    // duplicate the unification removed — two copies answering to one name,
    // which is the state RejectContainedNameCollision exists to refuse. So
    // onboarding installs the carrier and skt arrives inside it, along with
    // unit-authoring and the workflow skills.
    //
    // The coord is `tla-spec-dev-plugin`, NOT `tla-spec-dev`. Two
    // repositories, two surfaces: `tla-spec-dev` keeps shipping the
    // spec-double-compiler SKILL so existing installs keep syncing, and
    // installing that coord gets the skill, not the plugin.
    //
    // dirName is null because this repository carries no copy of the plugin
    // and is not going to: it is a leaf unit of its own, not a constituent of
    // this integration snapshot the way skill-manager-skill is. A null
    // dirName resolves from github in EVERY mode, including --install-dir.
    //
    // THE SECOND LIST. BundledSkills.GITHUB_COORDS holds the same three
    // facts for the reconciler, and OUN-4 had to remove skill-dev-skill from
    // both — the compiler cannot relate them, and a retired unit left in
    // either one is still onboarded by whichever path reads that copy.
    private static final List<BundledSkill> BUNDLED_SKILLS = List.of(
            new BundledSkill("skill-manager-skill", "skill-manager",
                    "github:haydenrear/skill-manager-skill"),
            new BundledSkill(null, "tla-spec-dev",
                    "github:haydenrear/tla-spec-dev-plugin")
    );

    @Option(names = "--install-dir",
            description = "Install the bundled skills from local directories under this "
                    + "root instead of cloning from github. Used by tests and in-tree dev "
                    + "to exercise uncommitted edits. Defaults to $SKILL_MANAGER_INSTALL_DIR "
                    + "if it points at a tree containing all bundled skill dirs; otherwise "
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
            if (root != null && !bundled.githubOnly()) {
                Path skillDir = root.resolve(bundled.dirName());
                if (!Files.isDirectory(skillDir) || !hasUnitShape(skillDir)) {
                    Log.error("bundled unit %s not found at %s", bundled.dirName(), skillDir);
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
            // A printed violation that does not reach an exit code is not a
            // check. Same code as install/sync so one caller check covers the
            // whole onboarding path.
            else if (report.markdownImportViolations() > 0) {
                rc = dev.skillmanager.validation.MarkdownImportValidator.EXIT_CODE;
            }
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
        stage1Effects.add(new SkillEffect.BuildInstallPlan(false, !skipGateway));

        if (!dryRun) {
            stage1Effects.add(new SkillEffect.CommitUnitsToStore());
            stage1Effects.add(SkillEffect.ValidateMarkdownImports.resolvedGraph());
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
            if (!skipGateway) {
                stage2Effects.add(new SkillEffect.SyncAgents(tailUnits, gw));
            }
            if (!dryRun) {
                stage2Effects.add(SkillEffect.RefreshHarnessPlugins.reinstallAll(pluginNames(tailUnits)));
            }
            if (!skipGateway) {
                stage2Effects.add(new SkillEffect.UnregisterMcpOrphans(gw));
            }
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
        int importViolations = 0;
        for (EffectReceipt r : receipts) {
            if (r.status() == EffectStatus.FAILED || r.status() == EffectStatus.PARTIAL) errorCount++;
            for (ContextFact f : r.facts()) {
                if (f instanceof ContextFact.SkillCommitted) committed++;
                else if (f instanceof ContextFact.BundledSkillAlreadyInstalled) skipped++;
                else if (f instanceof ContextFact.MarkdownImportViolation) importViolations++;
                else if (f instanceof ContextFact.HaltWithExitCode h && exitCode == 0) exitCode = h.code();
            }
        }
        return new OnboardReport(committed, skipped, errorCount, exitCode, importViolations);
    }

    /** Decoded onboard report — installed count, already-installed skipped count, error count, and the typed exit code from HaltWithExitCode (0 = no halt). */
    private record OnboardReport(int installed, int skipped, int errorCount, int exitCode,
                                 int markdownImportViolations) {}

    private static String gatewayStatus(SkillStore store) {
        try {
            GatewayConfig gw = GatewayConfig.resolve(store, null);
            return new GatewayClient(gw).ping() ? "up" : "down";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Locate a working tree containing all bundled skill directories,
     * if one exists. Returns null when no local source is available — in
     * that case onboard falls back to fetching the skills from github.
     */
    private Path resolveInstallRoot() {
        if (installDir != null) {
            Path p = installDir.toAbsolutePath();
            if (!hasBundledSkills(p)) {
                Log.warn("--install-dir %s does not contain all bundled skill directories — "
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

    /**
     * True when {@code candidate} is a working tree holding every bundled
     * unit this repository actually vendors.
     *
     * <p>github-only entries are skipped: they have no in-tree copy, so
     * requiring one would make this return false for THIS repository's own
     * root and silently send the local dev / test path back to github —
     * losing the uncommitted-edit install that {@code --install-dir} exists
     * to provide.
     */
    private static boolean hasBundledSkills(Path candidate) {
        boolean sawVendored = false;
        for (BundledSkill bundled : BUNDLED_SKILLS) {
            if (bundled.githubOnly()) continue;
            sawVendored = true;
            if (!hasUnitShape(candidate.resolve(bundled.dirName()))) {
                return false;
            }
        }
        // No vendored entry means no directory can identify an install root, so
        // "does this directory look like one" has no true answer and must not
        // be yes. Without this the loop body never runs, every candidate is
        // accepted, and resolveInstallRoot()'s upward walk stops at the first
        // ancestor it tries. Latent while skill-manager-skill remains, and
        // silent if it ever goes.
        return sawVendored;
    }

    /**
     * A bundled entry is either a skill (top-level SKILL.md) or a plugin
     * (skill-manager-plugin.toml), so a SKILL.md-only probe would reject
     * an install root carrying a plugin.
     */
    private static boolean hasUnitShape(Path unitDir) {
        return Files.isRegularFile(unitDir.resolve("SKILL.md"))
                || Files.isRegularFile(unitDir.resolve("skill-manager-plugin.toml"));
    }
}
