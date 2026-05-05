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
                assertEquals(EffectStatus.HALTED, r.status(),
                        k + ": RejectIfAlreadyInstalled HALTED when present");
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
