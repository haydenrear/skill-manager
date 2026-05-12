package dev.skillmanager.commands;

import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionKind;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager rebind <bindingId> --to <newRoot>} reverses
 * the existing binding's projections and rebuilds them under a new
 * target root. Atomic in the journal sense: a failure mid-rebind
 * walks every applied effect back, leaving the original binding
 * untouched.
 */
@Command(name = "rebind",
        description = "Move a binding to a new target root (unbind + bind in one journal).")
public final class RebindCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Binding id")
    String bindingId;

    @Option(names = "--to", required = true,
            description = "New target root.")
    String newRoot;

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
        Binding old = located.get().binding();
        Path newTarget = Path.of(expandHome(newRoot)).toAbsolutePath().normalize();

        // Reverse the old projections + drop the old row, then create a
        // new one pointing at newTarget. The new row reuses the old
        // bindingId so external references stay valid.
        List<SkillEffect> effects = new ArrayList<>();
        List<Projection> ordered = new ArrayList<>(old.projections());
        Collections.reverse(ordered);
        for (Projection p : ordered) {
            effects.add(new SkillEffect.UnmaterializeProjection(p));
        }
        effects.add(new SkillEffect.RemoveBinding(unitName, old.bindingId()));

        // Build the new projections — only whole-unit SYMLINK supported
        // here; sub-element rebinds defer to #48 / #47.
        if (old.subElement() != null) {
            Log.error("sub-element rebinds not yet supported (pending #48 / #47)");
            return 2;
        }
        Path source = store.unitDir(unitName, old.unitKind());
        Path dest = newTarget.resolve(unitName);
        Projection sym = new Projection(old.bindingId(), source, dest, ProjectionKind.SYMLINK, null);
        effects.add(new SkillEffect.MaterializeProjection(sym, old.conflictPolicy()));

        Binding next = new Binding(
                old.bindingId(), old.unitName(), old.unitKind(),
                old.subElement(), newTarget, old.conflictPolicy(),
                BindingStore.nowIso(), old.source(), List.of(sym));
        effects.add(new SkillEffect.CreateBinding(next));

        Program<Void> program = new Program<>("rebind-" + bindingId, effects, receipts -> null);
        GatewayConfig gw = GatewayConfig.resolve(store, null);
        if (dryRun) {
            new DryRunInterpreter(store).run(program);
            return 0;
        }
        Executor.Outcome<Void> outcome = new Executor(store, gw).run(program);
        if (outcome.rolledBack()) {
            Log.error("rebind rolled back %d effect(s) — original binding preserved",
                    outcome.applied().size());
            return 4;
        }
        return 0;
    }

    private static String expandHome(String path) {
        if (path == null) return null;
        if (path.equals("~") || path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
