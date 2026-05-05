package dev.skillmanager.model;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * {@link SkillUnit} is a thin pass-through over {@link Skill}. Every
 * accessor on the unit interface must return the underlying skill's
 * field byte-for-byte. This test pins that contract: if anyone
 * adds a transformation layer in {@code SkillUnit}, this fails.
 *
 * <p>Also pins {@link Skill#asUnit()} as the canonical bridge between
 * the legacy {@code Skill} record and the new {@link AgentUnit}
 * surface. Existing skill code that calls {@code Skill} accessors
 * keeps working unchanged; new code that consumes {@code AgentUnit}
 * goes through {@code asUnit()}.
 */
public final class SkillUnitWrapsSkillTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("skill-unit-wraps-test-");

        return Tests.suite("SkillUnitWrapsSkillTest")
                .test("Skill.asUnit returns a SkillUnit wrapping this", () -> {
                    Skill skill = UnitFixtures.scaffoldSkill(tmp, "wraps", DepSpec.empty());
                    SkillUnit unit = skill.asUnit();
                    assertNotNull(unit, "asUnit non-null");
                    assertTrue(unit.skill() == skill, "wraps the same Skill instance");
                    assertEquals(UnitKind.SKILL, unit.kind(), "kind is SKILL");
                })
                .test("every accessor delegates to the underlying skill", () -> {
                    DepSpec deps = DepSpec.of()
                            .cli("pip:cowsay==6.0")
                            .mcp("a-mcp")
                            .ref("skill:other")
                            .build();
                    Skill skill = UnitFixtures.scaffoldSkill(tmp, "delegates", deps);
                    SkillUnit unit = skill.asUnit();
                    assertEquals(skill.name(), unit.name(), "name");
                    assertEquals(skill.version(), unit.version(), "version");
                    assertEquals(skill.description(), unit.description(), "description");
                    assertEquals(skill.sourcePath(), unit.sourcePath(), "sourcePath");
                    assertEquals(skill.cliDependencies(), unit.cliDependencies(), "cliDependencies");
                    assertEquals(skill.mcpDependencies(), unit.mcpDependencies(), "mcpDependencies");
                    assertEquals(skill.skillReferences(), unit.references(), "references");
                })
                .test("AgentUnit polymorphism: SkillUnit and PluginUnit both legal", () -> {
                    Skill skill = UnitFixtures.scaffoldSkill(tmp, "poly-s", DepSpec.empty());
                    PluginUnit plugin = UnitFixtures.scaffoldPlugin(tmp, "poly-p", DepSpec.empty());
                    AgentUnit asSkill = skill.asUnit();
                    AgentUnit asPlugin = plugin;
                    assertEquals(UnitKind.SKILL, asSkill.kind(), "skill kind");
                    assertEquals(UnitKind.PLUGIN, asPlugin.kind(), "plugin kind");
                })
                .test("existing skill behavior unchanged after wrapping", () -> {
                    // Sanity: the underlying Skill record is unmodified by the wrap.
                    Skill skill = UnitFixtures.scaffoldSkill(tmp, "unmodified", DepSpec.empty());
                    Skill recheck = skill.asUnit().skill();
                    assertEquals(skill, recheck, "Skill records equal");
                    assertSize(skill.cliDependencies().size(), recheck.cliDependencies(),
                            "cli deps count unchanged");
                })
                .runAll();
    }
}
