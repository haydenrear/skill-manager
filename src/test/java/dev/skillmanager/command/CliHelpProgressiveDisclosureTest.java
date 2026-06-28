package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.SkillManagerCli;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class CliHelpProgressiveDisclosureTest {

    public static int run() {
        Tests.Suite suite = Tests.suite("CliHelpProgressiveDisclosureTest");

        suite.test("root help stays shallow", () -> {
            Result result = execute("--help");
            assertEquals(0, result.rc(), "root help rc");
            assertContains(result.out(), "Usage: skill-manager", "root usage");
            assertContains(result.out(), "install", "root lists install");
            assertContains(result.out(), "sync", "root lists sync");
            assertTrue(!result.out().contains("What install does:"), "root omits install workflow detail");
            assertTrue(!result.out().contains("Plugin vs skill detection"), "root omits install detection detail");
            assertTrue(!result.out().contains("Sync modes:"), "root omits sync workflow detail");
            assertTrue(!result.out().contains("Examples:"), "root omits bind examples");
            assertTrue(result.out().lines().count() < 80, "root help line count is bounded");
        });

        suite.test("representative command help is direct and complete", () -> {
            Result install = execute("install", "--help");
            assertEquals(0, install.rc(), "install help rc");
            assertContains(install.out(), "What install does:", "install workflow detail");
            assertContains(install.out(), "--force-scripts", "install force scripts option");
            assertTrue(!install.err().contains("Missing required parameter"), "install help bypasses params");

            Result sync = execute("sync", "--help");
            assertEquals(0, sync.rc(), "sync help rc");
            assertContains(sync.out(), "Sync modes:", "sync workflow detail");
            assertContains(sync.out(), "--force-scripts", "sync force scripts option");
            assertTrue(!sync.err().contains("Unknown option"), "sync help is recognized");

            Result profiles = execute("project", "profiles", "list", "--help");
            assertEquals(0, profiles.rc(), "project profiles list help rc");
            assertContains(profiles.out(), "List profiles declared in skill-project.toml.",
                    "nested help body");
            assertTrue(!profiles.err().contains("Unknown option"), "nested help is recognized");
        });

        return suite.runAll();
    }

    private static Result execute(String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out));
            System.setErr(new PrintStream(err));
            int rc = new CommandLine(new SkillManagerCli()).execute(args);
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private record Result(int rc, String out, String err) {}
}
