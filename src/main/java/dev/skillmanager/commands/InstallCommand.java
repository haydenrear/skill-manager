package dev.skillmanager.commands;

import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.app.PostUpdateUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.lock.CliLock;
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
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * {@code skill-manager install <source>} resolves and stages the dep graph,
 * then drives an {@link InstallUseCase} program — every side effect (registry
 * config, gateway start, store commit, audit log, source provenance,
 * transitives, tools/CLI/MCP/agents) is its own effect with a receipt and
 * failure mode the interpreter can act on.
 */
@Command(
        name = "install",
        description = "Install a skill and everything it depends on. Sources can be a registry name "
                + "(`name[@version]`), a github coordinate (`github:user/repo`), a git URL "
                + "(`git+https://...`), or a local directory (`./path`, `/abs/path`, or `file:<path>`). "
                + "Local-directory installs do not contact the registry — useful for iterating on "
                + "a skill from a working tree without publishing first. Use `skill-manager sync "
                + "<name> --from <dir>` to refresh an already-installed skill from the same dir."
)
public final class InstallCommand implements Callable<Integer> {

    @Parameters(index = "0",
            description = "Source: name[@version] (registry), github:user/repo, git+https://..., "
                    + "or a local directory (./path, /abs/path, file:<path>) — local sources do not "
                    + "contact the registry.")
    public String source;

    @Option(names = {"--version", "--ref"}, description = "Registry version / git ref")
    public String version;

    @Option(names = "--registry",
            description = "Registry URL override for this invocation (persisted so `search` and "
                    + "`install` stay consistent). Can also be set via SKILL_MANAGER_REGISTRY_URL.")
    public String registryUrl;

    @Option(names = "--dry-run",
            description = "Resolve, plan, and print the post-install effects without committing the "
                    + "skill to the store, contacting the gateway, or running side effects.")
    public boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        Policy.writeDefaultIfMissing(store);
        Policy policy = Policy.load(store);

        Log.step("resolving %s", source);
        Resolver resolver = new Resolver(store);
        ResolvedGraph graph = resolver.resolve(source, version);
        try {
            var resolvedList = graph.resolved();
            String top = resolvedList.isEmpty() ? null : resolvedList.get(0).name();
            if (top != null && store.contains(top)) {
                Log.error("skill '%s' is already installed at %s — remove it first (skill-manager remove %s)",
                        top, store.skillDir(top), top);
                return 3;
            }

            CliLock lock = CliLock.load(store);
            dev.skillmanager.pm.PackageManagerRuntime pmRuntime =
                    new dev.skillmanager.pm.PackageManagerRuntime(store);
            InstallPlan plan = new PlanBuilder(policy, lock, pmRuntime)
                    .plan(graph, true, true, store.cliBinDir());
            PlanPrinter.print(plan);
            if (plan.blocked()) {
                Log.error("plan has blocked items — see policy at %s", store.root().resolve("policy.toml"));
                return 2;
            }

            // Resolve the gateway BEFORE building the program so EnsureGateway
            // can target the right URL even when a registry override is also
            // pending (registry is persisted via the program effect; gateway
            // resolution doesn't depend on it).
            GatewayConfig gw = GatewayConfig.resolve(store, null);

            Program<InstallUseCase.Report> program = InstallUseCase.buildProgram(
                    gw, registryUrl, graph, plan, dryRun);
            ProgramInterpreter interpreter = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store, gw);
            InstallUseCase.Report report = interpreter.run(program);

            if (dryRun) return 0;

            for (var r : graph.resolved()) {
                System.out.println("INSTALLED: " + r.name()
                        + (r.version() == null ? "" : "@" + r.version())
                        + " -> " + store.skillDir(r.name()));
            }
            PostUpdateUseCase.printAgentConfigSummary(
                    new PostUpdateUseCase.Report(report.errorCount(),
                            report.agentConfigChanges(), report.orphansUnregistered()),
                    gw.mcpEndpoint().toString());
            return report.errorCount() == 0 ? 0 : 4;
        } finally {
            graph.cleanup();
        }
    }
}
