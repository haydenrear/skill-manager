package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ValidationNodeSpec
import com.hayden.testgraphsdk.ValidationRuntime
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphObservabilityTest {

    @Test
    fun unavailableColdCollectorBeforeFirstNodeRemainsFailOpen() {
        pointExportersAtUnavailableCollector()
        val reportDir = Files.createTempDirectory("test-graph-observability").toFile()

        val openStarted = System.nanoTime()
        val initial = GraphObservability.open(reportDir, "observabilitySmoke")
        val openElapsedMillis = (System.nanoTime() - openStarted) / 1_000_000
        val replay = GraphObservability.open(
            reportDir,
            "observabilitySmoke",
            requireExistingCarrier = true,
        )

        assertTrue(
            openElapsedMillis < 2_000,
            "graph-start telemetry must not wait for an unavailable cold collector",
        )
        assertTrue(initial.traceId.matches(Regex("^[0-9a-f]{32}$")))
        assertEquals(initial.traceId, replay.traceId)
        assertEquals(initial.carrier, replay.carrier)
        assertTrue(initial.carrier.getValue("traceparent").contains(initial.traceId))
        assertTrue(reportDir.resolve("trace-context.json").isFile)

        val spec = ValidationNodeSpec(
            id = "probe",
            kind = com.hayden.testgraphsdk.NodeKind.EVIDENCE,
            runtime = ValidationRuntime.Uv(reportDir.resolve("probe.py").absolutePath),
            dependsOn = emptyList(),
            tags = emptySet(),
            timeout = "30s",
            retries = 0,
            cacheable = false,
            sideEffects = emptySet(),
            inputs = emptyMap(),
            outputs = emptyMap(),
            rerun = true,
        )
        initial.nodeLaunch(spec, 1)
        initial.nodeResult(spec, "passed", java.time.Duration.ofMillis(2))

        val elapsed = measureTimeMillis { initial.finish("passed", timeoutMillis = 10) }
        assertTrue(elapsed < 1_000, "terminal flush must remain bounded")
    }

    @Test
    fun freshReplayReportContinuesSourceTraceWithoutMutatingSourceCarrier() {
        disableExportForUnitTest()
        val sourceDir = Files.createTempDirectory("test-graph-trace-source").toFile()
        val replayDir = Files.createTempDirectory("test-graph-trace-replay").toFile()
        val source = GraphObservability.open(sourceDir, "observabilityReplaySource")
        val sourceCarrierBefore = sourceDir.resolve("trace-context.json").readText()
        val snapshot = replaySnapshot(
            sourceDir,
            source.traceId,
            sourceCarrierBefore,
            source.carrier,
        )

        val replay = GraphObservability.open(
            replayDir,
            "observabilityReplayTarget",
            replaySourceSnapshot = snapshot,
        )

        assertEquals(source.traceId, replay.traceId)
        assertEquals(source.carrier, replay.carrier)
        assertEquals(sourceCarrierBefore, sourceDir.resolve("trace-context.json").readText())
        assertEquals(sourceCarrierBefore, replayDir.resolve("trace-context.json").readText())
    }

    @Test
    fun replayCarrierSerializationKeepsControlCharactersStrictJson() {
        disableExportForUnitTest()
        val sourceDir = Files.createTempDirectory("test-graph-control-carrier-source").toFile()
        val replayDir = Files.createTempDirectory("test-graph-control-carrier-replay").toFile()
        val source = GraphObservability.open(sourceDir, "controlCarrierSource")
        val carrierFile = sourceDir.resolve("trace-context.json")
        carrierFile.writeText(
            carrierFile.readText().trim().removeSuffix("}") +
                    ",\"baggage\":\"safe\\u0001value\"}\n"
        )
        val carrierJson = carrierFile.readText()
        val snapshot = replaySnapshot(
            sourceDir,
            source.traceId,
            carrierJson,
            GraphObservability.parseCarrierJson(carrierJson),
        )

        val replay = GraphObservability.open(
            replayDir,
            "controlCarrierReplay",
            replaySourceSnapshot = snapshot,
        )

        assertEquals(source.traceId, replay.traceId)
        val replayCarrier = replayDir.resolve("trace-context.json").readText()
        assertContains(replayCarrier, "safe\\u0001value")
        assertTrue('\u0001' !in replayCarrier)
        assertEquals(
            replay.traceId,
            GraphObservability.open(
                replayDir,
                "controlCarrierReread",
                requireExistingCarrier = true,
            ).traceId,
        )
    }

    @Test
    fun invalidReplaySourceCarrierFailsClosedWithoutCreatingTargetCarrier() {
        disableExportForUnitTest()
        val sourceDir = Files.createTempDirectory("test-graph-invalid-trace-source").toFile()
        val replayDir = Files.createTempDirectory("test-graph-invalid-trace-target").toFile()
        sourceDir.resolve("trace-context.json").writeText("{not-json")
        val snapshot = replaySnapshot(
            sourceDir,
            "0123456789abcdef0123456789abcdef",
            "{not-json",
            mapOf(
                "traceparent" to
                        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            GraphObservability.open(
                replayDir,
                "invalidReplaySource",
                replaySourceSnapshot = snapshot,
            )
        }

        assertEquals("{not-json", sourceDir.resolve("trace-context.json").readText())
        assertTrue(!replayDir.resolve("trace-context.json").exists())
    }

    @Test
    fun malformedW3cReplaySourceFailsBeforePersistingTargetCarrier() {
        disableExportForUnitTest()
        val sourceDir = Files.createTempDirectory("test-graph-invalid-w3c-source").toFile()
        val replayDir = Files.createTempDirectory("test-graph-invalid-w3c-target").toFile()
        val malformed = """{"traceparent":"not-a-w3c-traceparent"}"""
        sourceDir.resolve("trace-context.json").writeText(malformed)
        val snapshot = replaySnapshot(
            sourceDir,
            "0123456789abcdef0123456789abcdef",
            malformed,
            mapOf("traceparent" to "not-a-w3c-traceparent"),
        )

        assertFailsWith<IllegalStateException> {
            GraphObservability.open(
                replayDir,
                "invalidW3cReplaySource",
                replaySourceSnapshot = snapshot,
            )
        }

        assertTrue(!replayDir.resolve("trace-context.json").exists())
    }

    @Test
    fun resumeRequiresAnExistingTraceCarrier() {
        disableExportForUnitTest()
        val reportDir = Files.createTempDirectory("test-graph-missing-carrier").toFile()

        val failure = assertFailsWith<IllegalArgumentException> {
            GraphObservability.open(
                reportDir,
                "resumeMissingCarrier",
                requireExistingCarrier = true,
            )
        }

        assertTrue(failure.message.orEmpty().contains("resume requires an existing trace carrier"))
        assertTrue(!reportDir.resolve("trace-context.json").exists())
    }

    @Test
    fun invalidExistingTraceCarrierFailsClosedWithoutReplacement() {
        disableExportForUnitTest()
        val reportDir = Files.createTempDirectory("test-graph-invalid-carrier").toFile()
        val carrier = reportDir.resolve("trace-context.json")
        carrier.writeText("{not-json")

        assertFailsWith<IllegalArgumentException> {
            GraphObservability.open(reportDir, "invalidCarrier")
        }

        assertEquals("{not-json", carrier.readText())
    }

    @Test
    fun traceCarrierSymlinksAreNeverFollowedOrReplaced() {
        disableExportForUnitTest()
        val externalDir = Files.createTempDirectory("test-graph-external-carrier").toFile()
        GraphObservability.open(externalDir, "externalCarrier")
        val externalCarrier = externalDir.resolve("trace-context.json")
        val externalBytes = externalCarrier.readBytes()

        val danglingTargetDir = Files.createTempDirectory("test-graph-dangling-carrier-target").toFile()
        val missingExternal = danglingTargetDir.resolve("outside-missing.json")
        val carrierLink = danglingTargetDir.resolve("trace-context.json")
        Files.createSymbolicLink(carrierLink.toPath(), missingExternal.toPath())
        assertFailsWith<IllegalArgumentException> {
            GraphObservability.open(danglingTargetDir, "danglingCarrierTarget")
        }

        assertTrue(Files.isSymbolicLink(carrierLink.toPath()))
        assertTrue(!missingExternal.exists())
        assertTrue(externalBytes.contentEquals(externalCarrier.readBytes()))
    }

    @Test
    fun oversizedExistingTraceCarrierIsRejectedBeforeReading() {
        disableExportForUnitTest()
        val reportDir = Files.createTempDirectory("test-graph-oversized-carrier").toFile()
        val carrier = reportDir.resolve("trace-context.json")
        RandomAccessFile(carrier, "rw").use {
            it.setLength(GraphObservability.TRACE_CARRIER_MAX_UTF8_BYTES.toLong() + 1)
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            GraphObservability.open(reportDir, "oversizedCarrier")
        }

        assertTrue(
            failure.message.orEmpty().contains(
                "exceeds ${GraphObservability.TRACE_CARRIER_MAX_UTF8_BYTES} UTF-8 bytes"
            )
        )
    }

    private fun disableExportForUnitTest() {
        System.setProperty("otel.traces.exporter", "none")
        System.setProperty("otel.metrics.exporter", "none")
        System.setProperty("otel.logs.exporter", "none")
    }

    private fun pointExportersAtUnavailableCollector() {
        System.setProperty("otel.traces.exporter", "otlp")
        System.setProperty("otel.metrics.exporter", "otlp")
        System.setProperty("otel.logs.exporter", "otlp")
        System.setProperty("otel.exporter.otlp.protocol", "http/protobuf")
        System.setProperty("otel.exporter.otlp.endpoint", "http://127.0.0.1:1")
    }

    private fun replaySnapshot(
        sourceDir: java.io.File,
        traceId: String,
        carrierJson: String,
        carrier: Map<String, String>,
    ): ReplaySourceSnapshot {
        val contextJson = """{"items":[]}"""
        return ReplaySourceSnapshot.immutable(
            sourceBuild = sourceDir.canonicalFile,
            graphName = "sourceGraph",
            selectedNodeId = "selected",
            sourceExpectedNodeIds = listOf("selected"),
            traceId = traceId,
            carrierJson = carrierJson,
            carrier = carrier,
            selectedContextJson = contextJson,
            selectedContext = emptyList(),
            closureSha256 = "0".repeat(64),
            selectedContextSha256 = sha256Utf8(contextJson),
        )
    }
}
