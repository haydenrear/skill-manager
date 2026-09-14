# Wide round 1 — not a measurement

`runs/wide/2026-09-14T00-41-49-741Z.json` · Claude Code 2.1.270 · 46 cases, 1 run
each, `-j 3` · **$6.20** · overrides: git-epic-workflow (4d6a335),
git-issue-workflow (9ae39f8), spec-double-compiler (82f5a9f).

**No score from this round says anything about a skill.** Every red is
attributed to the harness below, and the round is recorded so a later reader
counting reds does not count these.

| runs | what happened | cause | fixed by |
|---:|---|---|---|
| 27 | `run could not start (ENOSPC)` at $0 | the fixture hook byte-copied the ~5 GB workspace home into every run; three in flight plus sealed homes filled the disk to 6 GB free | `place-fixture.sh` clones (`cp -Rc`), measured 0 s / 0 MB for 2 GB |
| 15 | `Reached maximum number of turns` (4 or 6) — each run used exactly one more | budgets sized for one command, in a session carrying 24 units' skill listings; a ceiling hit is UNDECIDED per plugin_evals.md §4, not FAIL | every wide case raised to 10 / 12 turns, 420 s |
| 3 | `timed out after 180s`, one of them at 0 turns | the first ~100 s of each run went to the byte copy above | the clone, and 420 s |
| 1 | `w-harness-smoke` 0.25 — the command WAS recorded, `.eval/case` was not | its Stop hook ran while the disk was full; the diagnostics archived as `unknown` | the clone |

Still to read against their traces before they count as findings in a later
round: `w-misc-plugin-repo-finalize-sh` (finalize.sh not issued in 4 turns) and
`w-misc-debug-bounded-wait` (llm FAIL FAIL FAIL after loading the debugging
skill and one `ls`).

The instrument defect, stated once so it is not repeated: **a harness change
that multiplies per-run cost by the number of cases in flight was not measured
at `-j 3` before a billed round.** The smoke case ran alone.
