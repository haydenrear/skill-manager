package dev.skillmanager.commands;

import dev.skillmanager.app.RemoveUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "remove", aliases = "rm",
        description = "Remove an installed unit (skill or plugin) from the store. Lower-level "
                + "than `uninstall` — by default does not unlink agent symlinks or unregister MCP "
                + "servers. Use `uninstall` for the full cleanup.")
public final class RemoveCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Unit name (skill or plugin)")
    String name;

    @Option(names = "--from", description = "Also unlink from the given agent(s)", split = ",")
    List<String> unlink;

    @Option(names = "--dry-run",
            description = "Print the effects the program would run without mutating filesystem or gateway.")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        if (!store.containsUnit(name)) {
            Log.warn("unit not found: %s", name);
            return 1;
        }
        GatewayConfig gw = GatewayConfig.resolve(store, null);
        Program<RemoveUseCase.Report> program = RemoveUseCase.buildProgram(
                store, gw, name, unlink == null ? List.of() : unlink, /*unregisterMcp=*/true);
        ProgramInterpreter interpreter = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store, gw);
        RemoveUseCase.Report report = interpreter.run(program);
        return report.errorCount() == 0 ? 0 : 4;
    }
}
