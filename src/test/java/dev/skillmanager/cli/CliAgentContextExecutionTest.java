package dev.skillmanager.cli;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.observability.CliObservability;
import dev.skillmanager.registry.RegistryUnavailableException;
import io.opentelemetry.context.Scope;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

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
                    CliObservability telemetry =
                            CliObservability.configure(disabledExporters());
                    assertNotNull(telemetry, "telemetry configured");
                    try {
                        System.setProperty("user.home", tmpHome.toString());
                        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
                        try (Scope ignored = telemetry.makeCurrent()) {
                            int rc = SkillManagerCli.handleExecutionException(ex, cmd, pr);
                            assertEquals(RegistryUnavailableException.EXIT_CODE, rc,
                                    "registry-unavailable exit code");
                        }
                    } finally {
                        System.setErr(originalErr);
                        System.setProperty("user.home", originalUserHome);
                        telemetry.flushAndClose(500);
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
                    assertTrue(rendered.matches(
                                    "(?s).*trace_id: [0-9a-f]{32}\\R.*"),
                            "valid trace id emitted");
                    assertContains(rendered, CliAgentContext.END,
                            "agent context end emitted");
                })
                .test("instrumented CLI keeps help stdout free of agent context", () -> {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ByteArrayOutputStream err = new ByteArrayOutputStream();
                    PrintStream originalOut = System.out;
                    PrintStream originalErr = System.err;
                    String originalUserHome = System.getProperty("user.home");
                    String originalTracesExporter =
                            System.getProperty("otel.traces.exporter");
                    String originalMetricsExporter =
                            System.getProperty("otel.metrics.exporter");
                    String originalLogsExporter =
                            System.getProperty("otel.logs.exporter");
                    Path tmpHome = Files.createTempDirectory("sm-observability-home-");
                    int rc;
                    try {
                        System.setProperty("user.home", tmpHome.toString());
                        System.setProperty("otel.traces.exporter", "none");
                        System.setProperty("otel.metrics.exporter", "none");
                        System.setProperty("otel.logs.exporter", "none");
                        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
                        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
                        rc = SkillManagerCli.run(new String[]{
                                "--agent-context", "--help"
                        });
                    } finally {
                        System.setOut(originalOut);
                        System.setErr(originalErr);
                        System.setProperty("user.home", originalUserHome);
                        restoreProperty("otel.traces.exporter", originalTracesExporter);
                        restoreProperty("otel.metrics.exporter", originalMetricsExporter);
                        restoreProperty("otel.logs.exporter", originalLogsExporter);
                    }

                    String stdout = out.toString(StandardCharsets.UTF_8);
                    String stderr = err.toString(StandardCharsets.UTF_8);
                    assertEquals(0, rc, "help exit code");
                    assertContains(stdout, "Usage: skill-manager",
                            "help remains on stdout");
                    assertTrue(!stdout.contains(CliAgentContext.BEGIN),
                            "agent context is absent from stdout");
                    assertTrue(!stdout.contains("trace_id:"),
                            "trace handle is absent from stdout");
                    assertContains(stderr, CliAgentContext.BEGIN,
                            "agent context is emitted on stderr");
                    assertTrue(stderr.matches(
                                    "(?s).*trace_id: [0-9a-f]{32}\\R.*"),
                            "stderr carries a valid trace handle");
                    assertContains(stderr, CliAgentContext.END,
                            "agent context end emitted");
                })
                .runAll();
    }

    private static Map<String, String> disabledExporters() {
        Map<String, String> env = new HashMap<>();
        env.put("OTEL_TRACES_EXPORTER", "none");
        env.put("OTEL_METRICS_EXPORTER", "none");
        env.put("OTEL_LOGS_EXPORTER", "none");
        return env;
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }
}
