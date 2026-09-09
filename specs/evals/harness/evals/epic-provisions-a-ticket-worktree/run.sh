#!/usr/bin/env bash
# The shared runner, like every other case.
#
# This case kept a bespoke run.sh from before lib.sh had one, and the cost of
# that was invisible until the scorecard existed: it was the ONLY case whose
# result was never archived, so the goal ledger reported it NOT RUN after a
# sweep in which it had in fact run and scored 0.83. A copy that drifts is
# worse than a copy, because it looks like the thing it no longer is.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
. "$ROOT/lib.sh"
eval_run_case "${EVAL_BUILD_ROOT:-/private/tmp/skill-evals}/$(basename "$HERE")" \
              "$(basename "$HERE")" "$@"
