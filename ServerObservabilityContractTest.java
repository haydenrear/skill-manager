///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES src/test/java/dev/skillmanager/_lib/test/Tests.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/observability/ServerObservability.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/observability/ServerObservabilityFilter.java
//DEPS org.slf4j:slf4j-api:2.0.16
//DEPS org.springframework:spring-webmvc:6.1.13
//DEPS org.springframework:spring-test:6.1.13
//DEPS jakarta.servlet:jakarta.servlet-api:6.0.0
//DEPS io.opentelemetry:opentelemetry-sdk-extension-autoconfigure:1.62.0
//DEPS io.opentelemetry:opentelemetry-exporter-otlp:1.62.0

package dev.skillmanager.server.observability;

import dev.skillmanager._lib.test.Tests;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class ServerObservabilityContractTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "0123456789abcdef";

    public static void main(String[] args) {
        int failures = run();
        if (failures != 0) {
            throw new AssertionError("ServerObservabilityContractTest failures: " + failures);
        }
        System.out.println("ServerObservabilityContractTest: PASS");
    }

    public static int run() {
        return Tests.suite("ServerObservabilityContractTest")
                .test("defaults enable three signals on one route",
                        ServerObservabilityContractTest::defaultsEnableThreeSignalsOnOneRoute)
                .test("W3C propagation uses the active trace identity",
                        ServerObservabilityContractTest::w3cPropagationUsesTheActiveTraceIdentity)
                .test("invalid context does not invent a header",
                        ServerObservabilityContractTest::invalidContextDoesNotInventAHeader)
                .test("trace handle requires lowercase hex",
                        ServerObservabilityContractTest::traceHandleRequiresLowercaseHex)
                .test("HTTP method metrics have bounded dimensions",
                        ServerObservabilityContractTest::httpMethodMetricsHaveBoundedDimensions)
                .test("filter preserves HTTP protocol and exposes incoming trace",
                        ServerObservabilityContractTest::filterPreservesHttpProtocolAndExposesIncomingTrace)
                .test("configured filter creates an agent-visible root trace",
                        ServerObservabilityContractTest::configuredFilterCreatesAnAgentVisibleRootTrace)
                .test("filter rethrows chain failure and reports error telemetry",
                        ServerObservabilityContractTest::filterRethrowsChainFailureAndReportsErrorTelemetry)
                .runAll();
    }

    private static void defaultsEnableThreeSignalsOnOneRoute() {
        Map<String, String> defaults = ServerObservability.configurationDefaults();
        assertEquals("otlp", defaults.get("otel.traces.exporter"), "traces default");
        assertEquals("otlp", defaults.get("otel.metrics.exporter"), "metrics default");
        assertEquals("otlp", defaults.get("otel.logs.exporter"), "logs default");
        assertEquals("http/protobuf", defaults.get("otel.exporter.otlp.protocol"), "OTLP protocol");
        assertEquals("http://localhost:4318", defaults.get("otel.exporter.otlp.endpoint"), "OTLP route");
        assertEquals("tracecontext", defaults.get("otel.propagators"), "W3C propagator");
        assertFalse(defaults.containsKey("otel.exporter.otlp.traces.endpoint"), "no traces-only route");
        assertFalse(defaults.containsKey("otel.exporter.otlp.metrics.endpoint"), "no metrics-only route");
        assertFalse(defaults.containsKey("otel.exporter.otlp.logs.endpoint"), "no logs-only route");
    }

    private static void w3cPropagationUsesTheActiveTraceIdentity() {
        SpanContext spanContext = SpanContext.create(
                TRACE_ID,
                SPAN_ID,
                TraceFlags.getSampled(),
                TraceState.getDefault());
        Context context = Context.root().with(Span.wrap(spanContext));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://example.test"));

        ServerObservability.injectW3c(context, builder);

        String traceparent = builder.build().headers()
                .firstValue("traceparent")
                .orElseThrow(() -> new AssertionError("traceparent missing"));
        assertEquals("00-" + TRACE_ID + "-" + SPAN_ID + "-01", traceparent, "traceparent");
    }

    private static void invalidContextDoesNotInventAHeader() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://example.test"));

        ServerObservability.injectW3c(Context.root(), builder);

        assertFalse(builder.build().headers().firstValue("traceparent").isPresent(), "invalid trace omitted");
    }

    private static void traceHandleRequiresLowercaseHex() {
        assertTrue(new ServerObservability.Operation(Context.root(), TRACE_ID).hasTraceId(), "valid trace id");
        assertFalse(
                new ServerObservability.Operation(Context.root(), TRACE_ID.toUpperCase()).hasTraceId(),
                "uppercase rejected");
        assertFalse(
                new ServerObservability.Operation(Context.root(), "0123").hasTraceId(),
                "short id rejected");
    }

    private static void httpMethodMetricsHaveBoundedDimensions() {
        assertEquals("GET", ServerObservability.normalizeHttpMethod("get"), "known method normalized");
        assertEquals("OTHER", ServerObservability.normalizeHttpMethod("CUSTOM-123"), "unknown method bounded");
        assertEquals("OTHER", ServerObservability.normalizeHttpMethod(null), "missing method bounded");
    }

    private static void filterPreservesHttpProtocolAndExposesIncomingTrace() {
        String traceparent = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader("TrAcEpArEnT", traceparent);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> activeTraceId = new AtomicReference<>();
        ServerObservabilityFilter filter =
                new ServerObservabilityFilter(ServerObservability.disabled());

        try {
            filter.doFilter(request, response, (req, res) -> {
                activeTraceId.set(Span.current().getSpanContext().getTraceId());
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/health");
                ((MockHttpServletResponse) res).setStatus(202);
                res.setContentType("application/json");
                res.getWriter().write("{\"ok\":true}");
            });
        } catch (Exception e) {
            throw new AssertionError("filter threw", e);
        }

        assertEquals(TRACE_ID, response.getHeader(ServerObservabilityFilter.TRACE_ID_HEADER), "trace handle");
        assertEquals(TRACE_ID, activeTraceId.get(), "active trace");
        assertEquals(202, response.getStatus(), "status unchanged");
        assertEquals("application/json", response.getContentType(), "content type unchanged");
        assertEquals(
                "{\"ok\":true}",
                new String(response.getContentAsByteArray(), StandardCharsets.UTF_8),
                "body unchanged");
    }

    private static void configuredFilterCreatesAnAgentVisibleRootTrace() {
        String originalTracesExporter = System.getProperty("otel.traces.exporter");
        String originalMetricsExporter = System.getProperty("otel.metrics.exporter");
        String originalLogsExporter = System.getProperty("otel.logs.exporter");
        ServerObservability observability = null;
        try {
            System.setProperty("otel.traces.exporter", "none");
            System.setProperty("otel.metrics.exporter", "none");
            System.setProperty("otel.logs.exporter", "none");
            observability = ServerObservability.configure();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<String> activeTraceId = new AtomicReference<>();
            ServerObservabilityFilter filter = new ServerObservabilityFilter(observability);

            try {
                filter.doFilter(request, response, (req, res) ->
                        activeTraceId.set(Span.current().getSpanContext().getTraceId()));
            } catch (Exception e) {
                throw new AssertionError("configured filter threw", e);
            }

            String handle = response.getHeader(ServerObservabilityFilter.TRACE_ID_HEADER);
            assertTrue(handle != null && handle.matches("[0-9a-f]{32}"), "root trace handle");
            assertEquals(handle, activeTraceId.get(), "root trace remains active");
        } finally {
            if (observability != null) observability.close();
            restoreProperty("otel.traces.exporter", originalTracesExporter);
            restoreProperty("otel.metrics.exporter", originalMetricsExporter);
            restoreProperty("otel.logs.exporter", originalLogsExporter);
        }
    }

    private static void filterRethrowsChainFailureAndReportsErrorTelemetry() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/failure");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServerObservabilityFilter filter =
                new ServerObservabilityFilter(ServerObservability.disabled());
        jakarta.servlet.ServletException expected =
                new jakarta.servlet.ServletException("expected failure");
        jakarta.servlet.ServletException observed = null;

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw expected;
            });
        } catch (jakarta.servlet.ServletException failure) {
            observed = failure;
        } catch (Exception failure) {
            throw new AssertionError("wrong failure type", failure);
        }

        assertTrue(observed == expected, "chain failure rethrown unchanged");
        assertEquals(200, response.getStatus(), "HTTP response status remains untouched");
        assertEquals(
                500,
                ServerObservabilityFilter.telemetryStatus(response.getStatus(), true),
                "thrown chain failure is classified as error telemetry");
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void assertFalse(boolean condition, String label) {
        if (condition) throw new AssertionError(label);
    }
}
