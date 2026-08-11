from __future__ import annotations

import os
import shlex
import signal
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from test_graph.support import rerun_harness as harness


def _python(source: str, *args: str) -> list[str]:
    return [sys.executable, "-c", textwrap.dedent(source), *args]


def _pid_exists(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def _kill_if_present(pid: int) -> None:
    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


@unittest.skipUnless(hasattr(os, "killpg"), "requires POSIX process groups")
class RunCommandLifecycleTest(unittest.TestCase):
    def test_nested_command_budgets_fit_inside_outer_node_timeout(self) -> None:
        self.assertLessEqual(
            harness.INNER_COMMAND_TIMEOUT_SECONDS
            * harness.MAX_NESTED_COMMANDS_PER_NODE,
            harness.OUTER_NODE_TIMEOUT_SECONDS
            - harness.OUTER_CLEANUP_RESERVE_SECONDS,
        )

    def assert_pid_gone(self, pid: int) -> None:
        self.addCleanup(_kill_if_present, pid)
        deadline = time.monotonic() + 2.0
        while _pid_exists(pid) and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertFalse(
            _pid_exists(pid), f"process {pid} survived process-group cleanup"
        )

    def test_normal_command_captures_both_streams(self) -> None:
        completed = harness.run_command(
            "normal",
            _python(
                """
                import sys
                print("hello")
                print("warning", file=sys.stderr)
                """
            ),
            timeout=2,
        )

        self.assertEqual(0, completed.returncode)
        self.assertEqual("hello\n", completed.stdout)
        self.assertEqual("warning\n", completed.stderr)

    def test_fast_exit_cannot_bypass_combined_output_limit(self) -> None:
        with patch.object(harness, "MAX_COMMAND_OUTPUT_BYTES", 4 * 1024):
            with self.assertRaisesRegex(
                harness.ReplayEvidenceError, "combined live limit"
            ):
                harness.run_command(
                    "fast-overflow",
                    _python(
                        """
                        import os
                        os.write(1, b"o" * 8192)
                        os.write(2, b"e" * 8192)
                        """
                    ),
                    timeout=2,
                )

    def test_timeout_kills_term_ignoring_leader_and_descendant(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            parent_pid_file = Path(tmp) / "parent.pid"
            child_pid_file = Path(tmp) / "child.pid"
            with (
                patch.object(harness, "PROCESS_POLL_SECONDS", 0.005),
                patch.object(harness, "PROCESS_TERM_GRACE_SECONDS", 0.2),
            ):
                with self.assertRaisesRegex(
                    harness.ReplayEvidenceError, "exceeded its 0.3s timeout"
                ):
                    harness.run_command(
                        "timeout-tree",
                        _python(
                            """
                            import os
                            import signal
                            import subprocess
                            import sys
                            import time
                            from pathlib import Path

                            signal.signal(signal.SIGTERM, signal.SIG_IGN)
                            child = subprocess.Popen(
                                [sys.executable, "-c", "import time; time.sleep(30)"]
                            )
                            Path(sys.argv[1]).write_text(str(os.getpid()))
                            Path(sys.argv[2]).write_text(str(child.pid))
                            time.sleep(30)
                            """,
                            str(parent_pid_file),
                            str(child_pid_file),
                        ),
                        timeout=0.3,
                    )

            self.assert_pid_gone(int(parent_pid_file.read_text()))
            self.assert_pid_gone(int(child_pid_file.read_text()))

    def test_successful_leader_cannot_leave_detached_output_descendant(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            child_pid_file = Path(tmp) / "child.pid"
            with (
                patch.object(harness, "PROCESS_POLL_SECONDS", 0.005),
                patch.object(harness, "PROCESS_TERM_GRACE_SECONDS", 0.2),
            ):
                with self.assertRaisesRegex(
                    harness.ReplayEvidenceError, "left live descendants"
                ):
                    harness.run_command(
                        "closed-pipe-descendant",
                        _python(
                            """
                            import subprocess
                            import sys
                            from pathlib import Path

                            child = subprocess.Popen(
                                [sys.executable, "-c", "import time; time.sleep(30)"],
                                stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL,
                            )
                            Path(sys.argv[1]).write_text(str(child.pid))
                            """,
                            str(child_pid_file),
                        ),
                        timeout=2,
                    )

            self.assert_pid_gone(int(child_pid_file.read_text()))

    def test_successful_leader_cannot_leave_inherited_output_pipes_open(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            child_pid_file = Path(tmp) / "child.pid"
            with (
                patch.object(harness, "PROCESS_POLL_SECONDS", 0.005),
                patch.object(harness, "PROCESS_TERM_GRACE_SECONDS", 0.2),
                patch.object(harness, "PROCESS_PIPE_DRAIN_SECONDS", 0.1),
            ):
                with self.assertRaisesRegex(
                    harness.ReplayEvidenceError, "descendant-held output pipes"
                ):
                    harness.run_command(
                        "open-pipe-descendant",
                        _python(
                            """
                            import subprocess
                            import sys
                            from pathlib import Path

                            child = subprocess.Popen(
                                [sys.executable, "-c", "import time; time.sleep(30)"]
                            )
                            Path(sys.argv[1]).write_text(str(child.pid))
                            """,
                            str(child_pid_file),
                        ),
                        timeout=2,
                    )

            self.assert_pid_gone(int(child_pid_file.read_text()))


class BoundedGradleEnvironmentTest(unittest.TestCase):
    @patch.dict(os.environ, {}, clear=True)
    def test_shared_sanitizer_removes_every_native_memory_sizing_form(self) -> None:
        unsafe = [
            "-Xmx5g",
            "-Xms4g",
            "-Xmn3g",
            "-Xss64m",
            "-XX:MaxMetaspaceSize=2g",
            "-XX:MetaspaceSize=1g",
            "-XX:MaxDirectMemorySize=8g",
            "-XX:ThreadStackSize=65536",
            "-XX:ReservedCodeCacheSize=2g",
            "-XX:InitialCodeCacheSize=1g",
            "-XX:CompressedClassSpaceSize=2g",
            "-XX:MaxRAM=16g",
            "-XX:MaxRAMPercentage=95",
            "-XX:InitialRAMPercentage=95",
            "-XX:MinRAMPercentage=95",
            "-XX:MaxRAMFraction=1",
            "-XX:InitialRAMFraction=1",
            "-XX:MinRAMFraction=1",
        ]
        for name in ("GRADLE_OPTS", *harness.JVM_OPTION_CHANNELS):
            with self.subTest(name=name):
                bounded = harness._bounded_gradle_env(
                    {name: shlex.join(["-Dsafe.option=true", *unsafe])}
                )
                self.assertEqual(
                    ["-Dsafe.option=true"],
                    [
                        token
                        for token in shlex.split(bounded.get(name, ""))
                        if token != "-Dorg.gradle.daemon=false"
                    ],
                )

    @patch.dict(os.environ, {}, clear=True)
    def test_quoted_non_memory_options_are_normalized_and_preserved(self) -> None:
        bounded = harness._bounded_gradle_env(
            {"JAVA_TOOL_OPTIONS": "'-Dmessage=hello world' -Dkeep=true"}
        )

        self.assertEqual(
            ["-Dmessage=hello world", "-Dkeep=true"],
            shlex.split(bounded["JAVA_TOOL_OPTIONS"]),
        )

    @patch.dict(os.environ, {}, clear=True)
    def test_gradle_and_project_property_overrides_are_replaced(self) -> None:
        bounded = harness._bounded_gradle_env(
            {
                "GRADLE_OPTS": (
                    "-Dkeep=true '-Dorg.gradle.jvmargs=-Xmx9g' "
                    "-Dkotlin.compiler.execution.strategy=daemon"
                ),
                "ORG_GRADLE_PROJECT_org.gradle.jvmargs": "-Xmx10g",
                "ORG_GRADLE_PROJECT_org.gradle.workers.max": "20",
                "ORG_GRADLE_PROJECT_kotlin.compiler.execution.strategy": "daemon",
                "ORG_GRADLE_PROJECT_kotlin.daemon.jvmargs": "-Xmx12g",
            }
        )

        self.assertEqual(
            ["-Dkeep=true", "-Dorg.gradle.daemon=false"],
            shlex.split(bounded["GRADLE_OPTS"]),
        )
        self.assertEqual(
            "-Xmx768m -XX:MaxMetaspaceSize=384m",
            bounded["ORG_GRADLE_PROJECT_org.gradle.jvmargs"],
        )
        self.assertEqual("1", bounded["ORG_GRADLE_PROJECT_org.gradle.workers.max"])
        self.assertEqual(
            "in-process",
            bounded["ORG_GRADLE_PROJECT_kotlin.compiler.execution.strategy"],
        )
        self.assertNotIn(
            "ORG_GRADLE_PROJECT_kotlin.daemon.jvmargs",
            bounded,
        )

    @patch.dict(os.environ, {}, clear=True)
    def test_indirect_option_files_fail_closed(self) -> None:
        for name in ("GRADLE_OPTS", *harness.JVM_OPTION_CHANNELS):
            with self.subTest(name=name):
                with self.assertRaisesRegex(
                    harness.ReplayEvidenceError, "indirect JVM option file"
                ):
                    harness._bounded_gradle_env({name: "@unbounded.options"})

    @patch.dict(os.environ, {}, clear=True)
    def test_malformed_option_channel_fails_closed(self) -> None:
        with self.assertRaisesRegex(
            harness.ReplayEvidenceError, "not valid shell syntax"
        ):
            harness._bounded_gradle_env({"JAVA_OPTS": "'unterminated"})


if __name__ == "__main__":
    unittest.main()
