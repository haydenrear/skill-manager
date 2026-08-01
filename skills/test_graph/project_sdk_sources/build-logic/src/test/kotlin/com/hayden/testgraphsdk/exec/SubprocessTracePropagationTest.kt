package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.MiniJson
import com.hayden.testgraphsdk.NodeKind
import com.hayden.testgraphsdk.ValidationNodeSpec
import com.hayden.testgraphsdk.ValidationRuntime
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The provider half of the `test-graph-subprocess-w3c-propagation` contract.
 *
 * A graph run owns one W3C context. Every node process the executor spawns must
 * receive it in its environment, and everything that node itself launches must
 * be able to join the same trace. These are process-level assertions on purpose:
 * the hop that matters is the one an in-process unit test cannot observe.
 */
class SubprocessTracePropagationTest {

    private val getter = object : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys
        override fun get(carrier: Map<String, String>?, key: String): String? =
            carrier?.get(key)
    }

    @Test
    fun plannedNodeEnvironmentCarriesTheGraphW3cContext() {
        disableExportForUnitTest()
        val observed = mutableMapOf<String, String>()
        val observability = runOneNode { invocation ->
            observed.putAll(invocation.environment)
            writePassedResult(invocation)
            ExecutionOutcome.Completed(0)
        }

        val traceparent = observed["traceparent"]
            ?: error("planned node environment has no W3C traceparent")
        val extracted = W3CTraceContextPropagator.getInstance()
            .extract(Context.root(), observed, getter)
        assertEquals(
            observability.traceId,
            Span.fromContext(extracted).spanContext.traceId,
            "the node environment must resolve to the graph's own trace",
        )
        assertTrue(
            traceparent.contains(observability.traceId),
            "traceparent must name the graph trace id",
        )
    }

    @Test
    fun realNodeProcessAndItsOwnChildBothObserveTheGraphTraceId() {
        disableExportForUnitTest()
        val shim = shimUv()
        lateinit var reportRoot: File
        val observability = runOneNode(
            executor = UvExecutor(shim.absolutePath),
            reportRootSink = { reportRoot = it },
        ) { error("the real executor runs instead of this stub") }

        val captured = reportRoot
            .resolve("node-logs/planned.node.stdout.log")
            .readText()
        assertTrue(
            captured.contains("node-process traceparent=00-${observability.traceId}-"),
            "spawned node process did not observe the graph trace: $captured",
        )
        assertTrue(
            captured.contains("grandchild traceparent=00-${observability.traceId}-"),
            "a process launched by the node did not observe the graph trace: $captured",
        )
    }

    @Test
    fun anUnreachableCollectorStillLetsEveryPlannedNodeSucceed() {
        pointExportersAtUnavailableCollector()
        lateinit var reportRoot: File
        val elapsed = measureTimeMillis {
            val observability = runOneNode(reportRootSink = { reportRoot = it }) { invocation ->
                writePassedResult(invocation)
                ExecutionOutcome.Completed(0)
            }
            observability.finish("passed", timeoutMillis = 10)
        }

        assertEquals(
            "passed",
            MiniJson.obj(
                MiniJson.parse(reportRoot.resolve("envelope/planned.node.json").readText())
            )["status"],
            "context export failure must never change a node result",
        )
        assertTrue(elapsed < 20_000, "a dead collector must not stall the plan")
    }

    private fun runOneNode(
        executor: ValidationExecutor? = null,
        reportRootSink: (File) -> Unit = {},
        execute: (NodeInvocation) -> ExecutionOutcome,
    ): GraphObservability {
        val projectRoot = Files.createTempDirectory("test-graph-w3c-propagation").toFile()
        val project = ProjectBuilder.builder().withProjectDir(projectRoot).build()
        val reportRoot = projectRoot.resolve("report").apply { mkdirs() }
        val reportDirectory = project.layout.dir(project.provider { reportRoot }).get()
        val observability = GraphObservability.open(reportRoot, "w3cPropagation")
        val selected = executor ?: object : ValidationExecutor {
            override val runtimeName: String = "uv"
            override fun execute(invocation: NodeInvocation): ExecutionOutcome =
                execute(invocation)
        }
        val spec = ValidationNodeSpec(
            id = "planned.node",
            kind = NodeKind.ASSERTION,
            runtime = ValidationRuntime.Uv("node.py"),
            timeout = "60s",
        )
        try {
            PlanExecutor(
                registry = ExecutorRegistry(mapOf("uv" to selected)),
                projectDir = project.layout.projectDirectory,
                reportDir = reportDirectory,
                runId = "w3c-propagation",
                logger = project.logger,
                graphName = "w3cPropagation",
                observability = observability,
            ).run(listOf(spec))
        } catch (_: RuntimeException) {
            // A node that fails its own assertions is still a completed hop for
            // this contract; the environment and captured stdout are the evidence.
        }
        reportRootSink(reportRoot)
        return observability
    }

    private fun writePassedResult(invocation: NodeInvocation) {
        val timestamp = Instant.now().toString()
        invocation.resultOut.parentFile.mkdirs()
        invocation.resultOut.writeText(
            """{
              "nodeId":"${invocation.spec.id}",
              "status":"passed",
              "startedAt":"$timestamp",
              "endedAt":"$timestamp",
              "assertions":[],
              "artifacts":[],
              "processes":[],
              "metrics":{},
              "logs":[],
              "published":{}
            }""".trimIndent()
        )
    }

    /**
     * Stands in for the `uv` binary. It ignores the runtime arguments, reports
     * the W3C context it was handed, launches one further process that reports
     * the context it inherited in turn, and writes a well-formed result so the
     * plan closes normally.
     */
    private fun shimUv(): File {
        val shim = Files.createTempFile("test-graph-uv-shim", ".sh").toFile()
        shim.writeText(
            """
            #!/bin/sh
            out=""
            for arg in "${'$'}@"; do
              case "${'$'}arg" in
                --result-out=*) out="${'$'}{arg#--result-out=}" ;;
              esac
            done
            echo "node-process traceparent=${'$'}{traceparent}"
            /bin/sh -c 'echo "grandchild traceparent=${'$'}{traceparent}"'
            if [ -n "${'$'}out" ]; then
              cat > "${'$'}out" <<'JSON'
            {"nodeId":"planned.node","status":"passed","startedAt":"2026-01-01T00:00:00Z","endedAt":"2026-01-01T00:00:00Z","assertions":[],"artifacts":[],"processes":[],"metrics":{},"logs":[],"published":{}}
            JSON
            fi
            exit 0
            """.trimIndent() + "\n"
        )
        shim.setExecutable(true)
        shim.deleteOnExit()
        return shim
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
}
