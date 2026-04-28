#!/usr/bin/env bash
# Collect and surface test_graph run diagnostics for a CI job.
#
# Layout (all relative to the repo root the workflow is running in):
#
#   ci-diagnostics/
#     validation-reports/       <- build/validation-reports/<runId>/summary.json + envelope/*.json
#     sm-home/<tempname>/       <- copy of each $SKILL_MANAGER_HOME from this run
#                                  (gateway.log, gateway.pid, gateway-config.json,
#                                   test-graph/registry.log, etc.)
#     postgres.log              <- docker compose logs for the postgres service
#
# Then tails the interesting files inline so a failing run shows the root
# cause without having to click through to artifacts.
#
# Never exits non-zero — this step runs with `if: always()` and must not mask
# the real failure from the preceding `Run graph` step.

set -u

DIAG_DIR="ci-diagnostics"
mkdir -p "$DIAG_DIR"

# 1. Validation reports (per-node envelopes + summary.json)
if [[ -d test_graph/build/validation-reports ]]; then
  cp -r test_graph/build/validation-reports "$DIAG_DIR/" 2>/dev/null || true
fi

# 2. Per-run SKILL_MANAGER_HOME tempdirs. EnvPrepared.java creates
#    /tmp/sm-testgraph-<random>/ on Linux runners; there will usually be
#    exactly one per job.
mkdir -p "$DIAG_DIR/sm-home"
shopt -s nullglob
for d in /tmp/sm-testgraph-*; do
  [[ -d "$d" ]] || continue
  cp -r "$d" "$DIAG_DIR/sm-home/$(basename "$d")" 2>/dev/null || true
done
shopt -u nullglob

# 3. docker-compose postgres logs
docker compose logs postgres > "$DIAG_DIR/postgres.log" 2>&1 || true

# 4. Gradle / test_graph stdout+stderr (teed from the Plan/Run steps).
#    This is the only place node scripts that use ProcessBuilder.inheritIO()
#    (e.g. CiLoggedIn → skill-manager login) show up — their stderr never
#    hits registry.log or the validation envelope.
if [[ -d ci-logs ]]; then
  cp -r ci-logs "$DIAG_DIR/gradle" 2>/dev/null || true
fi

#
# Inline surfacing — group blocks so each file folds in the GH Actions UI.
# report.md is first because it's the at-a-glance run report with status
# + duration + per-node assertions/processes already aggregated. The
# raw summary.json + per-envelope dumps below are the deeper-dive
# fallbacks.
#

echo "::group::report.md"
for f in "$DIAG_DIR"/validation-reports/*/report.md; do
  [[ -f "$f" ]] || continue
  echo "--- $f ---"
  cat "$f"
  echo
done
echo "::endgroup::"

echo "::group::summary.json"
find "$DIAG_DIR/validation-reports" -name summary.json -print -exec cat {} \; 2>/dev/null || true
echo "::endgroup::"

# Failing/errored node envelopes: status is "passed" / "failed" /
# "errored" / "skipped" (see PlanExecutor.VALID_STATUSES). Earlier
# revisions of this script grepped for an "outcome" field that never
# existed — fixed alongside report.md plumbing.
echo "::group::failing / errored node envelopes"
if command -v jq >/dev/null 2>&1; then
  for f in "$DIAG_DIR"/validation-reports/*/envelope/*.json; do
    [[ -f "$f" ]] || continue
    status="$(jq -r '.status // empty' "$f" 2>/dev/null)"
    if [[ "$status" == "failed" || "$status" == "errored" ]]; then
      echo "--- $f (status=$status) ---"
      cat "$f"
      echo
    fi
  done
else
  for f in "$DIAG_DIR"/validation-reports/*/envelope/*.json; do
    [[ -f "$f" ]] || continue
    if grep -Eq '"status"[[:space:]]*:[[:space:]]*"(failed|errored)"' "$f"; then
      echo "--- $f ---"
      cat "$f"
      echo
    fi
  done
fi
echo "::endgroup::"

echo "::group::gateway.log"
for f in "$DIAG_DIR"/sm-home/*/gateway.log; do
  [[ -f "$f" ]] || continue
  echo "--- $f ---"
  tail -400 "$f"
  echo
done
echo "::endgroup::"

echo "::group::registry.log"
for f in "$DIAG_DIR"/sm-home/*/test-graph/registry.log; do
  [[ -f "$f" ]] || continue
  echo "--- $f ---"
  tail -400 "$f"
  echo
done
echo "::endgroup::"

echo "::group::gateway-config.json"
for f in "$DIAG_DIR"/sm-home/*/gateway-config.json; do
  [[ -f "$f" ]] || continue
  echo "--- $f ---"
  cat "$f"
  echo
done
echo "::endgroup::"

echo "::group::postgres.log (tail)"
tail -200 "$DIAG_DIR/postgres.log" 2>/dev/null || true
echo "::endgroup::"

# The Gradle/test_graph run log is where inheritIO() subprocesses land
# (skill-manager login from ci.logged.in, skill-manager gateway up from
# gateway.up, etc.). Surface a generous tail so root causes are visible
# without downloading the artifact.
echo "::group::gradle run.log (tail 600)"
[[ -f "$DIAG_DIR/gradle/run.log" ]] && tail -600 "$DIAG_DIR/gradle/run.log"
echo "::endgroup::"

echo "::group::gradle plan.log (tail 200)"
[[ -f "$DIAG_DIR/gradle/plan.log" ]] && tail -200 "$DIAG_DIR/gradle/plan.log"
echo "::endgroup::"

# Surface any other *.log files under the per-run homes we haven't already
# dumped explicitly (e.g. subprocess logs from individual MCP backends).
echo "::group::other logs"
while IFS= read -r -d '' f; do
  case "$f" in
    */gateway.log|*/test-graph/registry.log) continue ;;
  esac
  echo "--- $f ---"
  tail -200 "$f"
  echo
done < <(find "$DIAG_DIR/sm-home" -type f -name '*.log' -print0 2>/dev/null)
echo "::endgroup::"

exit 0
