package dev.skillmanager.observability;

import dev.skillmanager.cli.CliAgentContext;
import dev.skillmanager.util.Log;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;

import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Filter;

/**
 * Process-owned observability lifecycle for the native skill-manager CLI.
 *
 * <p>The context held while a command runs contains only a non-recording W3C
 * trace handle. Recording spans cover the bounded start and completion marker
 * operations, never command execution, subprocess waits, or network calls.
 */
public final class CliObservability {
    public static final long DEFAULT_FLUSH_TIMEOUT_MILLIS = 5_000;

    private static final String INSTRUMENTATION_SCOPE = "dev.skillmanager.cli";
    private static final String HTTP_EXPORTER_LOGGER =
            "io.opentelemetry.exporter.internal.http.HttpExporter";
    private static final String METRIC_READER_LOGGER =
            "io.opentelemetry.sdk.metrics.export.PeriodicMetricReader";
    private static final String EXPORT_FAILURE_NOTICE =
            "Telemetry export unavailable; continuing without telemetry.";
    // LogManager retains named JUL loggers weakly. Keep the filtered instances
    // strongly reachable for the process lifetime.
    static final java.util.logging.Logger HTTP_EXPORTER_JUL_LOGGER =
            java.util.logging.Logger.getLogger(HTTP_EXPORTER_LOGGER);
    static final java.util.logging.Logger METRIC_READER_JUL_LOGGER =
            java.util.logging.Logger.getLogger(METRIC_READER_LOGGER);
    private static final ThreadLocal<CliObservability> ACTIVE = new ThreadLocal<>();
    private static final AtomicBoolean EXPORT_FAILURE_FILTERS_INSTALLED =
            new AtomicBoolean();
    private static final AtomicBoolean EXPORT_FAILURE_REPORTED = new AtomicBoolean();
    private static final TextMapPropagator W3C =
            W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<Map<String, String>> ENV_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    if (carrier == null) return null;
                    String value = carrier.get(key);
                    if (value != null) return value;
                    return carrier.get(key.toUpperCase(Locale.ROOT));
                }
            };
    private static final TextMapSetter<Map<String, String>> ENV_SETTER =
            (carrier, key, value) -> {
                if (carrier != null && key != null && value != null) {
                    carrier.put(key, value);
                }
            };
    private static final TextMapSetter<HttpRequest.Builder> HTTP_SETTER =
            (carrier, key, value) -> {
                if (carrier != null && key != null && value != null) {
                    carrier.setHeader(key, value);
                }
            };

    private final OpenTelemetrySdk sdk;
    private final Context extractedParent;
    private final Tracer tracer;
    private final Logger logger;
    private final LongCounter started;
    private final LongCounter completed;
    private final DoubleHistogram duration;
    private final LongCounter traceCorrelation;
    private final long startedNanos;
    private final AtomicBoolean completionRecorded = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Context traceContext;

    private CliObservability(OpenTelemetrySdk sdk, Context extractedParent) {
        this.sdk = sdk;
        this.extractedParent = extractedParent;
        this.startedNanos = System.nanoTime();
        this.traceContext = extractedParent;
        if (sdk == null) {
            this.tracer = null;
            this.logger = null;
            this.started = null;
            this.completed = null;
            this.duration = null;
            this.traceCorrelation = null;
            return;
        }

        this.tracer = sdk.getTracer(INSTRUMENTATION_SCOPE);
        this.logger = sdk.getLogsBridge().get(INSTRUMENTATION_SCOPE);
        var meter = sdk.getMeter(INSTRUMENTATION_SCOPE);
        this.started = meter.counterBuilder("skill_manager.cli.started")
                .setUnit("{command}")
                .setDescription("Started skill-manager CLI invocations.")
                .build();
        this.completed = meter.counterBuilder("skill_manager.cli.completed")
                .setUnit("{command}")
                .setDescription("Completed skill-manager CLI invocations.")
                .build();
        this.duration = meter.histogramBuilder("skill_manager.cli.duration")
                .setUnit("ms")
                .setDescription("End-to-end skill-manager CLI duration.")
                .build();
        this.traceCorrelation = meter
                .counterBuilder("tracing_observability.trace_correlation")
                .setUnit("{operation}")
                .setDescription("Bounded operations selected for trace correlation.")
                .build();
        markStarted();
    }

    /**
     * Configure all three signals through one autoconfigured SDK. Invalid
     * operator configuration is deliberately fail-open for CLI behavior.
     */
    public static CliObservability configure() {
        return configure(System.getenv());
    }

    /** Visible for native contract tests and embedders with an explicit carrier. */
    public static CliObservability configure(Map<String, String> environment) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        Context parent = extractW3c(env);
        OpenTelemetrySdk sdk = null;
        try {
            installExportFailureFilters();
            Map<String, String> defaults = defaultProperties(env);
            sdk = AutoConfiguredOpenTelemetrySdk.builder()
                    .addPropertiesSupplier(() -> defaults)
                    .disableShutdownHook()
                    .build()
                    .getOpenTelemetrySdk();
            return new CliObservability(sdk, parent);
        } catch (Throwable ignored) {
            shutdownQuietly(sdk);
            return new CliObservability(null, parent);
        }
    }

    private static Context extractW3c(Map<String, String> environment) {
        try {
            return W3C.extract(Context.root(), environment, ENV_GETTER);
        } catch (Throwable ignored) {
            return Context.root();
        }
    }

    static Map<String, String> defaultProperties(Map<String, String> env) {
        Map<String, String> defaults = new HashMap<>();
        defaults.put("otel.service.name",
                env.getOrDefault("OTEL_SERVICE_NAME", "skill-manager-cli"));
        defaults.put("otel.traces.exporter",
                env.getOrDefault("OTEL_TRACES_EXPORTER", "otlp"));
        defaults.put("otel.metrics.exporter",
                env.getOrDefault("OTEL_METRICS_EXPORTER", "otlp"));
        defaults.put("otel.logs.exporter",
                env.getOrDefault("OTEL_LOGS_EXPORTER", "otlp"));
        defaults.put("otel.exporter.otlp.protocol",
                env.getOrDefault("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf"));
        defaults.put("otel.propagators",
                env.getOrDefault("OTEL_PROPAGATORS", "tracecontext,baggage"));
        defaults.put("otel.java.exporter.otlp.retry.disabled",
                env.getOrDefault("OTEL_JAVA_EXPORTER_OTLP_RETRY_DISABLED", "true"));
        if (!env.containsKey("OTEL_EXPORTER_OTLP_ENDPOINT")
                && !env.containsKey("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
                && !env.containsKey("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT")
                && !env.containsKey("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT")) {
            defaults.put("otel.exporter.otlp.endpoint", "http://localhost:4318");
        }
        return defaults;
    }

    private static void installExportFailureFilters() {
        if (!EXPORT_FAILURE_FILTERS_INSTALLED.compareAndSet(false, true)) return;
        try {
            installExportFailureFilter(
                    HTTP_EXPORTER_JUL_LOGGER,
                    record -> record.getMessage() != null
                            && record.getMessage().startsWith("Failed to export "));
            installExportFailureFilter(
                    METRIC_READER_JUL_LOGGER,
                    record -> "Exporter failed".equals(record.getMessage()));
        } catch (Throwable ignored) {
            // Logging policy must never prevent telemetry or CLI startup.
        }
    }

    private static void installExportFailureFilter(
            java.util.logging.Logger julLogger,
            Filter target) {
        Filter previous = julLogger.getFilter();
        julLogger.setFilter(record -> {
            if (previous != null && !previous.isLoggable(record)) return false;
            if (!target.isLoggable(record)) return true;
            reportExportFailureOnce();
            return false;
        });
    }

    private static void reportExportFailureOnce() {
        if (!EXPORT_FAILURE_REPORTED.compareAndSet(false, true)) return;
        try {
            Log.warn(EXPORT_FAILURE_NOTICE);
        } catch (Throwable ignored) {
            // A diagnostic write must not alter exporter or CLI behavior.
        }
    }

    private void markStarted() {
        bounded("skill_manager.cli.start", baseAttributes(), active -> {
            started.add(1, baseAttributes(), active);
            emit("skill_manager.cli.started", baseAttributes(), active);
        }, true);
    }

    public void complete(String commandPath, int exitCode) {
        if (sdk == null || !completionRecorded.compareAndSet(false, true)) return;

        String normalized = commandPath == null || commandPath.isBlank()
                ? "skill-manager"
                : commandPath.trim();
        String status = exitCode == 0 ? "success" : "failed";
        Attributes attributes = Attributes.builder()
                .put("skill_manager.runtime", "jvm-cli")
                .put("skill_manager.cli.command", normalized)
                .put("skill_manager.cli.status", status)
                .put("skill_manager.cli.exit_code", (long) exitCode)
                .build();
        double durationMillis = (System.nanoTime() - startedNanos) / 1_000_000.0;
        bounded("skill_manager.cli.complete", attributes, active -> {
            completed.add(1, attributes, active);
            duration.record(durationMillis, attributes, active);
            emit("skill_manager.cli.completed", attributes, active);
        }, false);
    }

    public static void completeCurrent(String commandPath, int exitCode) {
        CliObservability current = ACTIVE.get();
        if (current != null) current.complete(commandPath, exitCode);
    }

    public Scope makeCurrent() {
        CliObservability previous = ACTIVE.get();
        ACTIVE.set(this);
        Scope contextScope = traceContext.makeCurrent();
        return () -> {
            try {
                contextScope.close();
            } finally {
                if (previous == null) ACTIVE.remove();
                else ACTIVE.set(previous);
            }
        };
    }

    public static String currentTraceId() {
        CliObservability current = ACTIVE.get();
        if (current != null) return current.traceId();
        SpanContext spanContext = Span.current().getSpanContext();
        String traceId = spanContext.getTraceId();
        return spanContext.isValid() && CliAgentContext.isValidTraceId(traceId)
                ? traceId
                : null;
    }

    public String traceId() {
        SpanContext spanContext = Span.fromContext(traceContext).getSpanContext();
        String traceId = spanContext.getTraceId();
        return spanContext.isValid() && CliAgentContext.isValidTraceId(traceId)
                ? traceId
                : null;
    }

    /**
     * Inject the active W3C context into a child process environment. Header
     * names stay lowercase to match the cross-runtime carrier contract.
     */
    public static void injectEnvironment(Map<String, String> environment) {
        CliObservability current = ACTIVE.get();
        if (current == null || environment == null) return;
        try {
            environment.remove("traceparent");
            environment.remove("tracestate");
            environment.remove("TRACEPARENT");
            environment.remove("TRACESTATE");
            W3C.inject(current.traceContext, environment, ENV_SETTER);
        } catch (Throwable ignored) {
            // Propagation must never prevent the gateway from starting.
        }
    }

    /**
     * Inject the active W3C context into a fresh outbound HTTP request builder.
     * The returned builder is the input builder so clients can apply this at
     * construction without opening a recording span around the network wait.
     */
    public static HttpRequest.Builder injectCurrentW3c(HttpRequest.Builder request) {
        CliObservability current = ACTIVE.get();
        if (current == null || request == null) return request;
        try {
            W3C.inject(current.traceContext, request, HTTP_SETTER);
        } catch (Throwable ignored) {
            // Propagation must never prevent the CLI request from running.
        }
        return request;
    }

    /**
     * Request delivery and provider shutdown under one wall-clock deadline.
     * Export failures never affect the already-computed CLI exit status.
     */
    public void flushAndClose(long timeoutMillis) {
        if (!closed.compareAndSet(false, true)) return;
        if (sdk == null) return;
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));

        join(() -> sdk.getSdkMeterProvider().forceFlush(), deadline);
        join(() -> sdk.getSdkLoggerProvider().forceFlush(), deadline);
        join(() -> sdk.getSdkTracerProvider().forceFlush(), deadline);
        join(() -> sdk.getSdkMeterProvider().shutdown(), deadline);
        join(() -> sdk.getSdkLoggerProvider().shutdown(), deadline);
        join(() -> sdk.getSdkTracerProvider().shutdown(), deadline);
    }

    boolean deliveryEnabled() {
        return sdk != null;
    }

    private void bounded(
            String name,
            Attributes attributes,
            TelemetryOperation operation,
            boolean retainContext) {
        try {
            Span span = tracer.spanBuilder(name).setParent(traceContext).startSpan();
            Context active = traceContext.with(span);
            try {
                span.setAllAttributes(attributes);
                traceCorrelation.add(
                        1,
                        Attributes.builder()
                                .put("trace_id", span.getSpanContext().getTraceId())
                                .build(),
                        active);
                try (Scope ignored = active.makeCurrent()) {
                    operation.run(active);
                }
            } finally {
                SpanContext spanContext = span.getSpanContext();
                span.end();
                if (retainContext && spanContext.isValid()) {
                    traceContext = extractedParent.with(Span.wrap(spanContext));
                }
            }
        } catch (Throwable ignored) {
            // Telemetry must not alter CLI execution.
        }
    }

    private Attributes baseAttributes() {
        return Attributes.builder()
                .put("skill_manager.runtime", "jvm-cli")
                .build();
    }

    private void emit(String body, Attributes attributes, Context context) {
        try {
            logger.logRecordBuilder()
                    .setContext(context)
                    .setBody(body)
                    .setAllAttributes(attributes)
                    .emit();
        } catch (Throwable ignored) {
            // Native structured logging is diagnostic and fail-open.
        }
    }

    private static void join(
            Supplier<CompletableResultCode> operation,
            long deadlineNanos) {
        try {
            CompletableResultCode result = operation.get();
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining > 0) {
                result.join(remaining, TimeUnit.NANOSECONDS);
            }
        } catch (Throwable ignored) {
            // Continue so every provider receives a terminal request.
        }
    }

    private static void shutdownQuietly(OpenTelemetrySdk sdk) {
        if (sdk == null) return;
        try {
            sdk.getSdkMeterProvider().shutdown();
        } catch (Throwable ignored) {}
        try {
            sdk.getSdkLoggerProvider().shutdown();
        } catch (Throwable ignored) {}
        try {
            sdk.getSdkTracerProvider().shutdown();
        } catch (Throwable ignored) {}
    }

    @FunctionalInterface
    private interface TelemetryOperation {
        void run(Context context);
    }
}
