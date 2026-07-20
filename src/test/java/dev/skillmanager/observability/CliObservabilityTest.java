package dev.skillmanager.observability;

import com.sun.net.httpserver.HttpServer;
import dev.skillmanager._lib.test.Tests;
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
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
                .runAll();
    }

    private static Map<String, String> disabledExporters() {
        Map<String, String> env = new HashMap<>();
        env.put("OTEL_TRACES_EXPORTER", "none");
        env.put("OTEL_METRICS_EXPORTER", "none");
        env.put("OTEL_LOGS_EXPORTER", "none");
        return env;
    }
}
