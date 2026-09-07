#!/usr/bin/env python3
"""Rewrite a built case with this machine's plugins list and environment.

Both are machine-specific, which is why they are GENERATED into the build tree
and never committed: what is in git is what MAKES the environment.
"""
import re, sys, pathlib

case, plugins, path, tmpdir, home = sys.argv[1:6]
p = pathlib.Path(case); s = p.read_text()

# (?m) ONLY. With (?s) the `.` matches newlines and this eats the rest of the
# file -- which surfaced as "execution.prompt is required" and cost a run.
s = re.sub(r"(?m)^plugins:\n(?:  - .*\n)+", "plugins:\n" + plugins + "\n", s)

# NO env HERE. `claude plugin eval` refuses it:
#     execution.env key "PATH" is not allowed — only EVAL_* keys can be set
#     from case.yaml. Anything else must come from the operator's shell.
# So PATH/TMPDIR/SKILL_MANAGER_HOME are exported by run.sh, which IS the
# operator's shell for a run. Cost $0.00 to learn: a case that fails to load
# never starts an agent.
