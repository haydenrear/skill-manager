#!/usr/bin/env python3
"""Verdicts for the WIDE lane: which commands did the agent actually issue?

One small case asks one question, and nearly every such question is "did the
agent reach for command X (and not command Y)". `plugin eval` has no grader for
that -- `tool_used` matches a tool NAME, never a command -- so this reads the
transcript's tool_use INPUTS after the run and writes one verdict file per rule.
Graders are then plain `file_exists` checks on those files.

RULES, from plugin_evals.md, and why each is here:
  1. every verdict path is deleted before looking (the hook does this);
  2. a verdict is written only when the transcript proves it;
  3. the hook exits 0 always;
  4. NOTHING FROM THE TRANSCRIPT IS EXECUTED. This module only matches text.
     The hook runs unsandboxed as the operator; replaying an eval subject's
     command here would hand it a shell on the real machine.

WHICH CASE? `EVAL_CASE` does not reach hooks (measured by the replay verifier),
and every wide case shares one build, so the build directory name cannot say
either. Each wide prompt carries a line `EVAL-CASE: <name>`; the first user
message in the transcript is the prompt, so that is where the case is read.

expect.json, next to case.yaml:

    {"require": [{"id": "uses-force", "tool": "Bash", "match": "--force"}],
     "forbid":  [{"id": "no-plan-edit", "tool": "Edit|Write", "match": "ticket_plan"}],
     "max_calls": {"Bash": 3}}

  require  -> .eval/require-<id> when SOME call of `tool` has an input matching
  forbid   -> .eval/forbid-<id>  when NO call of `tool` matches, AND the agent
              made at least one tool call (an idle run proves nothing)
  max_calls-> .eval/within-budget when every named tool stayed at or under
              its ceiling (tool_used can do this too; this one is recorded in
              the diagnostics beside the commands that spent it)

`tool` and `match` are regexes; `match` is searched in the JSON of the input.

RUNS ON /usr/bin/python3 (3.9 here): no match statements, annotations lazy.

    python3 expect.py --self-test
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

CASE_MARKER = re.compile(r"EVAL-CASE:\s*([A-Za-z0-9._-]+)")
SAFE_ID = re.compile(r"[A-Za-z0-9._-]+\Z")


def read_events(transcript: pathlib.Path) -> list[dict]:
    events = []
    if not transcript.is_file():
        return events
    for line in transcript.read_text(errors="replace").splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if isinstance(event, dict):
            events.append(event)
    return events


def _blocks(event: dict) -> list:
    content = (event.get("message") or {}).get("content")
    if isinstance(content, str):
        return [{"type": "text", "text": content}]
    return content if isinstance(content, list) else []


def case_name(events: list[dict]) -> str | None:
    """The EVAL-CASE marker from the first user message that carries one."""
    for event in events:
        if (event.get("message") or {}).get("role") != "user" and event.get("type") != "user":
            continue
        for block in _blocks(event):
            if isinstance(block, dict) and block.get("type") == "text":
                found = CASE_MARKER.search(block.get("text") or "")
                if found:
                    return found.group(1)
    return None


def tool_calls(events: list[dict]) -> list[tuple[str, str]]:
    """(tool name, JSON of its input) for every tool_use, in order."""
    calls = []
    for event in events:
        for block in _blocks(event):
            if isinstance(block, dict) and block.get("type") == "tool_use":
                name = str(block.get("name") or "")
                calls.append((name, json.dumps(block.get("input") or {}, sort_keys=True)))
    return calls


def _matches(rule: dict, calls: list[tuple[str, str]]) -> list[str]:
    tool = re.compile(r"(?:%s)\Z" % rule.get("tool", "Bash"))
    pattern = re.compile(rule["match"])
    return [text for name, text in calls if tool.match(name) and pattern.search(text)]


def verdicts(expect: dict, calls: list[tuple[str, str]]) -> dict[str, bool]:
    """verdict file name -> whether it is earned. Pure: no filesystem."""
    out = {}
    for rule in expect.get("require", []):
        out["require-" + rule["id"]] = bool(_matches(rule, calls))
    for rule in expect.get("forbid", []):
        out["forbid-" + rule["id"]] = bool(calls) and not _matches(rule, calls)
    ceilings = expect.get("max_calls") or {}
    if ceilings:
        out["within-budget"] = all(
            sum(1 for name, _ in calls if name == tool) <= limit
            for tool, limit in ceilings.items()
        )
    return out


def run(transcript: str, evals_dir: str, ev_dir: str) -> int:
    ev = pathlib.Path(ev_dir)
    ev.mkdir(parents=True, exist_ok=True)
    events = read_events(pathlib.Path(transcript))
    calls = tool_calls(events)
    (ev / "commands.txt").write_text(
        "\n---\n".join("%s %s" % call for call in calls) + ("\n" if calls else "")
    )
    name = case_name(events)
    if not name or not SAFE_ID.match(name):
        (ev / "WHY-NO-VERDICTS.txt").write_text(
            "no EVAL-CASE marker in the first user message; transcript=%s\n" % transcript
        )
        print("no case marker")
        return 0
    (ev / "case").write_text(name + "\n")
    spec = pathlib.Path(evals_dir) / name / "expect.json"
    if not spec.is_file():
        (ev / "WHY-NO-VERDICTS.txt").write_text("no expect.json at %s\n" % spec)
        print("no expect.json for", name)
        return 0
    try:
        expect = json.loads(spec.read_text())
    except ValueError as error:
        (ev / "WHY-NO-VERDICTS.txt").write_text("expect.json does not parse: %s\n" % error)
        return 0
    earned = verdicts(expect, calls)
    for verdict, ok in sorted(earned.items()):
        if ok:
            (ev / verdict).write_text("ok\n")
    print("case=%s calls=%d verdicts=%s" % (
        name, len(calls), " ".join("%s:%s" % (k, "ok" if v else "no") for k, v in sorted(earned.items()))))
    return 0


def _event(role: str, blocks: list) -> dict:
    return {"type": role, "message": {"role": role, "content": blocks}}


def self_test() -> int:
    transcript = [
        _event("user", [{"type": "text", "text": "Do the thing.\nEVAL-CASE: w-demo\n"}]),
        _event("assistant", [
            {"type": "tool_use", "name": "Skill", "input": {"skill": "git-epic-workflow"}},
            {"type": "tool_use", "name": "Bash",
             "input": {"command": "uv run scripts/validate_epic_plan.py plan.yaml --force"}},
            {"type": "tool_use", "name": "Edit",
             "input": {"file_path": "cases/w-demo/notes.md", "old_string": "a", "new_string": "b"}},
        ]),
    ]
    calls = tool_calls(transcript)
    failures = []

    def check(label: str, got, want) -> None:
        if got != want:
            failures.append("%s: got %r, want %r" % (label, got, want))

    check("case marker", case_name(transcript), "w-demo")
    check("marker only in user turns", case_name([_event("assistant", [
        {"type": "text", "text": "EVAL-CASE: w-forged"}])]), None)
    check("string content", case_name([{"type": "user", "message": {
        "role": "user", "content": "EVAL-CASE: w-str"}}]), "w-str")
    expect = {
        "require": [{"id": "force", "tool": "Bash", "match": r"--force\b"},
                    {"id": "strict", "tool": "Bash", "match": r"--strict"}],
        "forbid": [{"id": "plan-edit", "tool": "Edit|Write", "match": "ticket_plan"},
                   {"id": "notes-edit", "tool": "Edit|Write", "match": "notes\\.md"}],
        "max_calls": {"Bash": 1},
    }
    check("verdicts", verdicts(expect, calls), {
        "require-force": True, "require-strict": False,
        "forbid-plan-edit": True, "forbid-notes-edit": False,
        "within-budget": True,
    })
    check("idle run earns no forbid", verdicts(
        {"forbid": [{"id": "x", "match": "y"}]}, []), {"forbid-x": False})
    check("tool regex is anchored", verdicts(
        {"require": [{"id": "b", "tool": "Bash", "match": "x"}]},
        [("BashOutput", '{"command": "x"}')]), {"require-b": False})
    check("budget exceeded", verdicts({"max_calls": {"Bash": 1}},
        [("Bash", "{}"), ("Bash", "{}")]), {"within-budget": False})
    for failure in failures:
        print("FAIL", failure)
    print("self-test: %d failure(s)" % len(failures))
    return 1 if failures else 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if len(sys.argv) != 4:
        sys.exit("usage: expect.py <transcript> <evals-dir> <.eval-dir> | --self-test")
    sys.exit(run(*sys.argv[1:4]))
