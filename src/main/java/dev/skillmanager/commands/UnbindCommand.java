package dev.skillmanager.commands;

import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager unbind <bindingId>} reverses every projection
 * the binding produced and drops the row from the per-unit ledger.
 * Backups are restored in reverse order — symlink first, then the
 * {@code RENAMED_ORIGINAL_BACKUP} move-back so the original
 * destination contents are back where they started.
 */
@Command(name = "unbind",
        description = "Reverse every projection from a binding and remove it from the ledger.")
public final class UnbindCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Binding id (from `bindings list`)")
    String bindingId;

    @Option(names = "--dry-run",
            description = "Print the effects without touching the filesystem or ledger.")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        BindingStore bs = new BindingStore(store);
        var located = bs.findById(bindingId);
        if (located.isEmpty()) {
            Log.error("no binding with id %s", bindingId);
            return 1;
        }
        String unitName = located.get().unitName();
        Binding b = located.get().binding();

        // Reverse projections in LIFO order so backups come back last:
        // SYMLINK first (delete the link), then RENAMED_ORIGINAL_BACKUP
        // (move backup back into place).
        List<SkillEffect> effects = new ArrayList<>();
        List<Projection> ordered = new ArrayList<>(b.projections());
        Collections.reverse(ordered);
        for (Projection p : ordered) {
            effects.add(new SkillEffect.UnmaterializeProjection(p));
        }
        effects.add(new SkillEffect.RemoveBinding(unitName, b.bindingId()));

        Program<Void> program = new Program<>("unbind-" + bindingId, effects, receipts -> null);
        GatewayConfig gw = GatewayConfig.resolve(store, null);
        if (dryRun) {
            new DryRunInterpreter(store).run(program);
            return 0;
        }
        Executor.Outcome<Void> outcome = new Executor(store, gw).run(program);
        if (outcome.rolledBack()) {
            Log.error("unbind rolled back %d effect(s)", outcome.applied().size());
            return 4;
        }
        return 0;
    }
}
