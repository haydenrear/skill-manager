package dev.skillmanager.effects;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-09d contract: for every step index in a program, forcing the
 * effect at that step to FAIL must walk the journal back so the post-
 * rollback state matches the pre-program state.
 *
 * <p>The sweep uses the {@link Executor#withFaultInjection} test seam to
 * inject a synthetic FAILED receipt at a specific step, bypassing the
 * underlying handler. This isolates the rollback logic from the
 * variability of "what naturally fails in a real run" — every step gets
 * exercised, regardless of whether its real handler is easy to make
 * fail.
 *
 * <p>Coverage scopes:
 * <ol>
 *   <li><b>Synthetic chain</b> — a hand-built effect list that exercises
 *       the major compensation types (CommitUnitsToStore +
 *       RecordSourceProvenance + AddUnitError). Sweep injects failure at
 *       each step ≥ 1 and verifies the prior steps' mutations are
 *       reversed.</li>
 *   <li><b>Skill install + plugin install</b> — same chain across both
 *       UnitKinds, asserting the kind-aware rollback (DeleteUnitDir
 *       routes to the right tree) holds.</li>
 *   <li><b>Pre-existing-record restore</b> — when a unit has an
 *       installed-record before the program starts, RestoreInstalledUnit
 *       returns it to that exact pre-image after rollback.</li>
 * </ol>
 *
 * <p>Out of scope (deferred): InstallCommand / UpgradeCommand /
 * UninstallCommand end-to-end with real installers + gateway. Those
 * need the FakeProcessRunner / FakeGateway substrate the spec mentioned
 * in ticket 06's TestHarness; flagged for a follow-up after the
 * existing pipeline is fully gated. The current sweep covers the
 * contract that matters most: "no leaked state when an effect fails
 * mid-program".
 */
public final class FailureInjectionSweepTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("FailureInjectionSweepTest");

        for (UnitKind kind : UnitKind.values()) {
            String label = kind.name().toLowerCase();

            // Sweep failure at each non-zero step in the synthetic chain.
            // Step 0 (the first effect) being the failure means there's
            // nothing to roll back — that's a separate "no state mutation
            // ever happened" cell.
            for (int failAt = 1; failAt <= 2; failAt++) {
                final int target = failAt;
                suite.test(label + " — fail at step " + target + " of 3-effect chain → all prior mutations rolled back", () -> {
                    TestHarness h = TestHarness.create();
                    Path tmp = Files.createTempDirectory("failure-sweep-");
                    AgentUnit u = UnitFixtures.buildEquivalent(kind, tmp, "alpha", DepSpec.empty());
                    ResolvedGraph graph = singleton(u, kind);

                    List<SkillEffect> effects = List.of(
                            new SkillEffect.CommitUnitsToStore(graph),
                            new SkillEffect.RecordSourceProvenance(graph),
                            new SkillEffect.AddUnitError("alpha",
                                    InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE, "smoke")
                    );
                    Program<Void> program = new Program<>(
                            "sweep-" + target, effects, receipts -> null);

                    Executor.Outcome<Void> outcome = new Executor(h.store(), null)
                            .withFaultInjection(i -> i == target)
                            .runWithContext(program, h.context());

                    assertTrue(outcome.rolledBack(), "executor reports rollback");

                    // Pre-program state: nothing in the store, no installed-record.
                    // After rollback, both must hold again.
                    Path unitDir = h.store().unitDir("alpha", kind);
                    assertFalse(Files.exists(unitDir),
                            "unit dir gone (DeleteUnitDir compensation walked)");
                    assertFalse(new UnitStore(h.store()).read("alpha").isPresent(),
                            "installed-record gone (DeleteInstalledUnit compensation walked)");
                });
            }

            suite.test(label + " — fail at step 0 (first effect) → no prior steps, no leak", () -> {
                TestHarness h = TestHarness.create();
                Path tmp = Files.createTempDirectory("failure-sweep-step0-");
                AgentUnit u = UnitFixtures.buildEquivalent(kind, tmp, "alpha", DepSpec.empty());
                ResolvedGraph graph = singleton(u, kind);

                List<SkillEffect> effects = List.of(new SkillEffect.CommitUnitsToStore(graph));
                Program<Void> program = new Program<>("sweep-0", effects, receipts -> null);

                Executor.Outcome<Void> outcome = new Executor(h.store(), null)
                        .withFaultInjection(i -> i == 0)
                        .runWithContext(program, h.context());

                assertTrue(outcome.rolledBack(), "rollback fired");
                assertEquals(0, outcome.applied().size(),
                        "nothing to walk back — failure was the first effect");
                Path unitDir = h.store().unitDir("alpha", kind);
                assertFalse(Files.exists(unitDir), "unit dir never written (or fault prevented commit)");
            });
        }

        // ----------------------------------- pre-existing record is restored

        suite.test("pre-existing installed-record → AddUnitError + fail → record restored to pre-image", () -> {
            TestHarness h = TestHarness.create();
            h.seedUnit("widget", UnitKind.SKILL);
            InstalledUnit before = new UnitStore(h.store()).read("widget").orElseThrow();
            assertEquals(0, before.errors().size(), "precondition: no errors");

            // Two-step chain: AddUnitError (succeeds), then a fault at step 1.
            // The journal should have a RestoreInstalledUnit(prev=before) shape
            // for the AddUnitError pre-state, plus an ErrorAdded compensation
            // (none — AddUnitError doesn't have a post-state compensation;
            // restoration via pre-state covers it).
            //
            // Step 1 is a no-op AddUnitError on "widget" with a different
            // error kind, set up to fault before it runs.
            List<SkillEffect> effects = List.of(
                    new SkillEffect.AddUnitError("widget",
                            InstalledUnit.ErrorKind.AGENT_SYNC_FAILED, "first"),
                    new SkillEffect.AddUnitError("widget",
                            InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE, "second")
            );
            Program<Void> program = new Program<>("sweep-restore", effects, receipts -> null);

            Executor.Outcome<Void> outcome = new Executor(h.store(), null)
                    .withFaultInjection(i -> i == 1)
                    .runWithContext(program, h.context());

            assertTrue(outcome.rolledBack(), "rollback fired");
            InstalledUnit after = new UnitStore(h.store()).read("widget").orElseThrow();
            assertEquals(0, after.errors().size(),
                    "errors removed — RestoreInstalledUnit walked the pre-image back");
        });

        // ----------------------------------- multi-unit commit rollback

        suite.test("multi-unit commit + fail → every committed unit's dir removed", () -> {
            TestHarness h = TestHarness.create();
            Path tmp = Files.createTempDirectory("failure-sweep-multi-");
            AgentUnit a = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp.resolve("a"), "alpha", DepSpec.empty());
            AgentUnit b = UnitFixtures.buildEquivalent(UnitKind.PLUGIN, tmp.resolve("b"), "beta", DepSpec.empty());
            ResolvedGraph graph = new ResolvedGraph();
            graph.add(new ResolvedGraph.Resolved(
                    "alpha", "0.1.0", "alpha", ResolvedGraph.SourceKind.LOCAL,
                    a.sourcePath(), 0L, null, a, false, List.of()));
            graph.add(new ResolvedGraph.Resolved(
                    "beta", "0.1.0", "beta", ResolvedGraph.SourceKind.LOCAL,
                    b.sourcePath(), 0L, null, b, false, List.of()));

            List<SkillEffect> effects = List.of(
                    new SkillEffect.CommitUnitsToStore(graph),
                    new SkillEffect.RecordSourceProvenance(graph)
            );
            Program<Void> program = new Program<>("sweep-multi", effects, receipts -> null);

            Executor.Outcome<Void> outcome = new Executor(h.store(), null)
                    .withFaultInjection(i -> i == 1)
                    .runWithContext(program, h.context());

            assertTrue(outcome.rolledBack(), "rollback fired");
            assertFalse(Files.exists(h.store().skillDir("alpha")),
                    "skill alpha torn down");
            assertFalse(Files.exists(h.store().pluginsDir().resolve("beta")),
                    "plugin beta torn down (kind-aware DeleteUnitDir)");
        });

        // ----------------------------------- happy path: no fault, no rollback

        suite.test("clean run (no fault) → no rollback, mutations persist", () -> {
            TestHarness h = TestHarness.create();
            Path tmp = Files.createTempDirectory("failure-sweep-clean-");
            AgentUnit u = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp, "alpha", DepSpec.empty());
            ResolvedGraph graph = singleton(u, UnitKind.SKILL);

            List<SkillEffect> effects = List.of(new SkillEffect.CommitUnitsToStore(graph));
            Program<Void> program = new Program<>("sweep-clean", effects, receipts -> null);

            Executor.Outcome<Void> outcome = new Executor(h.store(), null)
                    .runWithContext(program, h.context());

            assertFalse(outcome.rolledBack(), "no rollback on clean run");
            assertTrue(Files.isDirectory(h.store().skillDir("alpha")),
                    "skill committed and persists");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- helpers

    private static ResolvedGraph singleton(AgentUnit unit, UnitKind kind) {
        ResolvedGraph g = new ResolvedGraph();
        g.add(new ResolvedGraph.Resolved(
                unit.name(), "0.1.0", unit.name(), ResolvedGraph.SourceKind.LOCAL,
                unit.sourcePath(), 0L, null, unit, false, List.of()));
        return g;
    }
}
