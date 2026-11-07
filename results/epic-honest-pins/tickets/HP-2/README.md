# HP-2 evidence (#406, #407)

Base: `origin/epic/honest-pins` 433f1090. Branch `feature/HP-2`.

## Local signal: `run.py honest-pins`, nodes pin-b, pin-c, pin-d

Expected effect: shapes (b), (c), (d) red -> green. Baseline (HP-0,
`results/epic-honest-pins/baseline/2026-11-07-1b52427/honest-pins.json`):
all three red on `shape_*`, every setup and control assertion green.

Graph result (`graph-run.sh honest-pins --continue`, sweep 20261107-184906,
run 20261107-184954; `sweep-honest-pins.json`, `honest-pins-local-signal.json`):

| node | baseline | HP-2 |
| --- | --- | --- |
| honest.pin-b | red (shape_*) | **green**, all 6 assertions incl. control_unchanged_lock_is_noop |
| honest.pin-c | red (shape_*) | **green**, all 5 incl. control_resolve_at_pin_keeps_pin |
| honest.pin-d | red (shape_*) | **green**, all 6 incl. control_local_edit_never_lost |
| pin-e, record-a..e | red (shape_*) | red on exactly the same shape_* assertions (other tickets' shapes); every setup/control green |

The graph task is FAILED overall only because of those other tickets' expected-red
nodes. GOAL-a on this branch: 1 of 5 -> 4 of 5 shapes (cli-pin from HP-1 plus b, c, d;
e remains for its ticket).

## Scratch-home reproduction of the three shapes (worktree build)

The same plant as the three nodes, run by hand with `./skill-manager` against
scratch homes and local git remotes (A, B, C on `main`):

| shape | before (HP-0 baseline) | after |
| --- | --- | --- |
| b: `sync --lock` with ref + resolved_sha bumped to B, main at C | rc 0, HEAD = C, lock = C | rc 0, HEAD = B, record = B, lock ref/resolved_sha = B, SKILL.md "marker B"; second `sync --lock` "no reconciliation needed"; a plain `sync <unit>` stays at B |
| c: unit ahead at B, pin A, no edit | rc 0, HEAD = B | rc 0, "pins: moved 1 unit(s)", HEAD = A, units.lock resolved_sha = A |
| d: as c plus one edit to NOTES.md | rc 0, HEAD = B, edit kept | rc 0, "moved … (local edits carried onto the pin)", HEAD = A, edit kept |

## Real home (scratch COPY of this repo's project home)

`installed/` backed up first (`installed.bak`, byte-compared afterwards). The
worktree's raw `./skill-manager`, `SKILL_MANAGER_HOME` = the copy,
`CLAUDE_HOME`/`CODEX_HOME` = scratch.

- `real-home-sync-lock.txt`: the live lock is a no-op; a desired lock with
  tracing-observability's resolved_sha moved one commit back (1669c89 ->
  aa6149c) dry-runs as `git-sync … pin=aa6149c… ref=main origin=https://github.com/haydenrear/tracing_skill`,
  then lands HEAD, record and lock on aa6149c (ref stays `main`, as the lock
  says). **The second `sync --lock` reports "disk state matches — no
  reconciliation needed."**
- `real-home-sync-to.txt`: `sync tracing-observability --to aa6149c` from
  1669c89 moves back ("moved 1669c89403 → pin aa6149c1cc"); **the second
  (plain) `sync tracing-observability` leaves HEAD at aa6149c** and the lock
  row reads ref = resolved_sha = aa6149c.

Incident, recorded as DEF-HP2-002: the copy carries the project's registration
and child-home record, which point at the REAL project home, so the first run's
post-sync claiming-project refresh materialized into it. Unit HEADs, worktrees,
`installed/` and `units.lock.toml` of the real home were verified unchanged; a
192 MB staging tree was left at
`/Users/hayde/IdeaProjects/skill-manager/.skill-manager/.materialization/tmp/plugin-tla-spec-dev-91d60ee5-…`
(not removed by hand). `real-home-sync-to.txt` was re-taken with the copy's
`projects/` and `child-homes/` detached.

## Unit

`jbang RunTests.java`: ALL PASSED (includes the new `SyncGitPinTest`, 10 cases:
newer pin short of the tip, older pin without and with an applying edit, a
conflicting edit refused with state preserved, a local commit refused, an
unavailable sha failing closed, ordinary git-latest unchanged, lock-row
mapping, and CLI `sync --lock` / `sync --to` with dry-run and second-run checks).
