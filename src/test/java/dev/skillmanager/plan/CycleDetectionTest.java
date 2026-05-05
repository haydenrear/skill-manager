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
import java.util.List;
import java.util.Optional;

import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Contract #7 — heterogeneous cycles across plugin and skill nodes are
 * caught at plan time. Covers all four chain types: skill→skill,
 * skill→plugin, plugin→skill, plugin→plugin, and mixed chains.
 */
public final class CycleDetectionTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("cycle-detection-test-");

        return Tests.suite("CycleDetectionTest")
                .test("skill→skill cycle detected", () -> {
                    Skill sa = UnitFixtures.scaffoldSkill(tmp.resolve("r1"), "sa",
                            DepSpec.of().ref("sb").build());
                    Skill sb = UnitFixtures.scaffoldSkill(tmp.resolve("r1"), "sb",
                            DepSpec.of().ref("sa").build());
                    Optional<List<String>> cycle = PlanBuilder.detectCycle(List.of(sa.asUnit(), sb.asUnit()));
                    assertTrue(cycle.isPresent(), "skill→skill cycle detected");
                    assertTrue(cycle.get().size() >= 2, "chain has at least 2 nodes");
                })
                .test("skill→plugin cycle detected", () -> {
                    Skill ss = UnitFixtures.scaffoldSkill(tmp.resolve("r2"), "ss",
                            DepSpec.of().ref("plugin:pp").build());
                    PluginUnit pp = UnitFixtures.scaffoldPlugin(tmp.resolve("r2"), "pp",
                            DepSpec.of().ref("skill:ss").build());
                    Optional<List<String>> cycle = PlanBuilder.detectCycle(List.of(ss.asUnit(), pp));
                    assertTrue(cycle.isPresent(), "skill→plugin cycle detected");
                })
                .test("plugin→skill cycle detected", () -> {
                    PluginUnit pa = UnitFixtures.scaffoldPlugin(tmp.resolve("r3"), "pa",
                            DepSpec.of().ref("skill:ps").build());
                    Skill ps = UnitFixtures.scaffoldSkill(tmp.resolve("r3"), "ps",
                            DepSpec.of().ref("plugin:pa").build());
                    Optional<List<String>> cycle = PlanBuilder.detectCycle(List.of(pa, ps.asUnit()));
                    assertTrue(cycle.isPresent(), "plugin→skill cycle detected");
                })
                .test("plugin→plugin cycle detected", () -> {
                    PluginUnit pA = UnitFixtures.scaffoldPlugin(tmp.resolve("r4"), "pA",
                            DepSpec.of().ref("plugin:pB").build());
                    PluginUnit pB = UnitFixtures.scaffoldPlugin(tmp.resolve("r4"), "pB",
                            DepSpec.of().ref("plugin:pA").build());
                    Optional<List<String>> cycle = PlanBuilder.detectCycle(List.of(pA, pB));
                    assertTrue(cycle.isPresent(), "plugin→plugin cycle detected");
                })
                .test("mixed chain cycle detected (s1→p1→s2→p2→s1)", () -> {
                    Skill s1 = UnitFixtures.scaffoldSkill(tmp.resolve("r5"), "s1",
                            DepSpec.of().ref("plugin:p1").build());
                    PluginUnit p1 = UnitFixtures.scaffoldPlugin(tmp.resolve("r5"), "p1",
                            DepSpec.of().ref("skill:s2").build());
                    Skill s2 = UnitFixtures.scaffoldSkill(tmp.resolve("r5"), "s2",
                            DepSpec.of().ref("plugin:p2").build());
                    PluginUnit p2 = UnitFixtures.scaffoldPlugin(tmp.resolve("r5"), "p2",
                            DepSpec.of().ref("skill:s1").build());
                    Optional<List<String>> cycle = PlanBuilder.detectCycle(
                            List.of(s1.asUnit(), p1, s2.asUnit(), p2));
                    assertTrue(cycle.isPresent(), "mixed chain cycle detected");
                })
                .test("no cycle in linear chain (a→b→c)", () -> {
                    Skill a = UnitFixtures.scaffoldSkill(tmp.resolve("r6"), "a",
                            DepSpec.of().ref("b").build());
                    Skill b = UnitFixtures.scaffoldSkill(tmp.resolve("r6"), "b",
                            DepSpec.of().ref("c").build());
                    Skill c = UnitFixtures.scaffoldSkill(tmp.resolve("r6"), "c", DepSpec.empty());
                    Optional<List<String>> cycle = PlanBuilder.detectCycle(
                            List.of(a.asUnit(), b.asUnit(), c.asUnit()));
                    assertFalse(cycle.isPresent(), "no cycle in a→b→c");
                })
                .test("self-reference is caught as a cycle", () -> {
                    Skill self = UnitFixtures.scaffoldSkill(tmp.resolve("r7"), "self",
                            DepSpec.of().ref("self").build());
                    Optional<List<String>> cycle = PlanBuilder.detectCycle(List.of(self.asUnit()));
                    assertTrue(cycle.isPresent(), "self-reference is a cycle");
                })
                .test("plan() throws PlanCycleException on cyclic input; chain preserved", () -> {
                    Skill x = UnitFixtures.scaffoldSkill(tmp.resolve("r8"), "x",
                            DepSpec.of().ref("y").build());
                    Skill y = UnitFixtures.scaffoldSkill(tmp.resolve("r8"), "y",
                            DepSpec.of().ref("x").build());
                    PlanBuilder pb = new PlanBuilder(Policy.defaults());
                    try {
                        pb.plan(List.of(x.asUnit(), y.asUnit()), false, false, tmp.resolve("bin"));
                        throw new AssertionError("expected PlanCycleException");
                    } catch (PlanCycleException e) {
                        assertTrue(e.chain().contains("x") || e.chain().contains("y"),
                                "exception chain mentions cycle participants");
                        assertTrue(e.getMessage().contains("→"), "message uses → separator");
                    }
                })
                .runAll();
    }
}
