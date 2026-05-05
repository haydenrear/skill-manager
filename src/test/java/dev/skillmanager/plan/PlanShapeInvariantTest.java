package dev.skillmanager.plan;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.policy.Policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;

/**
 * Contract #1 — equivalent {@link DepSpec} produces an identical plan
 * shape regardless of {@link UnitKind}. The only expected difference is
 * the {@code kind} field on {@link PlanAction.InstallUnitIntoStore}.
 */
public final class PlanShapeInvariantTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("plan-shape-invariant-test-");
        Path binDir = tmp.resolve("bin");

        return Tests.suite("PlanShapeInvariantTest")
                .test("skill and plugin with cli+mcp dep produce same action-count shapes", () -> {
                    DepSpec deps = DepSpec.of().cli("pip:cowsay==6.0").mcp("srv").build();
                    AgentUnit skill = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp.resolve("r1"), "widget", deps);
                    AgentUnit plugin = UnitFixtures.buildEquivalent(UnitKind.PLUGIN, tmp.resolve("r2"), "widget", deps);
                    PlanBuilder pb = new PlanBuilder(Policy.defaults());

                    InstallPlan planA = pb.plan(List.of(skill), true, true, binDir);
                    InstallPlan planB = pb.plan(List.of(plugin), true, true, binDir);

                    assertEquals(count(planA, PlanAction.InstallUnitIntoStore.class),
                            count(planB, PlanAction.InstallUnitIntoStore.class), "store action count");
                    assertEquals(count(planA, PlanAction.RunCliInstall.class),
                            count(planB, PlanAction.RunCliInstall.class), "cli action count");
                    assertEquals(count(planA, PlanAction.RegisterMcpServer.class),
                            count(planB, PlanAction.RegisterMcpServer.class), "mcp action count");
                })
                .test("store action carries SKILL kind for skill, PLUGIN kind for plugin", () -> {
                    DepSpec deps = DepSpec.of().cli("pip:cowsay==6.0").build();
                    AgentUnit skill = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp.resolve("r3"), "w2", deps);
                    AgentUnit plugin = UnitFixtures.buildEquivalent(UnitKind.PLUGIN, tmp.resolve("r4"), "w2", deps);
                    PlanBuilder pb = new PlanBuilder(Policy.defaults());

                    InstallPlan planA = pb.plan(List.of(skill), false, false, binDir);
                    InstallPlan planB = pb.plan(List.of(plugin), false, false, binDir);

                    PlanAction.InstallUnitIntoStore storeA = firstOf(planA, PlanAction.InstallUnitIntoStore.class);
                    PlanAction.InstallUnitIntoStore storeB = firstOf(planB, PlanAction.InstallUnitIntoStore.class);
                    assertEquals(UnitKind.SKILL, storeA.kind(), "skill plan carries SKILL kind");
                    assertEquals(UnitKind.PLUGIN, storeB.kind(), "plugin plan carries PLUGIN kind");
                })
                .test("cli dep name is stable across skill and plugin variants", () -> {
                    DepSpec deps = DepSpec.of().cli("pip:mylib==1.0").build();
                    AgentUnit skill = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp.resolve("r5"), "w3", deps);
                    AgentUnit plugin = UnitFixtures.buildEquivalent(UnitKind.PLUGIN, tmp.resolve("r6"), "w3", deps);
                    PlanBuilder pb = new PlanBuilder(Policy.defaults());

                    InstallPlan planA = pb.plan(List.of(skill), true, false, binDir);
                    InstallPlan planB = pb.plan(List.of(plugin), true, false, binDir);

                    PlanAction.RunCliInstall cliA = firstOf(planA, PlanAction.RunCliInstall.class);
                    PlanAction.RunCliInstall cliB = firstOf(planB, PlanAction.RunCliInstall.class);
                    assertEquals(cliA.dep().name(), cliB.dep().name(), "cli dep name same across kinds");
                })
                .test("no-dep unit: plan has exactly one action (store)", () -> {
                    AgentUnit skill = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp.resolve("r7"), "w4", DepSpec.empty());
                    AgentUnit plugin = UnitFixtures.buildEquivalent(UnitKind.PLUGIN, tmp.resolve("r8"), "w4", DepSpec.empty());
                    PlanBuilder pb = new PlanBuilder(Policy.defaults());

                    InstallPlan planA = pb.plan(List.of(skill), true, true, binDir);
                    InstallPlan planB = pb.plan(List.of(plugin), true, true, binDir);

                    assertEquals(1, planA.actions().size(), "skill: one action (store only)");
                    assertEquals(1, planB.actions().size(), "plugin: one action (store only)");
                })
                .runAll();
    }

    private static <T extends PlanAction> int count(InstallPlan plan, Class<T> type) {
        int n = 0;
        for (PlanAction a : plan.actions()) if (type.isInstance(a)) n++;
        return n;
    }

    @SuppressWarnings("unchecked")
    private static <T extends PlanAction> T firstOf(InstallPlan plan, Class<T> type) {
        for (PlanAction a : plan.actions()) if (type.isInstance(a)) return (T) a;
        throw new AssertionError("no action of type " + type.getSimpleName() + " in plan");
    }
}
