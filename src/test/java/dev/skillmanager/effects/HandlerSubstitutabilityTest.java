package dev.skillmanager.effects;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-06 substitutability contract: every leaf effect renamed from
 * {@code skillName} to {@code unitName} behaves identically regardless of
 * {@link UnitKind}. The handlers do not dispatch on kind — the rename is the
 * concrete proof that the field abstracted away the skill-specific label.
 *
 * <p>Sweep: {@code (UnitKind × leaf-effect variant)}. Each cell runs in its
 * own fresh {@link TestHarness} so mutation from one case never bleeds into
 * another.
 */
public final class HandlerSubstitutabilityTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("HandlerSubstitutabilityTest");

        for (UnitKind kind : UnitKind.values()) {
            // Handler substitutability is a SKILL × PLUGIN parity contract.
            // DOC and HARNESS fixtures use different scaffold paths (no
            // deps) so the shared sweep doesn't apply. Their handler
            // coverage lives in BindingsTest / DocRepoTest / HarnessTest.
            if (kind == UnitKind.DOC || kind == UnitKind.HARNESS) continue;
            String k = kind.name().toLowerCase();

            suite.test(k + " — AddUnitError writes ErrorAdded fact", () -> {
                TestHarness h = TestHarness.create();
                h.seedUnit("widget", kind);
                EffectReceipt r = h.run(new SkillEffect.AddUnitError(
                        "widget", InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE, "unreachable"));
                assertEquals(EffectStatus.OK, r.status(), k + ": status");
                assertTrue(
                        r.facts().stream().anyMatch(f ->
                                f instanceof ContextFact.ErrorAdded ea
                                        && "widget".equals(ea.skillName())
                                        && ea.kind() == InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE),
                        k + ": ErrorAdded fact present");
                assertTrue(
                        h.sourceOf("widget")
                                .map(u -> u.hasError(InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE))
                                .orElse(false),
                        k + ": error persisted to source record");
            });

            suite.test(k + " — ClearUnitError writes ErrorCleared fact", () -> {
                TestHarness h = TestHarness.create();
                h.seedUnit("widget", kind);
                h.run(new SkillEffect.AddUnitError(
                        "widget", InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE, "unreachable"));
                EffectReceipt r = h.run(new SkillEffect.ClearUnitError(
                        "widget", InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE));
                assertEquals(EffectStatus.OK, r.status(), k + ": status");
                assertTrue(
                        r.facts().stream().anyMatch(f ->
                                f instanceof ContextFact.ErrorCleared ec
                                        && "widget".equals(ec.skillName())
                                        && ec.kind() == InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE),
                        k + ": ErrorCleared fact present");
                assertFalse(
                        h.sourceOf("widget")
                                .map(u -> u.hasError(InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE))
                                .orElse(true),
                        k + ": error removed from source record");
            });

            suite.test(k + " — ValidateAndClearError(GATEWAY_UNAVAILABLE) keeps error (no probe)", () -> {
                TestHarness h = TestHarness.create();
                h.seedUnit("widget", kind);
                h.run(new SkillEffect.AddUnitError(
                        "widget", InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE, "unreachable"));
                EffectReceipt r = h.run(new SkillEffect.ValidateAndClearError(
                        "widget", InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE));
                assertEquals(EffectStatus.OK, r.status(), k + ": status");
                assertTrue(
                        r.facts().stream().anyMatch(f ->
                                f instanceof ContextFact.ErrorValidated ev
                                        && "widget".equals(ev.skillName())
                                        && ev.kind() == InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE
                                        && !ev.cleared()),
                        k + ": ErrorValidated(cleared=false) fact present");
            });

            suite.test(k + " — ValidateAndClearError(AGENT_SYNC_FAILED) keeps error (no probe)", () -> {
                TestHarness h = TestHarness.create();
                h.seedUnit("widget", kind);
                h.run(new SkillEffect.AddUnitError(
                        "widget", InstalledUnit.ErrorKind.AGENT_SYNC_FAILED, "agent err"));
                EffectReceipt r = h.run(new SkillEffect.ValidateAndClearError(
                        "widget", InstalledUnit.ErrorKind.AGENT_SYNC_FAILED));
                assertEquals(EffectStatus.OK, r.status(), k + ": status");
                assertTrue(
                        r.facts().stream().anyMatch(f ->
                                f instanceof ContextFact.ErrorValidated ev && !ev.cleared()),
                        k + ": ErrorValidated(cleared=false) for AGENT_SYNC_FAILED");
            });

            suite.test(k + " — ValidateAndClearError(AUTHENTICATION_NEEDED) keeps error (no probe)", () -> {
                // AUTHENTICATION_NEEDED is set by SyncGitHandler when the
                // registry rejects the cached bearer + refresh fails.
                // Probing here would cost a real registry round-trip per
                // unit; instead the error self-clears on the next
                // successful describeVersion (SyncGitHandler clears
                // both REGISTRY_UNAVAILABLE and AUTHENTICATION_NEEDED on
                // a Found result). Same no-probe semantics as the other
                // network-bound error kinds.
                TestHarness h = TestHarness.create();
                h.seedUnit("widget", kind);
                h.run(new SkillEffect.AddUnitError(
                        "widget", InstalledUnit.ErrorKind.AUTHENTICATION_NEEDED,
                        "registry refused cached credentials"));
                EffectReceipt r = h.run(new SkillEffect.ValidateAndClearError(
                        "widget", InstalledUnit.ErrorKind.AUTHENTICATION_NEEDED));
                assertEquals(EffectStatus.OK, r.status(), k + ": status");
                assertTrue(
                        r.facts().stream().anyMatch(f ->
                                f instanceof ContextFact.ErrorValidated ev && !ev.cleared()),
                        k + ": ErrorValidated(cleared=false) for AUTHENTICATION_NEEDED");
                assertTrue(
                        h.sourceOf("widget")
                                .map(u -> u.hasError(InstalledUnit.ErrorKind.AUTHENTICATION_NEEDED))
                                .orElse(false),
                        k + ": error still recorded on the source record");
            });

            suite.test(k + " — RejectIfAlreadyInstalled passes when unit absent from store", () -> {
                TestHarness h = TestHarness.create();
                EffectReceipt r = h.run(new SkillEffect.RejectIfAlreadyInstalled("no-such-unit"));
                assertEquals(EffectStatus.OK, r.status(),
                        k + ": RejectIfAlreadyInstalled OK when absent");
            });

            suite.test(k + " — RejectIfAlreadyInstalled HALTS when unit present on disk", () -> {
                TestHarness h = TestHarness.create();
                h.scaffoldUnitDir("widget", kind);
                EffectReceipt r = h.run(new SkillEffect.RejectIfAlreadyInstalled("widget"));
                // Cooperative halt — status stays OK (the precondition
                // check ran cleanly), continuation becomes HALT to stop
                // the program. Status / continuation split landed with
                // the Continuation refactor; before it was a single
                // overloaded HALTED status.
                assertEquals(EffectStatus.OK, r.status(),
                        k + ": RejectIfAlreadyInstalled OK status (cooperative halt)");
                assertEquals(dev.skillmanager.effects.Continuation.HALT, r.continuation(),
                        k + ": RejectIfAlreadyInstalled HALT continuation when present");
                String expectedDir = (kind == UnitKind.SKILL
                        ? h.store().skillDir("widget")
                        : h.store().pluginsDir().resolve("widget")).toString();
                assertTrue(r.errorMessage() != null && r.errorMessage().contains(expectedDir),
                        k + ": halt message points at on-disk location (" + expectedDir + ")");
            });
        }

        return suite.runAll();
    }
}
