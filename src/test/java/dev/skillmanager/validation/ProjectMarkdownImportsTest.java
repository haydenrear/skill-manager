package dev.skillmanager.validation;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * <b>A skill project's OWN markdown imports are validated.</b>
 *
 * <p>{@code ValidateMarkdownImports} was emitted only by {@code InstallUseCase},
 * {@code SyncUseCase}, {@code OnboardCommand} and {@code PublishCommand}, and
 * {@link MarkdownImportValidator#validateInstalled} walks INSTALLED UNIT ROOTS.
 * A skill project checkout is not a unit root, so a project whose own
 * {@code CLAUDE.md} imported a skill was checked by nothing: measured, with
 * that import broken to name both a missing unit and a missing path,
 * {@code project resolve --skip-gateway} exited 0 and a grep for "import" or
 * "violation" over the whole log returned nothing.
 *
 * <h2>The companions</h2>
 *
 * <ol>
 *   <li><b>Rejecting the mutation for the wrong reason.</b> Malformed YAML or a
 *       missing {@code reason} key would also produce a violation, and a
 *       validator that only caught those would look fixed.
 *       <br>Both planted mutations here are well-formed and complete, so the
 *       only thing wrong with either is the reference — one names a missing
 *       UNIT, one names a present unit and a missing PATH. Both must be named,
 *       and the count is asserted exactly.</li>
 *   <li><b>Passing because the walk found nothing.</b> "No violations" is the
 *       state a walk that skipped everything reports.
 *       <br>The valid-imports case asserts zero violations over a project whose
 *       files DO carry imports and DO resolve — and the scope case below proves
 *       the walk reaches {@code docs/**}{@code .md} and not the agent
 *       directories, by planting a broken import in each and requiring exactly
 *       one of them to be reported.</li>
 * </ol>
 */
public final class ProjectMarkdownImportsTest {

    public static int run() throws Exception {
        return Tests.suite("ProjectMarkdownImportsTest")

                .test("a project's own valid imports produce no violations", () -> {
                    Fx fx = Fx.build("valid");
                    Files.writeString(fx.project.resolve("CLAUDE.md"),
                            frontmatter("ob-alpha", "SKILL.md", "the alpha unit's own page")
                                    + "\n# acme widgets\n");

                    List<MarkdownImportValidator.Violation> v =
                            MarkdownImportValidator.validateProject(fx.store, "acme", fx.project);

                    assertEquals(0, v.size(), "a resolvable project import is clean: " + v);
                })

                .test("a missing unit AND a missing path in the project's own files are both named",
                        () -> {
                            Fx fx = Fx.build("broken");
                            // Well-formed, complete entries. The ONLY thing
                            // wrong with either is the reference itself.
                            Files.writeString(fx.project.resolve("CLAUDE.md"),
                                    frontmatter("ob-no-such-unit", "SKILL.md",
                                            "names a unit that is not installed")
                                            + "\n# acme widgets\n");
                            Files.createDirectories(fx.project.resolve("docs"));
                            Files.writeString(fx.project.resolve("docs/architecture.md"),
                                    frontmatter("ob-alpha", "references/definitely-missing.md",
                                            "names an installed unit and a path it does not have")
                                            + "\n# architecture\n");

                            List<MarkdownImportValidator.Violation> v =
                                    MarkdownImportValidator.validateProject(
                                            fx.store, "acme", fx.project);

                            assertEquals(2, v.size(), "exactly the two planted references: " + v);
                            String rendered = render(v);
                            assertTrue(rendered.contains("ob-no-such-unit"),
                                    "the missing UNIT is named: " + rendered);
                            assertTrue(rendered.contains("definitely-missing.md"),
                                    "the missing PATH is named: " + rendered);
                            assertTrue(rendered.contains("CLAUDE.md"),
                                    "the file carrying each one is named: " + rendered);
                            assertTrue(rendered.contains("architecture.md"),
                                    "including the one under docs/: " + rendered);
                            for (MarkdownImportValidator.Violation one : v) {
                                assertEquals("acme", one.unitName(),
                                        "attributed to the PROJECT, not to a unit");
                            }
                        })

                .test("COMPANION: the walk covers docs/ and stops at the agent directories", () -> {
                    Fx fx = Fx.build("scope");
                    Files.createDirectories(fx.project.resolve("docs/deep/deeper"));
                    Files.writeString(fx.project.resolve("docs/deep/deeper/note.md"),
                            frontmatter("ob-not-there-1", "SKILL.md", "deep in docs")
                                    + "\n# note\n");
                    // The projections an agent reads. Walking these would
                    // re-validate every installed unit's imports under the
                    // project's name — the project cannot fix those, and a
                    // violation it cannot fix is noise that hides the one it can.
                    for (String agentDir : List.of(".claude/skills/ob-alpha",
                            ".codex/skills/ob-alpha", ".skill-manager/skills/ob-alpha")) {
                        Path d = fx.project.resolve(agentDir);
                        Files.createDirectories(d);
                        Files.writeString(d.resolve("SKILL.md"),
                                frontmatter("ob-not-there-2", "SKILL.md", "inside an agent dir")
                                        + "\n# projected\n");
                    }
                    // Same for a materialized dev checkout of another repo.
                    Files.createDirectories(fx.project.resolve("libs/other-repo"));
                    Files.writeString(fx.project.resolve("libs/other-repo/README.md"),
                            frontmatter("ob-not-there-3", "SKILL.md", "another repo's file")
                                    + "\n# other\n");

                    List<MarkdownImportValidator.Violation> v =
                            MarkdownImportValidator.validateProject(fx.store, "acme", fx.project);

                    String rendered = render(v);
                    assertTrue(rendered.contains("ob-not-there-1"),
                            "docs/ is walked to full depth: " + rendered);
                    assertTrue(!rendered.contains("ob-not-there-2"),
                            "the agent + store directories are not the project's markdown: "
                                    + rendered);
                    assertTrue(!rendered.contains("ob-not-there-3"),
                            "nor is another repo's checkout under libs/: " + rendered);
                    assertEquals(1, v.size(), "exactly one finding, from docs/: " + rendered);
                })

                .runAll();
    }

    // --------------------------------------------------------------- fixture

    private record Fx(SkillStore store, Path project) {

        /** A home holding {@code ob-alpha}, and a project checkout beside it. */
        static Fx build(String label) throws Exception {
            Path root = Files.createTempDirectory("project-md-imports-" + label + "-");
            SkillStore store = new SkillStore(root.resolve("home"));
            store.init();
            Path alpha = store.skillDir("ob-alpha");
            Files.createDirectories(alpha.resolve("references"));
            Files.writeString(alpha.resolve("SKILL.md"), "---\nname: ob-alpha\n---\n\n# alpha\n");
            Files.writeString(alpha.resolve("references/page.md"), "# page\n");

            Path project = Files.createDirectories(root.resolve("proj"));
            return new Fx(store, project);
        }
    }

    private static String frontmatter(String unit, String path, String reason) {
        return "---\n"
                + MarkdownImportValidator.FRONTMATTER_KEY + ":\n"
                + "  - unit: " + unit + "\n"
                + "    path: " + path + "\n"
                + "    reason: " + reason + "\n"
                + "---\n";
    }

    private static String render(List<MarkdownImportValidator.Violation> violations) {
        StringBuilder sb = new StringBuilder();
        for (MarkdownImportValidator.Violation v : violations) sb.append(v.render()).append('\n');
        return sb.toString();
    }
}
