#!/usr/bin/env python3
"""$0 structural check of wide cases, before any run is billed against them.

A case that fails to load costs nothing, but a case that loads and can never
go green costs a run every round. These are the shapes that do that:

  * name != directory, or a prompt without its `EVAL-CASE: <name>` marker
    (wide-verify cannot tell which case it is, so no verdict is ever written)
  * a file_exists grader on `.eval/<verdict>` that expect.json never produces
  * an expect.json rule with no grader reading it (paid for, never scored)
  * a regex that does not compile, or an llm grader that does not say it reads
    the final response only
  * a `plugins:` block rewrite-case.py cannot replace
  * a units-override.txt naming a checkout with no SKILL.md

    python3 lint.py [glob]     # default w-*; exits 1 on any problem
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

EVALS = pathlib.Path(__file__).resolve().parent.parent / "evals"
PLUGINS = re.compile(r"(?m)^plugins:\n(?:  - .*\n)+")


def frontmatter(text: str) -> dict[str, str]:
    """The flat `key: value` frontmatter graders use. No YAML dependency: the
    hook's python has none, and graders never nest."""
    if not text.startswith("---\n"):
        return {}
    end = text.find("\n---", 4)
    if end < 0:
        return {}
    out = {}
    for line in text[4:end].splitlines():
        key, sep, value = line.partition(":")
        if not sep:
            continue
        value = value.strip()
        # Decode the quoting the way the grader's YAML does, so a pattern is
        # checked as the CLI will see it: "a\\sb" is the regex a\sb.
        if len(value) >= 2 and value[0] == value[-1] == '"':
            try:
                value = json.loads(value)
            except ValueError:
                value = value[1:-1]
        elif len(value) >= 2 and value[0] == value[-1] == "'":
            value = value[1:-1].replace("''", "'")
        out[key.strip()] = value
    return out


def expected_verdicts(expect: dict) -> set[str]:
    names = {"require-" + r["id"] for r in expect.get("require", [])}
    names |= {"forbid-" + r["id"] for r in expect.get("forbid", [])}
    if expect.get("max_calls"):
        names.add("within-budget")
    return names


def lint_case(case: pathlib.Path) -> list[str]:
    problems = []
    name = case.name
    yaml_text = (case / "case.yaml").read_text() if (case / "case.yaml").is_file() else ""
    if not yaml_text:
        return ["no case.yaml"]
    if not re.search(r"(?m)^name:\s*%s\s*$" % re.escape(name), yaml_text):
        problems.append("name: does not equal the directory name")
    if not PLUGINS.search(yaml_text):
        problems.append("plugins: block is not the `plugins:\\n  - ...` shape rewrite-case.py replaces")
    if not re.search(r"EVAL-CASE:\s*%s\s*$" % re.escape(name), yaml_text.rstrip(), re.M):
        problems.append("prompt does not carry `EVAL-CASE: %s`" % name)

    produced: set[str] = set()
    expect_path = case / "expect.json"
    if expect_path.is_file():
        try:
            expect = json.loads(expect_path.read_text())
        except ValueError as error:
            return problems + ["expect.json does not parse: %s" % error]
        for kind in ("require", "forbid"):
            for rule in expect.get(kind, []):
                for key in ("tool", "match"):
                    try:
                        re.compile(rule.get(key, "Bash"))
                    except re.error as error:
                        problems.append("%s %s.%s does not compile: %s" % (kind, rule.get("id"), key, error))
        produced = expected_verdicts(expect)

    read: set[str] = set()
    graders = sorted((case / "graders").glob("*.md"))
    if not graders:
        problems.append("no graders")
    for grader in graders:
        text = grader.read_text()
        meta = frontmatter(text)
        kind = meta.get("type")
        if kind == "file_exists":
            path = meta.get("path", "")
            if path.startswith(".eval/"):
                verdict = path[len(".eval/"):]
                read.add(verdict)
                if verdict not in produced and verdict != "case":
                    problems.append("%s reads .eval/%s, which expect.json never writes" % (grader.name, verdict))
        elif kind == "regex":
            pattern = meta.get("pattern", "")
            try:
                re.compile(pattern)
            except re.error as error:
                problems.append("%s pattern does not compile: %s" % (grader.name, error))
            # Regex graders are evaluated by the CLI, not by Python. Inline
            # flags and Python-only group syntax compile here and not there --
            # a grader that can never match is a permanent red, not an error.
            if re.search(r"\(\?[aiLmsux]+\)|\(\?P[<=]|\\A|\\Z", pattern):
                problems.append("%s pattern uses Python-only regex syntax: %s" % (grader.name, pattern))
        elif kind == "llm":
            if not re.search(r"final response|response only|scores the response|reads the response", text, re.I):
                problems.append("%s is an llm grader that does not say it reads the final response only" % grader.name)
        elif kind not in ("tool_used", "tool_order", "baseline"):
            problems.append("%s has unknown type %r" % (grader.name, kind))
    for verdict in sorted(produced - read):
        problems.append("expect.json writes %s but no grader reads it" % verdict)

    override = case / "units-override.txt"
    if override.is_file():
        for line in override.read_text().splitlines():
            unit, sep, checkout = line.partition("=")
            root = pathlib.Path(checkout.strip())
            is_plugin = (root / ".claude-plugin" / "plugin.json").is_file() and (root / "skills").is_dir()
            if sep and not (root / "SKILL.md").is_file() and not is_plugin:
                problems.append("units-override.txt: %s is neither a skill (SKILL.md) nor a plugin" % checkout.strip())
    return problems


def main(argv: list[str]) -> int:
    glob = argv[0] if argv else "w-*"
    cases = sorted(p for p in EVALS.glob(glob) if p.is_dir())
    bad = 0
    for case in cases:
        problems = lint_case(case)
        if problems:
            bad += 1
            print("FAIL %s" % case.name)
            for problem in problems:
                print("  - %s" % problem)
    print("lint: %d case(s), %d with problems" % (len(cases), bad))
    return 1 if bad or not cases else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
