#!/usr/bin/env python3
"""GOAL-a-failure-names-its-cause — measure it, do not assume it.

Of three seeded failure modes, how many produce a message naming the TRUE
cause?  The baseline was 0 of 3: a refusal, a genuinely old build and a CLI
that could not run at all all rendered as "too old", with a remedy that
cannot work (#264).

The three modes are seeded through SKILL_MANAGER_CLI, which `pick_cli` honours
as an explicit pin, so each one reaches `cli_verdict` as the candidate:

  (a) refused  -- exits 79 (LauncherShims.HOME_MISMATCH_EXIT_CODE)
  (b) old      -- exits 0 and answers `home clone --help` with no `--to`
  (c) broken   -- exits 127, the shape a missing interpreter or a dead pin has

A mode counts ONLY when the report names its own cause and does not name
another's.  Distinctness is asserted separately: three messages that all name
a cause but the same one would be the baseline defect wearing better words.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

# What each mode's report must say, and what it must NOT say.  The negative
# half is the load-bearing one: the baseline failure was every mode rendering
# as (b), so "names its cause" has to exclude naming somebody else's.
MODES = {
    "refused": {
        "cli": "#!/bin/sh\necho 'this home binds /somewhere/else and will not act on another' >&2\nexit 79\n",
        "must": ["refused:", "NOTHING IS OUT OF DATE"],
        "must_not": ["too old:", "broken:"],
    },
    "old": {
        "cli": "#!/bin/sh\necho 'usage: skill-manager home clone --from DIR'\nexit 0\n",
        "must": ["too old:", "predates"],
        "must_not": ["refused:", "broken:"],
    },
    "broken": {
        "cli": "#!/bin/sh\nexit 127\n",
        "must": ["broken:", "not a version problem"],
        "must_not": ["too old:", "refused:"],
    },
}


def bootstrap_script() -> Path:
    """The installed git-issue-workflow copy, or an explicit override."""
    override = os.environ.get("BOOTSTRAP_HOME_SH")
    if override:
        return Path(override)
    home = os.environ.get("SKILL_MANAGER_HOME") or str(Path.home() / ".skill-manager")
    return Path(home) / "skills/git-issue-workflow/scripts/bootstrap-home.sh"


def seed(mode: str, body: str, script: Path, source: Path) -> dict:
    tmp = Path(tempfile.mkdtemp(prefix=f"goal-cause-{mode}-"))
    try:
        repo = tmp / "checkout"
        repo.mkdir()
        subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
        fake = tmp / "fake-cli"
        fake.write_text(body)
        fake.chmod(0o755)

        # SKILL_MANAGER_HOME is UNSET and --source is explicit. Pointing the
        # home variable at a temp path makes bootstrap die on "source home does
        # not exist" before it ever probes the CLI, which scores every mode 0
        # for a reason that has nothing to do with the goal. Measured that way
        # first; the fixture was wrong, not the product.
        env = dict(os.environ)
        env.pop("SKILL_MANAGER_HOME", None)
        env["SKILL_MANAGER_CLI"] = str(fake)
        proc = subprocess.run(
            ["bash", str(script), "--root", str(repo), "--source", str(source)],
            capture_output=True, text=True, env=env, timeout=300,
        )
        out = proc.stdout + proc.stderr
        said = [m for m in MODES[mode]["must"] if m in out]
        wrong = [m for m in MODES[mode]["must_not"] if m in out]
        return {
            "mode": mode,
            "exit": proc.returncode,
            "names_its_cause": len(said) == len(MODES[mode]["must"]) and not wrong,
            "markers_found": said,
            "markers_of_other_modes": wrong,
            "relayed_the_cli_words": "It said:" in out,
            "excerpt": "\n".join(
                l for l in out.splitlines()
                if any(k in l for k in ("refused:", "too old:", "broken:", "It said:"))
            )[:600],
        }
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    script = bootstrap_script()
    # Read only, and only ever read: --source is a copy source. Any existing
    # home serves; the project home is the one a ticket agent would clone.
    source = Path(os.environ.get("GOAL_SOURCE_HOME")
                  or (Path.cwd() / ".skill-manager"))
    if not source.is_dir():
        print(json.dumps({"error": f"no source home at {source}; set GOAL_SOURCE_HOME"}, indent=1))
        return 2
    if not script.is_file():
        print(json.dumps({"error": f"bootstrap-home.sh not found at {script}"}, indent=1))
        return 2

    results = [seed(m, spec["cli"], script, source) for m, spec in MODES.items()]
    named = sum(1 for r in results if r["names_its_cause"])
    excerpts = [r["excerpt"] for r in results]
    report = {
        "goal": "GOAL-a-failure-names-its-cause",
        "metric": "of 3 seeded failure modes, how many produce a message naming the true cause",
        "harness": str(script),
        "source_home": str(source),
        "value": f"{named} of 3",
        "target": "3 of 3",
        "met": named == 3,
        # Three reports that each name A cause but the SAME one would score 3
        # while being the exact defect this goal is about.
        "all_three_distinct": len(set(excerpts)) == 3,
        "modes": results,
    }
    print(json.dumps(report, indent=1))
    return 0 if report["met"] and report["all_three_distinct"] else 1


if __name__ == "__main__":
    sys.exit(main())
