package dev.skillmanager.effects;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.source.InstalledUnit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-09b contract: every state-mutating {@link SkillEffect} that runs
 * cleanly under the {@link Executor} produces at least one
 * {@link Compensation} via {@link Executor#compensationsFor} or
 * {@link Executor#preStateCompensations}, so a downstream failure walks
 * the journal back to the pre-program state.
 *
 * <p>The pairing table from ticket 09:
 * <pre>
 * CommitUnitsToStore     → DeleteUnitDir (per committed unit, kind-aware)
 * RecordSourceProvenance → DeleteInstalledUnit (per resolved unit)
 * RunCliInstall          → UninstallCliIfOrphan
 * RegisterMcpServer      → UnregisterMcpIfOrphan
 * RegisterMcp (batch)    → UnregisterMcpIfOrphan per registered (unit, dep)
 * SyncAgents             → UnprojectIfOrphan per unit that synced
 * AddUnitError /         → RestoreInstalledUnit (prior exists) or
 *  ClearUnitError /        DeleteInstalledUnit (no prior) — pre-state
 *  OnboardUnit             snapshot taken before the effect runs
 * </pre>
 *
 * <p>Effects that don't get a compensation are deliberately exempt:
 * read-only checks ({@code RejectIfAlreadyInstalled}, {@code SnapshotMcpDeps}),
 * config writes that are tiny + idempotent ({@code ConfigureRegistry},
 * {@code ConfigureGateway}, {@code InitializePolicy}), bookkeeping
 * ({@code BuildInstallPlan}, {@code RecordAuditPlan}, {@code PrintInstalledSummary},
 * {@code LoadOutstandingErrors}), gateway lifecycle that can't be cleanly
 * un-spawned ({@code EnsureGateway}, {@code StopGateway}), tool installers
 * we don't tear down ({@code SetupPackageManagerRuntime},
 * {@code InstallPackageManager}, {@code EnsureTool}), per-graph cleanup
 * ({@code CleanupResolvedGraph}), and effects with handler-internal
 * rollback that doesn't fit the journal model ({@code SyncGit},
 * {@code SyncFromLocalDir} — both stash/abort/pop themselves on conflict).
 *
 * <p>{@code RunInstallPlan} expands into per-action sub-effects that each
 * get their own compensation through the journal — so the bulk
 * effect itself doesn't need a compensation entry. {@code InstallTools} /
 * {@code InstallCli} / orphan-unregister effects are themselves
 * compensation-shaped (they undo or batch-process; the Executor wires
 * granular-level rollback through the per-action effects).
 */
public final class CompensationPairingTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("CompensationPairingTest");

        suite.test("CommitUnitsToStore → DeleteUnitDir per committed (kind-aware)", () -> {
            Path tmp = Files.createTempDirectory("pairing-commit-");
            ResolvedGraph graph = singleton(tmp, "alpha", UnitKind.SKILL);
            SkillEffect.CommitUnitsToStore e = new SkillEffect.CommitUnitsToStore(graph);
            EffectReceipt r = EffectReceipt.ok(e, new ContextFact.SkillCommitted("alpha"));

            List<Compensation> comps = Executor.compensationsFor(e, r);
            assertEquals(1, comps.size(), "1 compensation per committed unit");
            assertTrue(comps.get(0) instanceof Compensation.DeleteUnitDir, "DeleteUnitDir shape");
            assertEquals(UnitKind.SKILL, ((Compensation.DeleteUnitDir) comps.get(0)).kind(), "kind preserved");
        });

        suite.test("RecordSourceProvenance → DeleteInstalledUnit per resolved", () -> {
            Path tmp = Files.createTempDirectory("pairing-prov-");
            ResolvedGraph graph = singleton(tmp, "beta", UnitKind.PLUGIN);
            SkillEffect.RecordSourceProvenance e = new SkillEffect.RecordSourceProvenance(graph);
            EffectReceipt r = EffectReceipt.ok(e, new ContextFact.ProvenanceRecorded(1));

            List<Compensation> comps = Executor.compensationsFor(e, r);
            assertEquals(1, comps.size(), "1 compensation per resolved unit");
            assertTrue(comps.get(0) instanceof Compensation.DeleteInstalledUnit, "DeleteInstalledUnit shape");
            assertEquals("beta", ((Compensation.DeleteInstalledUnit) comps.get(0)).unitName(), "carries unit name");
        });

        suite.test("RunCliInstall → UninstallCliIfOrphan", () -> {
            CliDependency dep = pip("cowsay", "6.0");
            SkillEffect.RunCliInstall e = new SkillEffect.RunCliInstall("widget", dep);
            EffectReceipt r = EffectReceipt.ok(e, new ContextFact.CliInstalled("widget", "cowsay", "pip"));

            List<Compensation> comps = Executor.compensationsFor(e, r);
            assertEquals(1, comps.size(), "1 compensation");
            assertTrue(comps.get(0) instanceof Compensation.UninstallCliIfOrphan, "UninstallCliIfOrphan shape");
        });

        suite.test("RegisterMcpServer → UnregisterMcpIfOrphan", () -> {
            McpDependency mcp = mcpDep("srv-a");
            GatewayConfig gw = GatewayConfig.of(java.net.URI.create("http://127.0.0.1:51717"));
            SkillEffect.RegisterMcpServer e = new SkillEffect.RegisterMcpServer("widget", mcp, gw);
            EffectReceipt r = EffectReceipt.ok(e,
                    new ContextFact.McpServerRegistered("widget",
                            dev.skillmanager.mcp.InstallResult.registered("srv-a", "global", "ok")));

            List<Compensation> comps = Executor.compensationsFor(e, r);
            assertEquals(1, comps.size(), "1 compensation");
            assertTrue(comps.get(0) instanceof Compensation.UnregisterMcpIfOrphan, "UnregisterMcpIfOrphan shape");
        });

        suite.test("SyncAgents → UnprojectIfOrphan per unit that synced", () -> {
            Path tmp = Files.createTempDirectory("pairing-sync-");
            AgentUnit u = dev.skillmanager._lib.fixtures.UnitFixtures.buildEquivalent(
                    UnitKind.SKILL, tmp, "widget", dev.skillmanager._lib.fixtures.DepSpec.empty());
            GatewayConfig gw = GatewayConfig.of(java.net.URI.create("http://127.0.0.1:51717"));
            SkillEffect.SyncAgents e = new SkillEffect.SyncAgents(List.of(u), gw);
            EffectReceipt r = EffectReceipt.ok(e,
                    new ContextFact.AgentSkillSynced("claude", "widget"));

            List<Compensation> comps = Executor.compensationsFor(e, r);
            assertEquals(1, comps.size(), "one per unit that synced");
            assertTrue(comps.get(0) instanceof Compensation.UnprojectIfOrphan, "UnprojectIfOrphan shape");
        });

        // ----------------------------------------------- preStateCompensations

        suite.test("preState(AddUnitError) on existing record → RestoreInstalledUnit", () -> {
            TestHarness h = TestHarness.create();
            h.seedUnit("widget", UnitKind.SKILL);
            InstalledUnit before = h.sourceOf("widget").orElseThrow();

            SkillEffect.AddUnitError e = new SkillEffect.AddUnitError(
                    "widget", InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE, "msg");
            List<Compensation> pre = Executor.preStateCompensations(e, h.context());

            assertEquals(1, pre.size(), "one pre-state snapshot");
            assertTrue(pre.get(0) instanceof Compensation.RestoreInstalledUnit, "RestoreInstalledUnit shape");
            assertEquals(before.name(), ((Compensation.RestoreInstalledUnit) pre.get(0)).previous().name(),
                    "carries the pre-image");
        });

        suite.test("preState(AddUnitError) on absent record → DeleteInstalledUnit", () -> {
            TestHarness h = TestHarness.create();  // no seedUnit — record absent
            SkillEffect.AddUnitError e = new SkillEffect.AddUnitError(
                    "ghost", InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE, "msg");
            List<Compensation> pre = Executor.preStateCompensations(e, h.context());

            assertEquals(1, pre.size(), "one pre-state shape");
            assertTrue(pre.get(0) instanceof Compensation.DeleteInstalledUnit,
                    "DeleteInstalledUnit when no prior record exists");
        });

        suite.test("preState(OnboardUnit) on absent record → DeleteInstalledUnit", () -> {
            TestHarness h = TestHarness.create();
            Path tmp = Files.createTempDirectory("pairing-onboard-");
            AgentUnit u = dev.skillmanager._lib.fixtures.UnitFixtures.buildEquivalent(
                    UnitKind.SKILL, tmp, "fresh", dev.skillmanager._lib.fixtures.DepSpec.empty());

            SkillEffect.OnboardUnit e = new SkillEffect.OnboardUnit(u);
            List<Compensation> pre = Executor.preStateCompensations(e, h.context());

            assertEquals(1, pre.size(), "one pre-state shape");
            assertTrue(pre.get(0) instanceof Compensation.DeleteInstalledUnit,
                    "DeleteInstalledUnit — onboard adds a new record");
        });

        suite.test("preState(OnboardUnit) on existing record → empty (effect skips)", () -> {
            TestHarness h = TestHarness.create();
            h.seedUnit("widget", UnitKind.SKILL);
            Path tmp = Files.createTempDirectory("pairing-onboard-skip-");
            AgentUnit u = dev.skillmanager._lib.fixtures.UnitFixtures.buildEquivalent(
                    UnitKind.SKILL, tmp, "widget", dev.skillmanager._lib.fixtures.DepSpec.empty());

            SkillEffect.OnboardUnit e = new SkillEffect.OnboardUnit(u);
            List<Compensation> pre = Executor.preStateCompensations(e, h.context());

            assertEquals(0, pre.size(),
                    "empty — onboard handler skips when record already present, so no rollback shape needed");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- helpers

    private static ResolvedGraph singleton(Path tmp, String name, UnitKind kind) {
        AgentUnit u = dev.skillmanager._lib.fixtures.UnitFixtures.buildEquivalent(
                kind, tmp, name, dev.skillmanager._lib.fixtures.DepSpec.empty());
        ResolvedGraph g = new ResolvedGraph();
        g.add(new ResolvedGraph.Resolved(
                name, "0.1.0", name, ResolvedGraph.SourceKind.LOCAL,
                u.sourcePath(), 0L, null, u, false, List.of()));
        return g;
    }

    private static CliDependency pip(String name, String version) {
        return new CliDependency(name, "pip:" + name + "==" + version,
                null, null, null, true, java.util.Map.of());
    }

    private static McpDependency mcpDep(String name) {
        McpDependency.DockerLoad load = new McpDependency.DockerLoad(
                "ghcr.io/example/" + name + ":latest", true, null,
                List.of(), List.of(), java.util.Map.of(), List.of(), null, null);
        return new McpDependency(name, name, name + " fixture", load,
                List.of(), java.util.Map.of(), List.of(), null, null);
    }
}
