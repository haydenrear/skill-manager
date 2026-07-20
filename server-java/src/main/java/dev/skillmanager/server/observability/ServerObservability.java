package dev.skillmanager.server.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
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
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;

import java.net.http.HttpRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One fail-open OpenTelemetry SDK for the registry server.
 *
 * <p>Recording spans are marker spans: they cover only the telemetry calls
 * made by {@link #beginHttp} / {@link #endHttp} and the equivalent
 * background methods. The returned context can remain current during slow
 * business work without keeping a recording span open.
 */
public final class ServerObservability implements AutoCloseable {

    private static final org.slf4j.Logger diagnostics =
            LoggerFactory.getLogger(ServerObservability.class);
    private static final String INSTRUMENTATION_SCOPE = "dev.skillmanager.server";
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 2_000;
    private static final TextMapPropagator W3C = W3CTraceContextPropagator.getInstance();

    private static final TextMapGetter<HttpServletRequest> HTTP_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(HttpServletRequest carrier) {
                    if (carrier == null || carrier.getHeaderNames() == null) return Collections.emptyList();
                    return Collections.list(carrier.getHeaderNames());
                }

                @Override
                public String get(HttpServletRequest carrier, String key) {
                    return carrier == null ? null : carrier.getHeader(key);
                }
            };

    private static final TextMapSetter<HttpRequest.Builder> HTTP_SETTER =
            (carrier, key, value) -> {
                if (carrier != null) carrier.header(key, value);
            };

    private final OpenTelemetrySdk sdk;
    private final Tracer tracer;
    private final Logger logger;
    private final LongCounter requestStarted;
    private final LongCounter requestCompleted;
    private final DoubleHistogram requestDuration;
    private final LongCounter backgroundStarted;
    private final LongCounter backgroundCompleted;
    private final DoubleHistogram backgroundDuration;
    private final LongCounter traceCorrelation;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ServerObservability(OpenTelemetrySdk sdk) {
        this.sdk = sdk;
        this.tracer = sdk.getTracer(INSTRUMENTATION_SCOPE);
        this.logger = sdk.getLogsBridge().get(INSTRUMENTATION_SCOPE);
        var meter = sdk.getMeter(INSTRUMENTATION_SCOPE);
        this.requestStarted = meter.counterBuilder("skill_manager.server.request.started").build();
        this.requestCompleted = meter.counterBuilder("skill_manager.server.request.completed").build();
        this.requestDuration = meter.histogramBuilder("skill_manager.server.request.duration")
                .setUnit("ms")
                .build();
        this.backgroundStarted = meter.counterBuilder("skill_manager.server.background.started").build();
        this.backgroundCompleted = meter.counterBuilder("skill_manager.server.background.completed").build();
        this.backgroundDuration = meter.histogramBuilder("skill_manager.server.background.duration")
                .setUnit("ms")
                .build();
        this.traceCorrelation = meter.counterBuilder("tracing_observability.trace_correlation")
                .setUnit("{operation}")
                .setDescription("Bounded operations selected for trace correlation.")
                .build();
    }

    private ServerObservability() {
        this.sdk = null;
        this.tracer = null;
        this.logger = null;
        this.requestStarted = null;
        this.requestCompleted = null;
        this.requestDuration = null;
        this.backgroundStarted = null;
        this.backgroundCompleted = null;
        this.backgroundDuration = null;
        this.traceCorrelation = null;
    }

    /**
     * Configures all three signals through the maintained SDK-owned OTLP
     * exporters. Any configuration failure returns a propagation-only
     * instance so telemetry cannot prevent the server from starting.
     */
    public static ServerObservability configure() {
        try {
            OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                    .addPropertiesSupplier(ServerObservability::configurationDefaults)
                    .disableShutdownHook()
                    .build()
                    .getOpenTelemetrySdk();
            return new ServerObservability(sdk);
        } catch (Throwable failure) {
            diagnostics.warn(
                    "OpenTelemetry configuration failed; continuing with propagation only ({})",
                    failure.getClass().getSimpleName());
            return disabled();
        }
    }

    public static ServerObservability disabled() {
        return new ServerObservability();
    }

    static Map<String, String> configurationDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("otel.service.name", "skill-manager-server");
        defaults.put("otel.traces.exporter", "otlp");
        defaults.put("otel.metrics.exporter", "otlp");
        defaults.put("otel.logs.exporter", "otlp");
        defaults.put("otel.exporter.otlp.protocol", "http/protobuf");
        defaults.put("otel.exporter.otlp.endpoint", "http://localhost:4318");
        defaults.put("otel.propagators", "tracecontext");
        return Map.copyOf(defaults);
    }

    public Operation beginHttp(HttpServletRequest request) {
        Context extracted = extract(request);
        String method = normalizeHttpMethod(request == null ? null : request.getMethod());
        Attributes attributes = Attributes.builder()
                .put("http.request.method", method)
                .build();
        return marker(
                "skill_manager.server.request.start",
                extracted,
                attributes,
                context -> {
                    requestStarted.add(1, attributes, context);
                    emit("skill_manager.server.request.started", Severity.INFO, attributes, context);
                });
    }

    public void endHttp(
            Operation operation,
            String method,
            String route,
            int statusCode,
            double durationMillis) {
        String outcome = statusCode >= 500 ? "error" : "ok";
        Attributes attributes = Attributes.builder()
                .put("http.request.method", normalizeHttpMethod(method))
                .put("http.route", route)
                .put("http.response.status_code", (long) statusCode)
                .put("skill_manager.server.outcome", outcome)
                .build();
        marker(
                "skill_manager.server.request.result",
                operation.context(),
                attributes,
                context -> {
                    requestCompleted.add(1, attributes, context);
                    requestDuration.record(Math.max(0, durationMillis), attributes, context);
                    emit(
                            "skill_manager.server.request.completed",
                            statusCode >= 500 ? Severity.ERROR : Severity.INFO,
                            attributes,
                            context);
                });
    }

    public Operation beginBackground(String operationName) {
        Attributes attributes = Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("skill_manager.operation"),
                operationName);
        return marker(
                "skill_manager.server.background.start",
                Context.current(),
                attributes,
                context -> {
                    backgroundStarted.add(1, attributes, context);
                    emit("skill_manager.server.background.started", Severity.INFO, attributes, context);
                });
    }

    public void endBackground(
            Operation operation,
            String operationName,
            String status,
            double durationMillis) {
        Attributes attributes = Attributes.builder()
                .put("skill_manager.operation", operationName)
                .put("skill_manager.status", status)
                .build();
        marker(
                "skill_manager.server.background.result",
                operation.context(),
                attributes,
                context -> {
                    backgroundCompleted.add(1, attributes, context);
                    backgroundDuration.record(Math.max(0, durationMillis), attributes, context);
                    emit(
                            "skill_manager.server.background.completed",
                            "error".equals(status) ? Severity.ERROR : Severity.INFO,
                            attributes,
                            context);
                });
    }

    public Scope makeCurrent(Operation operation) {
        try {
            return operation == null ? Scope.noop() : operation.context().makeCurrent();
        } catch (Throwable ignored) {
            return Scope.noop();
        }
    }

    /**
     * Propagates the active request identity across the registry's raw JDK
     * HTTP client without creating a span around the potentially slow call.
     */
    public static void injectCurrentW3c(HttpRequest.Builder request) {
        try {
            W3C.inject(Context.current(), request, HTTP_SETTER);
        } catch (Throwable ignored) {
            // Propagation is diagnostic and must not change the request.
        }
    }

    static void injectW3c(Context context, HttpRequest.Builder request) {
        try {
            W3C.inject(context, request, HTTP_SETTER);
        } catch (Throwable ignored) {
            // Testable context-explicit form of the same fail-open boundary.
        }
    }

    static String normalizeHttpMethod(String method) {
        if (method == null) return "OTHER";
        String normalized = method.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CONNECT", "DELETE", "GET", "HEAD", "OPTIONS", "PATCH",
                    "POST", "PUT", "TRACE" -> normalized;
            default -> "OTHER";
        };
    }

    private Context extract(HttpServletRequest request) {
        try {
            return W3C.extract(Context.root(), request, HTTP_GETTER);
        } catch (Throwable ignored) {
            return Context.root();
        }
    }

    private Operation marker(
            String name,
            Context parent,
            Attributes attributes,
            SignalOperation signalOperation) {
        Context safeParent = parent == null ? Context.root() : parent;
        if (sdk == null) return Operation.from(safeParent);

        Span span = null;
        Context active = safeParent;
        try {
            span = tracer.spanBuilder(name).setParent(safeParent).startSpan();
            span.setAllAttributes(attributes);
            active = safeParent.with(span);
            SpanContext spanContext = span.getSpanContext();
            try (Scope ignored = active.makeCurrent()) {
                if (spanContext.isValid()) {
                    traceCorrelation.add(
                            1,
                            Attributes.builder()
                                    .put("operation", name)
                                    .put("trace_id", spanContext.getTraceId())
                                    .build(),
                            active);
                }
                signalOperation.emit(active);
            }
        } catch (Throwable ignored) {
            // Every telemetry operation is fail-open.
        } finally {
            if (span != null) {
                try {
                    span.end();
                } catch (Throwable ignored) {
                    // Ending a marker must not affect business work.
                }
            }
        }
        return Operation.from(active);
    }

    private void emit(
            String body,
            Severity severity,
            Attributes attributes,
            Context context) {
        try {
            logger.logRecordBuilder()
                    .setContext(context)
                    .setSeverity(severity)
                    .setBody(body)
                    .setAllAttributes(attributes)
                    .emit();
        } catch (Throwable ignored) {
            // Direct structured OTLP logging is diagnostic and fail-open.
        }
    }

    @Override
    public void close() {
        if (sdk == null || !closed.compareAndSet(false, true)) return;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_TIMEOUT_MILLIS);
        try {
            await(sdk.getSdkMeterProvider().forceFlush(), deadline);
            await(sdk.getSdkLoggerProvider().forceFlush(), deadline);
            await(sdk.getSdkTracerProvider().forceFlush(), deadline);
            await(sdk.shutdown(), deadline);
        } catch (Throwable ignored) {
            // Shutdown delivery is bounded and cannot alter server exit.
        }
    }

    private static void await(CompletableResultCode result, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) result.join(remaining, TimeUnit.NANOSECONDS);
    }

    @FunctionalInterface
    private interface SignalOperation {
        void emit(Context context);
    }

    public record Operation(Context context, String traceId) {
        private static Operation from(Context context) {
            SpanContext spanContext = Span.fromContext(context).getSpanContext();
            return new Operation(
                    context,
                    spanContext.isValid() ? spanContext.getTraceId() : null);
        }

        public boolean hasTraceId() {
            return traceId != null && traceId.matches("[0-9a-f]{32}");
        }
    }
}
