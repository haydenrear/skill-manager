"""run.py sweep behaviour: keep going past a red graph, count only this run.

DEF-OUN-023: Gradle stopped the sweep at the first failing graph, and the
reported coverage was read from build/validation-reports/, which keeps
passing reports from earlier runs.
"""
from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import run  # noqa: E402


def scheduled(task: str, graph: bool = True) -> dict:
    return {"event": "scheduled", "task": task, "graph": graph}


def finished(task: str, outcome: str, **extra) -> dict:
    record = {"event": "finished", "task": task, "graph": True, "outcome": outcome,
              "seconds": 1.0, "runDirs": [], "failure": None, "skipMessage": None}
    record.update(extra)
    return record


def fixture_root(tmp: str) -> Path:
    root = Path(tmp)
    (root / "settings.gradle.kts").write_text('rootProject.name = "fixture"\n')
    (root / "build.gradle.kts").write_text("validationGraph { }\n")
    return root


class SummarizeSweepTest(unittest.TestCase):
    def test_a_red_graph_does_not_hide_the_graphs_after_it(self) -> None:
        records = [
            scheduled("doc-smoke"), scheduled("artifact-dag"), scheduled("smoke"),
            finished("doc-smoke", "passed"),
            finished("artifact-dag", "failed", failure="node failed"),
            finished("smoke", "passed"),
        ]
        summary = run.summarize_sweep(["doc-smoke", "artifact-dag", "smoke"], records, run_all=False)
        self.assertEqual(
            (3, 3, 2, 1, 0),
            (summary["graphs_selected"], summary["graphs_executed"], summary["graphs_passed"],
             summary["graphs_failed"], summary["graphs_not_run"]),
        )
        self.assertEqual(["doc-smoke", "artifact-dag", "smoke"], [r["graph"] for r in summary["results"]])

    def test_scheduled_but_never_finished_and_skipped_are_not_run(self) -> None:
        records = [
            scheduled("a"), scheduled("b"), scheduled("c"), scheduled("validationRunAll", graph=False),
            finished("a", "failed"),
            finished("b", "skipped", skipMessage="SKIPPED"),
        ]
        summary = run.summarize_sweep([], records, run_all=True)
        self.assertEqual(3, summary["graphs_selected"])
        self.assertEqual(1, summary["graphs_executed"])
        self.assertEqual(2, summary["graphs_not_run"])
        self.assertEqual({"a": "failed", "b": "not_run", "c": "not_run"},
                         {r["graph"]: r["status"] for r in summary["results"]})

    def test_requested_graph_gradle_never_scheduled_is_not_run(self) -> None:
        summary = run.summarize_sweep(["doc-smoke", "no-such-graph"], [], run_all=False)
        self.assertEqual(2, summary["graphs_selected"])
        self.assertEqual(0, summary["graphs_executed"])
        self.assertEqual(2, summary["graphs_not_run"])

    def test_all_with_no_ledger_has_an_unknown_selection_and_fails(self) -> None:
        summary = run.summarize_sweep([], [], run_all=True)
        self.assertFalse(summary["selection_known"])
        self.assertIsNone(summary["graphs_selected"])
        self.assertEqual(1, run.sweep_exit_code(0, summary))

    def test_exit_code_is_honest(self) -> None:
        green = run.summarize_sweep(["a"], [scheduled("a"), finished("a", "passed")], run_all=False)
        self.assertEqual(0, run.sweep_exit_code(0, green))
        self.assertEqual(1, run.sweep_exit_code(1, green))
        not_run = run.summarize_sweep(["a", "b"], [scheduled("a"), finished("a", "passed")], run_all=False)
        self.assertEqual(1, run.sweep_exit_code(0, not_run))

    def test_malformed_ledger_lines_are_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            ledger = Path(tmp) / "ledger.jsonl"
            ledger.write_text(json.dumps(scheduled("a")) + "\n{truncated\n\n[1]\n")
            self.assertEqual([scheduled("a")], run.read_ledger(ledger))
            self.assertEqual([], run.read_ledger(Path(tmp) / "missing.jsonl"))


class SweepInvocationTest(unittest.TestCase):
    def _run_main(self, argv: list[str], root: Path, ledger_lines: list[dict], rc: int):
        calls = []

        def fake_run_gradle(args, test_graph_root=None, extra_env=None):
            calls.append((list(args), test_graph_root, dict(extra_env or {})))
            if extra_env and run.SWEEP_LEDGER_ENV in extra_env:
                with open(extra_env[run.SWEEP_LEDGER_ENV], "a", encoding="utf-8") as fh:
                    for line in ledger_lines:
                        fh.write(json.dumps(line) + "\n")
            return rc

        out = io.StringIO()
        with patch.object(run, "run_gradle", side_effect=fake_run_gradle), \
                contextlib.redirect_stdout(out):
            code = run.main([*argv, "--test-graph-root", str(root)])
        return code, calls, out.getvalue()

    def test_multi_graph_continues_and_stale_reports_are_not_counted(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = fixture_root(tmp)
            # A passing report for `smoke` left by an EARLIER run.
            stale = root / "build" / "validation-reports" / "20200101-000000"
            stale.mkdir(parents=True)
            (stale / "summary.json").write_text(json.dumps(
                {"status": "passed", "execution": {"graphName": "smoke"}}))
            fresh = str(root / "build" / "validation-reports" / "20990101-000000")
            ledger = [
                scheduled("doc-smoke"), scheduled("artifact-dag"), scheduled("smoke"),
                finished("doc-smoke", "passed", runDirs=[fresh]),
                finished("artifact-dag", "failed", failure="uninstall.prunes.the.subgraph failed"),
            ]
            code, calls, out = self._run_main(["doc-smoke", "artifact-dag", "smoke"], root, ledger, rc=1)

            self.assertEqual(1, code)
            args = calls[0][0]
            self.assertIn("--continue", args)
            self.assertEqual(str(run.SWEEP_INIT_SCRIPT), args[args.index("--init-script") + 1])
            self.assertEqual(["doc-smoke", "artifact-dag", "smoke"], args[-3:])

            records = list((root / "build" / "validation-sweeps").glob("*/sweep.json"))
            self.assertEqual(1, len(records))
            summary = json.loads(records[0].read_text())
            self.assertEqual(2, summary["graphs_executed"])
            self.assertEqual(1, summary["graphs_not_run"])
            self.assertEqual("not_run", summary["results"][2]["status"])
            self.assertIn("graphs_executed=2", out)
            self.assertIn("build/validation-reports/20990101-000000", out)

    def test_all_runs_validation_run_all_with_continue(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = fixture_root(tmp)
            ledger = [scheduled("a"), scheduled("b"), finished("a", "passed"), finished("b", "passed")]
            code, calls, out = self._run_main(["--all"], root, ledger, rc=0)
            self.assertEqual(0, code)
            self.assertEqual(["--continue", "validationRunAll"], calls[0][0][-2:])
            self.assertIn("graphs_selected=2  graphs_executed=2  graphs_passed=2", out)

    def test_fail_fast_omits_continue(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = fixture_root(tmp)
            _code, calls, _out = self._run_main(["--all", "--fail-fast"], root, [], rc=1)
            self.assertNotIn("--continue", calls[0][0])

    def test_single_graph_invocation_is_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = fixture_root(tmp)
            code, calls, out = self._run_main(["smoke"], root, [], rc=0)
            self.assertEqual(0, code)
            self.assertEqual(["--console=plain", "smoke"], calls[0][0])
            self.assertEqual({}, calls[0][2])
            self.assertFalse((root / "build" / "validation-sweeps").exists())
            self.assertEqual("", out)

    def test_resume_options_refuse_a_sweep(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = fixture_root(tmp)
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                self._run_main(["a", "b", "--resume-from-build", "x", "--resume-from-node", "n.x"],
                               root, [], rc=0)


if __name__ == "__main__":
    unittest.main()
