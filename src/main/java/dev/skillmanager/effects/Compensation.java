package dev.skillmanager.effects;

import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;

/**
 * The inverse-side of an executed {@link SkillEffect}. A successful
 * {@link Executor} run records one or more {@code Compensation}s per
 * effect into a {@link RollbackJournal}; on a downstream failure the
 * journal is walked LIFO and each compensation is applied against the
 * shared {@link EffectContext}, restoring the store / gateway / agent
 * dirs to the pre-program state.
 *
 * <h3>Pairing table (ticket 09)</h3>
 *
 * <pre>
 * CommitUnitsToStore (per committed unit) → DeleteUnitDir(name, kind)
 * RecordSourceProvenance / OnboardUnit    → RestoreInstalledUnit(prev) or DeleteInstalledUnit(name)
 * RunCliInstall                           → UninstallCliIfOrphan(name, dep)
 * RegisterMcpServer                       → UnregisterMcpIfOrphan(name, dep, gw)
 * SyncAgents (per (agent, unit) project)  → UnprojectIfOrphan(name, kind)
 * </pre>
 *
 * <p>The {@code *IfOrphan} variants consult the live store at apply
 * time — if any surviving unit still claims the dep, the compensation
 * is a no-op. That's how a partial install of skill A doesn't tear
 * down skill B's CLI dependency just because B happened to install
 * the same dep first.
 *
 * <p>{@code SyncGit} and {@code SyncFromLocalDir} have their rollback
 * built into the handlers (stash/pop, merge-abort) — they don't get a
 * declarative compensation here. The journal still tracks effects
 * that ran <em>after</em> a sync committed (CLI install, MCP register,
 * etc.), so a downstream failure walks those back even though the
 * merge itself stays.
 */
public sealed interface Compensation permits
        Compensation.DeleteUnitDir,
        Compensation.RestoreInstalledUnit,
        Compensation.DeleteInstalledUnit,
        Compensation.UninstallCliIfOrphan,
        Compensation.UnregisterMcpIfOrphan,
        Compensation.UnprojectIfOrphan {

    /** Reverse of one {@link SkillEffect.CommitUnitsToStore} entry. */
    record DeleteUnitDir(String unitName, UnitKind kind) implements Compensation {}

    /** Restore an {@link InstalledUnit} record to its pre-program value. */
    record RestoreInstalledUnit(String unitName, InstalledUnit previous) implements Compensation {}

    /** Delete an {@link InstalledUnit} record entirely (no prior value to restore). */
    record DeleteInstalledUnit(String unitName) implements Compensation {}

    /**
     * Uninstall a CLI dep iff no surviving unit still claims it. The applier
     * scans {@link EffectContext#sources()} / {@code store.listInstalled()}
     * at apply time — by then, any committed-but-rolled-back unit is gone
     * from the store, so its claim doesn't keep the dep pinned.
     */
    record UninstallCliIfOrphan(String unitName, CliDependency dep) implements Compensation {}

    /** Unregister an MCP server with the gateway iff no surviving unit still declares it. */
    record UnregisterMcpIfOrphan(String unitName, McpDependency dep, GatewayConfig gateway)
            implements Compensation {}

    /**
     * Remove an agent's projection (symlink for SKILL, projector entry for
     * PLUGIN once ticket 11 lands) iff no surviving unit needs the
     * projection. Per-agent fan-out happens in the applier.
     */
    record UnprojectIfOrphan(String unitName, UnitKind kind) implements Compensation {}
}
