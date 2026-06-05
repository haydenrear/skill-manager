package dev.skillmanager.effects;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.source.InstalledUnit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;

public final class SourceProvenanceRecorderTest {

    public static int run() throws Exception {
        return Tests.suite("SourceProvenanceRecorderTest")
                .test("local skill with bundled published name keeps local provenance outside bundled install root", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path sourceRoot = Files.createTempDirectory("local-bundled-name-");
                        Skill skill = UnitFixtures.scaffoldSkill(
                                sourceRoot.resolve("ordinary-local"),
                                "skill-manager",
                                DepSpec.empty());
                        ResolvedGraph graph = new ResolvedGraph();
                        graph.add(new ResolvedGraph.Resolved(
                                "skill-manager",
                                "0.1.0",
                                skill.sourcePath().toString(),
                                ResolvedGraph.SourceKind.LOCAL,
                                skill.sourcePath(),
                                0L,
                                null,
                                skill.asUnit(),
                                false,
                                List.of()));

                        new Executor(h.store(), null).runWithContext(new Program<>(
                                "local-bundled-name-provenance",
                                List.of(
                                        new SkillEffect.CommitUnitsToStore(graph),
                                        new SkillEffect.RecordSourceProvenance(graph)),
                                receipts -> null), h.context());

                        InstalledUnit installed = h.sourceOf("skill-manager").orElseThrow();
                        assertEquals(InstalledUnit.Kind.LOCAL_DIR, installed.kind(),
                                "normal local source is not converted to bundled git provenance");
                        assertEquals(skill.sourcePath().toAbsolutePath().normalize().toString(),
                                Path.of(installed.origin()).toAbsolutePath().normalize().toString(),
                                "origin remains the user-selected local source");
                        assertFalse(installed.origin().contains("haydenrear/skill-manager-skill"),
                                "origin is not rewritten to bundled GitHub remote");
                    }
                })
                .test("lookalike bundled local tree keeps local provenance without onboard registration", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path sourceRoot = Files.createTempDirectory("lookalike-bundled-root-");
                        Skill skill = scaffoldSkillAt(
                                sourceRoot.resolve("skill-manager-skill"),
                                "skill-manager");
                        scaffoldSkillAt(sourceRoot.resolve("skill-publisher-skill"), "skill-publisher");
                        scaffoldSkillAt(sourceRoot.resolve("skill-dev-skill"), "skill-dev-skill");

                        ResolvedGraph graph = graphFor(skill);
                        new Executor(h.store(), null).runWithContext(new Program<>(
                                "lookalike-bundled-provenance",
                                List.of(
                                        new SkillEffect.CommitUnitsToStore(graph),
                                        new SkillEffect.RecordSourceProvenance(graph)),
                                receipts -> null), h.context());

                        InstalledUnit installed = h.sourceOf("skill-manager").orElseThrow();
                        assertEquals(InstalledUnit.Kind.LOCAL_DIR, installed.kind(),
                                "lookalike local source is not converted to bundled git provenance");
                        assertEquals(skill.sourcePath().toAbsolutePath().normalize().toString(),
                                Path.of(installed.origin()).toAbsolutePath().normalize().toString(),
                                "origin remains the user-selected lookalike local source");
                    }
                })
                .test("registered onboard local bundled source records bundled github provenance", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path sourceRoot = Files.createTempDirectory("registered-bundled-root-");
                        Skill skill = scaffoldSkillAt(
                                sourceRoot.resolve("skill-manager-skill"),
                                "skill-manager");
                        h.context().registerBundledLocalSource("skill-manager", skill.sourcePath());

                        ResolvedGraph graph = graphFor(skill);
                        new Executor(h.store(), null).runWithContext(new Program<>(
                                "registered-bundled-provenance",
                                List.of(
                                        new SkillEffect.CommitUnitsToStore(graph),
                                        new SkillEffect.RecordSourceProvenance(graph)),
                                receipts -> null), h.context());

                        InstalledUnit installed = h.sourceOf("skill-manager").orElseThrow();
                        assertEquals(InstalledUnit.Kind.GIT, installed.kind(),
                                "registered onboard local source is converted to git provenance");
                        assertEquals("https://github.com/haydenrear/skill-manager-skill.git",
                                installed.origin(),
                                "origin is the bundled upstream");
                    }
                })
                .runAll();
    }

    private static ResolvedGraph graphFor(Skill skill) {
        ResolvedGraph graph = new ResolvedGraph();
        graph.add(new ResolvedGraph.Resolved(
                skill.name(),
                "0.1.0",
                skill.sourcePath().toString(),
                ResolvedGraph.SourceKind.LOCAL,
                skill.sourcePath(),
                0L,
                null,
                skill.asUnit(),
                false,
                List.of()));
        return graph;
    }

    private static Skill scaffoldSkillAt(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SkillParser.SKILL_FILENAME), """
                ---
                name: %s
                description: %s fixture
                ---
                Body of %s.
                """.formatted(name, name, name));
        Files.writeString(dir.resolve(SkillParser.TOML_FILENAME), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "%s fixture"
                """.formatted(name, name));
        return SkillParser.load(dir);
    }
}
