package dev.skillmanager.commands;

import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.resolve.Resolver;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * One-shot onboarding for a fresh checkout / install. Builds a SINGLE
 * {@link ResolvedGraph} containing every bundled skill that isn't yet in
 * the store (skipping ones already installed), then drives ONE
 * {@link InstallUseCase} program over the combined graph.
 *
 * <p>Avoids invoking {@link InstallCommand} per-skill — that would
 * re-resolve, re-commit, re-run the full post-update tail (transitives,
 * tools, CLI, MCP, agents) once per skill. With a unified graph, each
 * step (commit, audit, provenance, tools, CLI, MCP, agents) runs once.
 *
 * <p>By default the bundled skills are fetched directly from their
 * github repos (so brew installs work without a registry, and {@code
 * upgrade} can fast-forward them like any other git-tracked unit). When
 * {@code --install-dir} is passed (or {@code SKILL_MANAGER_INSTALL_DIR}
 * points at a tree containing the {@code skill-manager-skill/} and
 * {@code skill-publisher-skill/} directories) we install from those
 * local paths instead — the in-tree dev / test path that lets you
 * exercise uncommitted changes to either skill before they're pushed.
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

        // Filter out skills that are already installed — onboard is idempotent.
        List<Resolver.Coord> toResolve = new ArrayList<>();
        int skipped = 0;
        for (BundledSkill bundled : BUNDLED_SKILLS) {
            String skillName;
            String coord;
            if (root != null) {
                Path skillDir = root.resolve(bundled.dirName());
                // resolveInstallRoot only returned non-null after
                // hasBundledSkills() confirmed both SKILL.md files
                // exist, so a missing dir here is a real bug, not a
                // user error.
                if (!Files.isDirectory(skillDir) || !Files.isRegularFile(skillDir.resolve("SKILL.md"))) {
                    Log.error("bundled skill %s not found at %s", bundled.dirName(), skillDir);
                    return 2;
                }
                skillName = readSkillName(skillDir, bundled.skillName());
                coord = skillDir.toString();
            } else {
                skillName = bundled.skillName();
                coord = bundled.githubCoord();
            }
            if (store.contains(skillName)) {
                Log.info("%s already installed at %s — skipping", skillName, store.skillDir(skillName));
                skipped++;
                continue;
            }
            toResolve.add(new Resolver.Coord(coord, null));
        }

        int installedCount = 0;
        int rc = 0;
        if (!toResolve.isEmpty()) {
            Resolver resolver = new Resolver(store);
            Resolver.ResolveOutcome outcome = resolver.resolveAll(toResolve);
            if (outcome.hasFailures()) {
                // Onboard installs a fixed bundled set; if any of them
                // doesn't resolve we render the failures and bail with
                // a typed exit code, same as InstallCommand. Half-
                // onboard would leave the user in a confusing state
                // (one bundled skill installed, the other not, with no
                // record of why).
                dev.skillmanager.resolve.TransitiveFailures.renderAll(outcome.failures());
                return dev.skillmanager.resolve.TransitiveFailures.exitCodeFor(outcome.failures());
            }
            ResolvedGraph graph = outcome.graph();
            // CleanupResolvedGraph is wired into InstallUseCase as
            // alwaysAfter — no manual try/finally cleanup here.
            Program<InstallUseCase.Report> install = InstallUseCase.buildProgram(
                    store, gw, registryUrl, graph, dryRun, !skipGateway);
            Program<OnboardReport> program;
            if (skipGateway) {
                program = new Program<>(
                        install.operationId(),
                        install.effects(),
                        install.alwaysAfter(),
                        receipts -> {
                            // Slice off the alwaysAfter receipts the
                            // interpreter appended; the InstallUseCase
                            // decoder operates on the main-effect slice.
                            int n = install.effects().size();
                            InstallUseCase.Report r = install.decoder().decode(receipts.subList(0, n));
                            return new OnboardReport(r.committed().size(), r.errorCount());
                        });
            } else {
                Program<Integer> gateway = new Program<>(
                        "onboard-gw-" + UUID.randomUUID(),
                        List.of(new SkillEffect.EnsureGateway(gw)),
                        receipts -> 0);
                program = install.then(gateway,
                        (instReport, gwRc) -> new OnboardReport(
                                instReport.committed().size(), instReport.errorCount()));
            }
            ProgramInterpreter interp = dryRun
                    ? new DryRunInterpreter()
                    : new LiveInterpreter(store, gw);
            OnboardReport report = interp.run(program);
            installedCount = report.installed;
            if (!dryRun && report.errorCount > 0) rc = 1;
        } else if (!skipGateway) {
            // Nothing to install but the user still wants the gateway up.
            Program<Integer> gateway = new Program<>(
                    "onboard-gw-" + UUID.randomUUID(),
                    List.of(new SkillEffect.EnsureGateway(gw)),
                    receipts -> 0);
            ProgramInterpreter interp = dryRun
                    ? new DryRunInterpreter()
                    : new LiveInterpreter(store, gw);
            interp.run(gateway);
        }

        System.out.println();
        System.out.println("onboard summary: installed=" + installedCount
                + " skipped=" + skipped
                + (skipGateway ? "" : " gateway=" + gatewayStatus(store)));
        return rc;
    }

    /** Combined report for the install + gateway-up chain. */
    private record OnboardReport(int installed, int errorCount) {}

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
     *
     * <p>The lookup is deliberately silent on miss: a brew-installed
     * {@code skill-manager} binary has {@code SKILL_MANAGER_INSTALL_DIR}
     * pointing at a {@code share/} dir that does NOT contain the skill
     * source trees, and that's the most common case where onboard
     * should just clone from github rather than error out.
     */
    private Path resolveInstallRoot() {
        if (installDir != null) {
            Path p = installDir.toAbsolutePath();
            if (!hasBundledSkills(p)) {
                // --install-dir is an explicit user signal: if it
                // doesn't contain the bundled skills, that's a typo /
                // wrong path, not a request to fall through to github.
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

    private static String readSkillName(Path skillDir, String fallback) {
        Path toml = skillDir.resolve("skill-manager.toml");
        if (!Files.isRegularFile(toml)) return fallback;
        try {
            boolean inSkillTable = false;
            for (String raw : Files.readAllLines(toml)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    inSkillTable = line.equals("[skill]");
                    continue;
                }
                if (!inSkillTable) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                if (!"name".equals(key)) continue;
                String value = line.substring(eq + 1).trim();
                if (value.length() >= 2
                        && (value.startsWith("\"") && value.endsWith("\"")
                            || value.startsWith("'") && value.endsWith("'"))) {
                    return value.substring(1, value.length() - 1);
                }
                return value;
            }
        } catch (Exception ignored) {}
        return fallback;
    }
}
