package dev.skillmanager.commands;

import dev.skillmanager.app.RemoveUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * {@code skill-manager uninstall <name>} — full removal counterpart to
 * {@link InstallCommand}. Drives a {@link RemoveUseCase} program with
 * {@code agentsToUnlink == null} (= all known agents) and (by default)
 * orphan-MCP unregistration on.
 */
@Command(name = "uninstall", aliases = "un",
        description = "Uninstall a skill: clear store entry, all agent symlinks, and orphan MCP servers.")
public final class UninstallCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Skill name")
    String name;

    @Option(names = "--keep-mcp",
            description = "Don't unregister orphan MCP servers from the gateway.")
    boolean keepMcp;

    @Option(names = "--dry-run",
            description = "Print the effects the program would run without mutating filesystem or gateway.")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        // Kind-agnostic existence check — uninstall has to work for plugins
        // too, and plugins live under plugins/<name>, not skills/<name>.
        if (!store.containsUnit(name)) {
            Log.warn("unit not found: %s", name);
            return 1;
        }
        GatewayConfig gw = GatewayConfig.resolve(store, null);
        Program<RemoveUseCase.Report> program = RemoveUseCase.buildProgram(
                store, gw, name, /*agentsToUnlink=*/null, /*unregisterMcp=*/!keepMcp);
        RemoveUseCase.Report report;
        if (dryRun) {
            report = new DryRunInterpreter().run(program);
        } else {
            // Uninstall runs through Executor: a mid-program failure (e.g.
            // gateway unreachable when unregistering orphan MCP servers
            // after the store dir was already deleted) walks the journal
            // back. Without rollback the user would be left with the unit
            // gone but the gateway still claiming its MCP server.
            Executor.Outcome<RemoveUseCase.Report> outcome =
                    new Executor(store, gw).run(program);
            report = outcome.result();
            if (outcome.rolledBack()) {
                Log.warn("uninstall rolled back %d effect(s) — store + agent + gateway state restored",
                        outcome.applied().size());
            }
        }
        return report.errorCount() == 0 ? 0 : 4;
    }
}
