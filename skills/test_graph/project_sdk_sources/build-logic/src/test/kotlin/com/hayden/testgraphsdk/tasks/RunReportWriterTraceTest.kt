package com.hayden.testgraphsdk.tasks

import com.hayden.testgraphsdk.MiniJson
import com.hayden.testgraphsdk.exec.CONTEXT_JSON_MAX_DEPTH
import com.hayden.testgraphsdk.exec.CONTEXT_JSON_MAX_UTF8_BYTES
import com.hayden.testgraphsdk.exec.ContextItem
import com.hayden.testgraphsdk.exec.ContextSerde
import com.hayden.testgraphsdk.exec.readInputContextSnapshot
import com.hayden.testgraphsdk.exec.writeInputContextSnapshot
import org.gradle.testfixtures.ProjectBuilder
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RunReportWriterTraceTest {

    private val graphName = "reportFixture"

    @Test
    fun runDirectoryAllocationNeverReopensAnExistingAttempt() {
        val reportRoot = Files.createTempDirectory("test-graph-run-allocation").toFile()
        val first = RunIds.allocate(reportRoot)
        first.resolve("source-marker.txt").writeText("immutable source")

        val second = RunIds.allocate(reportRoot)

        assertTrue(first.canonicalFile != second.canonicalFile)
        assertEquals("immutable source", first.resolve("source-marker.txt").readText())
        assertTrue(second.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun concurrentRunDirectoryAllocationCreatesAnAbsentRootWithoutCollisions() {
        val parent = Files.createTempDirectory("test-graph-concurrent-allocation").toFile()
        val reportRoot = parent.resolve("not-created-yet")
        val workers = 8
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures = List(workers) {
                executor.submit<java.io.File> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    RunIds.allocate(reportRoot)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val allocated = futures.map { it.get(10, TimeUnit.SECONDS).canonicalFile }

            assertTrue(reportRoot.isDirectory)
            assertEquals(workers, allocated.toSet().size)
            assertTrue(allocated.all { it.parentFile == reportRoot.canonicalFile })
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun runDirectoryAllocationRejectsSymlinkedReportRoot() {
        val parent = Files.createTempDirectory("test-graph-symlinked-report-root")
        val realRoot = Files.createDirectory(parent.resolve("real-root"))
        val linkedRoot = parent.resolve("linked-root")
        Files.createSymbolicLink(linkedRoot, realRoot)

        assertFailsWith<IllegalStateException> {
            RunIds.allocate(linkedRoot.toFile())
        }
        assertTrue(realRoot.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun replaySourceMustBeARealDirectChildOfTheConfiguredReportRoot() {
        val reportRoot = Files.createTempDirectory("test-graph-replay-root")
        val source = Files.createDirectory(reportRoot.resolve("source"))
        assertEquals(
            source.toFile().canonicalFile,
            requireReplaySourceInReportRoot(reportRoot.toFile(), source.toFile()),
        )

        val external = Files.createTempDirectory("test-graph-external-replay-source")
        assertFailsWith<IllegalArgumentException> {
            requireReplaySourceInReportRoot(reportRoot.toFile(), external.toFile())
        }

        val linkedSource = reportRoot.resolve("linked-source")
        Files.createSymbolicLink(linkedSource, source)
        assertFailsWith<IllegalArgumentException> {
            requireReplaySourceInReportRoot(reportRoot.toFile(), linkedSource.toFile())
        }

        val linkedRoot = reportRoot.parent.resolve("${reportRoot.fileName}-linked")
        Files.createSymbolicLink(linkedRoot, reportRoot)
        assertFailsWith<IllegalArgumentException> {
            requireReplaySourceInReportRoot(
                linkedRoot.toFile(),
                linkedRoot.resolve("source").toFile(),
            )
        }
    }

    @Test
    fun exposesTheEnvelopeTraceIdInSummaryAndMarkdown() {
        val runDir = Files.createTempDirectory("test-graph-report").toFile()
        val traceId = "0123456789abcdef0123456789abcdef"
        writePublishedEnvelope(runDir, "probe", emptyMap(), traceId)

        RunReportWriter.writeRunReport(runDir)

        assertContains(runDir.resolve("summary.json").readText(), "\"traceId\":\"$traceId\"")
        assertContains(runDir.resolve("report.md").readText(), "**Trace ID**: `$traceId`")
    }

    @Test
    fun abortedFiveNodeRunIsErroredAndRetainsThreeCompletedNodeEnvelopes() {
        val runDir = Files.createTempDirectory("test-graph-aborted-report").toFile()
        writePassingEnvelopes(runDir, listOf("one", "two", "three"))

        RunReportWriter.writeRunReport(
            runDir = runDir,
            graphName = graphName,
            expectedNodeIds = listOf("one", "two", "three", "four", "five"),
            executionFailure = StackOverflowError("context extraction overflowed"),
        )

        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        assertEquals("errored", summary["status"])
        val execution = MiniJson.obj(summary["execution"])
        assertFalse(execution["complete"] as Boolean)
        assertEquals(listOf("four", "five"), MiniJson.stringList(execution["missingNodeIds"]))
        assertEquals(
            "java.lang.StackOverflowError",
            MiniJson.obj(execution["failure"])["type"],
        )
        val retainedNodeIds = MiniJson.list(summary["nodes"])
            .map { MiniJson.obj(it)["nodeId"] as String }
            .toSet()
        assertEquals(setOf("one", "two", "three"), retainedNodeIds)

        val markdown = runDir.resolve("report.md").readText()
        assertContains(markdown, "**Overall**: ERRORED")
        assertContains(markdown, "**Plan evidence**: 3/5 expected node envelopes observed")
        assertContains(markdown, "**Missing node envelopes**: `four`, `five`")
        assertContains(markdown, "## `one` — **PASS**")
    }

    @Test
    fun incompletePlanWithoutAThrowableIsStillErrored() {
        val runDir = Files.createTempDirectory("test-graph-incomplete-report").toFile()
        writePassingEnvelopes(runDir, listOf("one", "two", "three"))

        val reportOutcome = RunReportWriter.writeRunReport(
            runDir = runDir,
            graphName = graphName,
            expectedNodeIds = listOf("one", "two", "three", "four", "five"),
        )

        assertFalse(reportOutcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        assertEquals("errored", summary["status"])
        assertContains(runDir.resolve("report.md").readText(), "**Overall**: ERRORED")
    }

    @Test
    fun runOnlyReportIsCompleteForItsSingleNodeReplayScope() {
        val sourceBuild = Files.createTempDirectory("test-graph-run-only-source").toFile()
        RunReportWriter.persistExecutionScope(sourceBuild, graphName, listOf("selected"))
        writePassingEnvelopes(
            sourceBuild,
            listOf("selected"),
            "0123456789abcdef0123456789abcdef",
        )
        closeAttempt(sourceBuild, listOf("selected"))
        val runDir = Files.createTempDirectory("test-graph-run-only-report").toFile()
        val traceId = "0123456789abcdef0123456789abcdef"
        writePublishedEnvelope(runDir, "selected", emptyMap(), traceId)
        writeInputContexts(runDir, listOf("selected"))
        writeTraceCarrier(runDir, traceId)
        val replay = replayMetadata(
            RunReportWriter.ReplayMode.RUN_ONLY_NODE,
            "selected",
            sourceBuild,
        )
        RunReportWriter.persistExecutionScope(runDir, graphName, listOf("selected"), replay)
        RunReportWriter.persistAttemptClosure(
            runDir,
            graphName,
            listOf("selected"),
            traceId,
            replay,
        )

        val outcome = RunReportWriter.writeRunReport(
            runDir = runDir,
            graphName = graphName,
            expectedNodeIds = listOf("selected"),
            expectedTraceId = traceId,
            replay = replay,
        )

        assertTrue(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("passed", summary["status"])
        assertEquals("run-only-node", execution["mode"])
        assertEquals("selected", execution["selectedNodeId"])
        assertEquals(sourceBuild.canonicalPath, execution["sourceBuild"])
        assertEquals(listOf("selected"), MiniJson.stringList(execution["expectedNodeIds"]))
        assertTrue(execution["complete"] as Boolean)
        val markdown = runDir.resolve("report.md").readText()
        assertContains(markdown, "**Execution scope**: `run-only-node` from `selected`")
        assertContains(markdown, "**Plan evidence**: 1/1 expected node envelopes observed")

        val regenerated = RunReportWriter.writeRunReport(runDir)
        assertTrue(regenerated.passed)
        val regeneratedExecution = MiniJson.obj(
            MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))["execution"]
        )
        assertEquals("run-only-node", regeneratedExecution["mode"])
        assertEquals(listOf("selected"), MiniJson.stringList(regeneratedExecution["expectedNodeIds"]))
    }

    @Test
    fun incompleteResumeReportFailsClosedWithinItsSelectedTailScope() {
        val sourceBuild = Files.createTempDirectory("test-graph-resume-source").toFile()
        RunReportWriter.persistExecutionScope(
            sourceBuild,
            graphName,
            listOf("selected", "downstream"),
        )
        writePassingEnvelopes(
            sourceBuild,
            listOf("selected", "downstream"),
            "0123456789abcdef0123456789abcdef",
        )
        closeAttempt(sourceBuild, listOf("selected", "downstream"))
        val runDir = Files.createTempDirectory("test-graph-resume-report").toFile()
        val traceId = "0123456789abcdef0123456789abcdef"
        writePassingEnvelopes(runDir, listOf("selected"), traceId)
        writeTraceCarrier(runDir, traceId)
        val replay = replayMetadata(
            RunReportWriter.ReplayMode.RESUME_FROM_NODE,
            "selected",
            sourceBuild,
        )
        RunReportWriter.persistExecutionScope(
            runDir,
            graphName,
            listOf("selected", "downstream"),
            replay,
        )

        val outcome = RunReportWriter.writeRunReport(
            runDir = runDir,
            graphName = graphName,
            expectedNodeIds = listOf("selected", "downstream"),
            expectedTraceId = traceId,
            replay = replay,
        )

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals("resume-from-node", execution["mode"])
        assertEquals(listOf("downstream"), MiniJson.stringList(execution["missingNodeIds"]))
        assertFalse(execution["complete"] as Boolean)

        val regenerated = RunReportWriter.writeRunReport(runDir)
        assertFalse(regenerated.passed)
        val regeneratedSummary = MiniJson.obj(
            MiniJson.parse(runDir.resolve("summary.json").readText())
        )
        assertEquals("errored", regeneratedSummary["status"])
        assertEquals(
            "resume-from-node",
            MiniJson.obj(regeneratedSummary["execution"])["mode"],
        )
    }

    @Test
    fun regeneratedScopedReportAnchorsEnvelopeTracesToPersistedCarrier() {
        val runDir = Files.createTempDirectory("test-graph-regenerated-trace-anchor").toFile()
        val expectedTraceId = "0123456789abcdef0123456789abcdef"
        val wrongTraceId = "fedcba9876543210fedcba9876543210"
        writeTraceCarrier(runDir, expectedTraceId)
        writePassingEnvelopes(runDir, listOf("only"), wrongTraceId)
        RunReportWriter.persistExecutionScope(runDir, graphName, listOf("only"))

        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.writeRunReport(
                runDir,
                graphName = graphName,
                expectedNodeIds = listOf("only"),
                expectedTraceId = wrongTraceId,
            )
        }

        val outcome = RunReportWriter.writeRunReport(runDir)

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals(expectedTraceId, summary["traceId"])
        assertEquals(listOf("only"), MiniJson.stringList(execution["mismatchedTraceNodeIds"]))
    }

    @Test
    fun missingPersistedScopeIsLegacyUnknownAndCannotClaimCompleteness() {
        val runDir = Files.createTempDirectory("test-graph-legacy-unknown-scope").toFile()
        writePassingEnvelopes(runDir, listOf("only"))

        val outcome = RunReportWriter.writeRunReport(runDir)

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals("legacy-unknown", execution["mode"])
        assertEquals(true, execution["unknownExecutionScope"])
        assertFalse(execution["complete"] as Boolean)
        assertContains(runDir.resolve("report.md").readText(), "completeness cannot be proven")
    }

    @Test
    fun persistedExecutionScopeCannotBeReplacedByAnotherPlan() {
        val runDir = Files.createTempDirectory("test-graph-immutable-scope").toFile()
        RunReportWriter.persistExecutionScope(runDir, graphName, listOf("one"))
        val scopeFile = runDir.resolve("execution-scope.json")
        val original = scopeFile.readText()

        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.persistExecutionScope(runDir, graphName, listOf("two"))
        }

        assertEquals(original, scopeFile.readText())
        val scope = MiniJson.obj(MiniJson.parse(original))
        assertEquals(3L, scope["version"])
        assertEquals(graphName, scope["graphName"])
    }

    @Test
    fun persistedReplayScopeEnforcesModeShapeAndDistinctSource() {
        val source = Files.createTempDirectory("test-graph-scope-shape-source").toFile()
        RunReportWriter.persistExecutionScope(source, graphName, listOf("selected"))
        writePassingEnvelopes(
            source,
            listOf("selected"),
            "0123456789abcdef0123456789abcdef",
        )
        closeAttempt(source, listOf("selected"))
        val runDir = Files.createTempDirectory("test-graph-scope-shape-target").toFile()

        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.persistExecutionScope(
                runDir,
                graphName,
                listOf("selected", "extra"),
                unverifiedReplayMetadata(
                    RunReportWriter.ReplayMode.RUN_ONLY_NODE,
                    "selected",
                    source,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.persistExecutionScope(
                runDir,
                graphName,
                listOf("before", "selected"),
                unverifiedReplayMetadata(
                    RunReportWriter.ReplayMode.RESUME_FROM_NODE,
                    "selected",
                    source,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.persistExecutionScope(
                runDir,
                graphName,
                listOf("selected"),
                unverifiedReplayMetadata(
                    RunReportWriter.ReplayMode.RUN_ONLY_NODE,
                    "selected",
                    runDir,
                ),
            )
        }
        val runAlias = runDir.parentFile.resolve("${runDir.name}-alias")
        Files.createSymbolicLink(runAlias.toPath(), runDir.toPath())
        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.persistExecutionScope(
                runDir,
                graphName,
                listOf("selected"),
                unverifiedReplayMetadata(
                    RunReportWriter.ReplayMode.RUN_ONLY_NODE,
                    "selected",
                    runAlias,
                ),
            )
        }
        assertFalse(runDir.resolve("execution-scope.json").exists())
    }

    @Test
    fun replaySourceMustBeStrictV3ScopeForTheSameGraphAndSelectedNode() {
        val source = Files.createTempDirectory("test-graph-strict-replay-source").toFile()
        RunReportWriter.persistExecutionScope(
            source,
            graphName,
            listOf("before", "selected", "after"),
        )
        writePassingEnvelopes(
            source,
            listOf("before", "selected", "after"),
            "0123456789abcdef0123456789abcdef",
        )
        closeAttempt(source, listOf("before", "selected", "after"))

        RunReportWriter.requireReplaySource(source, graphName, "selected")
        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.requireReplaySource(source, "anotherGraph", "selected")
        }
        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.requireReplaySource(source, graphName, "absent")
        }

        val legacy = Files.createTempDirectory("test-graph-legacy-replay-source").toFile()
        legacy.resolve("execution-scope.json").writeText(
            """{"version":1,"mode":"full","expectedNodeIds":["selected"]}"""
        )
        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.requireReplaySource(legacy, graphName, "selected")
        }

        val arbitrary = Files.createTempDirectory("test-graph-arbitrary-replay-source").toFile()
        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.requireReplaySource(arbitrary, graphName, "selected")
        }
    }

    @Test
    fun activeOrCrashedUnsealedReplaySourceFailsUntilClosureIsPublished() {
        val source = Files.createTempDirectory("test-graph-active-replay-source").toFile()
        RunReportWriter.persistExecutionScope(source, graphName, listOf("selected"))
        val traceId = "0123456789abcdef0123456789abcdef"
        writePassingEnvelopes(source, listOf("selected"), traceId)
        writeTraceCarrier(source, traceId)
        val publish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val closure = executor.submit {
                check(publish.await(5, TimeUnit.SECONDS))
                RunReportWriter.persistAttemptClosure(
                    source,
                    graphName,
                    listOf("selected"),
                    traceId,
                )
            }

            assertFailsWith<IllegalArgumentException> {
                RunReportWriter.requireReplaySource(source, graphName, "selected")
            }
            publish.countDown()
            closure.get(10, TimeUnit.SECONDS)

            RunReportWriter.requireReplaySource(source, graphName, "selected")
        } finally {
            publish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun malformedAndSymlinkAttemptClosuresFailClosed() {
        val malformed = Files.createTempDirectory("test-graph-malformed-closure").toFile()
        RunReportWriter.persistExecutionScope(malformed, graphName, listOf("selected"))
        writeTraceCarrier(malformed, "0123456789abcdef0123456789abcdef")
        malformed.resolve("attempt-closure.json").writeText("""{"version":1}""")
        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.requireReplaySource(malformed, graphName, "selected")
        }

        val linked = Files.createTempDirectory("test-graph-linked-closure").toFile()
        RunReportWriter.persistExecutionScope(linked, graphName, listOf("selected"))
        writeTraceCarrier(linked, "0123456789abcdef0123456789abcdef")
        val external = Files.createTempFile("test-graph-external-closure", ".json")
        Files.writeString(external, "{}")
        Files.createSymbolicLink(linked.resolve("attempt-closure.json").toPath(), external)
        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.requireReplaySource(linked, graphName, "selected")
        }
        assertEquals("{}", Files.readString(external))
    }

    @Test
    fun concurrentAttemptClosurePublicationIsAtomicAndNoReplace() {
        val source = Files.createTempDirectory("test-graph-racing-closure").toFile()
        val traceId = "0123456789abcdef0123456789abcdef"
        RunReportWriter.persistExecutionScope(source, graphName, listOf("selected"))
        writePassingEnvelopes(source, listOf("selected"), traceId)
        writeTraceCarrier(source, traceId)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                executor.submit<Result<Unit>> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    runCatching {
                        RunReportWriter.persistAttemptClosure(
                            source,
                            graphName,
                            listOf("selected"),
                            traceId,
                        )
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val completed = results.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, completed.count { it.isSuccess })
            assertEquals(1, completed.count { it.isFailure })
            RunReportWriter.requireReplaySource(source, graphName, "selected")
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun closureV3BindsFinalizersRawScopeCarrierAndExactSortedEvidenceMaps() {
        val source = Files.createTempDirectory("test-graph-closure-v3").toFile()
        val traceId = "0123456789abcdef0123456789abcdef"
        val expected = listOf("before", "selected")
        RunReportWriter.persistExecutionScope(source, graphName, expected)
        writePassingEnvelopes(source, expected, traceId)
        writeTraceCarrier(source, traceId)

        RunReportWriter.persistAttemptClosure(source, graphName, expected, traceId)

        val closureRaw = source.resolve("attempt-closure.json").readText()
        val closure = MiniJson.obj(MiniJson.parse(closureRaw))
        assertEquals(3L, closure["version"])
        assertEquals(emptyList<Any?>(), closure["finalizerNodeIds"])
        assertEquals(
            com.hayden.testgraphsdk.exec.sha256Utf8(
                source.resolve("execution-scope.json").readText()
            ),
            closure["scopeSha256"],
        )
        assertEquals(
            com.hayden.testgraphsdk.exec.sha256Utf8(
                source.resolve("trace-context.json").readText()
            ),
            closure["carrierSha256"],
        )
        assertEquals(
            listOf("context/before.input.json", "context/selected.input.json"),
            MiniJson.obj(closure["contextSha256"]).keys.toList(),
        )
        assertEquals(
            listOf("envelope/before.json", "envelope/selected.json"),
            MiniJson.obj(closure["envelopeSha256"]).keys.toList(),
        )
    }

    @Test
    fun closureV2WithoutFinalizerMetadataRemainsReplayable() {
        val source = Files.createTempDirectory("test-graph-closure-v2-compatible").toFile()
        val traceId = "0123456789abcdef0123456789abcdef"
        val expected = listOf("before", "selected")
        RunReportWriter.persistExecutionScope(source, graphName, expected)
        writePassingEnvelopes(source, expected, traceId)
        writeTraceCarrier(source, traceId)
        RunReportWriter.persistAttemptClosure(source, graphName, expected, traceId)

        val closure = source.resolve("attempt-closure.json")
        closure.writeText(
            closure.readText()
                .replaceFirst("\"version\":3", "\"version\":2")
                .replace(",\"finalizerNodeIds\":[]", "")
        )

        RunReportWriter.requireReplaySource(source, graphName, "selected")
    }

    @Test
    fun failedAttemptWithSkippedSuffixAndPassedFinalizerIsClosedAndReplayable() {
        val source = Files.createTempDirectory("test-graph-finalizer-closure").toFile()
        val traceId = "0123456789abcdef0123456789abcdef"
        val expected = listOf("work.failed", "work.skipped", "work.cleanup")
        RunReportWriter.persistExecutionScope(source, graphName, expected)
        writePassingEnvelopes(source, expected, traceId)
        source.resolve("envelope/work.failed.json").let { envelope ->
            envelope.writeText(
                envelope.readText().replace(
                    "\"status\":\"passed\"",
                    "\"status\":\"failed\",\"failureMessage\":\"expected fixture failure\"",
                )
            )
        }
        source.resolve("envelope/work.skipped.json").let { envelope ->
            envelope.writeText(
                envelope.readText().replace(
                    "\"status\":\"passed\"",
                    "\"status\":\"skipped\",\"failureMessage\":\"skipped after failure\"",
                )
            )
        }
        Files.delete(source.resolve("context/work.cleanup.input.json").toPath())
        writeInputContextSnapshot(
            listOf(ContextItem("work.failed", emptyMap())),
            source,
            "work.cleanup",
        )
        writeTraceCarrier(source, traceId)

        RunReportWriter.persistAttemptClosure(
            runDir = source,
            graphName = graphName,
            expectedNodeIds = expected,
            traceId = traceId,
            finalizerNodeIds = setOf("work.cleanup"),
        )

        val replay = RunReportWriter.requireReplaySource(
            source,
            graphName,
            "work.failed",
        )
        assertEquals("work.failed", replay.selectedNodeId)
        val closure = MiniJson.obj(
            MiniJson.parse(source.resolve("attempt-closure.json").readText())
        )
        assertEquals(listOf("work.cleanup"), closure["finalizerNodeIds"])

        val outcome = RunReportWriter.writeRunReport(
            runDir = source,
            graphName = graphName,
            expectedNodeIds = expected,
            executionFailure = RuntimeException("expected fixture failure"),
            expectedTraceId = traceId,
        )
        assertEquals("errored", outcome.status)
        val summary = source.resolve("summary.json").readText()
        assertContains(summary, "\"contextProvenanceViolationNodeIds\":[]")
        assertFalse(summary.contains("\"attemptClosureIntegrityError\""))
    }

    @Test
    fun regeneratedReportCannotTurnGreenAfterClosedFailureEvidenceIsRewritten() {
        val runDir = Files.createTempDirectory("test-graph-closed-report-tamper").toFile()
        val traceId = "0123456789abcdef0123456789abcdef"
        RunReportWriter.persistExecutionScope(runDir, graphName, listOf("selected"))
        writePassingEnvelopes(runDir, listOf("selected"), traceId)
        val envelope = runDir.resolve("envelope/selected.json")
        envelope.writeText(
            envelope.readText().replace(
                "\"status\":\"passed\"",
                "\"status\":\"failed\",\"failureMessage\":\"expected fixture failure\"",
            )
        )
        closeAttempt(runDir, listOf("selected"), traceId)

        val original = RunReportWriter.writeRunReport(runDir)
        assertEquals("failed", original.status)
        assertTrue(original.complete)

        envelope.writeText(
            envelope.readText().replace(
                "\"status\":\"failed\",\"failureMessage\":\"expected fixture failure\"",
                "\"status\":\"passed\"",
            )
        )
        val regenerated = RunReportWriter.writeRunReport(runDir)

        assertEquals("errored", regenerated.status)
        assertFalse(regenerated.complete)
        val summary = runDir.resolve("summary.json").readText()
        assertContains(summary, "attemptClosureIntegrityError")
        assertContains(
            runDir.resolve("report.md").readText(),
            "**Attempt closure integrity**: ERROR",
        )
    }

    @Test
    fun replayAcquisitionRejectsContentAdditionRemovalAndSymlinkTampering() {
        fun source(): java.io.File {
            val run = Files.createTempDirectory("test-graph-bound-source").toFile()
            val traceId = "0123456789abcdef0123456789abcdef"
            RunReportWriter.persistExecutionScope(run, graphName, listOf("selected"))
            writePassingEnvelopes(run, listOf("selected"), traceId)
            closeAttempt(run, listOf("selected"), traceId)
            return run
        }

        source().also { run ->
            run.resolve("context/selected.input.json").writeText(
                """{"items":[{"nodeId":"other","data":{}}]}"""
            )
            assertFailsWith<IllegalArgumentException> {
                RunReportWriter.requireReplaySource(run, graphName, "selected")
            }
        }
        source().also { run ->
            run.resolve("trace-context.json").appendText(" ")
            assertFailsWith<IllegalArgumentException> {
                RunReportWriter.requireReplaySource(run, graphName, "selected")
            }
        }
        source().also { run ->
            run.resolve("envelope/selected.json").appendText(" ")
            assertFailsWith<IllegalArgumentException> {
                RunReportWriter.requireReplaySource(run, graphName, "selected")
            }
        }
        source().also { run ->
            run.resolve("context/extra.input.json").writeText("""{"items":[]}""")
            assertFailsWith<IllegalArgumentException> {
                RunReportWriter.requireReplaySource(run, graphName, "selected")
            }
        }
        source().also { run ->
            Files.delete(run.resolve("context/selected.input.json").toPath())
            assertFailsWith<IllegalArgumentException> {
                RunReportWriter.requireReplaySource(run, graphName, "selected")
            }
        }
        source().also { run ->
            val selected = run.resolve("context/selected.input.json").toPath()
            val external = Files.createTempFile("test-graph-external-context", ".json")
            Files.writeString(external, """{"items":[]}""")
            Files.delete(selected)
            Files.createSymbolicLink(selected, external)
            assertFailsWith<IllegalArgumentException> {
                RunReportWriter.requireReplaySource(run, graphName, "selected")
            }
            assertEquals("""{"items":[]}""", Files.readString(external))
        }
    }

    @Test
    fun closureAndAcquisitionBothRejectDigestConsistentSemanticCorruption() {
        val traceId = "0123456789abcdef0123456789abcdef"
        val invalidPrefix = Files.createTempDirectory("test-graph-invalid-prefix").toFile()
        RunReportWriter.persistExecutionScope(
            invalidPrefix,
            graphName,
            listOf("before", "selected"),
        )
        writeInputContextSnapshot(emptyList(), invalidPrefix, "before")
        writeInputContextSnapshot(emptyList(), invalidPrefix, "selected")
        writePublishedEnvelope(invalidPrefix, "before", mapOf("ready" to "true"), traceId)
        writePublishedEnvelope(invalidPrefix, "selected", emptyMap(), traceId)
        writeTraceCarrier(invalidPrefix, traceId)

        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.persistAttemptClosure(
                invalidPrefix,
                graphName,
                listOf("before", "selected"),
                traceId,
            )
        }
        assertFalse(invalidPrefix.resolve("attempt-closure.json").exists())

        for (nonterminalStatus in listOf("failed", "errored", "skipped")) {
            val nonterminalFailure = Files.createTempDirectory(
                "test-graph-nonterminal-$nonterminalStatus-publication"
            ).toFile()
            val twoNodePrefix = listOf("before", "selected")
            RunReportWriter.persistExecutionScope(
                nonterminalFailure,
                graphName,
                twoNodePrefix,
            )
            writePassingEnvelopes(nonterminalFailure, twoNodePrefix, traceId)
            val firstEnvelope = nonterminalFailure.resolve("envelope/before.json")
            firstEnvelope.writeText(
                firstEnvelope.readText().replace(
                    "\"status\":\"passed\"",
                    "\"status\":\"$nonterminalStatus\"",
                )
            )
            writeTraceCarrier(nonterminalFailure, traceId)

            assertFailsWith<IllegalArgumentException> {
                RunReportWriter.persistAttemptClosure(
                    nonterminalFailure,
                    graphName,
                    twoNodePrefix,
                    traceId,
                )
            }
            assertFalse(nonterminalFailure.resolve("attempt-closure.json").exists())
        }

        fun closedSource(): java.io.File {
            val source = Files.createTempDirectory("test-graph-self-consistent-corrupt").toFile()
            RunReportWriter.persistExecutionScope(source, graphName, listOf("selected"))
            writePassingEnvelopes(source, listOf("selected"), traceId)
            closeAttempt(source, listOf("selected"), traceId)
            return source
        }

        closedSource().also { source ->
            val contextFile = source.resolve("context/selected.input.json")
            val oldDigest = com.hayden.testgraphsdk.exec.sha256Utf8(contextFile.readText())
            val corrupted = """{"items":[{"nodeId":"other","data":{}}]}"""
            contextFile.writeText(corrupted)
            val newDigest = com.hayden.testgraphsdk.exec.sha256Utf8(corrupted)
            val closure = source.resolve("attempt-closure.json")
            closure.writeText(closure.readText().replace(oldDigest, newDigest))

            assertFailsWith<IllegalArgumentException> {
                RunReportWriter.requireReplaySource(source, graphName, "selected")
            }
        }

        closedSource().also { source ->
            val envelopeFile = source.resolve("envelope/selected.json")
            val oldDigest = com.hayden.testgraphsdk.exec.sha256Utf8(envelopeFile.readText())
            val corrupted = "{not-json"
            envelopeFile.writeText(corrupted)
            val newDigest = com.hayden.testgraphsdk.exec.sha256Utf8(corrupted)
            val closure = source.resolve("attempt-closure.json")
            closure.writeText(closure.readText().replace(oldDigest, newDigest))

            assertFailsWith<IllegalArgumentException> {
                RunReportWriter.requireReplaySource(source, graphName, "selected")
            }
        }

        Files.createTempDirectory("test-graph-nonterminal-failure").toFile().also { source ->
            val expected = listOf("before", "selected")
            RunReportWriter.persistExecutionScope(source, graphName, expected)
            writePassingEnvelopes(source, expected, traceId)
            closeAttempt(source, expected, traceId)

            val firstEnvelope = source.resolve("envelope/before.json")
            val original = firstEnvelope.readText()
            val corrupted = original.replace("\"status\":\"passed\"", "\"status\":\"failed\"")
            firstEnvelope.writeText(corrupted)
            val closure = source.resolve("attempt-closure.json")
            closure.writeText(
                closure.readText().replace(
                    com.hayden.testgraphsdk.exec.sha256Utf8(original),
                    com.hayden.testgraphsdk.exec.sha256Utf8(corrupted),
                )
            )

            assertFailsWith<IllegalArgumentException> {
                RunReportWriter.requireReplaySource(source, graphName, "selected")
            }
        }
    }

    @Test
    fun acquiredSnapshotSurvivesPostValidationSourceMutationWithoutRereads() {
        val source = Files.createTempDirectory("test-graph-snapshot-race-source").toFile()
        val traceId = "0123456789abcdef0123456789abcdef"
        RunReportWriter.persistExecutionScope(source, graphName, listOf("selected"))
        writePassingEnvelopes(source, listOf("selected"), traceId)
        closeAttempt(source, listOf("selected"), traceId)
        val snapshot = RunReportWriter.requireReplaySource(source, graphName, "selected")
        val originalContext = snapshot.selectedContextJson
        val originalCarrier = snapshot.carrierJson

        source.resolve("context/selected.input.json").writeText(
            """{"items":[{"nodeId":"other","data":{"value":"tampered"}}]}"""
        )
        source.resolve("trace-context.json").writeText(
            """{"traceparent":"00-$traceId-0123456789abcdef-01","baggage":"tampered"}"""
        )

        assertEquals(originalContext, snapshot.selectedContextJson)
        assertEquals(originalCarrier, snapshot.carrierJson)
        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.requireReplaySource(source, graphName, "selected")
        }

        val target = Files.createTempDirectory("test-graph-snapshot-race-target").toFile()
        writePublishedEnvelope(target, "selected", emptyMap(), traceId)
        target.resolve("context").mkdirs()
        target.resolve("context/selected.input.json").writeText(originalContext)
        target.resolve("trace-context.json").writeText(originalCarrier)
        val replay = RunReportWriter.ReplayMetadata(
            mode = RunReportWriter.ReplayMode.RUN_ONLY_NODE,
            selectedNodeId = "selected",
            sourceBuild = source,
            sourceClosureSha256 = snapshot.closureSha256,
            sourceContextSha256 = snapshot.selectedContextSha256,
        )
        RunReportWriter.persistExecutionScope(target, graphName, listOf("selected"), replay)

        val outcome = RunReportWriter.writeRunReport(
            runDir = target,
            graphName = graphName,
            expectedNodeIds = listOf("selected"),
            expectedTraceId = traceId,
            replay = replay,
            replaySourceSnapshot = snapshot,
        )

        assertFalse(outcome.passed)
        assertEquals("errored", outcome.status)
        assertContains(
            target.resolve("summary.json").readText(),
            "attemptClosureIntegrityError",
        )

        RunReportWriter.persistAttemptClosure(
            target,
            graphName,
            listOf("selected"),
            traceId,
            replay,
        )
        val replaySourceFailure = assertFailsWith<IllegalArgumentException> {
            RunReportWriter.requireReplaySource(target, graphName, "selected")
        }
        assertContains(replaySourceFailure.message.orEmpty(), "must be a full graph attempt")
        assertTrue(
            RunReportWriter.writeRunReport(
                runDir = target,
                graphName = graphName,
                expectedNodeIds = listOf("selected"),
                expectedTraceId = traceId,
                replay = replay,
                replaySourceSnapshot = snapshot,
            ).passed
        )
        assertTrue(RunReportWriter.writeRunReport(target).passed)
    }

    @Test
    fun noncanonicalEnvelopeShapesCannotCloseOrProduceAGreenReport() {
        val traceId = "0123456789abcdef0123456789abcdef"
        val corruptions = linkedMapOf<String, (String) -> String>(
            "passed-failed-assertion" to { raw ->
                raw.replace(
                    "\"assertions\":[]",
                    "\"assertions\":[{\"name\":\"proof\",\"status\":\"failed\"}]",
                )
            },
            "assertions-type" to { raw ->
                raw.replace("\"assertions\":[]", "\"assertions\":\"garbage\"")
            },
            "artifacts-type" to { raw ->
                raw.replace("\"artifacts\":[]", "\"artifacts\":\"garbage\"")
            },
            "processes-type" to { raw ->
                raw.replace("\"processes\":[]", "\"processes\":\"garbage\"")
            },
            "metrics-type" to { raw ->
                raw.replace("\"metrics\":{}", "\"metrics\":\"garbage\"")
            },
            "logs-type" to { raw ->
                raw.replace("\"logs\":[]", "\"logs\":\"garbage\"")
            },
            "missing-executor-start" to { raw ->
                raw.replace(",\"executorStartedAt\":\"2026-01-01T00:00:00Z\"", "")
            },
            "invalid-executor-end" to { raw ->
                raw.replace(
                    "\"executorEndedAt\":\"2026-01-01T00:00:00Z\"",
                    "\"executorEndedAt\":\"not-an-instant\"",
                )
            },
            "spawn-exit-type" to { raw ->
                raw.replace("\"spawnExitCode\":0", "\"spawnExitCode\":\"zero\"")
            },
            "passed-nonzero-spawn-exit" to { raw ->
                raw.replace("\"spawnExitCode\":0", "\"spawnExitCode\":125")
            },
            "context-pointer" to { raw ->
                raw.replace(
                    "\"inputContextFile\":\"context/selected.input.json\"",
                    "\"inputContextFile\":\"context/other.input.json\"",
                )
            },
            "stdout-pointer" to { raw ->
                raw.replace(
                    "\"capturedStdoutLog\":\"node-logs/selected.stdout.log\"",
                    "\"capturedStdoutLog\":\"node-logs/other.stdout.log\"",
                )
            },
            "unknown-v1-extension" to { raw ->
                raw.dropLast(1) + ",\"unversionedExtension\":true}"
            },
        )

        for ((caseName, corrupt) in corruptions) {
            val runDir = Files.createTempDirectory("test-graph-envelope-$caseName").toFile()
            RunReportWriter.persistExecutionScope(runDir, graphName, listOf("selected"))
            writePublishedEnvelope(runDir, "selected", emptyMap(), traceId)
            writeInputContexts(runDir, listOf("selected"))
            writeTraceCarrier(runDir, traceId)
            val envelope = runDir.resolve("envelope/selected.json")
            val canonical = envelope.readText()
            val corrupted = corrupt(canonical)
            assertTrue(corrupted != canonical, "$caseName must mutate the fixture")
            envelope.writeText(corrupted)

            assertFailsWith<IllegalArgumentException>(caseName) {
                RunReportWriter.persistAttemptClosure(
                    runDir,
                    graphName,
                    listOf("selected"),
                    traceId,
                )
            }
            assertFalse(runDir.resolve("attempt-closure.json").exists())

            val outcome = RunReportWriter.writeRunReport(
                runDir,
                graphName = graphName,
                expectedNodeIds = listOf("selected"),
                expectedTraceId = traceId,
            )
            assertFalse(outcome.passed, "$caseName must not produce a green report")
            val execution = MiniJson.obj(
                MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))[
                    "execution"
                ]
            )
            assertEquals(
                listOf("selected.json"),
                MiniJson.stringList(execution["invalidEnvelopeFiles"]),
                caseName,
            )
        }
    }

    @Test
    fun executionScopeSymlinkIsRejectedInsteadOfFollowedOrReplaced() {
        val runDir = Files.createTempDirectory("test-graph-symlink-scope-run").toFile()
        val external = Files.createTempFile("test-graph-external-scope", ".json")
        Files.createSymbolicLink(runDir.resolve("execution-scope.json").toPath(), external)

        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.persistExecutionScope(runDir, graphName, listOf("only"))
        }
        assertTrue(Files.isSymbolicLink(runDir.resolve("execution-scope.json").toPath()))
    }

    @Test
    fun manualRunScanIsBoundedRejectsSymlinksAndIncludesScopeOnlyAttempts() {
        val root = Files.createTempDirectory("test-graph-manual-run-scan").toFile()
        val scopeOnly = root.resolve("scope-only").apply { mkdirs() }
        val traceId = "0123456789abcdef0123456789abcdef"
        RunReportWriter.persistExecutionScope(scopeOnly, graphName, listOf("missing"))
        writeTraceCarrier(scopeOnly, traceId)

        assertEquals(listOf(scopeOnly), scanReportRunDirectories(root))
        val outcome = RunReportWriter.writeRunReport(scopeOnly)
        assertFalse(outcome.passed)
        val execution = MiniJson.obj(
            MiniJson.obj(MiniJson.parse(scopeOnly.resolve("summary.json").readText()))["execution"]
        )
        assertEquals(listOf("missing"), MiniJson.stringList(execution["missingNodeIds"]))

        root.resolve("second").resolve("envelope").mkdirs()
        assertFailsWith<IllegalStateException> {
            scanReportRunDirectories(root, maxRuns = 1)
        }

        val outside = Files.createTempDirectory("test-graph-manual-run-scan-outside")
        Files.createSymbolicLink(root.resolve("linked-run").toPath(), outside)
        assertFailsWith<IllegalArgumentException> {
            scanReportRunDirectories(root)
        }

        val linkedRoot = root.parentFile.resolve("${root.name}-link")
        Files.createSymbolicLink(linkedRoot.toPath(), root.toPath())
        assertFailsWith<IllegalArgumentException> {
            scanReportRunDirectories(linkedRoot)
        }

        assertEquals(
            scopeOnly.toPath().toAbsolutePath().normalize().toFile(),
            selectReportRunDirectory(root, "scope-only"),
        )
        assertFailsWith<IllegalArgumentException> {
            selectReportRunDirectory(root, "../scope-only")
        }
        assertFailsWith<IllegalArgumentException> {
            selectReportRunDirectory(root, "linked-run")
        }
        assertFailsWith<IllegalArgumentException> {
            selectReportRunDirectory(root, "missing")
        }
    }

    @Test
    fun targetedManualReportTaskCanBeDecoratedAndTouchesOnlyTheSelectedRun() {
        val projectRoot = Files.createTempDirectory("test-graph-targeted-report-task").toFile()
        val project = ProjectBuilder.builder().withProjectDir(projectRoot).build()
        val reportRoot = projectRoot.resolve("reports").apply { mkdirs() }
        val selected = reportRoot.resolve("selected").apply { mkdirs() }
        val unrelated = reportRoot.resolve("unrelated").apply { mkdirs() }
        RunReportWriter.persistExecutionScope(selected, graphName, listOf("missing"))
        writeTraceCarrier(selected, "0123456789abcdef0123456789abcdef")
        unrelated.resolve("execution-scope.json").writeText("""{"version":-1}""")

        val task = project.tasks.register(
            "targetedValidationReport",
            ValidationReportTask::class.java,
        ).get()
        task.reportRoot.set(project.layout.dir(project.provider { reportRoot }))
        task.setReportRunId("selected")
        task.report()

        assertTrue(selected.resolve("summary.json").isFile)
        assertFalse(unrelated.resolve("summary.json").exists())
    }

    @Test
    fun derivedReportOutputsReplaceSymlinksWithoutTouchingTheirTargets() {
        val runDir = Files.createTempDirectory("test-graph-derived-output-symlink").toFile()
        writePassingEnvelopes(runDir, listOf("only"))
        val externalSummary = Files.createTempFile("test-graph-external-summary", ".json")
        val externalMarkdown = Files.createTempFile("test-graph-external-report", ".md")
        Files.writeString(externalSummary, "external summary")
        Files.writeString(externalMarkdown, "external report")
        Files.createSymbolicLink(runDir.resolve("summary.json").toPath(), externalSummary)
        Files.createSymbolicLink(runDir.resolve("report.md").toPath(), externalMarkdown)

        RunReportWriter.writeRunReport(runDir)

        assertEquals("external summary", Files.readString(externalSummary))
        assertEquals("external report", Files.readString(externalMarkdown))
        assertFalse(Files.isSymbolicLink(runDir.resolve("summary.json").toPath()))
        assertFalse(Files.isSymbolicLink(runDir.resolve("report.md").toPath()))
        assertTrue(Files.isRegularFile(runDir.resolve("summary.json").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(runDir.resolve("report.md").toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun failedMarkdownPublicationCannotLeaveACompletePassedSummary() {
        val runDir = Files.createTempDirectory("test-graph-derived-pair-failure").toFile()
        writePassingEnvelopes(runDir, listOf("only"))
        RunReportWriter.writeRunReport(runDir)
        assertContains(runDir.resolve("summary.json").readText(), "\"status\":\"passed\"")

        Files.delete(runDir.resolve("report.md").toPath())
        Files.createDirectory(runDir.resolve("report.md").toPath())
        Files.writeString(runDir.resolve("report.md/blocker").toPath(), "not replaceable")

        assertFailsWith<Exception> {
            RunReportWriter.writeRunReport(runDir)
        }

        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        assertEquals("errored", summary["status"])
        val execution = MiniJson.obj(summary["execution"])
        assertFalse(execution["complete"] as Boolean)
        assertFalse(execution["reportPublicationComplete"] as Boolean)
    }

    @Test
    fun envelopeDirectoryAndJsonEntriesMustNotBeSymlinks() {
        val externalEnvelope = Files.createTempFile("test-graph-external-envelope", ".json")
        Files.writeString(externalEnvelope, """{"nodeId":"only","status":"passed"}""")

        val linkedEntryRun = Files.createTempDirectory("test-graph-linked-envelope-entry").toFile()
        val linkedEntryDir = linkedEntryRun.resolve("envelope").apply { mkdirs() }
        Files.createSymbolicLink(linkedEntryDir.resolve("only.json").toPath(), externalEnvelope)
        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.writeRunReport(
                linkedEntryRun,
                graphName = graphName,
                expectedNodeIds = listOf("only"),
            )
        }

        val externalDirectory = Files.createTempDirectory("test-graph-external-envelope-dir")
        val linkedDirectoryRun = Files.createTempDirectory("test-graph-linked-envelope-dir").toFile()
        Files.createSymbolicLink(linkedDirectoryRun.resolve("envelope").toPath(), externalDirectory)
        assertFailsWith<IllegalArgumentException> {
            RunReportWriter.writeRunReport(
                linkedDirectoryRun,
                graphName = graphName,
                expectedNodeIds = listOf("only"),
            )
        }

        assertContains(Files.readString(externalEnvelope), "\"nodeId\":\"only\"")
    }

    @Test
    fun contextSnapshotIntegrityIsExactBoundedAndSymlinkSafe() {
        val runDir = Files.createTempDirectory("test-graph-context-integrity").toFile()
        val contextDir = runDir.resolve("context").apply { mkdirs() }
        contextDir.resolve("valid.input.json").writeText("""{"items":[]}""")
        contextDir.resolve("invalid.input.json").writeText(
            """{"items":[],"extra":true}"""
        )
        contextDir.resolve("item-extra.input.json").writeText(
            """{"items":[{"nodeId":"upstream","data":{},"extra":true}]}"""
        )
        contextDir.resolve("rogue.input.json").writeText("""{"items":[]}""")
        val external = Files.createTempFile("test-graph-linked-context", ".json")
        Files.writeString(external, """{"items":[]}""")
        Files.createSymbolicLink(contextDir.resolve("linked.input.json").toPath(), external)

        val integrity = RunReportWriter.inspectContextSnapshots(
            runDir,
            listOf("valid", "invalid", "item-extra", "linked", "missing"),
        )

        assertEquals(listOf("valid", "rogue"), integrity.observedNodeIds)
        assertEquals(
            listOf("invalid", "item-extra", "linked", "missing"),
            integrity.missingNodeIds,
        )
        assertEquals(
            listOf("invalid.input.json", "item-extra.input.json", "linked.input.json"),
            integrity.invalidFiles,
        )
        assertEquals(listOf("rogue"), integrity.unexpectedNodeIds)
        assertFalse(integrity.fileCountExceeded)
        assertFalse(integrity.aggregateBytesExceeded)

        val countBound = RunReportWriter.inspectContextSnapshots(
            runDir,
            emptyList(),
            maxFiles = 1,
        )
        assertTrue(countBound.fileCountExceeded)

        val aggregateBound = RunReportWriter.inspectContextSnapshots(
            runDir,
            listOf("valid"),
            maxAggregateBytes = 1,
        )
        assertTrue(aggregateBound.aggregateBytesExceeded)
        assertTrue(aggregateBound.observedNodeIds.isEmpty())
    }

    @Test
    fun completeReportRequiresEveryCanonicalInputContextSnapshot() {
        val runDir = Files.createTempDirectory("test-graph-missing-context-report").toFile()
        writePublishedEnvelope(runDir, "only", emptyMap())

        val outcome = RunReportWriter.writeRunReport(
            runDir,
            graphName = graphName,
            expectedNodeIds = listOf("only"),
        )

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals(listOf("only"), MiniJson.stringList(execution["missingContextNodeIds"]))
        assertEquals(false, execution["complete"])
        assertContains(runDir.resolve("report.md").readText(), "Missing input-context snapshots")
    }

    @Test
    fun completeReportRequiresExactCanonicalPredecessorContextAndPublishedData() {
        val runDir = Files.createTempDirectory("test-graph-exact-context-report").toFile()
        val rootData = linkedMapOf("root" to "ready")
        val middleData = linkedMapOf("middle" to "ready")
        writePublishedEnvelope(runDir, "root", rootData)
        writePublishedEnvelope(runDir, "middle", middleData)
        writePublishedEnvelope(runDir, "leaf", emptyMap())
        writeInputContextSnapshot(emptyList(), runDir, "root")
        writeInputContextSnapshot(listOf(ContextItem("root", rootData)), runDir, "middle")
        writeInputContextSnapshot(
            listOf(ContextItem("root", rootData), ContextItem("middle", middleData)),
            runDir,
            "leaf",
        )
        val expected = listOf("root", "middle", "leaf")
        val traceId = "0123456789abcdef0123456789abcdef"
        RunReportWriter.persistExecutionScope(runDir, graphName, expected)
        writeTraceCarrier(runDir, traceId)
        RunReportWriter.persistAttemptClosure(runDir, graphName, expected, traceId)

        val outcome = RunReportWriter.writeRunReport(
            runDir,
            graphName = graphName,
            expectedNodeIds = expected,
        )

        assertTrue(outcome.passed)
        val execution = MiniJson.obj(
            MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))["execution"]
        )
        assertTrue(MiniJson.stringList(execution["contextProvenanceViolationNodeIds"]).isEmpty())
        assertTrue(MiniJson.stringList(execution["replaySourceContextMismatchNodeIds"]).isEmpty())
    }

    @Test
    fun extraStaleReorderedAndTamperedContextEvidenceFailsProvenance() {
        val rootData = linkedMapOf("root" to "ready")
        val middleData = linkedMapOf("middle" to "ready")
        val cases = linkedMapOf(
            "extra" to Pair(
                "middle",
                listOf(ContextItem("root", rootData), ContextItem("rogue", emptyMap())),
            ),
            "stale" to Pair("leaf", listOf(ContextItem("root", rootData))),
            "reordered" to Pair(
                "leaf",
                listOf(ContextItem("middle", middleData), ContextItem("root", rootData)),
            ),
            "tampered" to Pair(
                "leaf",
                listOf(
                    ContextItem("root", mapOf("root" to "tampered")),
                    ContextItem("middle", middleData),
                ),
            ),
        )

        for ((caseName, mutation) in cases) {
            val runDir = Files.createTempDirectory("test-graph-$caseName-context").toFile()
            writePublishedEnvelope(runDir, "root", rootData)
            writePublishedEnvelope(runDir, "middle", middleData)
            writePublishedEnvelope(runDir, "leaf", emptyMap())
            val contexts = linkedMapOf(
                "root" to emptyList(),
                "middle" to listOf(ContextItem("root", rootData)),
                "leaf" to listOf(
                    ContextItem("root", rootData),
                    ContextItem("middle", middleData),
                ),
            )
            contexts[mutation.first] = mutation.second
            contexts.forEach { (nodeId, items) ->
                writeInputContextSnapshot(items, runDir, nodeId)
            }

            val outcome = RunReportWriter.writeRunReport(
                runDir,
                graphName = graphName,
                expectedNodeIds = listOf("root", "middle", "leaf"),
            )

            assertFalse(outcome.passed, "$caseName context evidence must fail closed")
            val execution = MiniJson.obj(
                MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))["execution"]
            )
            assertContains(
                MiniJson.stringList(execution["contextProvenanceViolationNodeIds"]),
                mutation.first,
            )
            assertContains(
                runDir.resolve("report.md").readText(),
                "Input-context provenance violations",
            )
        }
    }

    @Test
    fun replaySelectedContextMustBeByteAndSemanticallyEqualToClosedSource() {
        val source = Files.createTempDirectory("test-graph-replay-context-source").toFile()
        val traceId = "0123456789abcdef0123456789abcdef"
        val sourceItems = listOf(ContextItem("before", mapOf("value" to "ready")))
        RunReportWriter.persistExecutionScope(
            source,
            graphName,
            listOf("before", "selected"),
        )
        writeInputContextSnapshot(emptyList(), source, "before")
        writeInputContextSnapshot(sourceItems, source, "selected")
        writePublishedEnvelope(source, "before", mapOf("value" to "ready"), traceId)
        writePublishedEnvelope(source, "selected", emptyMap(), traceId)
        closeAttempt(source, listOf("before", "selected"), traceId)

        val target = Files.createTempDirectory("test-graph-replay-context-target").toFile()
        writePublishedEnvelope(target, "selected", emptyMap(), traceId)
        val contextDir = target.resolve("context").apply { mkdirs() }
        contextDir.resolve("selected.input.json").writeText(
            ContextSerde.toJson(sourceItems) + "\n"
        )
        writeTraceCarrier(target, traceId)
        val replay = replayMetadata(
            RunReportWriter.ReplayMode.RUN_ONLY_NODE,
            "selected",
            source,
        )
        RunReportWriter.persistExecutionScope(
            target,
            graphName,
            listOf("selected"),
            replay,
        )

        val outcome = RunReportWriter.writeRunReport(
            target,
            graphName = graphName,
            expectedNodeIds = listOf("selected"),
            expectedTraceId = traceId,
            replay = replay,
        )

        assertFalse(outcome.passed)
        val execution = MiniJson.obj(
            MiniJson.obj(MiniJson.parse(target.resolve("summary.json").readText()))["execution"]
        )
        assertEquals(
            listOf("selected"),
            MiniJson.stringList(execution["replaySourceContextMismatchNodeIds"]),
        )
        assertTrue(MiniJson.stringList(execution["contextProvenanceViolationNodeIds"]).isEmpty())
    }

    @Test
    fun replayInputContextSnapshotRejectsSymlinkFiles() {
        val source = Files.createTempDirectory("test-graph-linked-input-source").toFile()
        val contextDir = source.resolve("context").apply { mkdirs() }
        val external = Files.createTempFile("test-graph-linked-input", ".json")
        Files.writeString(external, """{"items":[]}""")
        Files.createSymbolicLink(contextDir.resolve("selected.input.json").toPath(), external)

        assertFailsWith<IllegalArgumentException> {
            readInputContextSnapshot(source, "selected")
        }
        assertEquals("""{"items":[]}""", Files.readString(external))
    }

    @Test
    fun envelopeFilenameMustMatchItsEmbeddedNodeIdentity() {
        val runDir = Files.createTempDirectory("test-graph-envelope-identity").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        envelopeDir.resolve("expected.json").writeText(
            """{"nodeId":"impersonator","status":"passed","metrics":{},"published":{}}"""
        )

        val reportOutcome = RunReportWriter.writeRunReport(
            runDir = runDir,
            graphName = graphName,
            expectedNodeIds = listOf("expected"),
        )

        assertFalse(reportOutcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals(listOf("expected.json"), MiniJson.stringList(execution["invalidEnvelopeFiles"]))
        assertEquals(listOf("expected"), MiniJson.stringList(execution["missingNodeIds"]))
        assertTrue(MiniJson.list(summary["nodes"]).isEmpty())
    }

    @Test
    fun unsafeEmbeddedNodeIdentityIsInvalidEvidence() {
        val runDir = Files.createTempDirectory("test-graph-unsafe-envelope-id").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        envelopeDir.resolve("unsafe.json").writeText(
            """{"nodeId":"a:b","status":"passed","metrics":{},"published":{}}"""
        )

        val outcome = RunReportWriter.writeRunReport(
            runDir = runDir,
            graphName = graphName,
            expectedNodeIds = listOf("unsafe"),
        )

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals(listOf("unsafe.json"), MiniJson.stringList(execution["invalidEnvelopeFiles"]))
        assertEquals(listOf("unsafe"), MiniJson.stringList(execution["missingNodeIds"]))
    }

    @Test
    fun activeRunRejectsMissingAndMismatchedEnvelopeTraceIds() {
        val runDir = Files.createTempDirectory("test-graph-envelope-traces").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        val expectedTraceId = "0123456789abcdef0123456789abcdef"
        envelopeDir.resolve("correct.json").writeText(
            """{"nodeId":"correct","status":"passed","traceId":"$expectedTraceId"}"""
        )
        envelopeDir.resolve("missing.json").writeText(
            """{"nodeId":"missing","status":"passed"}"""
        )
        envelopeDir.resolve("mismatch.json").writeText(
            """{"nodeId":"mismatch","status":"passed","traceId":"fedcba9876543210fedcba9876543210"}"""
        )
        envelopeDir.resolve("numeric.json").writeText(
            """{"nodeId":"numeric","status":"passed","traceId":7}"""
        )
        envelopeDir.resolve("zero.json").writeText(
            """{"nodeId":"zero","status":"passed","traceId":"00000000000000000000000000000000"}"""
        )

        val outcome = RunReportWriter.writeRunReport(
            runDir = runDir,
            graphName = graphName,
            expectedNodeIds = listOf("correct", "missing", "mismatch", "numeric", "zero"),
            expectedTraceId = expectedTraceId,
        )

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals(expectedTraceId, summary["traceId"])
        assertEquals(listOf("missing"), MiniJson.stringList(execution["missingTraceNodeIds"]))
        assertEquals(
            listOf("numeric", "zero"),
            MiniJson.stringList(execution["invalidTraceNodeIds"]),
        )
        assertEquals(listOf("mismatch"), MiniJson.stringList(execution["mismatchedTraceNodeIds"]))
        val markdown = runDir.resolve("report.md").readText()
        assertContains(markdown, "**Missing node trace IDs**: `missing`")
        assertContains(markdown, "**Invalid node trace IDs**: `numeric`")
        assertContains(markdown, "**Mismatched node trace IDs**: `mismatch`")
    }

    @Test
    fun manualReportRejectsAllSameInvalidTraceIdsWithoutPublishingOne() {
        val runDir = Files.createTempDirectory("test-graph-manual-invalid-traces").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        for (nodeId in listOf("one", "two")) {
            envelopeDir.resolve("$nodeId.json").writeText(
                """{"nodeId":"$nodeId","status":"passed","traceId":"foo"}"""
            )
        }

        val outcome = RunReportWriter.writeRunReport(runDir)

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertFalse(summary.containsKey("traceId"))
        assertEquals(listOf("one", "two"), MiniJson.stringList(execution["invalidTraceNodeIds"]))
    }

    @Test
    fun manualReportTreatsPresentBlankTraceIdsAsMissingNotLegacy() {
        val runDir = Files.createTempDirectory("test-graph-manual-blank-traces").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        envelopeDir.resolve("blank.json").writeText(
            """{"nodeId":"blank","status":"passed","traceId":""}"""
        )
        envelopeDir.resolve("whitespace.json").writeText(
            """{"nodeId":"whitespace","status":"passed","traceId":"  "}"""
        )

        val outcome = RunReportWriter.writeRunReport(runDir)

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertFalse(summary.containsKey("traceId"))
        assertEquals(
            listOf("blank", "whitespace"),
            MiniJson.stringList(execution["missingTraceNodeIds"]),
        )
    }

    @Test
    fun manualReportDerivesOnlyTheValidTraceWhenInvalidAndValidAreMixed() {
        val runDir = Files.createTempDirectory("test-graph-manual-valid-invalid-traces").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        val validTraceId = "0123456789abcdef0123456789abcdef"
        envelopeDir.resolve("invalid.json").writeText(
            """{"nodeId":"invalid","status":"passed","traceId":"foo"}"""
        )
        envelopeDir.resolve("valid.json").writeText(
            """{"nodeId":"valid","status":"passed","traceId":"$validTraceId"}"""
        )

        val outcome = RunReportWriter.writeRunReport(runDir)

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals(validTraceId, summary["traceId"])
        assertEquals(listOf("invalid"), MiniJson.stringList(execution["invalidTraceNodeIds"]))
        assertTrue(MiniJson.stringList(execution["mismatchedTraceNodeIds"]).isEmpty())
    }

    @Test
    fun manualReportRejectsMixedTracesOnceAnyEnvelopeIsTraced() {
        val runDir = Files.createTempDirectory("test-graph-manual-mixed-traces").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        val firstTraceId = "0123456789abcdef0123456789abcdef"
        envelopeDir.resolve("first.json").writeText(
            """{"nodeId":"first","status":"passed","traceId":"$firstTraceId"}"""
        )
        envelopeDir.resolve("legacy.json").writeText(
            """{"nodeId":"legacy","status":"passed"}"""
        )
        envelopeDir.resolve("other.json").writeText(
            """{"nodeId":"other","status":"passed","traceId":"fedcba9876543210fedcba9876543210"}"""
        )

        val outcome = RunReportWriter.writeRunReport(runDir)

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals(firstTraceId, summary["traceId"])
        assertEquals(listOf("legacy"), MiniJson.stringList(execution["missingTraceNodeIds"]))
        assertEquals(listOf("other"), MiniJson.stringList(execution["mismatchedTraceNodeIds"]))
    }

    @Test
    fun terminalDecisionUsesReportIntegrityForTaskAndTelemetry() {
        val passedDir = Files.createTempDirectory("test-graph-passed-outcome").toFile()
        writePassingEnvelopes(passedDir, listOf("only"))
        RunReportWriter.persistExecutionScope(passedDir, graphName, listOf("only"))
        closeAttempt(passedDir, listOf("only"))
        val passedReport = RunReportWriter.writeRunReport(
            runDir = passedDir,
            graphName = graphName,
            expectedNodeIds = listOf("only"),
        )

        val passed = resolveRunTerminalOutcome(null, passedReport, null)
        assertNull(passed.failure)
        assertEquals("passed", passed.observabilityStatus)

        val incompleteDir = Files.createTempDirectory("test-graph-failed-outcome").toFile()
        incompleteDir.resolve("envelope").mkdirs()
        val incompleteReport = RunReportWriter.writeRunReport(
            runDir = incompleteDir,
            graphName = graphName,
            expectedNodeIds = listOf("missing"),
        )
        val failed = resolveRunTerminalOutcome(null, incompleteReport, null)
        assertTrue(failed.failure is IllegalStateException)
        assertEquals("failed", failed.observabilityStatus)

        requireExecutablePlanSize(RunReportWriter.MAX_ENVELOPE_FILES)
        assertFailsWith<IllegalArgumentException> { requireExecutablePlanSize(0) }
        assertFailsWith<IllegalArgumentException> {
            requireExecutablePlanSize(RunReportWriter.MAX_ENVELOPE_FILES + 1)
        }
    }

    @Test
    fun terminalDecisionPreservesExecutionFailureAndAttachesReportWriteFailure() {
        val executionFailure = StackOverflowError("original execution failure")
        val reportWriteFailure = IllegalStateException("report write failure")

        val terminal = resolveRunTerminalOutcome(
            executionFailure = executionFailure,
            reportOutcome = null,
            reportWriteFailure = reportWriteFailure,
        )

        assertSame(executionFailure, terminal.failure)
        assertEquals(listOf(reportWriteFailure), executionFailure.suppressed.toList())
        assertEquals("failed", terminal.observabilityStatus)

        val reportOnly = resolveRunTerminalOutcome(
            executionFailure = null,
            reportOutcome = null,
            reportWriteFailure = reportWriteFailure,
        )
        assertSame(reportWriteFailure, reportOnly.failure)
        assertEquals("failed", reportOnly.observabilityStatus)
    }

    @Test
    fun unexpectedNodesAndUnknownStatusesAreIntegrityErrors() {
        val runDir = Files.createTempDirectory("test-graph-unexpected-report").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        envelopeDir.resolve("expected.json").writeText(
            """{"nodeId":"expected","status":"passed","metrics":{},"published":{}}"""
        )
        envelopeDir.resolve("rogue.json").writeText(
            """{"nodeId":"rogue","status":"mystery","metrics":{},"published":{}}"""
        )

        RunReportWriter.writeRunReport(
            runDir,
            graphName = graphName,
            expectedNodeIds = listOf("expected", "planned-but-missing"),
        )

        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals(listOf("rogue"), MiniJson.stringList(execution["unexpectedNodeIds"]))
        assertEquals(listOf("rogue"), MiniJson.stringList(execution["unknownStatusNodeIds"]))
    }

    @Test
    fun overDeepAndOversizedEnvelopeFilesFailClosedAsInvalidEvidence() {
        val runDir = Files.createTempDirectory("test-graph-bounded-report").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        envelopeDir.resolve("deep.json").writeText(buildString {
            append("{\"nodeId\":\"deep\",\"status\":\"passed\",\"nested\":")
            repeat(CONTEXT_JSON_MAX_DEPTH + 1) { append('[') }
            append('0')
            repeat(CONTEXT_JSON_MAX_DEPTH + 1) { append(']') }
            append('}')
        })
        RandomAccessFile(envelopeDir.resolve("huge.json"), "rw").use {
            it.setLength(CONTEXT_JSON_MAX_UTF8_BYTES.toLong() + 1)
        }

        RunReportWriter.writeRunReport(
            runDir,
            graphName = graphName,
            expectedNodeIds = listOf("deep", "huge"),
        )

        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals(
            listOf("deep.json", "huge.json"),
            MiniJson.stringList(execution["invalidEnvelopeFiles"]),
        )
        assertContains(runDir.resolve("report.md").readText(), "**Invalid envelope files**")
    }

    @Test
    fun emptyEnvelopeDirectoryNeverRendersPassed() {
        val runDir = Files.createTempDirectory("test-graph-empty-report").toFile()
        runDir.resolve("envelope").mkdirs()

        RunReportWriter.writeRunReport(runDir)

        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        assertEquals("errored", summary["status"])
        assertContains(runDir.resolve("report.md").readText(), "no node envelopes were produced")
    }

    @Test
    fun aggregateEnvelopeBytesAreBoundedBeforeAnyFileIsRead() {
        val runDir = Files.createTempDirectory("test-graph-aggregate-report").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        repeat(5) { index ->
            RandomAccessFile(envelopeDir.resolve("sparse-$index.json"), "rw").use {
                it.setLength(16L * 1024 * 1024)
            }
        }

        RunReportWriter.writeRunReport(
            runDir,
            graphName = graphName,
            expectedNodeIds = List(5) { index -> "sparse-$index" },
        )

        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals(true, execution["aggregateEnvelopeBytesExceeded"])
        assertEquals(0, MiniJson.list(summary["nodes"]).size)
        assertContains(runDir.resolve("report.md").readText(), "envelope parsing was skipped")
    }

    @Test
    fun aggregateJsonInventoryRejectsSmallHighCardinalityEvidenceDeterministically() {
        val runDir = Files.createTempDirectory("test-graph-aggregate-structure").toFile()
        val traceId = "0123456789abcdef0123456789abcdef"
        RunReportWriter.persistExecutionScope(runDir, graphName, listOf("only"))
        writePassingEnvelopes(runDir, listOf("only"), traceId)
        closeAttempt(runDir, listOf("only"), traceId)

        val outcome = RunReportWriter.writeRunReport(
            runDir = runDir,
            maxAggregateStructuralTokens = 10,
        )

        assertEquals("errored", outcome.status)
        assertFalse(outcome.complete)
        val execution = MiniJson.obj(
            MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))["execution"]
        )
        assertEquals(true, execution["aggregateJsonStructureExceeded"])
        assertContains(runDir.resolve("report.md").readText(), "Aggregate JSON structure")
    }

    @Test
    fun expectedPlanBoundsEnvelopeDirectoryEnumerationAtTheNextJsonFile() {
        val runDir = Files.createTempDirectory("test-graph-envelope-count").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        envelopeDir.resolve("expected.json").writeText(
            """{"nodeId":"expected","status":"passed","metrics":{},"published":{}}"""
        )
        envelopeDir.resolve("excess.json").writeText(
            """{"nodeId":"excess","status":"passed","metrics":{},"published":{}}"""
        )

        val outcome = RunReportWriter.writeRunReport(
            runDir = runDir,
            graphName = graphName,
            expectedNodeIds = listOf("expected"),
        )

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals(true, execution["envelopeFileCountExceeded"])
        assertTrue(MiniJson.list(summary["nodes"]).size <= 1)
        assertContains(runDir.resolve("report.md").readText(), "**Envelope file count**")
    }

    @Test
    fun unknownPlanDirectoryScanHasAnExplicitBoundedSeam() {
        val envelopeDir = Files.createTempDirectory("test-graph-manual-envelope-count").toFile()
        repeat(3) { index ->
            envelopeDir.resolve("node-$index.json").writeText("{}")
        }

        val scan = RunReportWriter.scanEnvelopeFiles(envelopeDir, maxFiles = 2)

        assertEquals(2, scan.files.size)
        assertTrue(scan.countExceeded)
        assertEquals(
            RunReportWriter.MAX_ENVELOPE_FILES,
            RunReportWriter.envelopeFileLimit(RunReportWriter.MAX_ENVELOPE_FILES + 1),
        )
    }

    @Test
    fun oversizedExpectedPlanIsRejectedBeforeAnyElementIsTraversed() {
        val oversizedPlan = object : AbstractList<String>() {
            override val size: Int = RunReportWriter.MAX_ENVELOPE_FILES + 1
            override fun get(index: Int): String =
                error("oversized expected plan must not be traversed")
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            RunReportWriter.writeRunReport(
                runDir = Files.createTempDirectory("test-graph-oversized-plan").toFile(),
                graphName = graphName,
                expectedNodeIds = oversizedPlan,
            )
        }

        assertContains(failure.message.orEmpty(), "absolute report limit")
    }

    @Test
    fun regeneratedReportWithSkippedEvidenceNeverRendersPassed() {
        val runDir = Files.createTempDirectory("test-graph-skipped-report").toFile()
        writePublishedEnvelope(
            runDir,
            "skipped",
            emptyMap(),
            status = "skipped",
        )

        RunReportWriter.writeRunReport(runDir)

        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        assertEquals("errored", summary["status"])
        assertContains(runDir.resolve("report.md").readText(), "**Overall**: ERRORED")
    }

    private fun writePublishedEnvelope(
        runDir: java.io.File,
        nodeId: String,
        published: Map<String, String>,
        traceId: String? = null,
        status: String = "passed",
    ) {
        val publishedJson = published.entries.joinToString(",") { (key, value) ->
            "\"$key\":\"$value\""
        }
        val effectiveTraceId = traceId ?: "0123456789abcdef0123456789abcdef"
        runDir.resolve("envelope").apply { mkdirs() }
            .resolve("$nodeId.json").writeText(
                buildString {
                    append("{\"envelopeVersion\":1,\"nodeId\":\"").append(nodeId)
                    append("\",\"traceId\":\"").append(effectiveTraceId)
                    append("\",\"status\":\"").append(status)
                    append("\",\"startedAt\":\"2026-01-01T00:00:00Z\"")
                    append(",\"endedAt\":\"2026-01-01T00:00:00Z\"")
                    append(",\"executorStartedAt\":\"2026-01-01T00:00:00Z\"")
                    append(",\"executorEndedAt\":\"2026-01-01T00:00:00Z\"")
                    append(",\"spawnExitCode\":0")
                    append(",\"capturedStdoutLog\":\"node-logs/").append(nodeId)
                    append(".stdout.log\"")
                    append(",\"inputContextFile\":\"context/").append(nodeId)
                    append(".input.json\"")
                    append(",\"assertions\":[],\"artifacts\":[],\"processes\":[]")
                    append(",\"metrics\":{},\"logs\":[],\"published\":{")
                    append(publishedJson)
                    append("}}")
                }
            )
    }

    private fun writePassingEnvelopes(
        runDir: java.io.File,
        nodeIds: List<String>,
        traceId: String? = null,
    ) {
        for (nodeId in nodeIds) {
            writePublishedEnvelope(runDir, nodeId, emptyMap(), traceId)
        }
        writeInputContexts(runDir, nodeIds)
    }

    private fun writeInputContexts(runDir: java.io.File, nodeIds: List<String>) {
        val prior = mutableListOf<ContextItem>()
        for (nodeId in nodeIds) {
            writeInputContextSnapshot(prior.toList(), runDir, nodeId)
            prior += ContextItem(nodeId, emptyMap())
        }
    }

    private fun writeTraceCarrier(runDir: java.io.File, traceId: String) {
        runDir.resolve("trace-context.json").writeText(
            """{"traceparent":"00-$traceId-0123456789abcdef-01"}"""
        )
    }

    private fun replayMetadata(
        mode: RunReportWriter.ReplayMode,
        selectedNodeId: String,
        sourceBuild: java.io.File,
    ): RunReportWriter.ReplayMetadata {
        val snapshot = RunReportWriter.requireReplaySource(
            sourceBuild,
            graphName,
            selectedNodeId,
        )
        return RunReportWriter.ReplayMetadata(
            mode = mode,
            selectedNodeId = selectedNodeId,
            sourceBuild = sourceBuild,
            sourceClosureSha256 = snapshot.closureSha256,
            sourceContextSha256 = snapshot.selectedContextSha256,
        )
    }

    private fun unverifiedReplayMetadata(
        mode: RunReportWriter.ReplayMode,
        selectedNodeId: String,
        sourceBuild: java.io.File,
    ): RunReportWriter.ReplayMetadata = RunReportWriter.ReplayMetadata(
        mode = mode,
        selectedNodeId = selectedNodeId,
        sourceBuild = sourceBuild,
        sourceClosureSha256 = "0".repeat(64),
        sourceContextSha256 = "1".repeat(64),
    )

    private fun closeAttempt(
        runDir: java.io.File,
        expectedNodeIds: List<String>,
        traceId: String = "0123456789abcdef0123456789abcdef",
    ) {
        writeTraceCarrier(runDir, traceId)
        RunReportWriter.persistAttemptClosure(
            runDir = runDir,
            graphName = graphName,
            expectedNodeIds = expectedNodeIds,
            traceId = traceId,
        )
    }
}
