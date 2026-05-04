package dev.skillmanager.commands;

import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanBuilder;
import dev.skillmanager.plan.PlanPrinter;
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
 */
@Command(
        name = "onboard",
        description = "Install the bundled skills (one shared install program) and start the gateway."
)
public final class OnboardCommand implements Callable<Integer> {

    private static final List<String> BUNDLED_SKILLS = List.of(
            "skill-manager-skill",
            "skill-publisher-skill"
    );

    @Option(names = "--install-dir",
            description = "Override the install root that contains the bundled skill "
                    + "directories. Defaults to $SKILL_MANAGER_INSTALL_DIR (set by the "
                    + "skill-manager bash wrapper) or the current working directory.")
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
        if (root == null) {
            Log.error("could not locate install root containing %s. "
                    + "Pass --install-dir or set SKILL_MANAGER_INSTALL_DIR.", BUNDLED_SKILLS);
            return 2;
        }
        Log.step("onboarding from %s", root);

        SkillStore store = SkillStore.defaultStore();
        store.init();
        Policy.writeDefaultIfMissing(store);
        Policy policy = Policy.load(store);
        GatewayConfig gw = GatewayConfig.resolve(store, null);

        // Filter out skills that are already installed — onboard is idempotent.
        List<Resolver.Coord> toResolve = new ArrayList<>();
        int skipped = 0;
        for (String name : BUNDLED_SKILLS) {
            Path skillDir = root.resolve(name);
            if (!Files.isDirectory(skillDir) || !Files.isRegularFile(skillDir.resolve("SKILL.md"))) {
                Log.error("bundled skill %s not found at %s", name, skillDir);
                return 2;
            }
            String skillName = readSkillName(skillDir, name);
            if (store.contains(skillName)) {
                Log.info("%s already installed at %s — skipping", skillName, store.skillDir(skillName));
                skipped++;
                continue;
            }
            toResolve.add(new Resolver.Coord(skillDir.toString(), null));
        }

        int installedCount = 0;
        int rc = 0;
        if (!toResolve.isEmpty()) {
            Resolver resolver = new Resolver(store);
            ResolvedGraph graph = resolver.resolveAll(toResolve);
            try {
                CliLock lock = CliLock.load(store);
                dev.skillmanager.pm.PackageManagerRuntime pmRuntime =
                        new dev.skillmanager.pm.PackageManagerRuntime(store);
                InstallPlan plan = new PlanBuilder(policy, lock, pmRuntime)
                        .plan(graph, true, true, store.cliBinDir());
                PlanPrinter.print(plan);
                if (plan.blocked()) {
                    Log.error("plan has blocked items — see policy at %s",
                            store.root().resolve("policy.toml"));
                    return 2;
                }

                // One program per command (composition rule): chain the
                // install with the gateway-up via Program.then so they share
                // one interpreter call + one EffectContext.
                Program<InstallUseCase.Report> install = InstallUseCase.buildProgram(
                        gw, registryUrl, graph, plan, dryRun);
                Program<OnboardReport> program;
                if (skipGateway) {
                    program = new Program<>(
                            install.operationId(),
                            install.effects(),
                            receipts -> {
                                InstallUseCase.Report r = install.decoder().decode(receipts);
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
            } finally {
                graph.cleanup();
            }
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

    private Path resolveInstallRoot() {
        if (installDir != null) {
            Path p = installDir.toAbsolutePath();
            return hasBundledSkills(p) ? p : null;
        }
        String env = System.getenv("SKILL_MANAGER_INSTALL_DIR");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env).toAbsolutePath();
            if (hasBundledSkills(p)) return p;
        }
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path cur = cwd;
        while (cur != null) {
            if (hasBundledSkills(cur)) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean hasBundledSkills(Path candidate) {
        for (String name : BUNDLED_SKILLS) {
            if (!Files.isRegularFile(candidate.resolve(name).resolve("SKILL.md"))) return false;
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
