package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.MiniJson
import com.hayden.testgraphsdk.NodeKind
import com.hayden.testgraphsdk.ValidationNodeSpec
import com.hayden.testgraphsdk.ValidationRuntime
import com.hayden.testgraphsdk.tasks.RunReportWriter
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanExecutorResultIntegrityTest {

    @Test
    fun rejectsAChildResultForAnotherNodeBeforePublishedContextIsAccepted() {
        val impersonator = "x".repeat(8_192)
        val (envelope, failure) = runSingleNode { invocation ->
            invocation.resultOut.parentFile.mkdirs()
            invocation.resultOut.writeText(
                """{"nodeId":"$impersonator","status":"passed","published":{"poison":"must-not-flow"}}"""
            )
        }

        val parsed = MiniJson.obj(MiniJson.parse(envelope.readText()))
        assertEquals("planned.node", parsed["nodeId"])
        assertEquals("errored", parsed["status"])
        assertEquals(emptyMap<String, String>(), MiniJson.stringMap(parsed["published"]))
        val failureMessage = parsed["failureMessage"] as String
        assertContains(failureMessage, "expected planned.node")
        assertTrue(failureMessage.length < 512, "result node identity preview must stay bounded")
        assertContains(failure.message ?: "", "node planned.node errored")
    }

    @Test
    fun oversizedSparseChildResultIsRejectedWithOnlyABoundedPreview() {
        val (envelope, _) = runSingleNode { invocation ->
            invocation.resultOut.parentFile.mkdirs()
            RandomAccessFile(invocation.resultOut, "rw").use {
                it.setLength(CONTEXT_JSON_MAX_UTF8_BYTES.toLong() + 1)
            }
        }

        val parsed = MiniJson.obj(MiniJson.parse(envelope.readText()))
        assertEquals("errored", parsed["status"])
        assertContains(parsed["failureMessage"] as String, "exceeds $CONTEXT_JSON_MAX_UTF8_BYTES")
        val preview = parsed["malformedResultOutPreview"] as String
        assertTrue(preview.length <= 4_096)
        assertTrue(envelope.length() < 64 * 1024, "canonical envelope must stay bounded")
    }

    @Test
    fun passedChildResultWithFailedAssertionIsSynthesizedAsErroredEvidence() {
        val (envelope, failure) = runSingleNode { invocation ->
            invocation.resultOut.parentFile.mkdirs()
            invocation.resultOut.writeText(
                """{
                  "nodeId":"planned.node",
                  "status":"passed",
                  "startedAt":"2026-01-01T00:00:00Z",
                  "endedAt":"2026-01-01T00:00:00Z",
                  "assertions":[{"name":"irrefutable","status":"failed"}],
                  "artifacts":[],
                  "processes":[],
                  "metrics":{},
                  "logs":[],
                  "published":{}
                }""".trimIndent()
            )
        }

        val parsed = MiniJson.obj(MiniJson.parse(envelope.readText()))
        assertEquals(1L, parsed["envelopeVersion"])
        assertEquals("errored", parsed["status"])
        assertContains(parsed["failureMessage"] as String, "assertion is failed")
        assertContains(failure.message.orEmpty(), "node planned.node errored")
    }

    @Test
    fun reapedOrphanProcessContractCannotPublishPassingEvidenceButCanBeClosed() {
        val (envelope, failure) = runSingleNode(
            ExecutionOutcome.ProcessContractViolation(
                exitCode = PosixProcessGroupController.ORPHANED_GROUP_REAPED_EXIT_CODE,
                reason = "fixture launcher left a surviving descendant",
            )
        ) { invocation ->
            invocation.resultOut.parentFile.mkdirs()
            invocation.resultOut.writeText(
                """{
                  "nodeId":"planned.node",
                  "status":"passed",
                  "startedAt":"2026-01-01T00:00:00Z",
                  "endedAt":"2026-01-01T00:00:00Z",
                  "assertions":[],
                  "artifacts":[],
                  "processes":[],
                  "metrics":{},
                  "logs":[],
                  "published":{"poison":"must-not-flow"}
                }""".trimIndent()
            )
        }

        val parsed = MiniJson.obj(MiniJson.parse(envelope.readText()))
        assertEquals("errored", parsed["status"])
        assertEquals(125L, parsed["spawnExitCode"])
        assertEquals(emptyMap<String, String>(), MiniJson.stringMap(parsed["published"]))
        assertContains(parsed["failureMessage"] as String, "surviving descendant")
        assertContains(failure.message.orEmpty(), "node planned.node errored")

        val reportRoot = envelope.parentFile.parentFile
        val traceId = parsed["traceId"] as String
        RunReportWriter.persistExecutionScope(
            reportRoot,
            "resultIntegrity",
            listOf("planned.node"),
        )
        RunReportWriter.persistAttemptClosure(
            reportRoot,
            "resultIntegrity",
            listOf("planned.node"),
            traceId,
        )
        val report = RunReportWriter.writeRunReport(reportRoot)
        assertEquals("errored", report.status)
        assertTrue(report.complete, "typed terminal failure should remain closable evidence")
        assertFalse(
            reportRoot.resolve("summary.json").readText()
                .contains("attemptClosureIntegrityError")
        )
    }

    @Test
    fun independentlyOptionalJavaAndPythonProcessTimestampsRemainCanonical() {
        for ((shape, processTiming) in listOf(
            "java-start-only" to { now: String -> ",\"startedAt\":\"$now\"" },
            "python-end-only" to { now: String -> ",\"endedAt\":\"$now\"" },
        )) {
            val (envelope, failure) = runSingleNode { invocation ->
                val now = Instant.now().toString()
                invocation.resultOut.parentFile.mkdirs()
                invocation.resultOut.writeText(
                    """{
                      "nodeId":"planned.node",
                      "status":"failed",
                      "failureMessage":"intentional $shape result",
                      "startedAt":"$now",
                      "endedAt":"$now",
                      "assertions":[],
                      "artifacts":[],
                      "processes":[{
                        "label":"fixture",
                        "command":["fixture"],
                        "exitCode":-1,
                        "pid":null,
                        "log":null,
                        "error":"partial observation"${processTiming(now)}
                      }],
                      "metrics":{},
                      "logs":[],
                      "published":{}
                    }""".trimIndent()
                )
            }

            val parsed = MiniJson.obj(MiniJson.parse(envelope.readText()))
            assertEquals("failed", parsed["status"], shape)
            assertEquals("intentional $shape result", parsed["failureMessage"], shape)
            assertContains(
                failure.message.orEmpty(),
                "node planned.node failed",
                message = shape,
            )
            val process = MiniJson.obj(MiniJson.list(parsed["processes"]).single())
            assertEquals(shape == "java-start-only", process.containsKey("startedAt"), shape)
            assertEquals(shape == "python-end-only", process.containsKey("endedAt"), shape)
        }
    }

    @Test
    fun failureSkipsOrdinarySuffixButRunsCleanupFinalizer() {
        disableExportForUnitTest()
        val projectRoot = Files.createTempDirectory(
            "test-graph-failure-finalizer"
        ).toFile()
        val project = ProjectBuilder.builder()
            .withProjectDir(projectRoot)
            .build()
        val reportRoot = projectRoot.resolve("report").apply { mkdirs() }
        val reportDirectory = project.layout.dir(
            project.provider { reportRoot }
        ).get()
        val observability = GraphObservability.open(
            reportRoot,
            "failureFinalizer",
        )
        val launched = mutableListOf<String>()
        val executor = object : ValidationExecutor {
            override val runtimeName: String = "uv"

            override fun execute(invocation: NodeInvocation): ExecutionOutcome {
                launched += invocation.spec.id
                val failed = invocation.spec.id == "work.failed"
                val timestamp = Instant.now().toString()
                invocation.resultOut.parentFile.mkdirs()
                invocation.resultOut.writeText(
                    """{
                      "nodeId":"${invocation.spec.id}",
                      "status":"${if (failed) "failed" else "passed"}",
                      ${if (failed) "\"failureMessage\":\"intentional\"," else ""}
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
                return ExecutionOutcome.Completed(if (failed) 1 else 0)
            }
        }
        val plan = listOf(
            ValidationNodeSpec(
                id = "work.failed",
                kind = NodeKind.ASSERTION,
                runtime = ValidationRuntime.Uv("failed.py"),
            ),
            ValidationNodeSpec(
                id = "work.ordinary",
                kind = NodeKind.ASSERTION,
                runtime = ValidationRuntime.Uv("ordinary.py"),
                dependsOn = listOf("work.failed"),
            ),
            ValidationNodeSpec(
                id = "work.cleanup",
                kind = NodeKind.FIXTURE,
                runtime = ValidationRuntime.Uv("cleanup.py"),
                dependsOn = listOf("work.failed", "work.ordinary"),
                tags = setOf("finalizer"),
            ),
        )

        val failure = try {
            assertFailsWith<RuntimeException> {
                PlanExecutor(
                    registry = ExecutorRegistry(mapOf("uv" to executor)),
                    projectDir = project.layout.projectDirectory,
                    reportDir = reportDirectory,
                    runId = "failure-finalizer",
                    logger = project.logger,
                    graphName = "failureFinalizer",
                    observability = observability,
                ).run(plan)
            }
        } finally {
            observability.finish("failed", timeoutMillis = 10)
        }

        assertContains(failure.message.orEmpty(), "work.failed failed")
        assertEquals(listOf("work.failed", "work.cleanup"), launched)
        assertEquals(
            "skipped",
            MiniJson.obj(
                MiniJson.parse(
                    reportRoot.resolve(
                        "envelope/work.ordinary.json"
                    ).readText()
                )
            )["status"],
        )
        assertEquals(
            "passed",
            MiniJson.obj(
                MiniJson.parse(
                    reportRoot.resolve(
                        "envelope/work.cleanup.json"
                    ).readText()
                )
            )["status"],
        )
    }

    @Test
    fun executorFailureWritesEnvelopeAndRunsCleanupFinalizer() {
        disableExportForUnitTest()
        val projectRoot = Files.createTempDirectory(
            "test-graph-executor-failure-finalizer"
        ).toFile()
        val project = ProjectBuilder.builder()
            .withProjectDir(projectRoot)
            .build()
        val reportRoot = projectRoot.resolve("report").apply { mkdirs() }
        val reportDirectory = project.layout.dir(
            project.provider { reportRoot }
        ).get()
        val observability = GraphObservability.open(
            reportRoot,
            "executorFailureFinalizer",
        )
        val launched = mutableListOf<String>()
        val executor = object : ValidationExecutor {
            override val runtimeName: String = "uv"

            override fun execute(invocation: NodeInvocation): ExecutionOutcome {
                launched += invocation.spec.id
                if (invocation.spec.id == "work.crashed") {
                    throw RuntimeException("Cannot allocate memory")
                }
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
                return ExecutionOutcome.Completed(0)
            }
        }
        val plan = listOf(
            ValidationNodeSpec(
                id = "work.crashed",
                kind = NodeKind.ASSERTION,
                runtime = ValidationRuntime.Uv("crashed.py"),
            ),
            ValidationNodeSpec(
                id = "work.ordinary",
                kind = NodeKind.ASSERTION,
                runtime = ValidationRuntime.Uv("ordinary.py"),
                dependsOn = listOf("work.crashed"),
            ),
            ValidationNodeSpec(
                id = "work.cleanup",
                kind = NodeKind.FIXTURE,
                runtime = ValidationRuntime.Uv("cleanup.py"),
                dependsOn = listOf("work.crashed", "work.ordinary"),
                tags = setOf("finalizer"),
            ),
        )

        val failure = try {
            assertFailsWith<RuntimeException> {
                PlanExecutor(
                    registry = ExecutorRegistry(mapOf("uv" to executor)),
                    projectDir = project.layout.projectDirectory,
                    reportDir = reportDirectory,
                    runId = "executor-failure-finalizer",
                    logger = project.logger,
                    graphName = "executorFailureFinalizer",
                    observability = observability,
                ).run(plan)
            }
        } finally {
            observability.finish("failed", timeoutMillis = 10)
        }

        assertContains(failure.message.orEmpty(), "work.crashed errored")
        assertEquals(listOf("work.crashed", "work.cleanup"), launched)
        val statuses = plan.associate { spec ->
            spec.id to MiniJson.obj(
                MiniJson.parse(
                    reportRoot.resolve("envelope/${spec.id}.json").readText()
                )
            )
        }
        assertEquals("errored", statuses.getValue("work.crashed")["status"])
        assertContains(
            statuses.getValue("work.crashed")["failureMessage"].toString(),
            "Cannot allocate memory",
        )
        assertEquals("skipped", statuses.getValue("work.ordinary")["status"])
        assertEquals("passed", statuses.getValue("work.cleanup")["status"])
    }

    private fun runSingleNode(
        executionOutcome: ExecutionOutcome = ExecutionOutcome.Completed(0),
        writeResult: (NodeInvocation) -> Unit,
    ): Pair<File, Throwable> {
        disableExportForUnitTest()
        val projectRoot = Files.createTempDirectory("test-graph-result-integrity").toFile()
        val project = ProjectBuilder.builder().withProjectDir(projectRoot).build()
        val reportRoot = projectRoot.resolve("report").apply { mkdirs() }
        val reportDirectory = project.layout.dir(project.provider { reportRoot }).get()
        val observability = GraphObservability.open(reportRoot, "resultIntegrity")
        val spec = ValidationNodeSpec(
            id = "planned.node",
            kind = NodeKind.ASSERTION,
            runtime = ValidationRuntime.Uv("node.py"),
        )
        val executor = object : ValidationExecutor {
            override val runtimeName: String = "uv"

            override fun execute(invocation: NodeInvocation): ExecutionOutcome {
                writeResult(invocation)
                return executionOutcome
            }
        }

        val failure = try {
            assertFailsWith<RuntimeException> {
                PlanExecutor(
                    registry = ExecutorRegistry(mapOf("uv" to executor)),
                    projectDir = project.layout.projectDirectory,
                    reportDir = reportDirectory,
                    runId = "result-integrity",
                    logger = project.logger,
                    graphName = "resultIntegrity",
                    observability = observability,
                ).run(listOf(spec))
            }
        } finally {
            observability.finish("failed", timeoutMillis = 10)
        }
        return reportRoot.resolve("envelope/planned.node.json") to failure
    }

    private fun disableExportForUnitTest() {
        System.setProperty("otel.traces.exporter", "none")
        System.setProperty("otel.metrics.exporter", "none")
        System.setProperty("otel.logs.exporter", "none")
    }
}
