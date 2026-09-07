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
PLUGINS = re.compile(r"(?m)^plugins:\n(?:  - .*\n)+")

# ASSERT THE MATCH, THEN WRITE. Both halves were missing and the second is why:
# this script computed the new text and never wrote it, so it was a no-op from
# the day it was added. Nothing caught that, because the committed list
# happened to match the units the build then produced -- and when the source
# home changed from the project home (10 units) to the root home (25), the case
# still named ../../units/eval-skill and the whole run failed to load.
#
# A rewrite that silently does nothing is the same shape as a patch applied
# without asserting its anchor, which has now cost this session three times.
if not PLUGINS.search(s):
    sys.exit(f"rewrite-case: no `plugins:` block in {case} -- refusing to "
             f"write a case whose unit list was not replaced")
s = PLUGINS.sub("plugins:\n" + plugins + "\n", s)
p.write_text(s)

# NO env HERE. `claude plugin eval` refuses it:
#     execution.env key "PATH" is not allowed — only EVAL_* keys can be set
#     from case.yaml. Anything else must come from the operator's shell.
# So PATH/TMPDIR/SKILL_MANAGER_HOME are exported by run.sh, which IS the
# operator's shell for a run. Cost $0.00 to learn: a case that fails to load
# never starts an agent.
