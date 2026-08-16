#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#   "PyYAML>=6.0.2,<7",
# ]
# ///
"""Parse every hand-authored YAML file this repository ships, and check the
shape of the spec manifests among them.

WHY THIS EXISTS
---------------
Three times now, a YAML file this repository authors by hand has been wrong in
a way nothing noticed until somebody needed the file to be true. All three are
the same defect: prose typed into YAML, an unquoted `: ` inside it, and no
parser in CI.

  1. `skill-dev-skill`'s SKILL.md description. Invalid frontmatter, so the
     parser DROPPED the unit from a sync; descendant worktrees deadlocked on
     exit 6 while the error blamed links. Cost most of a wave to diagnose.
     (skill-dev-skill#4, git-issue-workflow#16)
  2. `specs/program_model/spec_manifest.yaml`. A copy of the ticket model's
     manifest, naming three TLA+ modules that do not exist in that directory,
     with its contract test red on main across two releases. ARTI-02 (#103)
     spent an entire ticket repairing it.
  3. `specs/desired_program_model/spec_manifest.yaml`. Six prose entries under
     `status.done` carrying unquoted `: `. The manifest describing an
     in-flight 17-ticket workflow could not be read by any YAML parser.
     (ARTI-25, #127)

ARTI-14 (#115) shipped `.github/scripts/check_units.py` in the leaf repos,
which catches exactly shape (1) for UNIT manifests and frontmatter. It does not
exist here and would not cover spec manifests if it did. This is the sibling,
and it is deliberately wider than the file that prompted it: a per-file fix
guarantees a fourth instance somewhere else, so the first check below applies to
EVERY hand-authored YAML in the tree, not to the three manifests.

WHAT IT CHECKS
--------------
1. **Every YAML file parses.** `yaml.safe_load` over everything outside
   SKIP_DIRS. This is the check that would have caught instances (1) and (3)
   one second after the commit that introduced them.

2. **No mapping key in a spec manifest looks like a sentence.** A parse alone
   is NOT sufficient, and this is the part that is easy to get wrong. YAML
   turns

       - Found TWO defects, reproduced against the real classes: CHM-9 (...)

   into the single-key MAPPING `{"Found TWO defects, ... real classes":
   "CHM-9 (...)"}`. It parses. It is silently the wrong data, and a reader
   asking for `status.done[0].startswith(...)` gets an AttributeError somewhere
   far away. Only when a line carries a SECOND `: ` does the parser raise. Of
   the six broken entries in instance (3), three were silent and three raised —
   so "safe_load succeeds" would have accepted half the defect. A key
   containing whitespace is the unmistakable signature; no legitimate key in
   any manifest in this tree has one.

3. **A spec manifest declares the keys a reader needs**, and every file it
   names under `model:` EXISTS beside it. Instance (2) parsed perfectly and
   named `Core.tla`, `Internal.tla` and `External.tla` in a directory that has
   only ever held `SkillManager.tla`. Nothing but an existence check catches
   that, and without it this script would not, in fact, catch all three.

WHAT IT DELIBERATELY DOES NOT CHECK
-----------------------------------
`specs/.history/` is an APPEND-ONLY archive of closed workflows. Two snapshots
in it carry instance (3) — the broken manifest was archived twice before it was
found — and they cannot be repaired without rewriting history the workflow
contract says is immutable. Failing on them would make this check unfixable on
day one, so the directory is excluded HERE, with the reason, rather than
silently missed by a glob that happened not to reach it.

Prints one line per file checked, so a green run is evidence about a set of
files rather than an exit code. Exit 0 clean, 1 with findings.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any

import yaml

# VCS metadata, build output, virtualenvs, dependency caches — and a checkout's
# own Skill Manager home and agent projections, which hold COPIES of other
# repositories' files. Validating those would report another repo's defects as
# this one's.
SKIP_DIRS = {
    ".git",
    ".venv",
    "venv",
    "node_modules",
    "__pycache__",
    ".pytest_cache",
    ".skill-manager",
    ".claude",
    ".codex",
    ".gemini",
    ".ruff_cache",
    ".tools",
    ".idea",
    ".gradle",
    "build",
    "dist",
    "target",
    "out",
}

# Append-only archive; see the module docstring. Relative to the repo root.
SKIP_PATHS = ("specs/.history",)

SUFFIXES = (".yaml", ".yml")

# The live spec models. A directory is a spec model when it holds a
# spec_manifest.yaml, so this glob follows the files rather than a hardcoded
# list that a new model would silently fall outside of.
SPEC_MANIFEST_GLOB = "specs/*/spec_manifest.yaml"

# Keys every spec manifest states about itself. `spec-double-compiler` reads
# `module` and `package` to locate and name generated cases; `status` is what
# every close-out and promotion decision is read out of.
MANIFEST_REQUIRED = ("module", "package", "status")

# Keys under `model:` that name a file in the manifest's own directory. Single
# values and lists of values are both accepted; a key that is absent is not a
# finding, because the three manifests declare different subsets.
MODEL_FILE_KEYS = ("tla", "cfg", "promotion_cfg", "module_files")


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.checked: list[str] = []

    def fail(self, path: Path, message: str) -> None:
        self.errors.append(f"{path}: {message}")

    def ok(self, path: Path, message: str) -> None:
        self.checked.append(f"{path}: {message}")


def iter_yaml(root: Path) -> list[Path]:
    skipped = {(root / p).resolve() for p in SKIP_PATHS}
    found: list[Path] = []
    stack = [root]
    while stack:
        directory = stack.pop()
        try:
            entries = sorted(directory.iterdir())
        except (PermissionError, FileNotFoundError):
            continue
        for entry in entries:
            if entry.is_symlink():
                continue
            if entry.is_dir():
                if entry.name in SKIP_DIRS or entry.resolve() in skipped:
                    continue
                stack.append(entry)
            elif entry.suffix in SUFFIXES:
                found.append(entry)
    return sorted(found)


def load(report: Report, path: Path, root: Path) -> Any | None:
    """`yaml.safe_load`, with the failure phrased as its consequence.

    Every consumer of these files performs this exact read. Performing it here
    is not a lint — it is the same read, one second after the commit.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        report.fail(path.relative_to(root), f"unreadable: {exc}")
        return None
    try:
        return yaml.safe_load(text)
    except yaml.YAMLError as exc:
        detail = " ".join(str(exc).split())
        report.fail(
            path.relative_to(root),
            "is not valid YAML — nothing that reads this file as YAML can read "
            f"it: {detail}",
        )
        return None


def sentence_keys(node: Any, trail: str = "") -> list[tuple[str, str]]:
    """Mapping keys that are prose, i.e. a `- text: more` that lost its quotes.

    Returns (path, key) pairs. Whitespace in the key is the signature: an
    authored key in these manifests is a snake_case identifier, and a sentence
    fragment ending up as one means a list entry silently became a mapping.
    """
    found: list[tuple[str, str]] = []
    if isinstance(node, dict):
        for key, value in node.items():
            where = f"{trail}.{key}" if trail else str(key)
            if isinstance(key, str) and any(ch.isspace() for ch in key):
                found.append((trail or "<root>", key))
            else:
                found.extend(sentence_keys(value, where))
    elif isinstance(node, list):
        for i, item in enumerate(node):
            found.extend(sentence_keys(item, f"{trail}[{i}]"))
    return found


def check_manifest(report: Report, path: Path, root: Path, data: Any) -> None:
    rel = path.relative_to(root)
    if not isinstance(data, dict):
        report.fail(rel, f"spec manifest is a {type(data).__name__}, not a mapping")
        return

    missing = [key for key in MANIFEST_REQUIRED if key not in data]
    if missing:
        report.fail(rel, f"spec manifest declares no {', '.join('`' + k + '`' for k in missing)}")

    for trail, key in sentence_keys(data):
        clipped = key if len(key) <= 90 else key[:87] + "..."
        report.fail(
            rel,
            f"under `{trail}`, a list entry parsed as a MAPPING because of an "
            f"unquoted `: ` inside it — the text is silently no longer a string: "
            f"{clipped!r}",
        )

    model = data.get("model")
    if isinstance(model, dict):
        for key in MODEL_FILE_KEYS:
            value = model.get(key)
            if value is None:
                continue
            names = value if isinstance(value, list) else [value]
            for name in names:
                if not isinstance(name, str):
                    report.fail(rel, f"`model.{key}` names a {type(name).__name__}, not a file")
                    continue
                if not (path.parent / name).is_file():
                    report.fail(
                        rel,
                        f"`model.{key}` names {name}, which does not exist in "
                        f"{path.parent.relative_to(root)}/ — the manifest describes a "
                        "model that is not there (this is the ARTI-02 shape)",
                    )
    elif model is not None:
        report.fail(rel, f"`model` is a {type(model).__name__}, not a mapping")

    if not report.errors or not any(str(rel) in e for e in report.errors):
        keys = ", ".join(k for k in ("module", "package", "model", "status") if k in data)
        report.ok(rel, f"spec manifest valid ({keys})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root", type=Path, default=Path(__file__).resolve().parents[2],
        help="repository root (default: the repo this script lives in)",
    )
    parser.add_argument("--quiet", action="store_true", help="print findings only")
    args = parser.parse_args()
    root = args.root.resolve()

    report = Report()
    manifests = {p.resolve() for p in root.glob(SPEC_MANIFEST_GLOB)}
    files = iter_yaml(root)
    for path in files:
        data = load(report, path, root)
        if data is None:
            continue
        if path.resolve() in manifests:
            check_manifest(report, path, root, data)
        else:
            report.ok(path.relative_to(root), "parses")

    if not args.quiet:
        for line in report.checked:
            print(f"ok    {line}")
    for line in report.errors:
        print(f"FAIL  {line}", file=sys.stderr)

    print()
    print(
        f"checked {len(files)} YAML file(s) under {root} "
        f"({len(manifests)} spec manifest(s)); {len(report.errors)} finding(s)"
    )
    return 1 if report.errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
