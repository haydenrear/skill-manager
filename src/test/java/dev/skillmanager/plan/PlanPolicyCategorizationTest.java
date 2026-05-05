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

import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * {@link PlanBuilder#categorize} produces the expected display lines.
 * {@code +} lines describe what gets installed; {@code !} lines flag
 * categories requiring review. Display-only this ticket; gating is
 * ticket 12.
 */
public final class PlanPolicyCategorizationTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("plan-policy-categorization-test-");
        Path binDir = tmp.resolve("bin");

        return Tests.suite("PlanPolicyCategorizationTest")
                .test("skill unit produces '+ SKILLS' line containing the unit name", () -> {
                    Skill s = UnitFixtures.scaffoldSkill(tmp.resolve("r1"), "echo", DepSpec.empty());
                    AgentUnit unit = s.asUnit();
                    InstallPlan plan = new PlanBuilder(Policy.defaults())
                            .plan(List.of(unit), false, false, binDir);

                    List<String> lines = PlanBuilder.categorize(List.of(unit), plan);
                    assertTrue(lines.stream().anyMatch(l -> l.contains("+ SKILLS") && l.contains("echo")),
                            "produces '+ SKILLS echo'");
                })
                .test("plugin unit produces '+ AGENTS' line containing the unit name", () -> {
                    PluginUnit p = UnitFixtures.scaffoldPlugin(tmp.resolve("r2"), "repo-intel", DepSpec.empty());
                    InstallPlan plan = new PlanBuilder(Policy.defaults())
                            .plan(List.of((AgentUnit) p), false, false, binDir);

                    List<String> lines = PlanBuilder.categorize(List.of(p), plan);
                    assertTrue(lines.stream().anyMatch(l -> l.contains("+ AGENTS") && l.contains("repo-intel")),
                            "produces '+ AGENTS repo-intel'");
                })
                .test("mixed install produces both '+ SKILLS' and '+ AGENTS' lines", () -> {
                    Skill s = UnitFixtures.scaffoldSkill(tmp.resolve("r3"), "ech", DepSpec.empty());
                    PluginUnit p = UnitFixtures.scaffoldPlugin(tmp.resolve("r3"), "rep", DepSpec.empty());
                    List<AgentUnit> units = List.of(s.asUnit(), (AgentUnit) p);
                    InstallPlan plan = new PlanBuilder(Policy.defaults())
                            .plan(units, false, false, binDir);

                    List<String> lines = PlanBuilder.categorize(units, plan);
                    assertTrue(lines.stream().anyMatch(l -> l.startsWith("+ SKILLS")), "+ SKILLS present");
                    assertTrue(lines.stream().anyMatch(l -> l.startsWith("+ AGENTS")), "+ AGENTS present");
                })
                .test("unit with CLI dep produces '! CLI' line", () -> {
                    Skill s = UnitFixtures.scaffoldSkill(tmp.resolve("r4"), "cow",
                            DepSpec.of().cli("pip:cowsay==6.0").build());
                    AgentUnit unit = s.asUnit();
                    InstallPlan plan = new PlanBuilder(Policy.defaults())
                            .plan(List.of(unit), true, false, binDir);

                    List<String> lines = PlanBuilder.categorize(List.of(unit), plan);
                    assertTrue(lines.stream().anyMatch(l -> l.equals("! CLI")), "! CLI present");
                    assertFalse(lines.stream().anyMatch(l -> l.equals("! MCP")), "! MCP absent");
                })
                .test("unit with MCP dep produces '! MCP' line", () -> {
                    Skill s = UnitFixtures.scaffoldSkill(tmp.resolve("r5"), "srv",
                            DepSpec.of().mcp("my-srv").build());
                    AgentUnit unit = s.asUnit();
                    InstallPlan plan = new PlanBuilder(Policy.defaults())
                            .plan(List.of(unit), false, true, binDir);

                    List<String> lines = PlanBuilder.categorize(List.of(unit), plan);
                    assertTrue(lines.stream().anyMatch(l -> l.equals("! MCP")), "! MCP present");
                    assertFalse(lines.stream().anyMatch(l -> l.equals("! CLI")), "! CLI absent");
                })
                .test("no-dep unit has no '!' warning lines", () -> {
                    Skill s = UnitFixtures.scaffoldSkill(tmp.resolve("r6"), "plain", DepSpec.empty());
                    AgentUnit unit = s.asUnit();
                    InstallPlan plan = new PlanBuilder(Policy.defaults())
                            .plan(List.of(unit), true, true, binDir);

                    List<String> lines = PlanBuilder.categorize(List.of(unit), plan);
                    assertFalse(lines.stream().anyMatch(l -> l.startsWith("!")),
                            "no '!' lines for no-dep unit");
                    assertTrue(lines.stream().anyMatch(l -> l.startsWith("+")),
                            "has '+' categorization line");
                })
                .runAll();
    }
}
