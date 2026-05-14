package dev.skillmanager.model;

import dev.skillmanager._lib.fixtures.ContainedSkillSpec;
import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The plugin's {@link AgentUnit} surface ({@code cliDependencies()},
 * {@code mcpDependencies()}, {@code references()}) returns the
 * <em>union</em> of plugin-level entries plus every contained skill's
 * entries. This is the parser-time invariant that lets every
 * downstream effect handler treat plugins and skills uniformly:
 * the planner sees one flat dep list per dimension, regardless of
 * whether any individual entry was declared at the plugin root or
 * inside one of its contained skills.
 *
 * <p>Substitutability lemma exercised here: a bare skill carrying
 * {@code DepSpec X} produces the same {@code AgentUnit} surface as
 * a plugin whose only contained skill carries {@code DepSpec X} (and
 * which has no plugin-level deps). This is the building block for
 * the planner's plan-shape invariance test in ticket 05.
 */
public final class EffectiveDepUnionTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("effective-dep-union-test-");

        return Tests.suite("EffectiveDepUnionTest")
                .test("plugin-level + one contained skill: deps unioned", () -> {
                    DepSpec pluginLevel = DepSpec.of()
                            .cli("pip:cowsay==6.0")
                            .mcp("plugin-mcp")
                            .build();
                    ContainedSkillSpec contained = new ContainedSkillSpec(
                            "echo",
                            DepSpec.of()
                                    .cli("pip:requests==2.31")
                                    .mcp("contained-mcp")
                                    .build()
                    );
                    PluginUnit unit = UnitFixtures.scaffoldPlugin(tmp, "p1", pluginLevel, contained);

                    assertSize(2, unit.cliDependencies(), "CLI: 1 plugin-level + 1 contained");
                    Set<String> cliNames = nameSet(unit.cliDependencies(), CliDependency::name);
                    assertTrue(cliNames.contains("cowsay"), "plugin-level CLI present");
                    assertTrue(cliNames.contains("requests"), "contained CLI present");

                    assertSize(2, unit.mcpDependencies(), "MCP: 1 plugin-level + 1 contained");
                    Set<String> mcpNames = nameSet(unit.mcpDependencies(), McpDependency::name);
                    assertTrue(mcpNames.contains("plugin-mcp"), "plugin-level MCP present");
                    assertTrue(mcpNames.contains("contained-mcp"), "contained MCP present");
                })
                .test("plugin-level only: contained-skill list is empty union contributor", () -> {
                    DepSpec pluginLevel = DepSpec.of()
                            .cli("pip:black==24.3.0")
                            .build();
                    PluginUnit unit = UnitFixtures.scaffoldPlugin(tmp, "p2", pluginLevel);
                    assertSize(1, unit.cliDependencies(), "only plugin-level CLI");
                    assertSize(0, unit.containedSkills(), "no contained skills");
                })
                .test("contained-only: plugin's view is the contained-skill's view", () -> {
                    ContainedSkillSpec contained = new ContainedSkillSpec(
                            "lone",
                            DepSpec.of().cli("pip:rich==13.7").build()
                    );
                    PluginUnit unit = UnitFixtures.scaffoldPlugin(tmp, "p3", DepSpec.empty(), contained);
                    assertSize(1, unit.cliDependencies(), "single CLI from contained");
                    assertEquals("rich", unit.cliDependencies().get(0).name(), "exposed via plugin");
                })
                .test("scoped npm CLI dep derives the package name, not an empty name", () -> {
                    Skill skill = UnitFixtures.scaffoldSkill(tmp, "scoped-npm",
                            DepSpec.of().cli("npm:@google/gemini-cli").build());
                    assertSize(1, skill.cliDependencies(), "one CLI dep");
                    assertEquals("@google/gemini-cli", skill.cliDependencies().get(0).name(),
                            "scoped npm package name");
                })
                .test("multiple contained skills: union order is plugin-level then sorted-by-dirname", () -> {
                    ContainedSkillSpec a = new ContainedSkillSpec("a-skill",
                            DepSpec.of().cli("pip:a==1.0").build());
                    ContainedSkillSpec b = new ContainedSkillSpec("b-skill",
                            DepSpec.of().cli("pip:b==1.0").build());
                    DepSpec pluginLevel = DepSpec.of().cli("pip:p==1.0").build();
                    PluginUnit unit = UnitFixtures.scaffoldPlugin(tmp, "p4", pluginLevel, a, b);
                    assertSize(3, unit.cliDependencies(), "1 plugin-level + 2 contained");
                    assertEquals("p", unit.cliDependencies().get(0).name(), "plugin-level first");
                    // Contained skills iterate in sorted order.
                    assertEquals("a", unit.cliDependencies().get(1).name(), "a-skill before b-skill");
                    assertEquals("b", unit.cliDependencies().get(2).name(), "b-skill last");
                })
                .test("references unioned across plugin-level and contained skills", () -> {
                    // Both refs use the bare-name form; parseCoord's `plugin:`
                    // prefix lands in ticket 02. The substitutability claim
                    // here is about the *union*, not coord parsing.
                    DepSpec pluginLevel = DepSpec.of().ref("p-ref").build();
                    ContainedSkillSpec contained = new ContainedSkillSpec(
                            "ref-er",
                            DepSpec.of().ref("skill:s-ref").build()
                    );
                    PluginUnit unit = UnitFixtures.scaffoldPlugin(tmp, "p5", pluginLevel, contained);
                    assertSize(2, unit.references(), "two references");
                    Set<String> refNames = new HashSet<>();
                    for (UnitReference r : unit.references()) refNames.add(r.name());
                    assertTrue(refNames.contains("p-ref"), "plugin-level ref unioned");
                    assertTrue(refNames.contains("s-ref"), "contained ref unioned");
                })
                .test("substitutability: skill DepSpec X ≡ plugin with one contained carrying X (deps surface)", () -> {
                    DepSpec spec = DepSpec.of()
                            .cli("pip:cowsay==6.0")
                            .mcp("shared-mcp")
                            .ref("skill:other")
                            .build();
                    AgentUnit asSkill = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp, "sub-s", spec);
                    AgentUnit asPlugin = UnitFixtures.buildEquivalent(UnitKind.PLUGIN, tmp, "sub-p", spec);

                    assertEquals(asSkill.cliDependencies().size(),
                            asPlugin.cliDependencies().size(), "CLI dep count matches");
                    assertEquals(nameSet(asSkill.cliDependencies(), CliDependency::name),
                            nameSet(asPlugin.cliDependencies(), CliDependency::name), "CLI names match");
                    assertEquals(asSkill.mcpDependencies().size(),
                            asPlugin.mcpDependencies().size(), "MCP dep count matches");
                    assertEquals(nameSet(asSkill.mcpDependencies(), McpDependency::name),
                            nameSet(asPlugin.mcpDependencies(), McpDependency::name), "MCP names match");
                    assertEquals(asSkill.references().size(),
                            asPlugin.references().size(), "ref count matches");
                })
                .runAll();
    }

    private static <T> Set<String> nameSet(List<T> list, java.util.function.Function<T, String> name) {
        Set<String> out = new HashSet<>();
        for (T t : list) out.add(name.apply(t));
        return out;
    }
}
