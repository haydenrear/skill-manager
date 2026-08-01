package com.hayden.testgraphsdk.sdk;

import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class NodeTelemetryTest {

    @Test
    void extractsTheGraphCarrierAndKeepsTerminalFlushBounded() {
        System.setProperty("otel.traces.exporter", "none");
        System.setProperty("otel.metrics.exporter", "none");
        System.setProperty("otel.logs.exporter", "none");
        var traceId = "0123456789abcdef0123456789abcdef";
        var environment = new HashMap<String, String>();
        environment.put(
                "traceparent",
                "00-" + traceId + "-0123456789abcdef-01");

        var telemetry = Node.NodeTelemetry.configure(
                NodeSpec.of("probe").kind(NodeSpec.Kind.EVIDENCE),
                environment);

        assertEquals(
                traceId,
                Span.fromContext(telemetry.parentContext()).getSpanContext().getTraceId());
        telemetry.nodeStart();
        telemetry.nodeResult("passed", 2.0);
        assertTimeout(
                java.time.Duration.ofSeconds(1),
                () -> telemetry.flush(10));
    }
}
