package dev.skillmanager.lifecycle;

import dev.skillmanager.app.ReconcileUseCase;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

/**
 * Thin façade over {@link ReconcileUseCase}: builds the reconcile Program
 * and runs it through {@link LiveInterpreter}, plus the post-command
 * outstanding-errors banner. The real work — onboarding missing source
 * records and validating each {@link InstalledUnit.UnitError} — lives in
 * the use case so it shares the effect / interpreter pipeline with
 * install / sync / upgrade.
 */
public final class SkillReconciler {

    private SkillReconciler() {}

    public static void reconcile(SkillStore store, GatewayConfig gw) {
        // One-time legacy directory migration: sources/ → installed/.
        // Idempotent — finds nothing to do on subsequent runs.
        try {
            int migrated = UnitStore.migrateFromLegacy(store);
            if (migrated > 0) Log.info("reconcile: migrated %d legacy source records", migrated);
        } catch (Throwable t) {
            Log.warn("legacy source-record migration failed: %s", t.getMessage());
        }

        try {
            Program<ReconcileUseCase.Report> program = ReconcileUseCase.buildProgram(store);
            if (program.effects().isEmpty()) return;
            ReconcileUseCase.Report report = new LiveInterpreter(store, gw).run(program);
            if (report.onboarded() > 0 || report.cleared() > 0) {
                Log.info("reconcile: onboarded=%d cleared=%d", report.onboarded(), report.cleared());
            }
        } catch (Throwable t) {
            Log.warn("reconcile failed: %s", t.getMessage());
        }
    }

}
