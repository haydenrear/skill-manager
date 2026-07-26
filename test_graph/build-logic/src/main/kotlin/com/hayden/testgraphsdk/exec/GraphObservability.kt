package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ValidationNodeSpec
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * One executor-owned OpenTelemetry context for a graph run.
 *
 * The graph-start, node-launch, and node-result spans are deliberately short.
 * Between them, [context] carries only the ended graph-start span context so
 * correlation remains active without holding a recording span open for the
 * graph or a node body.
 */
class GraphObservability private constructor(
    private val sdk: OpenTelemetrySdk,
    private val graphName: String,
    private val context: Context,
    val traceId: String,
    val carrier: Map<String, String>,
    private val startedNanos: Long,
) {
    private val tracer = sdk.getTracer("com.hayden.testgraphsdk")
    private val meter = sdk.getMeter("com.hayden.testgraphsdk")
    private val eventLogger = sdk.logsBridge.get("com.hayden.testgraphsdk")
    private val graphStarted = meter.counterBuilder("test_graph.graph.started").build()
    private val graphCompleted = meter.counterBuilder("test_graph.graph.completed").build()
    private val graphDuration = meter.histogramBuilder("test_graph.graph.duration")
        .setUnit("ms")
        .build()
    private val nodeLaunched = meter.counterBuilder("test_graph.node.launched").build()
    private val nodeCompleted = meter.counterBuilder("test_graph.node.completed").build()
    private val nodeDuration = meter.histogramBuilder("test_graph.node.duration")
        .setUnit("ms")
        .build()
    private val traceCorrelation = meter.counterBuilder("tracing_observability.trace_correlation")
        .setUnit("{operation}")
        .setDescription("Bounded operations selected for trace correlation.")
        .build()

    init {
        val attributes = graphAttributes()
        graphStarted.add(1, attributes, context)
        emitLog("test_graph.graph.started", attributes)
    }

    fun nodeLaunch(spec: ValidationNodeSpec, attempt: Int) {
        val attributes = nodeAttributes(spec).toBuilder()
            .put("test_graph.node.attempt", attempt.toLong())
            .build()
        boundedSpan("test_graph.node.launch", attributes) {
            nodeLaunched.add(1, attributes, Context.current())
            emitLog("test_graph.node.launched", attributes, Context.current())
        }
    }

    fun nodeResult(spec: ValidationNodeSpec, status: String, duration: Duration) {
        val attributes = nodeAttributes(spec).toBuilder()
            .put("test_graph.node.status", status)
            .build()
        boundedSpan("test_graph.node.result", attributes) {
            val active = Context.current()
            nodeCompleted.add(1, attributes, active)
            nodeDuration.record(duration.toNanos() / 1_000_000.0, attributes, active)
            emitLog("test_graph.node.completed", attributes, active)
        }
    }

    fun finish(status: String, timeoutMillis: Long = FLUSH_TIMEOUT_MILLIS) {
        try {
            val attributes = graphAttributes().toBuilder()
                .put("test_graph.graph.status", status)
                .build()
            graphCompleted.add(1, attributes, context)
            graphDuration.record(
                (System.nanoTime() - startedNanos) / 1_000_000.0,
                attributes,
                context,
            )
            emitLog("test_graph.graph.completed", attributes)
        } catch (_: Throwable) {
            // Telemetry must never replace the graph result or its exception.
        } finally {
            forceFlush(timeoutMillis)
        }
    }

    private fun boundedSpan(name: String, attributes: Attributes, body: () -> Unit) {
        try {
            val span = tracer.spanBuilder(name).setParent(context).startSpan()
            try {
                attributes.forEach { key, value -> setSpanAttribute(span, key, value) }
                traceCorrelation.add(
                    1,
                    Attributes.of(AttributeKey.stringKey("trace_id"), span.spanContext.traceId),
                    context.with(span),
                )
                context.with(span).makeCurrent().use { body() }
            } finally {
                span.end()
            }
        } catch (_: Throwable) {
            // Export and instrumentation failures are fail-open.
        }
    }

    private fun emitLog(
        body: String,
        attributes: Attributes,
        logContext: Context = context,
    ) {
        try {
            val record = eventLogger.logRecordBuilder()
                .setContext(logContext)
                .setBody(body)
            attributes.forEach { key, value -> setLogAttribute(record, key, value) }
            record.emit()
        } catch (_: Throwable) {
            // Direct OTLP logging is diagnostic and never changes graph behavior.
        }
    }

    private fun graphAttributes(): Attributes = Attributes.builder()
        .put("test_graph.graph.name", graphName)
        .build()

    private fun nodeAttributes(spec: ValidationNodeSpec): Attributes = Attributes.builder()
        .put("test_graph.graph.name", graphName)
        .put("test_graph.node.id", spec.id)
        .put("test_graph.node.runtime", spec.runtime.name)
        .build()

    private fun forceFlush(timeoutMillis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
        for (flush in listOf(
            { sdk.sdkMeterProvider.forceFlush() },
            { sdk.sdkLoggerProvider.forceFlush() },
            { sdk.sdkTracerProvider.forceFlush() },
        )) {
            try {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return
                flush().join(remaining, TimeUnit.NANOSECONDS)
            } catch (_: Throwable) {
                // A bounded flush is requested once; delivery failure is fail-open.
            }
        }
    }

    companion object {
        private const val CARRIER_FILE = "trace-context.json"
        internal const val TRACE_CARRIER_MAX_UTF8_BYTES = 4 * 1024
        private const val FLUSH_TIMEOUT_MILLIS = 5_000L
        private val TRACE_ID = Regex("^[0-9a-f]{32}$")
        private val setter = TextMapSetter<MutableMap<String, String>> { carrier, key, value ->
            carrier?.set(key, value)
        }
        private val getter = object : TextMapGetter<Map<String, String>> {
            override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys
            override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
        }

        internal fun open(
            reportDir: File,
            graphName: String,
            requireExistingCarrier: Boolean = false,
            replaySourceSnapshot: ReplaySourceSnapshot? = null,
        ): GraphObservability {
            require(!(requireExistingCarrier && replaySourceSnapshot != null)) {
                "choose either an in-place existing carrier or a captured replay source"
            }
            val sdk = TestGraphOpenTelemetry.sdk
            val carrierFile = File(reportDir, CARRIER_FILE)
            val replayCarrier = replaySourceSnapshot?.let { snapshot ->
                val parsed = parseCarrierJson(snapshot.carrierJson)
                require(parsed == snapshot.carrier) {
                    "captured replay carrier raw and parsed values differ"
                }
                parsed
            }
            val originalCarrier = when {
                replayCarrier != null -> {
                    if (Files.exists(carrierFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        throw IllegalArgumentException(
                            "fresh replay report already contains a trace carrier at " +
                                    carrierFile.absolutePath
                        )
                    }
                    replayCarrier
                }
                Files.exists(carrierFile.toPath(), LinkOption.NOFOLLOW_LINKS) ->
                    readCarrier(carrierFile)
                requireExistingCarrier -> throw IllegalArgumentException(
                    "resume requires an existing trace carrier at ${carrierFile.absolutePath}"
                )
                else -> null
            }
            val context = if (originalCarrier == null) {
                createGraphContext(sdk, graphName, carrierFile)
            } else {
                sdk.propagators.textMapPropagator.extract(Context.root(), originalCarrier, getter)
            }
            val spanContext = Span.fromContext(context).spanContext
            val traceId = spanContext.traceId
            check(spanContext.isValid && TRACE_ID.matches(traceId)) {
                "OpenTelemetry did not provide a valid graph trace ID"
            }
            if (replaySourceSnapshot != null) {
                check(traceId == replaySourceSnapshot.traceId) {
                    "captured replay carrier trace does not match its verified trace id"
                }
                // Persist the exact verified bytes.  No source pathname is
                // reopened after ReplaySourceSnapshot acquisition.
                writeCarrierRaw(carrierFile, replaySourceSnapshot.carrierJson)
            }
            val carrier = originalCarrier ?: readCarrier(carrierFile)

            if (originalCarrier != null) {
                shortGraphStart(sdk, graphName, context)
            }
            return GraphObservability(
                sdk = sdk,
                graphName = graphName,
                context = context,
                traceId = traceId,
                carrier = carrier,
                startedNanos = System.nanoTime(),
            )
        }

        internal fun persistedTraceId(reportDir: File): String {
            val carrierFile = File(reportDir, CARRIER_FILE)
            if (!Files.isRegularFile(carrierFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalArgumentException(
                    "run report requires an existing regular non-symlink trace carrier at " +
                            carrierFile.absolutePath
                )
            }
            val carrier = readCarrier(carrierFile)
            return traceIdForCarrier(carrier, "persisted graph trace carrier")
        }

        internal fun parseCarrierJson(json: String): Map<String, String> {
            val parsed = try {
                parseBoundedJsonObject(json, "graph trace carrier")
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid graph trace carrier: ${e.message}", e)
            }
            val carrier = linkedMapOf<String, String>()
            for ((key, value) in parsed) {
                if (value !is String) {
                    throw IllegalArgumentException(
                        "invalid graph trace carrier: '$key' must have a string value"
                    )
                }
                carrier[key] = value
            }
            if (carrier["traceparent"].isNullOrBlank()) {
                throw IllegalArgumentException(
                    "invalid graph trace carrier: missing string traceparent"
                )
            }
            return carrier
        }

        internal fun traceIdForCarrier(
            carrier: Map<String, String>,
            label: String = "graph trace carrier",
        ): String {
            val context = TestGraphOpenTelemetry.sdk.propagators.textMapPropagator.extract(
                Context.root(),
                carrier,
                getter,
            )
            val spanContext = Span.fromContext(context).spanContext
            check(spanContext.isValid && TRACE_ID.matches(spanContext.traceId)) {
                "$label did not provide a valid graph trace ID"
            }
            return spanContext.traceId
        }

        private fun createGraphContext(
            sdk: OpenTelemetrySdk,
            graphName: String,
            carrierFile: File,
        ): Context {
            val span = sdk.getTracer("com.hayden.testgraphsdk")
                .spanBuilder("test_graph.graph.start")
                .setAttribute("test_graph.graph.name", graphName)
                .startSpan()
            val context = Context.root().with(span)
            try {
                recordTraceCorrelation(sdk, context)
                val carrier = linkedMapOf<String, String>()
                sdk.propagators.textMapPropagator.inject(context, carrier, setter)
                writeCarrier(carrierFile, carrier)
            } finally {
                span.end()
            }
            return context
        }

        private fun shortGraphStart(
            sdk: OpenTelemetrySdk,
            graphName: String,
            parent: Context,
        ) {
            try {
                val span = sdk.getTracer("com.hayden.testgraphsdk")
                    .spanBuilder("test_graph.graph.start")
                    .setParent(parent)
                    .setAttribute("test_graph.graph.name", graphName)
                    .startSpan()
                try {
                    recordTraceCorrelation(sdk, parent.with(span))
                } finally {
                    span.end()
                }
            } catch (_: Throwable) {
                // Resume correlation remains usable even when export fails.
            }
        }

        private fun readCarrier(file: File): Map<String, String> {
            val raw = try {
                readBoundedJsonObject(
                    file,
                    "graph trace carrier",
                    maxUtf8Bytes = TRACE_CARRIER_MAX_UTF8_BYTES,
                ).first
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid graph trace carrier: ${e.message}", e)
            }
            return parseCarrierJson(raw)
        }

        private fun writeCarrier(file: File, carrier: Map<String, String>) {
            val parent = file.parentFile
                ?: throw IllegalArgumentException("graph trace carrier requires a parent directory")
            require(Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "graph trace carrier parent must be a real directory, not a symlink: " +
                        parent.absolutePath
            }
            val json = carrier.entries.joinToString(
                prefix = "{",
                postfix = "}\n",
                separator = ",",
            ) { (key, value) -> "\"${jsonEscape(key)}\":\"${jsonEscape(value)}\"" }
            writeCarrierRaw(file, json)
        }

        private fun writeCarrierRaw(file: File, json: String) {
            val encoded = json.toByteArray(Charsets.UTF_8)
            require(encoded.size <= TRACE_CARRIER_MAX_UTF8_BYTES) {
                "graph trace carrier exceeds $TRACE_CARRIER_MAX_UTF8_BYTES UTF-8 bytes after serialization"
            }
            // Re-validate in-memory bytes immediately before publication.
            parseCarrierJson(json)
            publishImmutableEvidence(file.toPath(), encoded, "graph trace carrier")
        }

        private fun recordTraceCorrelation(sdk: OpenTelemetrySdk, context: Context) {
            val traceId = Span.fromContext(context).spanContext.traceId
            sdk.getMeter("com.hayden.testgraphsdk")
                .counterBuilder("tracing_observability.trace_correlation")
                .setUnit("{operation}")
                .setDescription("Bounded operations selected for trace correlation.")
                .build()
                .add(
                    1,
                    Attributes.of(AttributeKey.stringKey("trace_id"), traceId),
                    context,
                )
        }

        private fun jsonEscape(value: String): String = buildString {
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) {
                        append("\\u").append("%04x".format(char.code))
                    } else {
                        append(char)
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun setSpanAttribute(span: Span, key: AttributeKey<*>, value: Any) {
            span.setAttribute(key as AttributeKey<Any>, value)
        }

        @Suppress("UNCHECKED_CAST")
        private fun setLogAttribute(
            record: io.opentelemetry.api.logs.LogRecordBuilder,
            key: AttributeKey<*>,
            value: Any,
        ) {
            record.setAttribute(key as AttributeKey<Any>, value)
        }
    }
}

private object TestGraphOpenTelemetry {
    val sdk: OpenTelemetrySdk by lazy {
        AutoConfiguredOpenTelemetrySdk.builder()
            .addPropertiesSupplier {
                val env = System.getenv()
                buildMap {
                    put("otel.service.name", env["OTEL_SERVICE_NAME"] ?: "test-graph")
                    put("otel.traces.exporter", env["OTEL_TRACES_EXPORTER"] ?: "otlp")
                    put("otel.metrics.exporter", env["OTEL_METRICS_EXPORTER"] ?: "otlp")
                    put("otel.logs.exporter", env["OTEL_LOGS_EXPORTER"] ?: "otlp")
                    put(
                        "otel.exporter.otlp.protocol",
                        env["OTEL_EXPORTER_OTLP_PROTOCOL"] ?: "http/protobuf",
                    )
                    if (
                        env["OTEL_EXPORTER_OTLP_ENDPOINT"] == null &&
                        env["OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"] == null &&
                        env["OTEL_EXPORTER_OTLP_METRICS_ENDPOINT"] == null &&
                        env["OTEL_EXPORTER_OTLP_LOGS_ENDPOINT"] == null
                    ) {
                        put("otel.exporter.otlp.endpoint", "http://localhost:4318")
                    }
                }
            }
            .build()
            .openTelemetrySdk
    }
}
