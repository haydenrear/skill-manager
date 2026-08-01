package com.hayden.testgraphsdk.exec

import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ExecutorsProcessTreeTest {

    @Test
    fun timeoutReapsObservedDescendantTree() {
        val pidFile = Files.createTempFile("test-graph-timeout-child", ".pid")
        Files.deleteIfExists(pidFile)
        val managedCommand = PosixProcessGroupController.wrap(listOf(
            "/bin/sh",
            "-c",
            "sleep 30 & child=\$!; echo \$child > '${pidFile}'; wait \$child",
        ))
        val process = ProcessBuilder(managedCommand.arguments).start()

        try {
            val outcome = awaitWithTimeout(process, 250, managedCommand)
            assertIs<ExecutionOutcome.TimedOut>(outcome)
            val child = waitForChildPid(pidFile)
            assertEventuallyDead(child)
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            Files.deleteIfExists(pidFile)
        }
    }

    @Test
    fun fastSuccessfulLauncherCannotLeaveClosedPipeDescendant() {
        val pidFile = Files.createTempFile("test-graph-leaked-child", ".pid")
        Files.deleteIfExists(pidFile)
        val managedCommand = PosixProcessGroupController.wrap(listOf(
            "/bin/sh",
            "-c",
            "sleep 30 </dev/null >/dev/null 2>&1 & child=\$!; " +
                    "echo \$child > '${pidFile}'; exit 0",
        ))
        val process = ProcessBuilder(managedCommand.arguments).start()

        try {
            val outcome = assertIs<ExecutionOutcome.ProcessContractViolation>(
                awaitWithTimeout(process, 5_000, managedCommand)
            )
            assertEquals(
                PosixProcessGroupController.ORPHANED_GROUP_REAPED_EXIT_CODE,
                outcome.exitCode,
            )
            kotlin.test.assertTrue(outcome.reason.contains("surviving members"))
            val child = waitForChildPid(pidFile)
            assertEventuallyDead(child)
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            Files.deleteIfExists(pidFile)
        }
    }

    @Test
    fun successfulLauncherAllowsBoundedWorkerPoolQuiescence() {
        val pidFile = Files.createTempFile("test-graph-quiescing-child", ".pid")
        Files.deleteIfExists(pidFile)
        val managedCommand = PosixProcessGroupController.wrap(listOf(
            "/bin/sh",
            "-c",
            "sleep 0.2 </dev/null >/dev/null 2>&1 & child=\$!; " +
                    "echo \$child > '${pidFile}'; exit 0",
        ))
        val process = ProcessBuilder(managedCommand.arguments).start()

        try {
            assertEquals(
                ExecutionOutcome.Completed(0),
                awaitWithTimeout(process, 2_000, managedCommand),
            )
            val child = waitForChildPid(pidFile)
            assertEventuallyDead(child)
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            Files.deleteIfExists(pidFile)
        }
    }

    @Test
    fun genuineChildExitCodesDoNotCollideWithSupervisorStatuses() {
        for (exitCode in listOf(
            PosixProcessGroupController.PROCESS_GROUP_CLEANUP_FAILED_EXIT_CODE,
            PosixProcessGroupController.ORPHANED_GROUP_REAPED_EXIT_CODE,
        )) {
            val managedCommand = PosixProcessGroupController.wrap(
                listOf("/bin/sh", "-c", "exit $exitCode")
            )
            val process = ProcessBuilder(managedCommand.arguments).start()
            try {
                assertEquals(
                    ExecutionOutcome.Completed(exitCode),
                    awaitWithTimeout(process, 2_000, managedCommand),
                )
            } finally {
                process.destroyForcibly()
            }
        }
    }

    @Test
    fun timeoutRequiresATerminalSupervisorCleanupVerdict() {
        val statusFile = Files.createTempFile("test-graph-pending-supervisor", ".status")
        Files.writeString(statusFile, "pending\n")
        val managedCommand = PosixProcessGroupController.ManagedCommand(
            arguments = emptyList(),
            statusFile = statusFile,
        )
        val process = ProcessBuilder("/bin/sh", "-c", "sleep 30").start()

        try {
            val failure = assertFailsWith<ProcessOwnershipUncertainException> {
                awaitWithTimeout(process, 100, managedCommand)
            }
            kotlin.test.assertTrue(
                failure.message.orEmpty().contains("terminal status could not be proven")
            )
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    @Test
    fun supervisorCleanupFailureAndExitMismatchWithholdCompletion() {
        val cases = listOf(
            "process_group_cleanup_failed\n" to
                    PosixProcessGroupController.PROCESS_GROUP_CLEANUP_FAILED_EXIT_CODE,
            "child_exit:0\n" to 1,
        )
        for ((reportedStatus, processExit) in cases) {
            val statusFile = Files.createTempFile("test-graph-invalid-supervisor", ".status")
            Files.writeString(statusFile, reportedStatus)
            val managedCommand = PosixProcessGroupController.ManagedCommand(
                arguments = emptyList(),
                statusFile = statusFile,
            )
            val process = ProcessBuilder("/bin/sh", "-c", "exit $processExit").start()
            try {
                assertFailsWith<ProcessOwnershipUncertainException>(reportedStatus.trim()) {
                    awaitWithTimeout(process, 2_000, managedCommand)
                }
            } finally {
                process.destroyForcibly()
            }
        }
    }

    @Test
    fun descendantInventoryOverflowWithholdsCompletionEvenAfterBestEffortCleanup() {
        val process = ProcessBuilder(
            "/bin/sh",
            "-c",
            "sleep 30 & sleep 30 & wait",
        ).start()

        try {
            val failure = assertFailsWith<ProcessOwnershipUncertainException> {
                awaitWithTimeout(
                    process = process,
                    timeoutMillis = 2_000,
                    maxTrackedDescendants = 1,
                )
            }
            kotlin.test.assertTrue(failure.message.orEmpty().contains("bounded descendant limit"))
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    @Test
    fun transientInventoryEnomemFallsBackToSupervisorCleanup() {
        val managedCommand = PosixProcessGroupController.wrap(
            listOf("/bin/sh", "-c", "sleep 30")
        )
        val process = ProcessBuilder(managedCommand.arguments).start()

        try {
            val outcome = awaitWithTimeout(
                process = process,
                timeoutMillis = 250,
                managedCommand = managedCommand,
                visitDescendants = { _, _ ->
                    throw RuntimeException("Cannot allocate memory")
                },
            )
            assertIs<ExecutionOutcome.TimedOut>(outcome)
            assertFalse(process.isAlive)
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    private fun waitForChildPid(pidFile: java.nio.file.Path): Long {
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (!Files.isRegularFile(pidFile)) {
            check(System.nanoTime() < deadline) { "child pid file was not created" }
            Thread.sleep(10)
        }
        return Files.readString(pidFile).trim().toLong()
    }

    private fun assertEventuallyDead(pid: Long) {
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (isAlive(pid) && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(isAlive(pid), "descendant $pid remained alive")
    }

    private fun isAlive(pid: Long): Boolean =
        ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
}
