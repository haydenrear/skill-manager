# honest-pins baseline (HP-0, #408)

Measured on 2026-11-07 at `origin/epic/honest-pins` 1b52427487a0 (0.28.2 plus HP-1 #404),
with the HP-0 harness on top. Evidence: `2026-11-07-1b52427/`.

```
TESTGRAPH_CONTINUE_AFTER_FAILURE=1 /Volumes/dev/.honest-pins/graph-run.sh honest-pins --continue
#   = run.py honest-pins --continue with GRADLE_OPTS=-Dorg.gradle.daemon=false,
#     JAVA_TOOL_OPTIONS=-Djava.net.preferIPv6Addresses=true, COMPOSE_PROJECT_NAME=skill-manager
```

Sweep `20261107-172944`, run `20261107-173056`: graphs_executed=1, graphs_failed=1.
All 9 shape nodes executed. **Every setup_* and control_* assertion passed, and every node is
red only on its shape_* assertion(s).** `TESTGRAPH_CONTINUE_AFTER_FAILURE=1` is required:
without it the plan stops at pin-b and reports the other eight `skipped`.

## GOAL-a-pin-is-honoured: 1 of 5 shapes at the pin or refused by name

| shape | node | verdict | what happened |
| --- | --- | --- | --- |
| (a) #404 a unit bumps its own CLI pin | `cli-pin` graph | **honoured** | PASSED, sweep 20261107-173940 (HP-1 landed) |
| (b) #406 lock resolved_sha bumped | `honest.pin-b` | **off-pin** | `sync --lock` exit 0, HEAD/record/lock all at C (main's tip), pin was B |
| (c) #407 installed ahead of older pin | `honest.pin-c` | **off-pin** | `project resolve` exit 0, HEAD stays at B, units.lock resolved_sha B, pin A |
| (d) #407 same, one local edit that applies | `honest.pin-d` | **off-pin** | `project resolve` exit 0, HEAD stays at B (edit kept), pin A |
| (e) #103 MCP binary url changed, same bin_path | `honest.pin-e` | **off-pin** | gateway Provisioner returns the v1 binary (prints 1.0) for the v2 url |

## GOAL-the-record-describes-the-home: 5 of 5 measured shapes report falsely; every control arm detected

| shape | node | false/missing report | control arm |
| --- | --- | --- | --- |
| (a) #403 unit-changing `home sync` | `honest.record-a` | `home drift` pending=false after an `updated` sync | no-op sync: pending=false (green) |
| (b) #298 record version vs manifest | `honest.record-b` | after `home sync`: manifest 0.2.0, record 0.1.0; after `sync --from <dir>`: manifest 0.3.0, record 0.1.0 (2 clauses) | `sync --git-latest` rewrites the record (green) |
| (c) #409 system CLI as stale cli-shim | `honest.record-c` | `cli-shim:tar/sh` listed stale (sh is on PATH, never linked) | deleted `bin/cli/hp-own-probe` listed stale; present one not (green) |
| (d) #400 __pycache__ published | `honest.record-d` | `unit publish --direct` commits `__pycache__/tool.cpython-312.pyc` | the real SKILL.md edit is published (green) |
| (e) #394 stale cached manifest | `honest.record-e` | root sync removes `two`, which the project's own manifest declares; no audit entry in the child home (2 clauses) | `three`, undeclared everywhere, unmodified, is removed (green) |
| (f) #412 damaged unit .git | HP-10's node | unmeasured here | HP-10 adds it |

Second instrument, `skill-manager artifacts stale --json --kind cli-shim` on this repository's
project home (read-only, `SKILL_MANAGER_HOME=<repo>/.skill-manager`): **7 stale cli-shims**
(brew docker, gh, helm, k3d, kubernetes-cli, opentofu; pip pytest), all on PATH —
`2026-11-07-1b52427/project-home-artifacts-stale-cli-shim.json`. Matches #409's count.

## Files

- `2026-11-07-1b52427/honest-pins.json` — per-node status, failure detail and every assertion.
- `2026-11-07-1b52427/sweep.json` — the runner's sweep ledger for that invocation.
- `2026-11-07-1b52427/project-home-artifacts-stale-cli-shim.json` — the second instrument.
