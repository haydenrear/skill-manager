package com.hayden.testgraphsdk.sdk;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JBang half of the {@code test-graph-subprocess-w3c-propagation} contract.
 *
 * The executor hands a node its graph context in the environment. Anything the
 * node then launches through {@link Procs} must be able to join the same trace,
 * including when the node replaces the child's environment rather than
 * extending it — the one case plain {@link ProcessBuilder} inheritance misses.
 */
class ProcsTracePropagationTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String TRACEPARENT =
            "00-" + TRACE_ID + "-b7ad6b7169203331-01";

    private static final TextMapGetter<Map<String, String>> GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier == null ? null : carrier.get(key);
                }
            };

    @Test
    void aClearedChildEnvironmentStillJoinsTheNodeTrace() throws IOException {
        NodeContext ctx = context();
        ProcessBuilder pb = echoTraceparent();
        pb.environment().clear();

        ProcessRecord record;
        try (Scope ignored = graphContext().makeCurrent()) {
            record = Procs.run(ctx, "cleared-env", pb);
        }

        assertEquals(0, record.exitCode());
        assertNull(record.error());
        assertEquals(TRACEPARENT, captured(ctx, "cleared-env"));
    }

    @Test
    void anExtendedChildEnvironmentAlsoJoinsTheNodeTrace() throws IOException {
        NodeContext ctx = context();

        ProcessRecord record;
        try (Scope ignored = graphContext().makeCurrent()) {
            record = Procs.run(ctx, "inherited-env", echoTraceparent());
        }

        assertEquals(0, record.exitCode());
        assertEquals(TRACEPARENT, captured(ctx, "inherited-env"));
    }

    @Test
    void aSpawnedChildStillRunsWhenNoTraceContextIsAvailable() throws IOException {
        NodeContext ctx = context();
        ProcessBuilder pb = echoTraceparent();
        pb.environment().clear();

        ProcessRecord record = Procs.run(ctx, "no-context", pb);

        assertEquals(0, record.exitCode(), "absent telemetry must never fail the child");
        assertNull(record.error());
        assertTrue(captured(ctx, "no-context").isEmpty());
    }

    private Context graphContext() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put("traceparent", TRACEPARENT);
        return W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), carrier, GETTER);
    }

    private ProcessBuilder echoTraceparent() {
        return new ProcessBuilder(
                "/bin/sh", "-c", "printf '%s' \"${traceparent}\"");
    }

    private NodeContext context() throws IOException {
        Path reportDir = Files.createTempDirectory("test-graph-procs-propagation");
        reportDir.toFile().deleteOnExit();
        return NodeContext.parse(new String[] {
                "--nodeId=probe",
                "--runId=run-1",
                "--reportDir=" + reportDir,
        });
    }

    private String captured(NodeContext ctx, String label) throws IOException {
        return Files.readString(Procs.logFile(ctx, label)).trim();
    }
}
