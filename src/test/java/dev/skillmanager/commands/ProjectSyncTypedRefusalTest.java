package dev.skillmanager.commands;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.project.ProjectSyncUseCase;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.validation.MarkdownImportValidator;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Issue #187 — {@code project sync} loses the typed refusal that
 * {@code project resolve} renders.
 *
 * <p>Both commands reach {@link dev.skillmanager.project.ProjectDependencyResolver},
 * which throws {@link dev.skillmanager.project.ProjectImportViolationException}
 * when the STAGED closure's markdown names a unit that is in neither the
 * closure nor the store. {@code ResolveCmd} caught it and rendered it with
 * per-violation evidence and {@link MarkdownImportValidator#EXIT_CODE};
 * {@code SyncCmd} did not catch it at all, so the identical condition arrived
 * as an untyped {@link java.io.IOException} with a generic exit — and a caller
 * that already handled 11 from {@code resolve} could not classify it.
 *
 * <p>The two commands are driven over the SAME fixture in the same JVM and
 * their rendered output and exit codes are compared to each other, rather than
 * each being compared to a string this test made up. A shape assertion written
 * twice can agree with itself while both are wrong.
 *
 * <p>The destructive half of #187 — {@code --rebuild} tearing the realization
 * down and only THEN refusing — is separately covered: the rebuild path
 * captures a {@code ProjectRealizationSnapshot} and restores it before the
 * refusal escapes, and this asserts the home is byte-identical after a refused
 * {@code --rebuild}.
 */
public final class ProjectSyncTypedRefusalTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ProjectSyncTypedRefusalTest");

        suite.test("project sync renders the same typed refusal, and the same exit code, "
                + "as project resolve", () -> {
            try (TestHarness h = TestHarness.create()) {
                bindDefaultStore(h.store());
                Path repoRoot = brokenClosure("sync-typed-");

                Capture resolve = run(new ProjectCommand.ResolveCmd(),
                        "--project-dir", repoRoot.toString(), "--skip-gateway");
                Capture sync = run(new ProjectCommand.SyncCmd(h.store()),
                        "--project-dir", repoRoot.toString(), "--skip-gateway", "--no-pull");

                assertEquals(MarkdownImportValidator.EXIT_CODE, resolve.exit(),
                        "resolve's typed refusal exit code (the baseline)");
                assertEquals(resolve.exit(), sync.exit(),
                        "sync exits exactly what resolve exits for the same condition; got "
                                + sync.exit() + " with stderr: " + sync.err());

                assertContains(sync.err(), "project sync refused — declared closure has 1 "
                                + "unresolved markdown skill-import(s); nothing was installed:",
                        "sync renders the refusal, naming itself");
                assertEquals(
                        resolve.err().replace("project resolve refused", "REFUSED"),
                        sync.err().replace("project sync refused", "REFUSED"),
                        "every other byte of the two renderings is identical");
                assertContains(sync.err(), "ghost-unit",
                        "and it carries the per-violation evidence rather than a bare message");
            }
        });

        suite.test("the --json refusal is byte-identical between resolve and sync", () -> {
            try (TestHarness h = TestHarness.create()) {
                bindDefaultStore(h.store());
                Path repoRoot = brokenClosure("sync-typed-json-");

                Capture resolve = run(new ProjectCommand.ResolveCmd(),
                        "--project-dir", repoRoot.toString(), "--skip-gateway", "--json");
                Capture sync = run(new ProjectCommand.SyncCmd(h.store()),
                        "--project-dir", repoRoot.toString(), "--skip-gateway", "--no-pull",
                        "--json");

                assertEquals(resolve.exit(), sync.exit(), "same exit code under --json too");
                assertContains(sync.out(), "\"reason\":\"closure-import-violations\"",
                        "sync's machine-readable refusal carries the typed reason");
                assertEquals(resolve.out(), sync.out(),
                        "a --json consumer cannot tell the two apart, which is the point");
            }
        });

        suite.test("a refused --rebuild puts the realization back before the refusal escapes",
                () -> {
            try (TestHarness h = TestHarness.create()) {
                bindDefaultStore(h.store());
                Path repoRoot = brokenClosure("sync-typed-rebuild-");
                java.util.Map<String, String> before = Snapshots.tree(h.store().root());

                Capture sync = run(new ProjectCommand.SyncCmd(h.store()),
                        "--project-dir", repoRoot.toString(), "--skip-gateway", "--no-pull",
                        "--rebuild");

                assertEquals(MarkdownImportValidator.EXIT_CODE, sync.exit(),
                        "--rebuild is refused with the same typed code; stderr: " + sync.err());
                // Everything except the rollback scratch directory, which the
                // snapshot creates under <store>/tmp and empties on close. Its
                // empty shell is the one difference a refused --rebuild leaves,
                // and it holds nothing.
                assertEquals(before, Snapshots.tree(h.store().root(), h.store().root()
                                .resolve("tmp")),
                        "the home is byte-identical: the teardown was rolled back, and the "
                                + "refusal did not leave it emptier than it found it");
                try (var scratch = Files.list(h.store().root().resolve("tmp"))) {
                    assertEquals(List.of(), scratch.toList(),
                            "and the rollback scratch directory was emptied, not abandoned full");
                }
            }
        });

        suite.test("ProjectSyncUseCase.rebuild still captures a snapshot before it removes",
                () -> {
            // The mechanism the assertion above depends on, asserted directly
            // so a refactor that drops the snapshot fails HERE with the reason
            // rather than three tickets later with a lost realization. Read
            // from the source because the ordering — capture, THEN remove — is
            // the whole property, and no runtime observation distinguishes
            // "captured first" from "captured after" on a run that succeeds.
            String source = Files.readString(sourceFile(
                    "src/main/java/dev/skillmanager/project/ProjectSyncUseCase.java"));
            int rebuild = source.indexOf("private Result rebuild(");
            assertTrue(rebuild > 0, "rebuild() is findable");
            String body = source.substring(rebuild, source.indexOf("\n    }", rebuild));
            int capture = body.indexOf("ProjectRealizationSnapshot.capture(");
            int remove = body.indexOf(".removeRealization(");
            int restore = body.indexOf("restoreAndRethrow(");
            assertTrue(capture > 0 && remove > capture,
                    "the snapshot is captured BEFORE removeRealization");
            assertTrue(restore > remove, "and restored on the way out");
            assertEquals(ProjectSyncUseCase.class.getName(),
                    "dev.skillmanager.project.ProjectSyncUseCase",
                    "the class this reads is the class that runs");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- fixtures

    /**
     * A project whose declared closure carries a markdown import naming a unit
     * that is neither declared nor installed — the #168 refusal condition,
     * reached identically by resolve and by sync.
     */
    private static Path brokenClosure(String prefix) throws Exception {
        Path repoRoot = Files.createTempDirectory(prefix);
        Path units = repoRoot.resolve("units");
        Path a = UnitFixtures.scaffoldSkill(units, "typed-a", DepSpec.empty()).sourcePath();
        Files.createDirectories(a.resolve("docs"));
        Files.writeString(a.resolve("docs").resolve("uses-ghost.md"), """
                ---
                skill-imports:
                  - unit: ghost-unit
                    path: SKILL.md
                    reason: Refusal fixture for issue #187.
                ---
                Uses ghost-unit.
                """);
        Files.writeString(repoRoot.resolve("skill-project.toml"), """
                [project]
                name = "%s"

                [skills.a]
                source = "%s"
                """.formatted("typed-refusal-" + prefix.hashCode(), a));
        return repoRoot;
    }

    /**
     * Point {@code SkillStore.defaultStore()} at the harness home.
     *
     * <p>{@code ResolveCmd} has no injected-store constructor, and this test's
     * whole value is comparing it against {@code SyncCmd} over ONE fixture.
     * {@link AgentHomes#setOverride} is the seam every other test uses for the
     * same reason: {@code System.getenv} cannot be set from inside a JVM, and
     * running this against the real {@code SKILL_MANAGER_HOME} would install
     * fixtures into the operator's home.
     */
    private static void bindDefaultStore(SkillStore store) {
        AgentHomes.setOverride(SkillStore.HOME_ENV, store.root());
    }

    private record Capture(int exit, String out, String err) {}

    private static Capture run(Object command, String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            exit = new CommandLine(command).execute(args);
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        return new Capture(exit, out.toString(), err.toString());
    }

    private static Path sourceFile(String relative) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path candidate = p.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new AssertionError("cannot find " + relative + " from " + dir
                + " — run the suite from the repository root");
    }

    /** Byte-level tree snapshots, so "nothing was lost" is measured, not asserted. */
    private static final class Snapshots {
        static java.util.Map<String, String> tree(Path root, Path... excluded) throws Exception {
            java.util.Map<String, String> snapshot = new java.util.LinkedHashMap<>();
            if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return snapshot;
            try (var paths = Files.walk(root)) {
                outer:
                for (Path path : paths.sorted().toList()) {
                    for (Path skip : excluded) {
                        if (path.toAbsolutePath().normalize()
                                .startsWith(skip.toAbsolutePath().normalize())) continue outer;
                    }
                    String relative = root.equals(path) ? "." : root.relativize(path).toString();
                    if (Files.isSymbolicLink(path)) {
                        snapshot.put(relative, "link:" + Files.readSymbolicLink(path));
                    } else if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        snapshot.put(relative, "directory");
                    } else {
                        snapshot.put(relative, "file:" + java.util.Base64.getEncoder()
                                .encodeToString(Files.readAllBytes(path)));
                    }
                }
            }
            return snapshot;
        }
    }

    private ProjectSyncTypedRefusalTest() {}
}
