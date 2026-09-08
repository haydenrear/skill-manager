#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
. "$ROOT/lib.sh"
eval_run_case "${EVAL_BUILD_ROOT:-/private/tmp/skill-evals}/$(basename "$HERE")" \
              "$(basename "$HERE")" "$@"
