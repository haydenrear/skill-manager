#!/usr/bin/env python3
"""GOAL-no-own-home-path: no generated shim spells its own home's path, and a
half-rewritten one is reported.

Promoted from the kickoff baseline's O1 observation and the fleet script's G3
(OHV-0, #356).

  (1) regular-file bin/cli entries whose text spells the home's own absolute path
      (either spelling), on root and this project home. Each is classified:
        half_rewritten           -- carries SKILL_MANAGER_SHIM_HOME and still a literal
                                    home path (DEF-OHV-001)
        reported_by_home_repair  -- `home repair --json` names it (FROZEN_HOME_PATH_IN_SHIM
                                    or FOREIGN_PATH_IN_SHIM)
      The goal reads (1) after `home repair --fix`; this harness is read-only, so
      it reads the home as it stands and says which entries --fix would NOT touch
      (the unreported ones).
  (2) the copied-home probe -- measured by
      scripts/measure_goal_a_home_survives_being_copied.py, not here.
  (3) fleet homes with such an entry -- context, with --fleet (file reads only).

Kickoff (v0.27.2, 71c51464): root 3 (computeq, helm-deploy, monitoring; half-
rewritten, unreported); project 0. Fleet 51 of 61.

Read-only. Contract: one JSON object on stdout; exit 0 met, 1 not met, 2 could not measure.

  python3 scripts/measure_goal_no_own_home_path.py [--fleet] [<store> ...]
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import home_observations as obs  # noqa: E402

GOAL = "GOAL-no-own-home-path"
SHIM_KINDS = ("FROZEN_HOME_PATH_IN_SHIM", "FOREIGN_PATH_IN_SHIM")


def main() -> int:
    argv = sys.argv[1:]
    args = [a for a in argv if not a.startswith("-")]
    homes = [(Path(a).name, Path(a)) for a in args] or [(l, h) for l, h, _ in obs.default_homes()]
    payload = {"goal": GOAL,
               "metric": "(1) bin/cli entries spelling the home's own absolute path, on root and "
                         "this project home, split by whether `home repair` reports them",
               "target": "(1) 0 on both homes",
               "clause_2": "measured by scripts/measure_goal_a_home_survives_being_copied.py"}
    if not homes:
        payload.update(value="no home to measure", could_not_measure="no Skill Manager home found")
        return obs.emit(payload, unmeasured=True)

    per_home, parts, total = {}, [], 0
    for label, home in homes:
        entries = obs.bin_cli_own_path_entries(home)
        # OHV-4 (#341): an entry that names the home ONLY in its first-line
        # shebang (a venv-internal `#!<home>/venvs/.../python` entrypoint) is a
        # deliberately-unreported shape -- `home repair` does not report it and
        # --fix cannot rewrite it, because the kernel reads a shebang literally
        # and it cannot hold a token. Counted, and kept OUT of "unreported", so
        # that population never reads as a detector gap.
        # Likewise an entry that spells the home only in `#` comment lines
        # (possibly plus the shebang): prose about a path is not a reference to
        # one, so the detector does not read comments (#341 "Watch for") and
        # --fix leaves such a shim byte-identical.
        sp = obs.spellings(home)
        for e in entries:
            where = _where_spelled(home / "bin" / "cli" / e["entry"], sp)
            e["shebang_only"] = where == "shebang"
            e["comment_only"] = where == "comment"
        rep = obs.repair_report(home)
        doc = rep["doc"]
        reported = None
        if isinstance(doc, dict):
            reported = {f.get("subject") for f in doc.get("findings", [])
                        if f.get("kind") in SHIM_KINDS}
        for e in entries:
            e["reported_by_home_repair"] = (None if reported is None
                                            else f"bin/cli/{e['entry']}" in reported)
        total += len(entries)
        half = [e["entry"] for e in entries if e["half_rewritten"]]
        shebang_only = [e["entry"] for e in entries if e["shebang_only"]]
        comment_only = [e["entry"] for e in entries if e["comment_only"]]
        unreported = [e["entry"] for e in entries
                      if e["reported_by_home_repair"] is False
                      and not e["shebang_only"] and not e["comment_only"]]
        per_home[label] = {"home": str(home), "entries": entries,
                           "unreported": unreported,
                           "shebang_only_out_of_scope": shebang_only,
                           "comment_only_not_a_reference": comment_only,
                           "home_repair_rc": rep["rc"],
                           "home_repair_findings": (len(doc.get("findings", []))
                                                    if isinstance(doc, dict) else None),
                           "home_repair_stderr_tail": rep.get("stderr_tail")}
        detail = ""
        if entries:
            detail = (f" ({', '.join(e['entry'] for e in entries)}; {len(half)} half-rewritten, "
                      + (f"{len(unreported)} unreported by home repair" if reported is not None
                         else "home repair unreadable")
                      + (f", {len(shebang_only)} shebang-only (out of scope)" if shebang_only else "")
                      + (f", {len(comment_only)} comment-only (not a reference)" if comment_only else "")
                      + ")")
        parts.append(f"{label} {len(entries)}{detail}")

    payload["value"] = "; ".join(parts)
    payload["met"] = total == 0
    payload["homes"] = per_home
    if "--fleet" in argv:
        fleet = obs.discover_fleet()
        with_entry = [str(h) for h, _ in fleet if obs.bin_cli_own_path_entries(h)]
        payload["fleet"] = {"homes": len(fleet), "homes_with_own_path_bin_cli": len(with_entry),
                            "note": "context, no threshold"}
    return obs.emit(payload, unmeasured=False)


def _where_spelled(path: Path, spellings):
    """Where the home is spelled, when not on a line that runs.

    "shebang" -- only on the first-line shebang; "comment" -- only on `#`
    comment lines (with or without the shebang); None -- on at least one line
    that is neither, i.e. a line `home repair` is expected to report.
    """
    text = obs.is_text(path)
    if text is None:
        return None
    lines = text.split("\n")
    shebang = lines[0] if lines and lines[0].startswith("#!") else None
    body = lines[1:] if shebang is not None else lines
    in_shebang = shebang is not None and any(s in shebang for s in spellings)
    in_comment = any(l.lstrip().startswith("#") and any(s in l for s in spellings) for l in body)
    in_running = any(not l.lstrip().startswith("#") and any(s in l for s in spellings) for l in body)
    if in_running:
        return None
    if in_comment:
        return "comment"
    return "shebang" if in_shebang else None


if __name__ == "__main__":
    sys.exit(main())
