package dev.skillmanager.cli;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.registry.RegistryUnavailableException;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;

public final class CliAgentContextExecutionTest {

    public static int run() {
        return Tests.suite("CliAgentContextExecutionTest")
                .test("handled execution exceptions emit failed agent context", () -> {
                    CommandLine cmd = new CommandLine(new SkillManagerCli());
                    CommandLine.ParseResult pr = cmd.parseArgs(
                            "--agent-context", "search", "demo",
                            "--registry", "http://127.0.0.1:1");
                    RegistryUnavailableException ex = new RegistryUnavailableException(
                            "http://127.0.0.1:1",
                            new ConnectException("Connection refused"));

                    ByteArrayOutputStream err = new ByteArrayOutputStream();
                    PrintStream originalErr = System.err;
                    String originalUserHome = System.getProperty("user.home");
                    Path tmpHome = Files.createTempDirectory("sm-agent-context-home-");
                    try {
                        System.setProperty("user.home", tmpHome.toString());
                        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
                        int rc = SkillManagerCli.handleExecutionException(ex, cmd, pr);
                        assertEquals(RegistryUnavailableException.EXIT_CODE, rc,
                                "registry-unavailable exit code");
                    } finally {
                        System.setErr(originalErr);
                        System.setProperty("user.home", originalUserHome);
                    }

                    String rendered = err.toString(StandardCharsets.UTF_8);
                    assertContains(rendered, "ERROR: registry unreachable",
                            "registry banner still renders");
                    assertContains(rendered, CliAgentContext.BEGIN,
                            "agent context begin emitted");
                    assertContains(rendered, "command_path: search",
                            "failing command path retained");
                    assertContains(rendered, "exit_code: " + RegistryUnavailableException.EXIT_CODE,
                            "failing exit code retained");
                    assertContains(rendered, "status: failed",
                            "failed status emitted");
                    assertContains(rendered, CliAgentContext.END,
                            "agent context end emitted");
                })
                .runAll();
    }
}
