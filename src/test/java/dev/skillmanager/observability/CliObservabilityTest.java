package dev.skillmanager.observability;

import com.sun.net.httpserver.HttpServer;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.SkillManagerCli;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.GatewayRuntime;
import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.store.SkillStore;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class CliObservabilityTest {
    private static final String PARENT_TRACE_ID =
            "4bf92f3577b34da6a3ce929d0e0e4736";

    public static int run() {
        return Tests.suite("CliObservabilityTest")
                .test("valid W3C parent is retained and injected for a child process", () -> {
                    Map<String, String> env = disabledExporters();
                    env.put("traceparent",
                            "00-" + PARENT_TRACE_ID + "-00f067aa0ba902b7-01");
                    env.put("tracestate", "vendor=opaque");

                    CliObservability telemetry = CliObservability.configure(env);
                    assertNotNull(telemetry, "telemetry configured");
                    try {
                        try (Scope ignored = telemetry.makeCurrent()) {
                            assertEquals(PARENT_TRACE_ID,
                                    CliObservability.currentTraceId(),
                                    "incoming trace identity retained");

                            Map<String, String> child = new HashMap<>();
                            child.put("TRACEPARENT",
                                    "00-11111111111111111111111111111111-2222222222222222-01");
                            CliObservability.injectEnvironment(child);

                            String propagated = child.get("traceparent");
                            assertNotNull(propagated, "lowercase traceparent injected");
                            assertTrue(propagated.startsWith(
                                            "00-" + PARENT_TRACE_ID + "-"),
                                    "child carrier retains trace id");
                            assertTrue(!child.containsKey("TRACEPARENT"),
                                    "stale uppercase carrier removed");
                            assertEquals("vendor=opaque", child.get("tracestate"),
                                    "tracestate retained");
                            telemetry.complete("gateway up", 0);
                        }
                    } finally {
                        telemetry.flushAndClose(500);
                    }
                })
                .test("invalid W3C parent starts a fresh valid trace", () -> {
                    Map<String, String> env = disabledExporters();
                    env.put("traceparent",
                            "00-00000000000000000000000000000000"
                                    + "-0000000000000000-01");

                    CliObservability telemetry = CliObservability.configure(env);
                    assertNotNull(telemetry, "telemetry configured");
                    try {
                        String traceId = telemetry.traceId();
                        assertTrue(traceId != null && traceId.matches("[0-9a-f]{32}"),
                                "fresh lowercase trace id");
                        assertTrue(!"00000000000000000000000000000000".equals(traceId),
                                "fresh trace id is non-zero");
                    } finally {
                        telemetry.flushAndClose(500);
                    }
                })
                .test("active W3C context reaches registry and gateway HTTP boundaries", () -> {
                    List<String> traceparents = new CopyOnWriteArrayList<>();
                    HttpServer server = HttpServer.create(
                            new InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/health", exchange -> {
                        traceparents.add(exchange.getRequestHeaders()
                                .getFirst("traceparent"));
                        exchange.sendResponseHeaders(204, -1);
                        exchange.close();
                    });
                    server.start();
                    URI baseUrl = URI.create("http://127.0.0.1:"
                            + server.getAddress().getPort());

                    Map<String, String> env = disabledExporters();
                    env.put("traceparent",
                            "00-" + PARENT_TRACE_ID + "-00f067aa0ba902b7-01");
                    CliObservability telemetry = CliObservability.configure(env);
                    try {
                        try (Scope ignored = telemetry.makeCurrent()) {
                            assertFalse(Span.current().isRecording(),
                                    "network waits retain only a non-recording context");
                            assertTrue(new RegistryClient(RegistryConfig.of(baseUrl)).ping(),
                                    "registry health request succeeded");
                            assertTrue(new GatewayClient(GatewayConfig.of(baseUrl)).ping(),
                                    "gateway client health request succeeded");
                            GatewayRuntime runtime = new GatewayRuntime(new SkillStore(
                                    Files.createTempDirectory("gateway-observability-")));
                            assertTrue(runtime.waitForHealthy(
                                            baseUrl.toString(), Duration.ofSeconds(2)),
                                    "gateway startup health request succeeded");
                        }
                    } finally {
                        telemetry.flushAndClose(500);
                        server.stop(0);
                    }

                    assertEquals(3, traceparents.size(),
                            "all HTTP boundaries reached the server");
                    for (String traceparent : traceparents) {
                        assertNotNull(traceparent, "traceparent injected");
                        assertTrue(traceparent.startsWith(
                                        "00-" + PARENT_TRACE_ID + "-"),
                                "HTTP carrier retains trace id");
                    }
                })
                .test("invalid SDK configuration retains incoming W3C propagation", () -> {
                    Map<String, String> env = disabledExporters();
                    env.put("traceparent",
                            "00-" + PARENT_TRACE_ID + "-00f067aa0ba902b7-01");
                    env.put("tracestate", "vendor=opaque");
                    env.put("OTEL_PROPAGATORS", "not-a-real-propagator");
                    CliObservability telemetry = CliObservability.configure(env);
                    assertNotNull(telemetry, "propagation-only telemetry returned");
                    assertFalse(telemetry.deliveryEnabled(),
                            "failed SDK owns no signal delivery");
                    try {
                        try (Scope ignored = telemetry.makeCurrent()) {
                            assertEquals(PARENT_TRACE_ID,
                                    CliObservability.currentTraceId(),
                                    "incoming trace remains agent-visible");
                            Map<String, String> child = new HashMap<>();
                            CliObservability.injectEnvironment(child);
                            assertTrue(child.get("traceparent").startsWith(
                                            "00-" + PARENT_TRACE_ID + "-"),
                                    "incoming trace reaches the child process");
                            assertEquals("vendor=opaque", child.get("tracestate"),
                                    "incoming tracestate reaches the child process");
                            telemetry.complete("gateway up", 0);
                        }
                    } finally {
                        telemetry.flushAndClose(500);
                    }
                })
                .test("propagation-only fallback does not invent an absent trace", () -> {
                    Map<String, String> env = disabledExporters();
                    env.put("OTEL_PROPAGATORS", "not-a-real-propagator");
                    CliObservability telemetry = CliObservability.configure(env);
                    assertNotNull(telemetry, "propagation-only telemetry returned");
                    try {
                        try (Scope ignored = telemetry.makeCurrent()) {
                            assertTrue(CliObservability.currentTraceId() == null,
                                    "absent parent remains absent");
                            Map<String, String> child = new HashMap<>();
                            child.put("TRACEPARENT",
                                    "00-11111111111111111111111111111111"
                                            + "-2222222222222222-01");
                            CliObservability.injectEnvironment(child);
                            assertFalse(child.containsKey("TRACEPARENT"),
                                    "stale uppercase carrier removed");
                            assertFalse(child.containsKey("traceparent"),
                                    "no child trace is invented");
                            assertFalse(CliObservability.injectCurrentW3c(
                                            java.net.http.HttpRequest.newBuilder(
                                                    URI.create("https://example.test")))
                                            .build()
                                            .headers()
                                            .firstValue("traceparent")
                                            .isPresent(),
                                    "no HTTP trace is invented");
                        }
                    } finally {
                        telemetry.flushAndClose(500);
                    }
                })
                .test("flush and provider shutdown share a finite deadline", () -> {
                    CliObservability telemetry =
                            CliObservability.configure(disabledExporters());
                    assertNotNull(telemetry, "telemetry configured");
                    long started = System.nanoTime();
                    telemetry.flushAndClose(100);
                    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
                    assertTrue(elapsedMillis < 1_000,
                            "flush completed within a bounded wall-clock interval");
                })
                .test("short-lived retry default preserves an explicit operator override", () -> {
                    assertEquals("true",
                            CliObservability.defaultProperties(Map.of())
                                    .get("otel.java.exporter.otlp.retry.disabled"),
                            "CLI disables exporter retry by default");
                    Map<String, String> env = new HashMap<>();
                    env.put("OTEL_JAVA_EXPORTER_OTLP_RETRY_DISABLED", "false");
                    assertEquals("false",
                            CliObservability.defaultProperties(env)
                                    .get("otel.java.exporter.otlp.retry.disabled"),
                            "operator can explicitly restore SDK retry behavior");
                })
                .test("unavailable OTLP is concise, fail-open, and bounded", () -> {
                    AtomicInteger rejectedRequests = new AtomicInteger();
                    HttpServer unavailable = HttpServer.create(
                            new InetSocketAddress("127.0.0.1", 0), 0);
                    unavailable.createContext("/", exchange -> {
                        rejectedRequests.incrementAndGet();
                        exchange.sendResponseHeaders(503, -1);
                        exchange.close();
                    });
                    unavailable.start();
                    try {
                        String endpoint = "http://127.0.0.1:"
                                + unavailable.getAddress().getPort();
                        Path home = Files.createTempDirectory("cli-otel-unavailable-");
                        String java = Path.of(System.getProperty("java.home"), "bin", "java")
                                .toString();
                        ProcessBuilder builder = new ProcessBuilder(
                                java,
                                "-Dotel.sdk.disabled=false",
                                "-Dotel.exporter.otlp.endpoint=" + endpoint,
                                "-Dotel.exporter.otlp.protocol=http/protobuf",
                                "-Dotel.traces.exporter=otlp",
                                "-Dotel.metrics.exporter=otlp",
                                "-Dotel.logs.exporter=otlp",
                                "-Dotel.java.exporter.otlp.retry.disabled=true",
                                "-cp",
                                System.getProperty("java.class.path"),
                                CliProcess.class.getName());
                        Map<String, String> environment = builder.environment();
                        environment.put("SKILL_MANAGER_HOME", home.toString());
                        environment.put("OTEL_EXPORTER_OTLP_ENDPOINT", endpoint);
                        environment.put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
                        environment.put("OTEL_TRACES_EXPORTER", "otlp");
                        environment.put("OTEL_METRICS_EXPORTER", "otlp");
                        environment.put("OTEL_LOGS_EXPORTER", "otlp");
                        environment.remove("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
                        environment.remove("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT");
                        environment.remove("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT");
                        environment.remove("OTEL_JAVA_EXPORTER_OTLP_RETRY_DISABLED");
                        builder.redirectErrorStream(true);

                        long started = System.nanoTime();
                        Process process = builder.start();
                        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                            try {
                                return new String(process.getInputStream().readAllBytes(),
                                        StandardCharsets.UTF_8);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
                        boolean exited = process.waitFor(8, TimeUnit.SECONDS);
                        if (!exited) {
                            process.destroyForcibly();
                            process.waitFor();
                        }
                        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
                        String text = output.get(2, TimeUnit.SECONDS);

                        assertTrue(exited, "CLI exited before its telemetry deadline");
                        assertEquals(0, process.exitValue(), "CLI exit status remains successful");
                        assertTrue(rejectedRequests.get() > 0,
                                "CLI attempted delivery to the rejecting OTLP endpoint");
                        assertTrue(text.contains("skill-manager "),
                                "normal version output is preserved");
                        assertTrue(occurrences(text,
                                        "Telemetry export unavailable; continuing without telemetry.") <= 1,
                                "unavailable delivery emits at most one fail-open notice");
                        assertFalse(text.contains("Failed to export"),
                                "SDK exporter failure messages are suppressed");
                        assertFalse(text.contains("Exporter failed"),
                                "metric-reader duplicate messages are suppressed");
                        assertFalse(text.contains("\tat "),
                                "SDK exception stack frames are suppressed");
                        assertTrue(elapsedMillis < 8_000,
                                "unavailable exporter does not consume retry backoff");
                    } finally {
                        unavailable.stop(0);
                    }
                })
                .test("SDK failure records collapse to one process-wide notice", () -> {
                    Path home = Files.createTempDirectory("cli-otel-filter-");
                    String java = Path.of(System.getProperty("java.home"), "bin", "java")
                            .toString();
                    ProcessBuilder builder = new ProcessBuilder(
                            java,
                            "-Dotel.sdk.disabled=false",
                            "-Dotel.traces.exporter=none",
                            "-Dotel.metrics.exporter=none",
                            "-Dotel.logs.exporter=none",
                            "-cp",
                            System.getProperty("java.class.path"),
                            ExportFailureFilterProcess.class.getName());
                    builder.environment().put("SKILL_MANAGER_HOME", home.toString());
                    builder.redirectErrorStream(true);

                    Process process = builder.start();
                    CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                        try {
                            return new String(process.getInputStream().readAllBytes(),
                                    StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                    boolean exited = process.waitFor(5, TimeUnit.SECONDS);
                    if (!exited) {
                        process.destroyForcibly();
                        process.waitFor();
                    }
                    String text = output.get(2, TimeUnit.SECONDS);

                    assertTrue(exited, "filter fixture exits within its finite deadline");
                    assertEquals(0, process.exitValue(), "filter fixture exits successfully");
                    assertEquals(1, occurrences(text,
                                    "Telemetry export unavailable; continuing without telemetry."),
                            "three SDK-shaped failures produce one notice");
                    assertFalse(text.contains("Failed to export"),
                            "SDK exporter failure records are suppressed");
                    assertFalse(text.contains("Exporter failed"),
                            "metric-reader duplicate records are suppressed");
                    assertFalse(text.contains("\tat "),
                            "filter notice has no exception stack");
                })
                .runAll();
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int from = 0; (from = text.indexOf(needle, from)) >= 0;
                from += needle.length()) {
            count++;
        }
        return count;
    }

    public static final class CliProcess {
        public static void main(String[] args) {
            System.exit(SkillManagerCli.run(new String[]{"--version"}));
        }
    }

    public static final class ExportFailureFilterProcess {
        public static void main(String[] args) {
            CliObservability observability = CliObservability.configure(Map.of(
                    "OTEL_TRACES_EXPORTER", "none",
                    "OTEL_METRICS_EXPORTER", "none",
                    "OTEL_LOGS_EXPORTER", "none"));
            CliObservability.HTTP_EXPORTER_JUL_LOGGER
                    .log(java.util.logging.Level.SEVERE,
                            "Failed to export spans. Deterministic test fixture.");
            CliObservability.HTTP_EXPORTER_JUL_LOGGER
                    .log(java.util.logging.Level.SEVERE,
                            "Failed to export logs. Deterministic test fixture.");
            CliObservability.METRIC_READER_JUL_LOGGER
                    .log(java.util.logging.Level.SEVERE, "Exporter failed");
            observability.flushAndClose(1_000);
        }
    }

    private static Map<String, String> disabledExporters() {
        Map<String, String> env = new HashMap<>();
        env.put("OTEL_TRACES_EXPORTER", "none");
        env.put("OTEL_METRICS_EXPORTER", "none");
        env.put("OTEL_LOGS_EXPORTER", "none");
        return env;
    }
}
