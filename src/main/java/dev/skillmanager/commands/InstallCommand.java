package dev.skillmanager.commands;

import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.Program;
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

        // Apply --registry BEFORE resolution so the graph is built against
        // the same registry the program's ConfigureRegistry effect will
        // persist. Otherwise the override only takes effect for the next
        // command and the current install resolves against the old URL.
        // Skipped on dry-run to avoid persisting state during a no-op.
        if (!dryRun && registryUrl != null && !registryUrl.isBlank()) {
            dev.skillmanager.registry.RegistryConfig.resolve(store, registryUrl);
        }

        Log.step("resolving %s", source);
        Resolver resolver = new Resolver(store);
        ResolvedGraph graph = resolver.resolve(source, version);

        // Resolve gateway URL — the Program's EnsureGateway effect handles
        // the actual probe + start + persist; gateway resolution itself
        // doesn't side-effect.
        GatewayConfig gw = GatewayConfig.resolve(store, null);

        Program<InstallUseCase.Report> program = InstallUseCase.buildProgram(
                gw, registryUrl, graph, dryRun);
        InstallUseCase.Report report;
        if (dryRun) {
            report = new DryRunInterpreter().run(program);
            return 0;
        }
        // Live install runs through Executor: each successful effect
        // records its compensation, and a FAILED downstream effect (e.g.
        // gateway register fails after a clean commit) walks the journal
        // back so no half-applied state survives. Outcome.rolledBack
        // surfaces in the warn line; the report's committed list is
        // already empty after rollback (DeleteUnitDir compensations
        // deleted what CommitUnitsToStore put down), so the existing
        // exit-code path naturally returns 4.
        dev.skillmanager.effects.Executor.Outcome<InstallUseCase.Report> outcome =
                new dev.skillmanager.effects.Executor(store, gw).run(program);
        report = outcome.result();
        if (outcome.rolledBack()) {
            Log.warn("install rolled back %d effect(s) — no partial state retained",
                    outcome.applied().size());
        }
        // Renderer printed every user-facing line; the only command-level
        // decision left is the exit code. Install succeeds when the skill
        // committed — post-commit failures (MCP register, transitive install,
        // agent sync) are tracked as outstanding errors on the source record
        // for the reconciler to retry. Exit 4 is reserved for "nothing
        // committed" (commit failed or program halted before commit).
        return report.committed().isEmpty() ? 4 : 0;
    }
}
