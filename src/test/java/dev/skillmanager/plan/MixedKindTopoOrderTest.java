package dev.skillmanager.plan;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.PluginUnit;
import dev.skillmanager.model.Skill;
import dev.skillmanager.policy.Policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;

/**
 * Mixed plugin+skill install sets are topologically ordered in the plan:
 * dependencies appear before dependents in the STORE section. Covers
 * plugin-depends-on-skill, skill-depends-on-plugin, and multi-level chains.
 */
public final class MixedKindTopoOrderTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("mixed-topo-order-test-");
        Path binDir = tmp.resolve("bin");

        return Tests.suite("MixedKindTopoOrderTest")
                .test("plugin depending on skill: skill STORE action precedes plugin STORE action", () -> {
                    // p1 depends on s1; passed in wrong order [p1, s1]
                    PluginUnit p1 = UnitFixtures.scaffoldPlugin(tmp.resolve("r1"), "p1",
                            DepSpec.of().ref("skill:s1").build());
                    Skill s1 = UnitFixtures.scaffoldSkill(tmp.resolve("r1"), "s1", DepSpec.empty());
                    PlanBuilder pb = new PlanBuilder(Policy.defaults());

                    InstallPlan plan = pb.plan(List.of((AgentUnit) p1, s1.asUnit()), false, false, binDir);

                    List<PlanAction.InstallUnitIntoStore> stores = storeActions(plan);
                    assertEquals(2, stores.size(), "two store actions");
                    assertEquals("s1", stores.get(0).name(), "s1 installed first (dependency)");
                    assertEquals("p1", stores.get(1).name(), "p1 installed second (dependent)");
                })
                .test("skill depending on plugin: plugin STORE action precedes skill STORE action", () -> {
                    // s2 depends on p2; passed in wrong order [s2, p2]
                    Skill s2 = UnitFixtures.scaffoldSkill(tmp.resolve("r2"), "s2",
                            DepSpec.of().ref("plugin:p2").build());
                    PluginUnit p2 = UnitFixtures.scaffoldPlugin(tmp.resolve("r2"), "p2", DepSpec.empty());
                    PlanBuilder pb = new PlanBuilder(Policy.defaults());

                    InstallPlan plan = pb.plan(List.of(s2.asUnit(), (AgentUnit) p2), false, false, binDir);

                    List<PlanAction.InstallUnitIntoStore> stores = storeActions(plan);
                    assertEquals(2, stores.size(), "two store actions");
                    assertEquals("p2", stores.get(0).name(), "p2 installed first (dependency)");
                    assertEquals("s2", stores.get(1).name(), "s2 installed second (dependent)");
                })
                .test("3-node chain A→B→C ordered [C, B, A] regardless of input order", () -> {
                    // a depends on b (via skill:b), b depends on c (via plugin:c); input [a, b, c]
                    PluginUnit a = UnitFixtures.scaffoldPlugin(tmp.resolve("r3"), "a",
                            DepSpec.of().ref("skill:b").build());
                    Skill b = UnitFixtures.scaffoldSkill(tmp.resolve("r3"), "b",
                            DepSpec.of().ref("plugin:c").build());
                    PluginUnit c = UnitFixtures.scaffoldPlugin(tmp.resolve("r3"), "c", DepSpec.empty());
                    PlanBuilder pb = new PlanBuilder(Policy.defaults());

                    // Build with AgentUnit list
                    List<AgentUnit> units = new ArrayList<>();
                    units.add(a);
                    units.add(b.asUnit());
                    units.add(c);
                    InstallPlan plan = pb.plan(units, false, false, binDir);

                    List<PlanAction.InstallUnitIntoStore> stores = storeActions(plan);
                    assertEquals(3, stores.size(), "three store actions");
                    assertEquals("c", stores.get(0).name(), "c first (leaf dep)");
                    assertEquals("b", stores.get(1).name(), "b second");
                    assertEquals("a", stores.get(2).name(), "a last (root)");
                })
                .test("independent units preserve input order within their tier", () -> {
                    // x and y have no deps — input order should be preserved
                    Skill x = UnitFixtures.scaffoldSkill(tmp.resolve("r4"), "x", DepSpec.empty());
                    PluginUnit y = UnitFixtures.scaffoldPlugin(tmp.resolve("r4"), "y", DepSpec.empty());
                    PlanBuilder pb = new PlanBuilder(Policy.defaults());

                    InstallPlan plan = pb.plan(List.of(x.asUnit(), (AgentUnit) y), false, false, binDir);

                    List<PlanAction.InstallUnitIntoStore> stores = storeActions(plan);
                    assertEquals(2, stores.size(), "two store actions");
                    assertEquals("x", stores.get(0).name(), "x first (input order)");
                    assertEquals("y", stores.get(1).name(), "y second (input order)");
                })
                .runAll();
    }

    private static List<PlanAction.InstallUnitIntoStore> storeActions(InstallPlan plan) {
        List<PlanAction.InstallUnitIntoStore> out = new ArrayList<>();
        for (PlanAction a : plan.actions()) {
            if (a instanceof PlanAction.InstallUnitIntoStore s) out.add(s);
        }
        return out;
    }
}
