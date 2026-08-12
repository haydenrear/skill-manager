package dev.skillmanager.project;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.validation.MarkdownImportValidator;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Issue #168 — clean-home project resolution is atomic across the declared
 * closure. The resolver stages every missing declared unit with one combined
 * resolve, validates markdown {@code skill-imports} ONCE against that
 * candidate closure, and publishes commits + units-lock together. A genuinely
 * missing import refuses BEFORE any commit, leaving no newly installed units,
 * no project lock, no child home and no projections. The direct
 * {@code install} contract — an undeclared missing import still commits and
 * exits {@link MarkdownImportValidator#EXIT_CODE} — is preserved unchanged.
 */
public final class ProjectResolveAtomicClosureTest {

    public static int run() throws Exception {
        return Tests.suite("ProjectResolveAtomicClosureTest")
                .test("one clean resolve installs mutually importing units", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("atomic-mutual-");
                        Path units = repoRoot.resolve("units");
                        Path a = scaffoldImportingSkill(units, "atomic-a", "atomic-b");
                        Path b = scaffoldImportingSkill(units, "atomic-b", "atomic-a");
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "atomic-mutual"

                                [skills.a]
                                source = "%s"

                                [skills.b]
                                source = "%s"
                                """.formatted(a, b));

                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project, new ProjectDependencyResolver.Options(true, false));

                        assertTrue(h.store().containsUnit("atomic-a"), "a installed in one pass");
                        assertTrue(h.store().containsUnit("atomic-b"), "b installed in one pass");
                        assertTrue(result.installed().contains("atomic-a")
                                        && result.installed().contains("atomic-b"),
                                "resolver reports both units installed by THIS resolve");
                        assertEquals(Set.of("atomic-a", "atomic-b"),
                                lockClosure(result), "lock closure holds exactly the declared pair");
                        assertTrue(Files.isRegularFile(h.store().projectsDir()
                                        .resolve("atomic-mutual").resolve(SkillProjectLock.FILENAME)),
                                "project lock written");
                    }
                })
                .test("reversed declaration order produces the identical closure", () -> {
                    Path fixtures = Files.createTempDirectory("atomic-order-units-");
                    Path a = scaffoldImportingSkill(fixtures, "order-a", "order-b");
                    Path b = scaffoldImportingSkill(fixtures, "order-b", "order-a");

                    Set<String> forward;
                    Set<String> forwardRows;
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("atomic-order-fwd-");
                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project(repoRoot, """
                                        [project]
                                        name = "atomic-order"

                                        [skills.a]
                                        source = "%s"

                                        [skills.b]
                                        source = "%s"
                                        """.formatted(a, b)),
                                new ProjectDependencyResolver.Options(true, false));
                        forward = lockClosure(result);
                        forwardRows = lockRows(result);
                        assertTrue(h.store().containsUnit("order-a")
                                && h.store().containsUnit("order-b"), "forward order installs both");
                    }
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("atomic-order-rev-");
                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project(repoRoot, """
                                        [project]
                                        name = "atomic-order"

                                        [skills.b]
                                        source = "%s"

                                        [skills.a]
                                        source = "%s"
                                        """.formatted(b, a)),
                                new ProjectDependencyResolver.Options(true, false));
                        assertTrue(h.store().containsUnit("order-a")
                                && h.store().containsUnit("order-b"), "reversed order installs both");
                        assertEquals(forward, lockClosure(result),
                                "closure identical whichever way the pair is declared");
                        assertEquals(forwardRows, lockRows(result),
                                "resolved rows (kind/version/directness) identical too");
                    }
                })
                .test("a genuinely missing import refuses before anything is committed", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("atomic-missing-");
                        Path units = repoRoot.resolve("units");
                        Path a = scaffoldImportingSkill(units, "broken-a", "broken-b");
                        Path b = scaffoldImportingSkill(units, "broken-b", "broken-a");
                        // Plant an import naming a unit that is neither
                        // declared nor installed anywhere.
                        Files.writeString(a.resolve("docs").resolve("uses-ghost.md"),
                                importMd("ghost-unit"));
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "atomic-missing"

                                [skills.a]
                                source = "%s"

                                [skills.b]
                                source = "%s"
                                """.formatted(a, b));

                        // Register-only pre-state: the registration may retain
                        // declared intent; everything else must stay untouched.
                        Map<String, String> before = snapshotTree(
                                h.store().root(), h.store().projectsDir());

                        ProjectImportViolationException refusal = null;
                        try {
                            resolver(h).resolve(project,
                                    new ProjectDependencyResolver.Options(true, false));
                        } catch (ProjectImportViolationException e) {
                            refusal = e;
                        }
                        assertTrue(refusal != null, "resolve throws the typed refusal");
                        assertEquals(1, refusal.violations().size(), "one attributable violation");
                        MarkdownImportValidator.Violation v = refusal.violations().get(0);
                        assertEquals("broken-a", v.unitName(), "violation names the importing unit");
                        assertContains(v.file().toString(), "uses-ghost.md",
                                "violation names the importing file");
                        assertContains(v.message(), "missing unit `ghost-unit`",
                                "violation names the missing target");

                        assertFalse(h.store().containsUnit("broken-a"),
                                "no partial unit: a not committed");
                        assertFalse(h.store().containsUnit("broken-b"),
                                "no partial unit: b not committed");
                        assertFalse(Files.exists(h.store().projectsDir()
                                        .resolve("atomic-missing").resolve(SkillProjectLock.FILENAME)),
                                "no resolved project lock");
                        assertFalse(Files.exists(repoRoot.resolve(".skill-manager")),
                                "no child-home realization");
                        assertFalse(Files.exists(repoRoot.resolve(".claude")),
                                "no agent projections");
                        assertEquals(before,
                                snapshotTree(h.store().root(), h.store().projectsDir()),
                                "home outside the registration is byte-identical");
                    }
                })
                .test("direct install of an undeclared missing import still commits and exits 11", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path units = Files.createTempDirectory("atomic-direct-");
                        Path solo = scaffoldImportingSkill(units, "direct-solo", "nowhere-unit");

                        var program = InstallUseCase.buildProgram(
                                h.store(), null, null, solo.toString(), null,
                                true, false, false, true);
                        Executor.Outcome<InstallUseCase.Report> outcome =
                                new Executor(h.store(), null).runStaged(program);

                        assertTrue(h.store().containsUnit("direct-solo"),
                                "direct install still commits the unit");
                        assertEquals(MarkdownImportValidator.EXIT_CODE,
                                outcome.result().commandExitCode(),
                                "direct install still exits the typed import-violation code");
                    }
                })
                .runAll();
    }

    /**
     * A skill whose {@code docs/uses-<target>.md} imports {@code SKILL.md}
     * from {@code target} — the reciprocal-import fixture shape from the
     * issue's acceptance.
     */
    private static Path scaffoldImportingSkill(Path units, String name, String target)
            throws Exception {
        Path dir = UnitFixtures.scaffoldSkill(units, name, DepSpec.empty()).sourcePath();
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docs").resolve("uses-" + target + ".md"),
                importMd(target));
        return dir;
    }

    private static String importMd(String target) {
        return """
                ---
                skill-imports:
                  - unit: %s
                    path: SKILL.md
                    reason: Reciprocal closure fixture for issue #168.
                ---
                Uses %s.
                """.formatted(target, target);
    }

    private static ProjectDependencyResolver resolver(TestHarness h) {
        return new ProjectDependencyResolver(h.store(), null);
    }

    private static SkillProject project(Path root, String manifest) throws Exception {
        Files.writeString(root.resolve("skill-project.toml"), manifest);
        return SkillProjectParser.load(root);
    }

    private static Set<String> lockClosure(ProjectDependencyResolver.Result result) {
        return result.lock().resolvedUnits().stream()
                .map(SkillProjectLock.ResolvedUnit::name)
                .collect(Collectors.toSet());
    }

    private static Set<String> lockRows(ProjectDependencyResolver.Result result) {
        return result.lock().resolvedUnits().stream()
                .map(u -> u.name() + "|" + u.kind() + "|" + u.version() + "|" + u.direct())
                .collect(Collectors.toSet());
    }

    /**
     * Byte-level snapshot of {@code root}, skipping {@code excluded} (the
     * project registration dir, which is allowed to retain declared intent).
     */
    private static Map<String, String> snapshotTree(Path root, Path excluded) throws Exception {
        Map<String, String> snapshot = new LinkedHashMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return snapshot;
        Path excludedNorm = excluded.toAbsolutePath().normalize();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.toAbsolutePath().normalize().startsWith(excludedNorm)) continue;
                String relative = root.equals(path) ? "." : root.relativize(path).toString();
                if (Files.isSymbolicLink(path)) {
                    snapshot.put(relative, "link:" + Files.readSymbolicLink(path));
                } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    snapshot.put(relative, "directory");
                } else {
                    snapshot.put(relative, "file:" + Base64.getEncoder()
                            .encodeToString(Files.readAllBytes(path)));
                }
            }
        }
        return snapshot;
    }
}
