package com.hayden.testgraphsdk.exec

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Wrap an untrusted node command in a small POSIX supervisor.
 *
 * The supervisor forks the real command into a fresh session/process group and
 * remains alive until that group's leader exits. It then checks the group even
 * after the leader has been reaped, so a child that forks and whose launcher
 * exits before Java's ProcessHandle poll cannot escape. TERM/INT/HUP to the
 * supervisor are forwarded to the whole group, followed by bounded KILL.
 *
 * Perl is used only as the already-installed POSIX syscall facade; no modules
 * outside its core POSIX and Time::HiRes distributions are required. The
 * supported toolchain is macOS/Linux. A descendant that deliberately creates
 * another session is outside the process-group guarantee and remains covered
 * only when ProcessHandle observes it; a cgroup/job-object supervisor is
 * required for a stronger hostile-process guarantee.
 */
internal object PosixProcessGroupController {
    const val ORPHANED_GROUP_REAPED_EXIT_CODE = 125
    const val PROCESS_GROUP_CLEANUP_FAILED_EXIT_CODE = 124
    private const val MAX_ARGUMENTS = 4_096
    private const val MAX_ARGUMENT_UTF8_BYTES = 1024 * 1024
    private const val EXIT_QUIESCENCE_POLLS = 200

    internal sealed interface TerminalStatus {
        data class ChildExit(val exitCode: Int) : TerminalStatus
        data class SupervisorSignal(val exitCode: Int) : TerminalStatus
        object OrphanedGroupReaped : TerminalStatus
        object ProcessGroupCleanupFailed : TerminalStatus
    }

    internal class ManagedCommand(
        val arguments: List<String>,
        private val statusFile: Path,
    ) {
        init {
            statusFile.toFile().deleteOnExit()
        }

        internal fun terminalStatus(): TerminalStatus {
            require(Files.isRegularFile(statusFile, LinkOption.NOFOLLOW_LINKS)) {
                "managed process supervisor status is missing or not a regular file"
            }
            val encoded = Files.newInputStream(statusFile).use {
                it.readNBytes(MAX_STATUS_UTF8_BYTES + 1)
            }
            require(encoded.size <= MAX_STATUS_UTF8_BYTES) {
                "managed process supervisor status exceeds $MAX_STATUS_UTF8_BYTES bytes"
            }
            val status = encoded.toString(Charsets.UTF_8).trimEnd('\n')
            return when {
                status.startsWith("child_exit:") -> {
                    val exitCode = status.removePrefix("child_exit:").toIntOrNull()
                    require(exitCode != null && exitCode in 0..255) {
                        "managed process supervisor reported an invalid child exit status"
                    }
                    TerminalStatus.ChildExit(exitCode)
                }
                status.startsWith("supervisor_signal:") -> {
                    val exitCode = status.removePrefix("supervisor_signal:").toIntOrNull()
                    require(exitCode != null && exitCode in 0..255) {
                        "managed process supervisor reported an invalid signal exit status"
                    }
                    TerminalStatus.SupervisorSignal(exitCode)
                }
                status == "orphaned_group_reaped" -> TerminalStatus.OrphanedGroupReaped
                status == "process_group_cleanup_failed" ->
                    TerminalStatus.ProcessGroupCleanupFailed
                else -> throw IllegalArgumentException(
                    "managed process supervisor did not publish a terminal status"
                )
            }
        }

        internal fun discardStatus() {
            try {
                Files.deleteIfExists(statusFile)
            } catch (_: Exception) {
                // The bounded status file is delete-on-exit as a final fallback.
            }
        }
    }

    fun wrap(command: List<String>): ManagedCommand {
        require(command.isNotEmpty()) { "managed process command must not be empty" }
        require(command.size <= MAX_ARGUMENTS) {
            "managed process command exceeds $MAX_ARGUMENTS arguments"
        }
        var bytes = 0L
        for (argument in command) {
            require(argument.indexOf('\u0000') < 0) {
                "managed process argument must not contain NUL"
            }
            bytes += argument.toByteArray(Charsets.UTF_8).size
            require(bytes <= MAX_ARGUMENT_UTF8_BYTES) {
                "managed process arguments exceed $MAX_ARGUMENT_UTF8_BYTES UTF-8 bytes"
            }
        }
        val os = System.getProperty("os.name").lowercase()
        require("mac" in os || "linux" in os) {
            "managed process groups require the supported macOS/Linux toolchain"
        }
        val statusFile = Files.createTempFile("test-graph-process-supervisor-", ".status")
        Files.writeString(statusFile, "pending\n")
        return ManagedCommand(
            arguments = listOf(
                "/usr/bin/env",
                "perl",
                "-e",
                CONTROLLER,
                "--",
                statusFile.toString(),
            ) + command,
            statusFile = statusFile,
        )
    }

    private val CONTROLLER = """
        use strict;
        use warnings;
        use POSIX qw(setsid WNOHANG WIFEXITED WEXITSTATUS WIFSIGNALED WTERMSIG);
        use Time::HiRes qw(usleep);

        my ${'$'}status_file = shift @ARGV;
        my @command = @ARGV;
        die "managed command is empty\n" unless @command;
        my ${'$'}write_status = sub {
            my (${'$'}status) = @_;
            open(my ${'$'}status_handle, '>', ${'$'}status_file)
                or die "managed process status open failed: ${'$'}!\n";
            print {${'$'}status_handle} ${'$'}status, "\n"
                or die "managed process status write failed: ${'$'}!\n";
            close(${'$'}status_handle)
                or die "managed process status close failed: ${'$'}!\n";
        };
        my ${'$'}child = fork();
        die "managed command fork failed: ${'$'}!\n" unless defined ${'$'}child;
        if (${'$'}child == 0) {
            setsid() >= 0 or die "managed command setsid failed: ${'$'}!\n";
            exec {${'$'}command[0]} @command;
            die "managed command exec failed: ${'$'}!\n";
        }

        my ${'$'}cleaning = 0;
        my ${'$'}group_alive = sub {
            return kill(0, -${'$'}child) > 0;
        };
        my ${'$'}reap_group = sub {
            return 0 if ${'$'}cleaning++;
            kill('TERM', -${'$'}child);
            for (1..20) {
                waitpid(${'$'}child, WNOHANG);
                last unless ${'$'}group_alive->();
                usleep(10_000);
            }
            kill('KILL', -${'$'}child) if ${'$'}group_alive->();
            for (1..50) {
                waitpid(${'$'}child, WNOHANG);
                last unless ${'$'}group_alive->();
                usleep(10_000);
            }
            waitpid(${'$'}child, 0);
            return !${'$'}group_alive->();
        };

        ${'$'}SIG{TERM} = sub {
            my ${'$'}clean = ${'$'}reap_group->();
            ${'$'}write_status->(${'$'}clean
                ? "supervisor_signal:143"
                : "process_group_cleanup_failed");
            exit(${'$'}clean ? 143 : ${PROCESS_GROUP_CLEANUP_FAILED_EXIT_CODE});
        };
        ${'$'}SIG{INT} = sub {
            my ${'$'}clean = ${'$'}reap_group->();
            ${'$'}write_status->(${'$'}clean
                ? "supervisor_signal:130"
                : "process_group_cleanup_failed");
            exit(${'$'}clean ? 130 : ${PROCESS_GROUP_CLEANUP_FAILED_EXIT_CODE});
        };
        ${'$'}SIG{HUP} = sub {
            my ${'$'}clean = ${'$'}reap_group->();
            ${'$'}write_status->(${'$'}clean
                ? "supervisor_signal:129"
                : "process_group_cleanup_failed");
            exit(${'$'}clean ? 129 : ${PROCESS_GROUP_CLEANUP_FAILED_EXIT_CODE});
        };

        my ${'$'}waited = waitpid(${'$'}child, 0);
        die "managed command wait failed: ${'$'}!\n" if ${'$'}waited < 0;
        my ${'$'}status = ${'$'}?;
        # A multiprocessing launcher can reap its worker pool asynchronously:
        # the leader has returned, but an already-stopping worker remains in
        # the process group for a few scheduling quanta. Give that cooperative
        # shutdown a bounded two-second quiescence window before classifying it
        # as an orphan. A genuinely persistent member is still reaped and
        # reported as a contract violation below.
        for (1..${EXIT_QUIESCENCE_POLLS}) {
            last unless ${'$'}group_alive->();
            usleep(10_000);
        }
        if (${'$'}group_alive->()) {
            print STDERR "managed command leader exited with live process-group members; reaping group\n";
            unless (${'$'}reap_group->()) {
                ${'$'}write_status->("process_group_cleanup_failed");
                exit ${PROCESS_GROUP_CLEANUP_FAILED_EXIT_CODE};
            }
            ${'$'}write_status->("orphaned_group_reaped");
            exit ${ORPHANED_GROUP_REAPED_EXIT_CODE};
        }
        if (WIFEXITED(${'$'}status)) {
            my ${'$'}exit_code = WEXITSTATUS(${'$'}status);
            ${'$'}write_status->("child_exit:${'$'}exit_code");
            exit ${'$'}exit_code;
        }
        if (WIFSIGNALED(${'$'}status)) {
            my ${'$'}exit_code = 128 + WTERMSIG(${'$'}status);
            ${'$'}write_status->("child_exit:${'$'}exit_code");
            exit ${'$'}exit_code;
        }
        ${'$'}write_status->("child_exit:126");
        exit 126;
    """.trimIndent()

    private const val MAX_STATUS_UTF8_BYTES = 64
}
