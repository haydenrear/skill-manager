#!/usr/bin/env bash
# RUN the wide lane: every `w-*` case (or a narrower glob) in one invocation,
# against the one build setup.sh made.
#
#   ./run.sh                 all wide cases, 1 run each, capped at $25
#   ./run.sh 'w-epic-*'      a subset
#   EVAL_RUNS=3 ./run.sh 'w-epic-validator-*'    repeat the noisy ones
#   ./run.sh 'w-*' --keep    keep the build and sandboxes to read traces
#
# The glob MUST start with `w-`: discovery recurses into symlinked units, and
# spec-double-compiler ships its own example eval cases.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
. "$ROOT/lib.sh"

GLOB="w-*"
case "${1:-}" in w-*) GLOB="$1"; shift ;; esac
case "$GLOB" in w-*) ;; *) echo "run: glob must start with w- (got $GLOB)" >&2; exit 1 ;; esac

EVAL_CASE_GLOB="$GLOB" \
EVAL_MAX_COST_USD="${EVAL_MAX_COST_USD:-25}" \
EVAL_CONCURRENCY="${EVAL_CONCURRENCY:-3}" \
  eval_run_case "${EVAL_BUILD_ROOT:-/private/tmp/skill-evals}/wide" wide "$@"
