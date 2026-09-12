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
                .test("a home inside a git checkout does not repoint that checkout's origin", () -> {
                    // THE INCIDENT, end to end. A Skill Manager home resolved
                    // inside a git working tree (a run-scoped store under a
                    // repository), one unit installed from a local path that is
                    // also inside that tree. Provenance recording used to walk
                    // up from the unit dir, decide the unit "was" a git repo,
                    // and `git remote set-url origin <the source dir>` — on the
                    // ENCLOSING repository. The value left behind was the last
                    // curated unit's source directory, and the next push would
                    // have gone into it.
                    //
                    // The repository below is built fresh under the system temp
                    // dir. Pointing this at a real checkout would be the defect,
                    // not a test of it.
                    Path root = Files.createTempDirectory("home-inside-checkout-");
                    Path repo = root.resolve("enclosing");
                    Files.createDirectories(repo);
                    git(repo, "init", "-q", "-b", "main");
                    git(repo, "remote", "add", "origin", "https://example.invalid/enclosing.git");

                    Path source = repo.resolve("constituents/tracer-agent");
                    Skill skill = scaffoldSkillAt(source, "tracer-agent");

                    try (TestHarness h = TestHarness.createIn(repo.resolve("build/run/.skill-manager"))) {
                        ResolvedGraph graph = graphFor(skill);
                        new Executor(h.store(), null).runWithContext(new Program<>(
                                "home-inside-checkout-provenance",
                                List.of(
                                        new SkillEffect.CommitUnitsToStore(graph),
                                        new SkillEffect.RecordSourceProvenance(graph)),
                                receipts -> null), h.context());
                    }

                    assertEquals("https://example.invalid/enclosing.git", configuredOrigin(repo),
                            "the enclosing repository's origin is untouched by an install");

                    InstalledUnit installed = new dev.skillmanager.source.UnitStore(
                            new dev.skillmanager.store.SkillStore(repo.resolve("build/run/.skill-manager")))
                            .read("tracer-agent").orElseThrow();
                    assertEquals(InstalledUnit.Kind.LOCAL_DIR, installed.kind(),
                            "a unit dir that is not its own repository is local, not git");
                })

                .runAll();
    }

    /**
     * The origin in {@code repo}'s own config file, read without git — so the
     * assertion cannot be satisfied by the same walk-up it is checking for.
     */
    private static String configuredOrigin(Path repo) throws Exception {
        Path config = repo.resolve(".git/config");
        if (!Files.isRegularFile(config)) return null;
        boolean inOrigin = false;
        for (String line : Files.readString(config).split("\\R")) {
            String t = line.trim();
            if (t.startsWith("[")) {
                inOrigin = t.startsWith("[remote \"origin\"]");
                continue;
            }
            if (inOrigin && t.startsWith("url")) return t.substring(t.indexOf('=') + 1).trim();
        }
        return null;
    }

    private static void git(Path dir, String... args) throws Exception {
        java.util.ArrayList<String> argv = new java.util.ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        Process p = new ProcessBuilder(argv).directory(dir.toFile())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed");
        }
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
