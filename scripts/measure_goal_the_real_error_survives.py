#!/usr/bin/env python3
"""GOAL-the-real-error-survives — is the CLI's own error present in skt's output?

Baseline: absent, truncated (#264, second defect). `skt ticket new` relays a
failed bootstrap by printing ONE line of it, and bootstrap's failure report
ends with a remedy — so the substantive lines (`refused:`, the cause, and the
CLI's own words under `It said:`) are exactly the ones cut.

The fixture is a home with a CONTROLLABLE bootstrap-home.sh that emits a
report shaped like the real cross-home refusal and exits non-zero. That is
deliberate: driving the real bootstrap would measure bootstrap's reporting,
which is a DIFFERENT goal (GOAL-a-failure-names-its-cause) and already met.
This one is about what skt does with an error that is already good.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

# Shaped like the real report: cause first, the CLI's own words in the middle,
# a remedy LAST. The trailing remedy is what makes "print the last line" look
# like it is working.
REPORT = """\
error: SKILL_MANAGER_CLI cannot bootstrap this home.
    refused: /some/home/bin/cli/skill-manager
             it is the entrypoint of the home /some/home, which binds that
             home and will not act on another. NOTHING IS OUT OF DATE.
             It said:
               SENTINEL-CROSS-HOME-REFUSAL
  Point SKILL_MANAGER_CLI at a skill-manager BUILD rather than at a home's
  entrypoint, or unset SKILL_MANAGER_HOME so nothing is being aimed elsewhere.
"""

# The substantive half. "Present" means the reader can see WHAT went wrong and
# WHAT the CLI said, not merely that something did.
SUBSTANTIVE = ["refused:", "NOTHING IS OUT OF DATE", "SENTINEL-CROSS-HOME-REFUSAL"]


def main() -> int:
    # MEASURE WHAT RUNS, which is the INSTALLED plugin -- not the vendored
    # skill-publisher-skill/ tree in this repository. That distinction cost a
    # whole measurement: the vendored copy is 0.3.0 and the installed plugin is
    # 0.8.1, so pointing the harness at the source tree scored this goal
    # "absent" against an artifact nobody executes, while the running skt had
    # relayed correctly since HBR-3. A vendored copy is a snapshot, and a goal
    # measured against a snapshot answers a question nobody asked.
    home = Path(os.environ.get("SKILL_MANAGER_HOME") or (Path.cwd() / ".skill-manager"))
    skt_src = Path(os.environ.get("SKT_SRC") or (home / "plugins" / "skt" / "src")).resolve()
    if not (skt_src / "skt" / "ticket.py").is_file():
        print(json.dumps({
            "error": f"no installed skt at {skt_src}; set SKT_SRC to the src/ of the "
                     "skt that actually runs"}, indent=1))
        return 2

    tmp = Path(tempfile.mkdtemp(prefix="goal-error-survives-"))
    try:
        repo = tmp / "repo"
        repo.mkdir()
        subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
        (repo / "seed").write_text("x\n")
        # .skill-manager is gitignored in a real checkout, and it has to be here
        # too: `epic_new` refuses a dirty tree, so an untracked home would stop
        # the run before the relay this measures. Measured that way first.
        (repo / ".gitignore").write_text(".skill-manager/\n")
        subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
        subprocess.run(
            ["git", "-c", "user.email=t@t", "-c", "user.name=t",
             "commit", "-qm", "seed"], cwd=repo, check=True)

        # The home skt resolves bootstrap-home.sh out of, with a controllable one.
        fixture_home = repo / ".skill-manager"
        scripts = fixture_home / "skills" / "git-issue-workflow" / "scripts"
        scripts.mkdir(parents=True)
        boot = scripts / "bootstrap-home.sh"
        boot.write_text("#!/bin/sh\ncat <<'EOF' >&2\n" + REPORT + "EOF\nexit 1\n")
        boot.chmod(0o755)

        env = dict(os.environ)
        env["PYTHONPATH"] = str(skt_src)
        env["SKILL_MANAGER_HOME"] = str(fixture_home)
        proc = subprocess.run(
            [sys.executable, "-c",
             "import sys; from skt import ticket; "
             "sys.exit(ticket.epic_new('GOAL-4', None, sys.argv[1]))",
             str(tmp / "wt")],
            cwd=repo, capture_output=True, text=True, env=env, timeout=300,
        )
        out = proc.stdout + proc.stderr
        present = [m for m in SUBSTANTIVE if m in out]
        report = {
            "goal": "GOAL-the-real-error-survives",
            "metric": ("is the underlying CLI error present in skt's rendered "
                       "output for a seeded cross-home refusal"),
            "value": "present" if len(present) == len(SUBSTANTIVE) else "absent -- truncated",
            "target": "present",
            "met": len(present) == len(SUBSTANTIVE),
            "substantive_lines_expected": SUBSTANTIVE,
            "substantive_lines_present": present,
            "substantive_lines_missing": [m for m in SUBSTANTIVE if m not in out],
            "skt_output": out.strip()[:1200],
        }
        print(json.dumps(report, indent=1))
        return 0 if report["met"] else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
