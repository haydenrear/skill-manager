package dev.skillmanager.project;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The three failure modes surveyed across this integration repository, plus the
 * shapes that must stay legal.
 *
 * <p>Every case here builds a real filesystem, because the whole claim under
 * test is about what a path resolves to on a real filesystem. In particular the
 * "disguised" case cannot be expressed at all without one: its link text is
 * indistinguishable from a correct link and only the kernel's resolution shows
 * where it lands.
 */
public final class ProjectVendoredResolverTest {

    private static final String THREE_PATH_MANIFEST = """
            [project]
            name = "%s"

            [[vendored]]
            name = "test-graph-sdk"
            paths = ["test_graph/sdk", "test_graph/build-logic", "test_graph/standard-nodes"]
            from_unit = "test-graph"
            from_subpath = "project_sdk_sources"
            on_invalid = "%s"
            """;

    public static int run() throws Exception {
        return Tests.suite("ProjectVendoredResolverTest")

                // ------------------------------------------------ the legal shapes

                .test("accepts a relative vendored link into the project's own home", () -> {
                    Path root = tempProject("vendored-ok-");
                    seedHome(root.resolve(".skill-manager"));
                    linkVendored(root, "sdk", "../.skill-manager/skills/test-graph/project_sdk_sources/sdk");
                    linkVendored(root, "build-logic", "../.skill-manager/skills/test-graph/project_sdk_sources/build-logic");
                    linkVendored(root, "standard-nodes", "../.skill-manager/skills/test-graph/project_sdk_sources/standard-nodes");

                    ProjectVendoredResolver.Report report = check(root, "error", false);
                    assertTrue(report.clean(), "the support-agent-rears shape is the target shape: " + report.render());
                    assertEquals(3, report.entries().size(), "all three declared paths checked");
                    assertEquals(ProjectVendoredResolver.Status.OK, statusOf(report, "test_graph/sdk"),
                            "relative link into the project's own home");
                })

                .test("accepts a correct link when the project root is given in a non-canonical spelling", () -> {
                    // The trap HomeLinks.storedTarget was bitten by: one path
                    // arrives as /var/... and the other as /private/var/... for the
                    // same directory, and relativizing across the two returns a
                    // wrong answer rather than an error. Comparing real paths on
                    // both sides is what makes it a non-issue here.
                    //
                    // The divergent spelling is BUILT rather than borrowed from the
                    // platform. Relying on macOS handing back /var/... for a
                    // directory that lives at /private/var/... made the fixture
                    // vacuous on Linux, where createTempDirectory returns an already
                    // canonical /tmp/... and the precondition below could not hold —
                    // this case was one of exactly two that failed on ubuntu-latest.
                    // A symlinked parent reproduces the same two-spellings-one-
                    // directory shape on every platform, so the case now tests the
                    // resolver instead of testing the host's /var layout.
                    Path base = Files.createTempDirectory("vendored-spelling-").toRealPath();
                    Path canonical = Files.createDirectories(base.resolve("canonical"));
                    Path raw = Files.createSymbolicLink(base.resolve("alias"), canonical);
                    assertFalse(raw.equals(raw.toRealPath()),
                            "fixture is only meaningful when the two spellings differ");
                    assertEquals(canonical, raw.toRealPath(),
                            "the two spellings must name the same directory");
                    Files.createDirectories(raw.resolve("test_graph"));
                    seedHome(raw.resolve(".skill-manager"));
                    linkVendored(raw, "sdk", "../.skill-manager/skills/test-graph/project_sdk_sources/sdk");
                    linkVendored(raw, "build-logic", "../.skill-manager/skills/test-graph/project_sdk_sources/build-logic");
                    linkVendored(raw, "standard-nodes", "../.skill-manager/skills/test-graph/project_sdk_sources/standard-nodes");

                    ProjectVendoredResolver.Report report = check(raw, "error", false);
                    assertTrue(report.clean(),
                            "a correct link is not a finding just because the root was spelled differently: "
                                    + report.render());
                })

                .test("accepts a --copy-sdk snapshot directory and rejects an empty placeholder", () -> {
                    Path root = tempProject("vendored-copy-");
                    seedHome(root.resolve(".skill-manager"));
                    for (String leaf : List.of("sdk", "build-logic")) {
                        Path dir = root.resolve("test_graph").resolve(leaf);
                        Files.createDirectories(dir);
                        Files.writeString(dir.resolve("marker.txt"), leaf);
                    }
                    Files.createDirectories(root.resolve("test_graph/standard-nodes"));

                    ProjectVendoredResolver.Report report = check(root, "error", false);
                    assertEquals(ProjectVendoredResolver.Status.COPY, statusOf(report, "test_graph/sdk"),
                            "a snapshot copy is a supported vendoring mode");
                    assertEquals(ProjectVendoredResolver.Status.EMPTY,
                            statusOf(report, "test_graph/standard-nodes"),
                            "an empty vendored directory is a half-finished vendor");
                })

                // ----------------------------- failure mode 1: absolute into a foreign home

                .test("reports an absolute vendored link into a foreign home as machine-specific", () -> {
                    Path root = tempProject("vendored-absolute-");
                    seedHome(root.resolve(".skill-manager"));
                    Path foreign = seedHome(tempDir("vendored-foreign-home-").resolve(".skill-manager"));
                    linkVendored(root, "sdk", foreign.resolve("skills/test-graph/project_sdk_sources/sdk").toString());
                    linkVendored(root, "build-logic",
                            foreign.resolve("skills/test-graph/project_sdk_sources/build-logic").toString());
                    linkVendored(root, "standard-nodes",
                            foreign.resolve("skills/test-graph/project_sdk_sources/standard-nodes").toString());

                    ProjectVendoredResolver.Report report = check(root, "error", false);
                    // It RESOLVES on this machine. That is exactly why link
                    // existence is not the test and the finding must still fire.
                    assertTrue(Files.isDirectory(root.resolve("test_graph/sdk")),
                            "the absolute link resolves on this machine");
                    assertEquals(ProjectVendoredResolver.Status.FOREIGN_ABSOLUTE,
                            statusOf(report, "test_graph/sdk"), "absolute link into a foreign home");
                    assertEquals(3, report.problems().size(), "all three absolute links reported");

                    ProjectVendoredResolver.Entry entry = entryOf(report, "test_graph/sdk");
                    assertContains(entry.detail(), "tracked git blob",
                            "says WHY a link that resolves today is still wrong");
                    assertContains(entry.detail(), "concurrent Gradle daemon",
                            "names the shared-mutable-state consequence, not just the portability one");
                    assertContains(String.join("\n", entry.render()),
                            "../.skill-manager/skills/test-graph/project_sdk_sources/sdk",
                            "names the link text a correct link would hold");
                })

                // ----------------------- failure mode 2: relative text, foreign destination

                .test("reports a relative vendored link that resolves outside the project as disguised", () -> {
                    // (a) deploy-helm's actual shape: standard-nodes -> sdk/../standard-nodes.
                    // The text has no leading slash and no home path in it, so
                    // `find -lname '/*'` and every string match miss it; it
                    // resolves THROUGH the absolute sibling into the foreign home.
                    Path root = tempProject("vendored-disguised-");
                    seedHome(root.resolve(".skill-manager"));
                    Path foreign = seedHome(tempDir("vendored-disguised-foreign-").resolve(".skill-manager"));
                    linkVendored(root, "sdk", foreign.resolve("skills/test-graph/project_sdk_sources/sdk").toString());
                    linkVendored(root, "build-logic",
                            foreign.resolve("skills/test-graph/project_sdk_sources/build-logic").toString());
                    linkVendored(root, "standard-nodes", "sdk/../standard-nodes");

                    ProjectVendoredResolver.Report report = check(root, "error", false);
                    ProjectVendoredResolver.Entry disguised = entryOf(report, "test_graph/standard-nodes");
                    assertFalse(Path.of(disguised.linkText()).isAbsolute(),
                            "the link text really is relative — a text check calls it fine");
                    assertEquals(ProjectVendoredResolver.Status.FOREIGN_DISGUISED, disguised.status(),
                            "sibling-link form resolves into the foreign home");
                    assertTrue(disguised.resolvedTo().startsWith(foreign.toRealPath()),
                            "the finding names the physical destination: " + disguised.resolvedTo());
                    assertContains(disguised.detail(), "in disguise",
                            "explains that reading link text would call this fine");

                    // (b) the harder form: the link text is EXACTLY what a correct
                    // link holds, but .skill-manager is itself a link to the
                    // foreign home. Lexical normalization of the text produces the
                    // expected target and reports OK; only resolving does not.
                    Path shadow = tempProject("vendored-shadow-home-");
                    Path shadowForeign = seedHome(tempDir("vendored-shadow-foreign-").resolve(".skill-manager"));
                    Files.createSymbolicLink(shadow.resolve(".skill-manager"), shadowForeign);
                    linkVendored(shadow, "sdk", "../.skill-manager/skills/test-graph/project_sdk_sources/sdk");
                    linkVendored(shadow, "build-logic", "../.skill-manager/skills/test-graph/project_sdk_sources/build-logic");
                    linkVendored(shadow, "standard-nodes", "../.skill-manager/skills/test-graph/project_sdk_sources/standard-nodes");

                    ProjectVendoredResolver.Report shadowReport = check(shadow, "error", false);
                    ProjectVendoredResolver.Entry shadowed = entryOf(shadowReport, "test_graph/sdk");
                    assertEquals("../.skill-manager/skills/test-graph/project_sdk_sources/sdk",
                            shadowed.linkText(), "link text is byte-identical to a correct link");
                    assertEquals(ProjectVendoredResolver.Status.FOREIGN_DISGUISED, shadowed.status(),
                            "judged on the resolved physical path, not on the text or on lexical normalization");
                    assertEquals(3, shadowReport.problems().size(), "every path through the shadowed home reported");
                })

                // ------------------------------------------- failure mode 3: dangling

                .test("reports a dangling vendored link that escapes the repository", () -> {
                    // hyper-experiments' shape: ../../test_graph/project_sdk_sources/...
                    // leaves the repo entirely and lands on nothing.
                    Path root = tempProject("vendored-dangling-");
                    seedHome(root.resolve(".skill-manager"));
                    linkVendored(root, "sdk", "../../test_graph/project_sdk_sources/sdk");
                    linkVendored(root, "build-logic", "../../test_graph/project_sdk_sources/build-logic");
                    linkVendored(root, "standard-nodes", "../../test_graph/project_sdk_sources/standard-nodes");

                    ProjectVendoredResolver.Report report = check(root, "error", false);
                    assertTrue(Files.isSymbolicLink(root.resolve("test_graph/sdk")),
                            "the link exists as a directory entry");
                    assertFalse(Files.exists(root.resolve("test_graph/sdk")), "but resolves to nothing");
                    assertEquals(ProjectVendoredResolver.Status.DANGLING, statusOf(report, "test_graph/sdk"),
                            "dangling link detected");
                    assertEquals(3, report.problems().size(), "all three dangling links reported");
                    assertTrue(entryOf(report, "test_graph/sdk").problem(), "dangling is a problem");
                    assertContains(entryOf(report, "test_graph/sdk").detail(), "cannot build",
                            "says what the consequence is");
                })

                .test("reports a declared vendored path that is simply absent", () -> {
                    Path root = tempProject("vendored-missing-");
                    seedHome(root.resolve(".skill-manager"));
                    ProjectVendoredResolver.Report report = check(root, "error", false);
                    assertEquals(ProjectVendoredResolver.Status.MISSING, statusOf(report, "test_graph/sdk"),
                            "absent declared path");
                    assertEquals(3, report.problems().size(), "all three absent paths reported");
                })

                .test("reports a link that resolves inside the project but not at the declared source", () -> {
                    Path root = tempProject("vendored-mispointed-");
                    seedHome(root.resolve(".skill-manager"));
                    Path elsewhere = root.resolve("vendor/sdk");
                    Files.createDirectories(elsewhere);
                    Files.writeString(elsewhere.resolve("marker.txt"), "elsewhere");
                    linkVendored(root, "sdk", "../vendor/sdk");
                    linkVendored(root, "build-logic", "../.skill-manager/skills/test-graph/project_sdk_sources/build-logic");
                    linkVendored(root, "standard-nodes", "../.skill-manager/skills/test-graph/project_sdk_sources/standard-nodes");

                    ProjectVendoredResolver.Report report = check(root, "error", false);
                    assertEquals(ProjectVendoredResolver.Status.MISPOINTED, statusOf(report, "test_graph/sdk"),
                            "resolves inside the project but not at the declared source");
                    assertEquals(1, report.problems().size(), "only the mispointed path is a finding");
                })

                // ------------------------------- the relative target is computed, not templated

                .test("computes the link text from the link's own directory at any nesting depth", () -> {
                    // meta-orchestrator's projects sit two integration levels deep,
                    // and a vendored path may be nested below the project root as
                    // well. No fixed `../` count can express either, so assert the
                    // count actually varies with the geometry.
                    Path outer = tempDir("vendored-depth-");
                    Path inner = outer.resolve("constituents/inner");
                    Files.createDirectories(inner);
                    seedHome(inner.resolve(".skill-manager"));
                    SkillProject nested = projectAt(inner, """
                            [project]
                            name = "nested-project"

                            [[vendored]]
                            name = "test-graph-sdk"
                            paths = ["deep/nested/test_graph/sdk"]
                            from_unit = "test-graph"
                            from_subpath = "project_sdk_sources"
                            """);
                    ProjectVendoredResolver.Report deep =
                            ProjectVendoredResolver.check(nested, false);
                    assertEquals("../../../.skill-manager/skills/test-graph/project_sdk_sources/sdk",
                            deep.entries().get(0).expectedText().toString(),
                            "four segments below the home root means three ../");

                    // Same declaration, home one integration level further up:
                    // the count must change, which a template cannot do.
                    Path outerHomeOnly = tempDir("vendored-depth-outer-");
                    Path innerNoHome = outerHomeOnly.resolve("constituents/inner");
                    Files.createDirectories(innerNoHome);
                    seedHome(outerHomeOnly.resolve(".skill-manager"));
                    SkillProject outerHomed = projectAt(innerNoHome, """
                            [project]
                            name = "outer-homed-project"

                            [[vendored]]
                            name = "test-graph-sdk"
                            paths = ["deep/nested/test_graph/sdk"]
                            from_unit = "test-graph"
                            from_subpath = "project_sdk_sources"
                            """);
                    ProjectVendoredResolver.Report escalated =
                            ProjectVendoredResolver.check(outerHomed, false);
                    assertEquals("../../../../../.skill-manager/skills/test-graph/project_sdk_sources/sdk",
                            escalated.entries().get(0).expectedText().toString(),
                            "the nearest enclosing home is two levels further up, so two more ../");
                })

                .test("names every enclosing home as a candidate and says which holds the content", () -> {
                    Path outer = tempDir("vendored-candidates-");
                    Path inner = outer.resolve("constituents/inner");
                    Files.createDirectories(inner);
                    Files.createDirectories(inner.resolve(".skill-manager/skills"));   // exists, empty
                    seedHome(outer.resolve(".skill-manager"));                          // exists, populated
                    SkillProject project = projectAt(inner, THREE_PATH_MANIFEST.formatted("candidates", "error"));

                    ProjectVendoredResolver.Report report = ProjectVendoredResolver.check(project, false);
                    List<String> candidates = entryOf(report, "test_graph/sdk").candidates();
                    assertEquals(2, candidates.size(), "both enclosing homes named");
                    assertContains(candidates.get(0), "(absent)", "nearest home does not hold it");
                    assertContains(candidates.get(1), "(present)", "outer home does");
                })

                // ------------------------------------------------------------- repair

                .test("repair is opt-in: a check alone leaves the tracked symlink byte-identical", () -> {
                    Path root = tempProject("vendored-repair-optin-");
                    seedHome(root.resolve(".skill-manager"));
                    Path foreign = seedHome(tempDir("vendored-repair-foreign-").resolve(".skill-manager"));
                    String original = foreign.resolve("skills/test-graph/project_sdk_sources/sdk").toString();
                    linkVendored(root, "sdk", original);
                    linkVendored(root, "build-logic", original);
                    linkVendored(root, "standard-nodes", original);

                    ProjectVendoredResolver.Report report = check(root, "error", false);
                    assertEquals(0, report.repairs().size(), "nothing repaired without the flag");
                    // Assert the working tree is UNCHANGED, not merely that the
                    // command reported something: a vendored path is tracked in
                    // git, so an unrequested rewrite is the defect.
                    assertEquals(original,
                            Files.readSymbolicLink(root.resolve("test_graph/sdk")).toString(),
                            "the tracked symlink bytes are untouched by a plain check");
                })

                .test("repair re-points declared vendored paths at the project's own home", () -> {
                    Path root = tempProject("vendored-repair-");
                    seedHome(root.resolve(".skill-manager"));
                    Path foreign = seedHome(tempDir("vendored-repair-target-").resolve(".skill-manager"));
                    linkVendored(root, "sdk", foreign.resolve("skills/test-graph/project_sdk_sources/sdk").toString());
                    linkVendored(root, "build-logic", "sdk/../build-logic");            // disguised
                    linkVendored(root, "standard-nodes", "../../nowhere/standard-nodes"); // dangling

                    ProjectVendoredResolver.Report report = check(root, "error", true);
                    assertEquals(3, report.repairs().size(), "all three modes repaired");
                    assertTrue(report.clean(), "nothing left over: " + report.render());
                    assertEquals("../.skill-manager/skills/test-graph/project_sdk_sources/build-logic",
                            Files.readSymbolicLink(root.resolve("test_graph/build-logic")).toString(),
                            "repaired link is relative into the project's own home");
                    assertEquals("standard-nodes", Files.readString(root.resolve(
                            "test_graph/standard-nodes/marker.txt")), "and it resolves to real content");
                })

                .test("repair replaces an empty placeholder but never anything holding content", () -> {
                    Path root = tempProject("vendored-repair-content-");
                    seedHome(root.resolve(".skill-manager"));
                    // A snapshot copy: legal, so never even a candidate for repair.
                    Path snapshot = root.resolve("test_graph/sdk");
                    Files.createDirectories(snapshot);
                    Files.writeString(snapshot.resolve("marker.txt"), "hand-vendored");
                    // An empty placeholder: a finding, and provably safe to replace.
                    Files.createDirectories(root.resolve("test_graph/build-logic"));
                    // A regular file where a tree was declared: a finding, but it
                    // holds bytes, so repair must decline rather than delete it.
                    Files.writeString(root.resolve("test_graph/standard-nodes"), "someone's work");

                    ProjectVendoredResolver.Report report = check(root, "error", true);
                    assertEquals(ProjectVendoredResolver.Status.COPY, statusOf(report, "test_graph/sdk"),
                            "a snapshot copy is a legal vendoring mode");
                    assertEquals("hand-vendored", Files.readString(snapshot.resolve("marker.txt")),
                            "a snapshot copy is never deleted by repair");
                    assertFalse(Files.isSymbolicLink(snapshot), "and never replaced by a link");

                    assertTrue(Files.isSymbolicLink(root.resolve("test_graph/build-logic")),
                            "a provably empty placeholder is safe to replace");

                    assertEquals(ProjectVendoredResolver.Status.MISPOINTED,
                            statusOf(report, "test_graph/standard-nodes"),
                            "a regular file where a tree was declared is a finding");
                    assertEquals("someone's work", Files.readString(root.resolve("test_graph/standard-nodes")),
                            "repair declines rather than deleting content it did not create");
                    assertEquals(1, report.repairs().size(), "only the empty placeholder repaired");
                })

                .test("repair declines when the declared source does not exist in any enclosing home", () -> {
                    Path root = tempProject("vendored-repair-nosource-");
                    Files.createDirectories(root.resolve(".skill-manager/skills"));
                    linkVendored(root, "sdk", "../../nowhere/sdk");
                    linkVendored(root, "build-logic", "../../nowhere/build-logic");
                    linkVendored(root, "standard-nodes", "../../nowhere/standard-nodes");

                    ProjectVendoredResolver.Report report = check(root, "error", true);
                    assertEquals(0, report.repairs().size(), "repointing at nothing is not a repair");
                    assertEquals(3, report.problems().size(), "still reported");
                    assertContains(String.join("\n", report.render()), "--copy-sdk",
                            "names the SDK's own supported snapshot mode as the alternative remedy");
                })

                // -------------------------------------------------- on_invalid, end to end

                .test("on_invalid = error fails project resolve and warn does not", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path failing = tempProject("vendored-resolve-error-");
                        SkillProject project = projectAt(failing,
                                THREE_PATH_MANIFEST.formatted("vendored-error-project", "error"));
                        try {
                            new ProjectDependencyResolver(h.store(), null).resolve(
                                    project, new ProjectDependencyResolver.Options(true, false));
                            throw new AssertionError("expected resolve to fail on invalid vendored paths");
                        } catch (IOException e) {
                            assertContains(e.getMessage(), "project vendored paths are invalid",
                                    "resolve fails with the finding, not a generic error");
                            assertContains(e.getMessage(), "MISSING", "and names the mode");
                            assertContains(e.getMessage(), "--copy-sdk", "and the remedy");
                        }
                    }

                    try (TestHarness h = TestHarness.create()) {
                        Path warning = tempProject("vendored-resolve-warn-");
                        SkillProject project = projectAt(warning,
                                THREE_PATH_MANIFEST.formatted("vendored-warn-project", "warn"));
                        ProjectDependencyResolver.Result result =
                                new ProjectDependencyResolver(h.store(), null).resolve(
                                        project, new ProjectDependencyResolver.Options(true, false));
                        assertEquals(3, result.vendored().problems().size(),
                                "warn still reports every finding");
                        assertEquals(0, result.vendored().fatalProblems().size(),
                                "but none of them are fatal");
                    }
                })

                .test("project resolve repairs vendored paths only when asked, and then succeeds", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path root = tempProject("vendored-resolve-repair-");
                        Path foreign = seedHome(tempDir("vendored-resolve-foreign-").resolve(".skill-manager"));
                        String original = foreign.resolve("skills/test-graph/project_sdk_sources/sdk").toString();
                        linkVendored(root, "sdk", original);
                        linkVendored(root, "build-logic",
                                foreign.resolve("skills/test-graph/project_sdk_sources/build-logic").toString());
                        linkVendored(root, "standard-nodes", "sdk/../standard-nodes");
                        SkillProject project = projectAt(root,
                                THREE_PATH_MANIFEST.formatted("vendored-repair-project", "error"));

                        // resolve() scaffolds <root>/.skill-manager, so the declared
                        // source has to be seeded there for repair to have a target.
                        seedHome(root.resolve(".skill-manager"));

                        try {
                            new ProjectDependencyResolver(h.store(), null).resolve(
                                    project, new ProjectDependencyResolver.Options(true, false));
                            throw new AssertionError("expected resolve to fail without --repair-vendored");
                        } catch (IOException expected) {
                            assertContains(expected.getMessage(), "FOREIGN_ABSOLUTE", "mode named");
                        }
                        assertEquals(original, Files.readSymbolicLink(root.resolve("test_graph/sdk")).toString(),
                                "a failed resolve did not rewrite the tracked symlink");

                        ProjectDependencyResolver.Result result =
                                new ProjectDependencyResolver(h.store(), null).resolve(
                                        project, new ProjectDependencyResolver.Options(
                                                true, false, java.util.Set.of(), true));
                        assertTrue(result.vendored().clean(), "repaired resolve is clean");
                        assertEquals(3, result.vendored().repairs().size(), "three links re-pointed");
                        assertEquals("../.skill-manager/skills/test-graph/project_sdk_sources/sdk",
                                Files.readSymbolicLink(root.resolve("test_graph/sdk")).toString(),
                                "now relative into the project's own home");
                    }
                })

                // --------------------------------------------- SI-18: both rungs

                .test("a vendored source CONTAINED in a plugin resolves, and is not MISPOINTED", () -> {
                    // The single-rung defect. `from_unit = "test-graph"` names a
                    // UNIT, and the unit's bytes are at skills/test-graph/ when
                    // it is installed standalone or at
                    // plugins/<plugin>/skills/test-graph/ when a plugin carries
                    // it. Reading only the first rung, this exact home — which
                    // has the content, one rung over — reported all three paths
                    // MISPOINTED with a remedy telling the operator to install
                    // the standalone unit: that is, to undo the bundling.
                    Path root = tempProject("vendored-contained-");
                    seedContainedHome(root.resolve(".skill-manager"), "tla-spec-dev");
                    String target = "../.skill-manager/plugins/tla-spec-dev/skills/test-graph"
                            + "/project_sdk_sources/";
                    linkVendored(root, "sdk", target + "sdk");
                    linkVendored(root, "build-logic", target + "build-logic");
                    linkVendored(root, "standard-nodes", target + "standard-nodes");

                    ProjectVendoredResolver.Report report = check(root, "error", false);
                    assertTrue(report.clean(),
                            "a home that bundled the unit is the CORRECT shape: " + report.render());
                    assertEquals(ProjectVendoredResolver.Status.OK, statusOf(report, "test_graph/sdk"),
                            "relative link into the plugin rung of the project's own home");
                })

                .test("the standalone rung still wins when a home holds both", () -> {
                    // Precedence is unchanged where both exist, which is what
                    // makes the second rung safe to add: no home that resolves
                    // today resolves anywhere else tomorrow.
                    Path root = tempProject("vendored-both-rungs-");
                    Path home = root.resolve(".skill-manager");
                    seedHome(home);
                    seedContainedHome(home, "tla-spec-dev");
                    linkVendored(root, "sdk",
                            "../.skill-manager/skills/test-graph/project_sdk_sources/sdk");
                    linkVendored(root, "build-logic",
                            "../.skill-manager/skills/test-graph/project_sdk_sources/build-logic");
                    linkVendored(root, "standard-nodes",
                            "../.skill-manager/skills/test-graph/project_sdk_sources/standard-nodes");

                    ProjectVendoredResolver.Report report = check(root, "error", false);
                    assertTrue(report.clean(),
                            "standalone is still the expected target: " + report.render());
                })

                .test("a home holding BOTH rungs accepts a link on either one", () -> {
                    // The false MISPOINTED. `expectedTarget` names the rung a
                    // repair would WRITE — the first whose directory exists,
                    // i.e. the standalone — but a link already pointing at the
                    // other valid rung is correct. Judging against one rung
                    // called this damage and printed a remedy telling the
                    // operator to re-point at the standalone: undoing the
                    // bundling, which is what the two-rung change exists to stop.
                    Path root = tempProject("vendored-either-rung-");
                    Path home = root.resolve(".skill-manager");
                    seedHome(home);
                    seedContainedHome(home, "tla-spec-dev");
                    String plugin = "../.skill-manager/plugins/tla-spec-dev/skills/test-graph"
                            + "/project_sdk_sources/";
                    linkVendored(root, "sdk", plugin + "sdk");
                    linkVendored(root, "build-logic", plugin + "build-logic");
                    linkVendored(root, "standard-nodes", plugin + "standard-nodes");

                    ProjectVendoredResolver.Report report = check(root, "error", false);
                    assertTrue(report.clean(),
                            "a link on the plugin rung is correct even when the standalone "
                                    + "rung also exists: " + report.render());
                })

                .test("a link resolving to NEITHER rung is still MISPOINTED", () -> {
                    // The companion. Accepting any rung must not become
                    // accepting anything — a predicate that cannot fail is not
                    // a check, and this one is one clause away from that.
                    Path root = tempProject("vendored-neither-rung-");
                    Path home = root.resolve(".skill-manager");
                    seedHome(home);
                    seedContainedHome(home, "tla-spec-dev");
                    Path elsewhere = root.resolve("somewhere-else");
                    Files.createDirectories(elsewhere);
                    linkVendored(root, "sdk", "../somewhere-else");

                    ProjectVendoredResolver.Report report = check(root, "warn", false);
                    assertEquals(ProjectVendoredResolver.Status.MISPOINTED,
                            statusOf(report, "test_graph/sdk"),
                            "inside the project, but on no rung of the home");
                })

                .test("a finding names the plugin rung as a candidate, not only the standalone one", () -> {
                    // The misleading half of the defect. A candidate list that
                    // printed only `skills/test-graph  (absent)` for a home
                    // holding the content in a plugin told the operator the
                    // content was not there — and nothing in the output hinted
                    // a second rung existed to look on.
                    Path root = tempProject("vendored-candidates-rungs-");
                    seedContainedHome(root.resolve(".skill-manager"), "tla-spec-dev");
                    linkVendored(root, "sdk", "../nowhere/sdk");

                    ProjectVendoredResolver.Report report = check(root, "warn", false);
                    List<String> candidates = entryOf(report, "test_graph/sdk").candidates();
                    assertTrue(candidates.stream().anyMatch(c -> c.contains("plugins/tla-spec-dev")
                                    && c.contains("(present)")),
                            "the plugin rung is named AND reported present: " + candidates);
                    assertTrue(candidates.stream().anyMatch(c -> c.contains("skills/test-graph")
                                    && c.contains("(absent)")),
                            "the standalone rung is still named, and honestly absent: " + candidates);
                })

                .test("repair re-points a broken link at the plugin rung when that is where the bytes are", () -> {
                    Path root = tempProject("vendored-repair-contained-");
                    seedContainedHome(root.resolve(".skill-manager"), "tla-spec-dev");
                    linkVendored(root, "sdk", "/nonexistent/absolute/sdk");
                    linkVendored(root, "build-logic", "/nonexistent/absolute/build-logic");
                    linkVendored(root, "standard-nodes", "/nonexistent/absolute/standard-nodes");

                    ProjectVendoredResolver.Report report = check(root, "error", true);
                    assertTrue(report.clean(), "repaired against the rung that holds the content: "
                            + report.render());
                    String text = Files.readSymbolicLink(root.resolve("test_graph/sdk")).toString();
                    assertTrue(text.contains("plugins/tla-spec-dev/skills/test-graph"),
                            "the repaired link text names the plugin rung: " + text);
                    assertTrue(!text.startsWith("/"), "and it is relative: " + text);
                })

                .test("a project that declares nothing vendored is unaffected", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path root = tempProject("vendored-none-");
                        SkillProject project = projectAt(root, """
                                [project]
                                name = "no-vendored-project"
                                """);
                        ProjectDependencyResolver.Result result =
                                new ProjectDependencyResolver(h.store(), null).resolve(
                                        project, new ProjectDependencyResolver.Options(true, false));
                        assertEquals(0, result.vendored().entries().size(), "nothing checked");
                        assertTrue(result.vendored().clean(), "nothing to find");
                    }
                })

                .runAll();
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A canonical temp root. {@code createTempDirectory} hands back
     * {@code /var/folders/...} on macOS for a directory that physically lives at
     * {@code /private/var/folders/...}; canonicalizing here keeps that spelling
     * difference out of every case that is not about it, so a case that fails
     * fails for its own reason. The one case that IS about it constructs the
     * non-canonical spelling deliberately.
     */
    private static Path tempDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix).toRealPath();
    }

    private static Path tempProject(String prefix) throws IOException {
        Path root = tempDir(prefix);
        Files.createDirectories(root.resolve("test_graph"));
        return root;
    }

    /** A skill-manager home holding test-graph's three vendored source trees. */
    private static Path seedHome(Path home) throws IOException {
        Path sources = home.resolve("skills/test-graph/project_sdk_sources");
        for (String leaf : List.of("sdk", "build-logic", "standard-nodes")) {
            Files.createDirectories(sources.resolve(leaf));
            Files.writeString(sources.resolve(leaf).resolve("marker.txt"), leaf);
        }
        return home;
    }

    /**
     * The same three vendored source trees, but CONTAINED in a plugin:
     * {@code plugins/<plugin>/skills/test-graph/project_sdk_sources/}. This is
     * the rung a home reaches by bundling the unit, which is the state a home
     * is supposed to reach.
     */
    private static Path seedContainedHome(Path home, String plugin) throws IOException {
        Path sources = home.resolve("plugins").resolve(plugin)
                .resolve("skills/test-graph/project_sdk_sources");
        for (String leaf : List.of("sdk", "build-logic", "standard-nodes")) {
            Files.createDirectories(sources.resolve(leaf));
            Files.writeString(sources.resolve(leaf).resolve("marker.txt"), leaf);
        }
        return home;
    }

    private static void linkVendored(Path root, String leaf, String target) throws IOException {
        Path link = root.resolve("test_graph").resolve(leaf);
        Files.createDirectories(link.getParent());
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, Path.of(target));
    }

    private static ProjectVendoredResolver.Report check(Path root, String onInvalid, boolean repair)
            throws Exception {
        SkillProject project = projectAt(root,
                THREE_PATH_MANIFEST.formatted(root.getFileName().toString(), onInvalid));
        return ProjectVendoredResolver.check(project, repair);
    }

    private static SkillProject projectAt(Path root, String manifest) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("skill-project.toml"), manifest);
        return SkillProjectParser.load(root);
    }

    private static ProjectVendoredResolver.Entry entryOf(
            ProjectVendoredResolver.Report report, String declaredPath) {
        return report.entries().stream()
                .filter(e -> e.declaredPath().equals(declaredPath))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry for " + declaredPath
                        + " in " + report.entries()));
    }

    private static ProjectVendoredResolver.Status statusOf(
            ProjectVendoredResolver.Report report, String declaredPath) {
        return entryOf(report, declaredPath).status();
    }

    private ProjectVendoredResolverTest() {}
}
