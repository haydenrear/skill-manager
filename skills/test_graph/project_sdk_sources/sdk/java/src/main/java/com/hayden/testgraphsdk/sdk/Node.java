package com.hayden.testgraphsdk.sdk;

//DEPS io.opentelemetry:opentelemetry-sdk-extension-autoconfigure:1.62.0
//DEPS io.opentelemetry:opentelemetry-exporter-otlp:1.62.0

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for JBang-executed validation nodes.
 *
 * The same script serves two purposes:
 *
 *   (1) Discovery — the plugin invokes with {@code --describe-out=<path>}.
 *       We serialize the {@link NodeSpec} to that path and exit 0.
 *   (2) Execution — the plugin invokes with full context args plus
 *       {@code --result-out=<path>}. We parse a {@link NodeContext},
 *       invoke the body, write the resulting {@link NodeResult} JSON
 *       to {@code --result-out}, and exit 0.
 *
 * The envelope under {@code reportDir/envelope/} is no longer this
 * script's responsibility. The build-logic {@code PlanExecutor}
 * post-processes {@code --result-out} into the canonical envelope —
 * stamping executor-measured timing, recording the captured-stdout
 * path, and synthesizing a fallback envelope when this script never
 * gets a chance to write its result. Centralizing that authorship
 * means features like the unified {@code report.md} live in one
 * Kotlin code path, not duplicated across Java and Python SDKs.
 *
 * Usage:
 *   Node.run(args,
 *       NodeSpec.of("my.node").kind(NodeSpec.Kind.ASSERTION).dependsOn("other"),
 *       ctx -> NodeResult.pass("my.node").assertion("ok", true));
 */
public final class Node {

    private Node() {}

    public static void run(String[] args, com.hayden.testgraphsdk.sdk.NodeSpec spec, com.hayden.testgraphsdk.sdk.NodeBody body) {
        String describeOut = findArg(args, "--describe-out=");
        if (describeOut != null) {
            writeDescribe(describeOut, spec);
            return;
        }

        NodeTelemetry telemetry = null;
        try {
            telemetry = NodeTelemetry.configure(spec);
        } catch (Throwable ignored) {
            // Telemetry configuration is fail-open.
        }

        try (Scope ignored = telemetry == null ? Context.root().makeCurrent() : telemetry.makeCurrent()) {
            com.hayden.testgraphsdk.sdk.NodeContext ctx = com.hayden.testgraphsdk.sdk.NodeContext.parse(args);
            if (!ctx.nodeId().equals(spec.id())) {
                throw new IllegalStateException(
                        "spec/runtime id mismatch: spec=" + spec.id() + ", arg=" + ctx.nodeId());
            }

            if (telemetry != null) telemetry.nodeStart();
            Instant startedAt = Instant.now();
            long startedNanos = System.nanoTime();
            com.hayden.testgraphsdk.sdk.NodeResult result;
            try {
                result = body.apply(ctx).startedAt(startedAt).endedAt(Instant.now());
            } catch (Throwable t) {
                result = com.hayden.testgraphsdk.sdk.NodeResult.error(ctx.nodeId(), t)
                        .startedAt(startedAt)
                        .endedAt(Instant.now());
            }

            if (telemetry != null) {
                telemetry.nodeResult(
                        result.status().wire(),
                        (System.nanoTime() - startedNanos) / 1_000_000.0);
            }
            String resultOut = findArg(args, "--result-out=");
            if (resultOut != null) {
                writeResultOut(resultOut, result);
            }
        } finally {
            if (telemetry != null) telemetry.flush(5_000);
        }
        // Exit 0 regardless of status: the executor decides pass/fail
        // from the parsed envelope's status field. A non-zero exit
        // would be redundant for the executor and would mislead
        // operators who run a node script directly to inspect output.
        System.exit(0);
    }

    /**
     * Native JVM bootstrap scoped to the existing Node.run entry point.
     * It keeps only the extracted W3C context active while the body runs;
     * the recording spans cover the bounded start/result operations only.
     */
    static final class NodeTelemetry {
        private static final TextMapGetter<Map<String, String>> ENV_GETTER =
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

        private final OpenTelemetrySdk sdk;
        private final Context parent;
        private final String nodeId;
        private final Tracer tracer;
        private final Logger logger;
        private final LongCounter started;
        private final LongCounter completed;
        private final DoubleHistogram duration;
        private final LongCounter traceCorrelation;

        private NodeTelemetry(OpenTelemetrySdk sdk, Context parent, String nodeId) {
            this.sdk = sdk;
            this.parent = parent;
            this.nodeId = nodeId;
            this.tracer = sdk.getTracer("com.hayden.testgraphsdk.sdk");
            this.logger = sdk.getLogsBridge().get("com.hayden.testgraphsdk.sdk");
            var meter = sdk.getMeter("com.hayden.testgraphsdk.sdk");
            this.started = meter.counterBuilder("test_graph.node.started").build();
            this.completed = meter.counterBuilder("test_graph.node.completed").build();
            this.duration = meter.histogramBuilder("test_graph.node.duration")
                    .setUnit("ms")
                    .build();
            this.traceCorrelation = meter
                    .counterBuilder("tracing_observability.trace_correlation")
                    .setUnit("{operation}")
                    .setDescription("Bounded operations selected for trace correlation.")
                    .build();
        }

        static NodeTelemetry configure(NodeSpec spec) {
            return configure(spec, System.getenv());
        }

        static NodeTelemetry configure(NodeSpec spec, Map<String, String> env) {
            Map<String, String> defaults = new HashMap<>();
            defaults.put("otel.service.name",
                    env.getOrDefault("OTEL_SERVICE_NAME", "test-graph-node-java"));
            defaults.put("otel.traces.exporter",
                    env.getOrDefault("OTEL_TRACES_EXPORTER", "otlp"));
            defaults.put("otel.metrics.exporter",
                    env.getOrDefault("OTEL_METRICS_EXPORTER", "otlp"));
            defaults.put("otel.logs.exporter",
                    env.getOrDefault("OTEL_LOGS_EXPORTER", "otlp"));
            defaults.put("otel.exporter.otlp.protocol",
                    env.getOrDefault("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf"));
            if (!env.containsKey("OTEL_EXPORTER_OTLP_ENDPOINT")
                    && !env.containsKey("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
                    && !env.containsKey("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT")
                    && !env.containsKey("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT")) {
                defaults.put("otel.exporter.otlp.endpoint", "http://localhost:4318");
            }

            OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                    .addPropertiesSupplier(() -> defaults)
                    .build()
                    .getOpenTelemetrySdk();
            Context parent = sdk.getPropagators().getTextMapPropagator()
                    .extract(Context.root(), env, ENV_GETTER);
            return new NodeTelemetry(sdk, parent, spec.id());
        }

        Context parentContext() {
            return parent;
        }

        Scope makeCurrent() {
            return parent.makeCurrent();
        }

        void nodeStart() {
            Attributes attributes = attributes(null);
            bounded("test_graph.node.start", attributes, () -> {
                started.add(1, attributes, Context.current());
                emit("test_graph.node.started", attributes);
            });
        }

        void nodeResult(String status, double durationMillis) {
            Attributes attributes = attributes(status);
            bounded("test_graph.node.result", attributes, () -> {
                completed.add(1, attributes, Context.current());
                duration.record(durationMillis, attributes, Context.current());
                emit("test_graph.node.completed", attributes);
            });
        }

        void bounded(String name, Attributes attributes, Runnable operation) {
            try {
                Span span = tracer.spanBuilder(name).setParent(parent).startSpan();
                try {
                    span.setAllAttributes(attributes);
                    Context active = parent.with(span);
                    traceCorrelation.add(
                            1,
                            Attributes.builder()
                                    .put("trace_id", span.getSpanContext().getTraceId())
                                    .build(),
                            active);
                    try (Scope ignored = active.makeCurrent()) {
                        operation.run();
                    }
                } finally {
                    span.end();
                }
            } catch (Throwable ignored) {
                // Telemetry must not change the node result.
            }
        }

        void emit(String body, Attributes attributes) {
            try {
                logger.logRecordBuilder()
                        .setContext(Context.current())
                        .setBody(body)
                        .setAllAttributes(attributes)
                        .emit();
            } catch (Throwable ignored) {
                // Direct OTLP logging is diagnostic and fail-open.
            }
        }

        Attributes attributes(String status) {
            var builder = Attributes.builder()
                    .put("test_graph.node.id", nodeId)
                    .put("test_graph.node.runtime", "jbang");
            if (status != null) builder.put("test_graph.node.status", status);
            return builder.build();
        }

        void flush(long timeoutMillis) {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    sdk.getSdkMeterProvider().forceFlush()
                            .join(remaining, TimeUnit.NANOSECONDS);
                }
                remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    sdk.getSdkLoggerProvider().forceFlush()
                            .join(remaining, TimeUnit.NANOSECONDS);
                }
                remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    sdk.getSdkTracerProvider().forceFlush()
                            .join(remaining, TimeUnit.NANOSECONDS);
                }
            } catch (Throwable ignored) {
                // One bounded terminal request; delivery failure is fail-open.
            }
        }
    }

    private static String findArg(String[] args, String prefix) {
        for (String a : args) if (a.startsWith(prefix)) return a.substring(prefix.length());
        return null;
    }

    private static void writeDescribe(String outPath, com.hayden.testgraphsdk.sdk.NodeSpec spec) {
        try {
            Path out = Path.of(outPath);
            Path parent = out.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(out, spec.toJson());
            System.exit(0);
        } catch (Exception e) {
            System.err.println("failed to write describe output: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void writeResultOut(String outPath, com.hayden.testgraphsdk.sdk.NodeResult result) {
        try {
            Path out = Path.of(outPath);
            Path parent = out.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(out, result.toJson());
        } catch (Exception e) {
            // The executor will detect a missing / empty result-out
            // and synthesize an error envelope — so a write failure
            // here doesn't lose the run, it just downgrades it to a
            // synthesized "missing result-out" envelope with the
            // captured stdout as evidence.
            System.err.println("failed to write --result-out: " + e.getMessage());
        }
    }
}
