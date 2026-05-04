package dev.skillmanager.commands;

import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.app.PostUpdateUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.mcp.GatewayConfig;
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
 * {@code skill-manager install <source>} resolves and stages the dep
 * graph, then drives an {@link InstallUseCase} program. Every side
 * effect — registry override, gateway start, pre-condition checks,
 * plan build, commit, audit, source provenance, transitive resolution,
 * tool/CLI/MCP per-action effects, agent sync, orphan unregister, and
 * staged-graph cleanup — is its own effect with a receipt.
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
            description = "Resolve, plan, and print the install effects without committing to the "
                    + "store, contacting the gateway, or running side effects.")
    public boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        Policy.writeDefaultIfMissing(store);

        Log.step("resolving %s", source);
        Resolver resolver = new Resolver(store);
        ResolvedGraph graph = resolver.resolve(source, version);

        // Resolve gateway URL — the Program's EnsureGateway effect handles
        // the actual probe + start + persist; gateway resolution itself
        // doesn't side-effect.
        GatewayConfig gw = GatewayConfig.resolve(store, null);

        Program<InstallUseCase.Report> program = InstallUseCase.buildProgram(
                gw, registryUrl, graph, dryRun);
        ProgramInterpreter interpreter = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store, gw);
        InstallUseCase.Report report = interpreter.run(program);

        if (dryRun) return 0;

        PostUpdateUseCase.printAgentConfigSummary(
                new PostUpdateUseCase.Report(report.errorCount(),
                        report.agentConfigChanges(), report.orphansUnregistered()),
                gw.mcpEndpoint().toString());
        return report.errorCount() == 0 ? 0 : 4;
    }
}
