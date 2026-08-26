package dev.skillmanager.project;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.HomeCommand;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * DEF-096 — <b>a project home that declares units in its manifest either holds
 * them or says plainly that it does not.</b>
 *
 * <h2>The measurement</h2>
 *
 * <p>{@code skill-manager}'s own {@code skill-project.toml} declares
 * {@code [plugins.skt]} and {@code [skills.skill-manager]}. Read on 2026-08-24,
 * {@code <repo>/.skill-manager} held <b>neither</b> — it held
 * {@code deploy-helm}, {@code spec-double-compiler}, {@code test-graph} and
 * {@code tracing-observability} — and {@code projects/} was <b>empty</b>. Every
 * ticket worktree in the epic is a clone of that home, so twenty-two agents
 * worked without the two units documenting the thing they were working on, and
 * <b>no command anywhere said a word about it</b>.
 *
 * <h2>What is asserted, and what is deliberately not</h2>
 *
 * <p>Not "resolve the manifest": nothing here installs anything. The claim is
 * observability — the shortfall is stated, with the denominator, with what is
 * missing, and with the command that obtains it, on the surface where the
 * silence cost the most: {@code home clone}, which is how every worktree in
 * this epic acquired its home.
 *
 * <p>{@code CASE 3} is the half that keeps the reader honest. A home that holds
 * everything it declares reports NOTHING, and a home with no manifest at all
 * reports nothing either — a diagnostic that fires on every home is one an
 * operator stops reading, which is how the original silence would come back
 * wearing the opposite costume.
 */
public final class ProjectManifestRealizationTest {

    public static int run() throws Exception {
        return Tests.suite("ProjectManifestRealizationTest")

                // ------------------------------------------------------- CASE 1: the fix

                .test("a home short of what its own manifest declares says so, with the remedy", () -> {
                    Fixture f = Fixture.create("shortfall");
                    f.manifest("""
                            [project]
                            name = "shortfall-project"

                            [skills.present-unit]
                            source = "github:example/present-unit"

                            [plugins.absent-plugin]
                            source = "github:example/absent-plugin"

                            [skills.absent-skill]
                            source = "github:example/absent-skill"
                            """);
                    f.install("present-unit");

                    ProjectManifestRealization.Shortfall shortfall =
                            ProjectManifestRealization.inspect(f.home);

                    assertTrue(shortfall.hasManifest(), "the manifest beside the home was found");
                    assertEquals(3, shortfall.declared().size(), "three installable refs declared");
                    assertEquals(2, shortfall.missing().size(),
                            "and two of them are not here: " + shortfall.render());
                    assertFalse(shortfall.clean(), "so the home is not clean against its manifest");
                    assertContains(shortfall.summary(), "2 of 3",
                            "the sentence carries the denominator, not just a count");

                    String rendered = String.join("\n", shortfall.render());
                    assertContains(rendered, "plugin:absent-plugin", "the missing plugin is named");
                    assertContains(rendered, "skill:absent-skill", "and the missing skill");
                    assertContains(rendered, "github:example/absent-skill",
                            "with where the manifest says it comes from");
                    assertContains(rendered, "project resolve",
                            "and the command that obtains them");
                    assertFalse(rendered.contains("present-unit"),
                            "a unit the home holds is not a finding; got:\n" + rendered);
                })

                // ---------------------------- CASE 2: the surface the silence was measured on

                .test("home clone says the copy inherits the source's manifest shortfall", () -> {
                    Fixture f = Fixture.create("clone");
                    f.manifest("""
                            [project]
                            name = "clone-shortfall-project"

                            [plugins.absent-plugin]
                            source = "github:example/absent-plugin"
                            """);
                    f.install("present-unit");
                    Path to = f.root.resolve("worktree/.skill-manager");

                    Captured captured = clone(f.home.root(), to, false);

                    assertEquals(0, captured.rc, "the clone still succeeds: this is a report, not a gate");
                    assertContains(captured.err, "declared but absent",
                            "and it says what the copy is short — this is the sentence twenty-two "
                                    + "worktrees were never told; stderr was:\n" + captured.err);
                    assertContains(captured.err, "absent-plugin", "naming it");
                    assertContains(captured.err, "project resolve", "with the remedy");
                    assertFalse(Files.isDirectory(to.resolve("plugins/absent-plugin")),
                            "and it is genuinely short it — the report is about a real absence");

                    // JSON gets the same fact, and stdout stays exactly one
                    // document (the #235 purity rule this repo enforces).
                    Captured asJson = clone(f.home.root(), f.root.resolve("worktree2/.skill-manager"), true);
                    assertEquals(0, asJson.rc, "json mode succeeds too");
                    assertContains(asJson.out, "\"manifestShortfall\"", "the document carries it");
                    assertContains(asJson.out, "absent-plugin", "naming the unit");
                    assertEquals(1, (int) asJson.out.strip().lines().count(),
                            "and stdout is still exactly one line/document; got:\n" + asJson.out);
                })

                // -------------------------------- CASE 3: guard — silence when there is nothing to say

                .test("a realized home and a home with no manifest both report nothing", () -> {
                    Fixture realized = Fixture.create("realized");
                    realized.manifest("""
                            [project]
                            name = "realized-project"

                            [skills.present-unit]
                            source = "github:example/present-unit"
                            """);
                    realized.install("present-unit");
                    ProjectManifestRealization.Shortfall clean =
                            ProjectManifestRealization.inspect(realized.home);
                    assertTrue(clean.hasManifest(), "the manifest is there");
                    assertTrue(clean.clean(), "and nothing is missing: " + clean.render());
                    assertEquals(0, clean.render().size(), "so there is nothing to print");

                    // A root home: no manifest beside it, and it must not be
                    // reported as short of a manifest it does not have.
                    Fixture bare = Fixture.create("bare");
                    ProjectManifestRealization.Shortfall none =
                            ProjectManifestRealization.inspect(bare.home);
                    assertFalse(none.hasManifest(), "no manifest beside this home");
                    assertTrue(none.clean(), "and therefore nothing to say");
                    assertEquals(0, none.declared().size(), "nothing declared, nothing counted");
                })

                .runAll();
    }

    // ----------------------------------------------------------------- helpers

    private record Captured(int rc, String out, String err) {}

    private static Captured clone(Path from, Path to, boolean json) throws Exception {
        // Driven through picocli rather than by setting fields: this is the
        // operator's actual surface, and a test that reached past the parser
        // could pass while the flag it depends on was never wired.
        picocli.CommandLine cli = new picocli.CommandLine(new HomeCommand.CloneCmd());
        String[] args = json
                ? new String[] {"--from", from.toString(), "--to", to.toString(), "--json"}
                : new String[] {"--from", from.toString(), "--to", to.toString()};
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            return new Captured(cli.execute(args), out.toString(), err.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }

    /** A project checkout: {@code <repo>/skill-project.toml} beside {@code <repo>/.skill-manager}. */
    private static final class Fixture {
        final Path root;
        final Path repoRoot;
        final SkillStore home;

        private Fixture(Path root, Path repoRoot, SkillStore home) {
            this.root = root;
            this.repoRoot = repoRoot;
            this.home = home;
        }

        static Fixture create(String label) throws IOException {
            Path root = Files.createTempDirectory("manifest-realization-" + label + "-");
            Path repoRoot = Files.createDirectories(root.resolve("repo"));
            SkillStore home = new SkillStore(repoRoot.resolve(".skill-manager"));
            home.init();
            return new Fixture(root, repoRoot, home);
        }

        void manifest(String toml) throws IOException {
            Files.writeString(repoRoot.resolve("skill-project.toml"), toml);
        }

        void install(String unit) throws IOException {
            UnitFixtures.scaffoldSkill(home.skillsDir(), unit, DepSpec.empty());
            assertTrue(home.containsUnit(unit),
                    "fixture precondition: the home really holds " + unit);
        }
    }
}
