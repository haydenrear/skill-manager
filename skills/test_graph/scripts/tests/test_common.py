from __future__ import annotations

import shlex
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import _common  # noqa: E402


class BoundedGradleInvocationTest(unittest.TestCase):
    def test_run_gradle_always_uses_bounded_in_process_execution(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "settings.gradle.kts").write_text("rootProject.name = \"fixture\"\n")
            (root / "gradlew").write_text("#!/bin/sh\n")

            with patch.object(
                _common.subprocess,
                "run",
                return_value=SimpleNamespace(returncode=0),
            ) as run:
                self.assertEqual(0, _common.run_gradle(["--console=plain", "smoke"], root))

            command = run.call_args.args[0]
            self.assertEqual(str((root / "gradlew").resolve()), command[0])
            self.assertEqual(
                [
                    "--no-daemon",
                    "--max-workers=1",
                    "-Pkotlin.compiler.execution.strategy=in-process",
                    "-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m",
                ],
                command[1:5],
            )
            self.assertEqual(["--console=plain", "smoke"], command[5:])
            self.assertEqual("-Dorg.gradle.daemon=false", run.call_args.kwargs["env"]["GRADLE_OPTS"])

    def test_user_arguments_cannot_override_memory_or_daemon_guards(self) -> None:
        unsafe = [
            "--daemon",
            "--max-workers=8",
            "-Dorg.gradle.jvmargs=-Xmx5g",
            "-Dorg.gradle.workers.max=8",
            "-Porg.gradle.jvmargs=-Xmx5g",
            "--project-prop=org.gradle.jvmargs=-Xmx5g",
            "-Pkotlin.compiler.execution.strategy=daemon",
            "-Pkotlin.daemon.jvmargs=-Xmx5g",
            "--project-prop=kotlin.compiler.execution.strategy=daemon",
            "--system-prop=org.gradle.jvmargs=-Xmx5g",
        ]
        for argument in unsafe:
            with self.subTest(argument=argument):
                with self.assertRaises(ValueError):
                    _common._bounded_gradle_args([argument, "smoke"])

        with self.assertRaises(ValueError):
            _common._bounded_gradle_args(
                ["--project-prop", "kotlin.compiler.execution.strategy=daemon", "smoke"]
            )
        with self.assertRaises(ValueError):
            _common._bounded_gradle_args(
                ["--system-prop", "org.gradle.jvmargs=-Xmx5g", "smoke"]
            )
        with self.assertRaises(ValueError):
            _common._bounded_gradle_args(
                ["--project-prop", "org.gradle.jvmargs=-Xmx5g", "smoke"]
            )

    def test_gradle_environment_removes_conflicting_system_properties(self) -> None:
        env = _common.gradle_env_with_daemon_disabled(
            {
                "GRADLE_OPTS": (
                    "-Dkeep=true -Xmx6g -XX:MaxMetaspaceSize=2g "
                    "-Dorg.gradle.daemon=true "
                    "'-Dorg.gradle.jvmargs=-Xmx5g' "
                    "-Dkotlin.compiler.execution.strategy=daemon"
                ),
                "JAVA_OPTS": "-Djava.keep=true -Xmx4g -XX:MaxMetaspaceSize=1g",
                "JAVA_TOOL_OPTIONS": "-Dtool.keep=true -Xms3g",
                "_JAVA_OPTIONS": "-Dunderscore.keep=true -XX:MaxRAMPercentage=95",
                "JDK_JAVA_OPTIONS": "-Djdk.keep=true -XX:MaxDirectMemorySize=8g",
            }
        )

        self.assertEqual(
            "-Dkeep=true -Dorg.gradle.daemon=false",
            env["GRADLE_OPTS"],
        )
        self.assertEqual("-Djava.keep=true", env["JAVA_OPTS"])
        self.assertEqual("-Dtool.keep=true", env["JAVA_TOOL_OPTIONS"])
        self.assertEqual("-Dunderscore.keep=true", env["_JAVA_OPTIONS"])
        self.assertEqual("-Djdk.keep=true", env["JDK_JAVA_OPTIONS"])

    def test_all_jvm_option_channels_strip_native_memory_sizing(self) -> None:
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
        for name in _common._JVM_OPTION_ENV_VARS:
            with self.subTest(name=name):
                env = _common.gradle_env_with_daemon_disabled(
                    {name: shlex.join(["-Dsafe.option=true", *unsafe])}
                )
                expected = "-Dsafe.option=true"
                if name == "GRADLE_OPTS":
                    expected += " -Dorg.gradle.daemon=false"
                self.assertEqual(expected, env[name])

    def test_all_jvm_option_channels_reject_indirect_option_files(self) -> None:
        for name in _common._JVM_OPTION_ENV_VARS:
            for unsafe in (
                "@/tmp/unbounded-jvm.options",
                "-XX:Flags=/tmp/unbounded.flags",
                "-XX:VMOptionsFile=/tmp/unbounded.options",
            ):
                with self.subTest(name=name, unsafe=unsafe):
                    with self.assertRaisesRegex(ValueError, name):
                        _common.gradle_env_with_daemon_disabled(
                            {name: shlex.join(["-Dsafe.option=true", unsafe])}
                        )

    def test_gradle_project_environment_cannot_override_runtime_guards(self) -> None:
        env = _common.gradle_env_with_daemon_disabled(
            {
                "ORG_GRADLE_PROJECT_org.gradle.daemon": "true",
                "ORG_GRADLE_PROJECT_org.gradle.jvmargs": "-Xmx9g",
                "ORG_GRADLE_PROJECT_org.gradle.workers.max": "64",
                "ORG_GRADLE_PROJECT_kotlin.compiler.execution.strategy": "daemon",
                "ORG_GRADLE_PROJECT_kotlin.daemon.jvmargs": "-Xmx9g",
                "ORG_GRADLE_PROJECT_safe.option": "preserved",
            }
        )

        self.assertEqual("false", env["ORG_GRADLE_PROJECT_org.gradle.daemon"])
        self.assertEqual(
            "-Xmx768m -XX:MaxMetaspaceSize=384m",
            env["ORG_GRADLE_PROJECT_org.gradle.jvmargs"],
        )
        self.assertEqual("1", env["ORG_GRADLE_PROJECT_org.gradle.workers.max"])
        self.assertEqual(
            "in-process",
            env["ORG_GRADLE_PROJECT_kotlin.compiler.execution.strategy"],
        )
        self.assertNotIn("ORG_GRADLE_PROJECT_kotlin.daemon.jvmargs", env)
        self.assertEqual("preserved", env["ORG_GRADLE_PROJECT_safe.option"])


if __name__ == "__main__":
    unittest.main()
