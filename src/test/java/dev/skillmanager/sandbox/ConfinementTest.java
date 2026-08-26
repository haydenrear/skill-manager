package dev.skillmanager.sandbox;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.project.ProjectRoot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The confinement primitive and the refusal it arms (#237, DEF-046/DEF-047).
 *
 * <h2>What is deliberately NOT tested here</h2>
 *
 * <p>Nothing in this file changes the working directory, because nothing can:
 * a JVM cannot change its own, which is the whole reason the defect exists.
 * The CWD-derived case is driven through {@link ProjectRoot}'s stated-confinement
 * overload, and end to end — with a real subprocess and a real
 * {@code pb.directory(...)} — by the {@code home-integrity} graph node
 * {@code ProjectVerbStaysInItsHome}. Two mechanisms, and the graph one is the
 * one that would have caught the incident.
 */
public final class ConfinementTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ConfinementTest");

        suite.test("an undeclared confinement covers everything and confines nothing", () -> {
            withOverrides(() -> {
                Confinement c = Confinement.current();
                assertFalse(c.declared(), "nothing declared");
                assertFalse(c.confined(), "and therefore not confined");
                // The property the unconfined operator path depends on. If this
                // ever answered false, `cd ~/myrepo && skill-manager project
                // resolve` would start refusing, which is the product's main
                // path.
                assertTrue(c.covers(Path.of("/anywhere/at/all")),
                        "an undeclared confinement covers every path");
            });
        });

        suite.test("every axis is read, and the working directory is one of them", () -> {
            Path sandbox = Files.createTempDirectory("confinement-axes-");
            withOverrides(() -> {
                pinAll(sandbox);
                Confinement c = Confinement.current();
                assertTrue(c.declared(), "declared");
                assertEquals(6, c.axes().size(), "five variables plus the working directory");
                assertTrue(c.axis(Confinement.CWD) != null, "the cwd axis exists");
                assertEquals(Confinement.workingDirectory(), c.axis(Confinement.CWD).value(),
                        "the cwd axis carries the JVM's actual working directory");
                // The test JVM runs in the repository, never in a temp dir, so
                // this is the escape DEF-046 describes — asserted rather than
                // hoped over.
                assertEquals(List.of(Confinement.CWD), c.escapedAxes(),
                        "the ONLY escaped axis is the one no variable can pin\n" + c.describe());
                assertFalse(c.confined(), "an escaped axis means not confined");
            });
        });

        suite.test("an UNSET agent variable is an escape, not a pass", () -> {
            Path sandbox = Files.createTempDirectory("confinement-unset-");
            withOverrides(() -> {
                pinAll(sandbox);
                // "Unset" as distinct from "no override" — see AgentHomes.UNSET.
                AgentHomes.setUnset(AgentHomes.CODEX_HOME);
                Confinement c = Confinement.current();
                assertTrue(c.escapedAxes().contains(AgentHomes.CODEX_HOME),
                        "an unset agent root resolves to the operator's real home eventually, "
                                + "so it is reported as escaping\n" + c.describe());
            });
        });

        suite.test("covers() is exact about the root's own boundary", () -> {
            Path sandbox = Files.createTempDirectory("confinement-covers-");
            withOverrides(() -> {
                AgentHomes.setOverride(Confinement.ROOT_ENV, sandbox);
                Confinement c = Confinement.current();
                assertTrue(c.covers(sandbox), "the root covers itself");
                assertTrue(c.covers(sandbox.resolve("a/b/c")), "and everything under it");
                assertFalse(c.covers(sandbox.getParent()), "but not its parent");
                // The substring trap the epic already paid for: /var/X is a
                // substring of /private/var/X and a naive prefix test on the
                // STRING would say yes. Path.startsWith is per-segment.
                assertFalse(c.covers(Path.of(sandbox + "-sibling")),
                        "and not a sibling whose name merely starts with the root's");
            });
        });

        suite.test("ProjectRoot returns the CWD unchanged when nothing is declared", () -> {
            withOverrides(() -> {
                Path expected = Path.of(System.getProperty("user.dir"))
                        .toAbsolutePath().normalize();
                assertEquals(expected, ProjectRoot.resolve(null, "project resolve"),
                        "unconfined behaviour is byte-identical to what it was");
            });
        });

        suite.test("a confined process refuses a CWD-derived root outside the root", () -> {
            Path sandbox = Files.createTempDirectory("confinement-refuse-");
            withOverrides(() -> {
                pinAll(sandbox);
                Confinement c = Confinement.current();
                // Asserted BEFORE the claim, and blind to it: if the fixture
                // could not express the defect — if the cwd were somehow inside
                // the sandbox — the refusal below would be untestable and this
                // says so instead of passing quietly. Mechanism B.
                assertFalse(c.covers(Confinement.workingDirectory()),
                        "PRECONDITION: the working directory is outside the confinement");

                ConfinementEscapeException thrown = null;
                try {
                    ProjectRoot.resolve(null, "project resolve", c);
                } catch (ConfinementEscapeException e) {
                    thrown = e;
                }
                assertTrue(thrown != null, "the CWD-derived root is refused");
                assertEquals(ProjectRoot.FROM_CWD, thrown.origin(),
                        "the refusal says the target came from the working directory");
                assertEquals(14, ConfinementEscapeException.EXIT_CODE, "its own exit code");
                // "Names the conflict" is an acceptance criterion, so it is
                // asserted rather than left to the reader of the message.
                Tests.assertContains(thrown.getMessage(), sandbox.toString(),
                        "the refusal names the confinement root");
                Tests.assertContains(thrown.getMessage(),
                        Confinement.workingDirectory().toString(),
                        "the refusal names the target it would have acted on");
                Tests.assertContains(thrown.getMessage(), "--project-dir",
                        "the refusal names the remedy");
            });
        });

        suite.test("an explicit --project-dir inside the root is accepted", () -> {
            Path sandbox = Files.createTempDirectory("confinement-accept-");
            Path inside = Files.createDirectories(sandbox.resolve("a-project"));
            withOverrides(() -> {
                pinAll(sandbox);
                ProjectRoot.Resolved r = ProjectRoot.resolve(
                        inside.toString(), "project resolve", Confinement.current());
                assertEquals(inside.toRealPath(), r.path().toRealPath(), "the stated root");
                assertFalse(r.fromWorkingDirectory(), "and it did not come from the CWD");
            });
        });

        suite.test("an explicit --project-dir OUTSIDE the root is refused too", () -> {
            Path sandbox = Files.createTempDirectory("confinement-explicit-out-");
            withOverrides(() -> {
                pinAll(sandbox);
                ConfinementEscapeException thrown = null;
                try {
                    ProjectRoot.resolve("/somewhere/else", "project resolve",
                            Confinement.current());
                } catch (ConfinementEscapeException e) {
                    thrown = e;
                }
                assertTrue(thrown != null,
                        "a confinement is about what may be touched, not how it was spelled");
                assertEquals(ProjectRoot.FROM_OPTION, thrown.origin(),
                        "and the message distinguishes the two, because the remedy differs");
            });
        });

        return suite.runAll();
    }

    /** Pin all five home variables and declare the confinement over {@code root}. */
    private static void pinAll(Path root) throws Exception {
        Files.createDirectories(root.resolve("home"));
        Files.createDirectories(root.resolve("agents/.claude"));
        Files.createDirectories(root.resolve("agents/.codex"));
        Files.createDirectories(root.resolve("agents/.gemini"));
        AgentHomes.setOverride(AgentHomes.SKILL_MANAGER_HOME, root.resolve("home"));
        AgentHomes.setOverride(AgentHomes.CLAUDE_HOME, root.resolve("agents"));
        AgentHomes.setOverride(AgentHomes.CLAUDE_CONFIG_DIR, root.resolve("agents/.claude"));
        AgentHomes.setOverride(AgentHomes.CODEX_HOME, root.resolve("agents/.codex"));
        AgentHomes.setOverride(AgentHomes.GEMINI_HOME, root.resolve("agents/.gemini"));
        AgentHomes.setOverride(Confinement.ROOT_ENV, root);
    }

    private interface Body { void run() throws Exception; }

    /**
     * Run {@code body} with a clean override state and restore it afterwards.
     *
     * <p>{@code restoreOverrides} rather than {@code clearOverrides}: this
     * suite runs on a thread that other suites also use, and clearing would
     * silently discard a binding a caller established. AgentHomes' own javadoc
     * makes that distinction and it is not ours to re-decide.
     */
    private static void withOverrides(Body body) throws Exception {
        var snapshot = AgentHomes.snapshotOverrides();
        AgentHomes.clearOverrides();
        try {
            body.run();
        } finally {
            AgentHomes.restoreOverrides(snapshot);
        }
    }

    private ConfinementTest() {}
}
