# Wide round 4 — the over-budget fixes, measured

Results: `runs/wide/2026-09-14T16-*` (9 invocations, one kept build) · Claude
Code 2.1.270 · the 25 cases the fixes touched × 2 runs, `-j 4` · **$24.70** ·
overrides: git-epic-workflow a2c4962, git-issue-workflow 3d32fd1,
spec-double-compiler 57068be, **skt (plugin) 189406f**.

Compared with each case's most recent previous round (3 or 3b).

## Headline

| on these 25 cases | before | round 4 |
|---|---:|---:|
| mean score | 0.77 | **0.90** |
| green on both runs | 2 | **10** |
| budget-graded runs over budget | 34 / 42 | **22 / 42** |
| turns per run, median (mean) | 13 (13.9) | **9 (9.3)** |
| cases passing every deciding verdict | 20 / 22 | 21 / 22 |

## Why runs went over budget, and what was changed

A read of 59 over-budget runs (~615 Bash calls) found that most of the spend
was the environment or a doc misinforming a correct-minded agent, not the
agent wandering:

| cause | calls (est.) | fix |
|---|---:|---|
| the toolchain hook ran before the fixture and told 162 of 166 runs "THIS CHECKOUT HAS NO ./.skill-manager" | 40–60 | hook also asks the fixture source; names the `Base directory for this skill:` line (9b427aaa) |
| validators documented with an unfilled `<git-epic-workflow-skill>` placeholder, `python3` without PyYAML, positional assignment path | ~90 | exact `uv run --script "<base dir>/scripts/..."` + `--assignment` (git-epic a2c4962) |
| git-issue-workflow's front-door probe tested `$SKILL_MANAGER_HOME` (unset); `--dirty-ok` undocumented | ~25 + greps | `command -v skt` first; `--dirty-ok` and exit-79 home mismatch documented (git-issue-workflow 3d32fd1) |
| untracked `.eval-bin/` made every workspace dirty → clean-tree refusals → `fix:` line chases | ~35 | `.git/info/exclude` in the fixture hook; prompts say a `fix:`/log after that refusal is expected |
| a plugin's own `references/` never reached a run; skt had no `[plugins.x]` example | 13+/run | plugin references placed in wrappers (807fa8fd); example added (skt 569088b) |
| missing facts: "unverifiable" meaning, drift `--ack --home=`, install vs sync | 5–19 | skt SKILL.md (189406f); drift fixture corrected to the remedy skill-manager prints today (72f4ccc4) |
| missing fixtures / prompts inviting search (TICKET-9, the plan) | 4–15 | prompt lines (9b427aaa) |

Biggest movers: `w-giw-wt-refusal-quotes-subject` 11→4 turns,
`w-skt-migration-delete-project-block` 17→3–6 (0.29→1.00),
`w-giw-exit6-is-unreadable-frontmatter` 13→6–7, the epic validator cases 21→9
(`plan-free-form-lane` 0.75→1.00, `assignment-no-force-on-blocking`
0.88→1.00), `w-misc-debug-bounded-wait` 0.25→1.00,
`w-sdc-attribution-before-close` 0.64→1.00.

## Per case

| case | score | deciding | green | over budget | turns |
|---|---|---|---|---|---|
| w-epic-assignment-no-force-on-blocking | 0.88→1.00 | 2/2→2/2 | 0→2 | 2→0 | 11,12→7,7 |
| w-epic-force-when-owner-decided | 0.86→0.86 | 4/4→4/4 | 0→0 | 2→2 | 16,18→10,9 |
| w-epic-merged-by-is-epic-owner | 0.83→1.00 | 2/2→2/2 | 0→2 | 2→0 | 21,23→11,9 |
| w-epic-plan-free-form-lane | 0.75→1.00 | 2/2→2/2 | 0→2 | 2→0 | 23,21→9,9 |
| w-epic-retire-part-of-goal-warns-only | 0.88→0.88 | 2/2→2/2 | 0→0 | 2→2 | 17,21→10,12 |
| w-giw-bootstrap-cross-home-is-not-old-cli | 0.83→**0.58** | 2/2→**1/2** | 0→0 | 2→2 | 22,19→11,17 |
| w-giw-epic-ticket-plan-values-win | 0.83→0.83 | 2/2→2/2 | 1→1 | 0→0 | 11,11→11,11 |
| w-giw-epic-ticket-stops-on-wrong-pr-base | 1.00→1.00 | 2/2→2/2 | 2→2 | 0→0 | 8,8→7,7 |
| w-giw-exit6-is-unreadable-frontmatter | 0.86→1.00 | 2/2→2/2 | 0→2 | 2→0 | 13,13→7,6 |
| w-giw-wt-close-no-force-on-unpublished | 0.86→**0.71** | 2/2→2/2 | 0→0 | 2→2 | 11,16→10,9 |
| w-giw-wt-new-dirty-ok | 0.89→0.94 | 4/4→4/4 | 0→1 | 2→1 | 18,20→9,8 |
| w-giw-wt-refusal-quotes-subject | 0.88→1.00 | 2/2→2/2 | 0→2 | 2→0 | 12,11→4,4 |
| w-giw-wt-stale-branch-point | 0.33→0.92 | 0/2→2/2 | 0→1 | 2→1 | 11,11→11,7 |
| w-harness-smoke | 1.00→1.00 | – | 2→2 | – | 2,2→2,2 |
| w-misc-debug-bounded-wait | 0.25→1.00 | – | 0→2 | – | 3,4→3,3 |
| w-misc-plugin-repo-finalize-sh | 0.92→0.83 | 2/2→2/2 | 1→0 | 1→2 | 9,10→11,9 |
| w-sdc-attribution-before-close | 0.64→1.00 | 2/2→2/2 | 0→2 | – | 21,21→23,18 |
| w-skt-migration-delete-project-block | 0.29→1.00 | 0/2→2/2 | 0→2 | – | 17,17→3,6 |
| w-skt-not-installed-is-not-not-synced | 0.83→0.83 | 2/2→2/2 | 0→0 | 2→2 | 17,17→20,16 |
| w-sm-cold-shim-means-build | 0.92→0.83 | 2/2→2/2 | 1→0 | 1→2 | 7,9→15,8 |
| w-sm-drift-ack-once | 0.83→0.83 | 2/2→2/2 | 0→0 | 2→2 | 17,13→11,10 |
| w-sm-sync-retired-name-redirects | 0.83→0.83 | 2/2→2/2 | 0→0 | 2→2 | 12,10→10,9 |
| w-sm-sync-skt-when-absent | 0.75→0.88 | 2/2→2/2 | 0→1 | 2→1 | 11,9→7,4 |
| w-sm-verify-is-not-currency | 0.67→0.75 | 2/2→2/2 | 0→0 | 2→1 | 14,27→16,7 |

(The previous-round columns for `w-sm-*`, `w-skt-not-installed` and
`w-misc-plugin-repo-finalize-sh` are round 3; the rest are round 3b.)

## The operator's root home, and where it was being written from

`~/.skill-manager/bin/cli/{computeq,helm-deploy,monitoring}` were rewritten to
point into test-graph artifacts twice today (reports 20260914-123718 and
20260914-152657). Repaired (`home repair --fix`, 6/6; `home verify` clean; a
fresh clone exits 0).

The source: commit-diff-context-parent's `cdcWorktreeOverlayIsolation` graph,
node `cdc_skill_manager_worktree_provisioned.py`, clones a checkout home whose
`bin/cli` entries are links into the root home, then runs an unforced
`skill-manager sync`. deploy-helm's installer writes
`$SKILL_MANAGER_HOME/bin/cli/<tool>` with `cat >`, which follows the link into
the root. skill-manager only takes ownership of an inherited link before a
producer runs when forced (`InstallerRegistry.installOne`, HIS-7).

* Stopgap, committed: `--force-scripts` on that sync (commit-diff-context-parent
  `fix/graph-sync-force-scripts`, bcd3cd73).
* Product fix, NOT made: taking ownership on every install would make
  producers that decline when a tool is present run anyway in every child
  home, breaking the shared-toolchain design the "SANCTIONED mirror" test
  guards. The real fix is a producer that never writes THROUGH a link — a
  design change for skill-manager, recorded here.

## Remaining reds, read against their transcripts

The transcript review agent hit a session rate limit, so these were read
directly from the round-4 diagnostics (commands.txt + final reply per run).

| case | class | what it was | done |
|---|---|---|---|
| w-giw-bootstrap-cross-home-is-not-old-cli | CASE | both replies right ("not a stale CLI… nothing to upgrade") and the fix issued was the documented `SKILL_MANAGER_CLI=…` re-run; the forbid matched `echo "--- install dir export? ---"; grep … /opt/homebrew/bin/skill-manager` | forbid anchored to real upgrade/reinstall commands, 7/7 spot tests (5bed4475) |
| w-giw-wt-close-no-force-on-unpublished | CASE | run 2 surfaced the blocker, named the publish remedy, did not force; judges failed it for not "asking" | an offer or question counts; trying the publish fix allowed (5bed4475) |
| w-sm-verify-is-not-currency | ENV (harness) | the override copies carry no `.git`, so the four overridden units' checkouts resolve to the fixture repo's commit and read as record ≠ checkout; agents correctly said "not current" on a premise the harness manufactured | **not fixed** — recorded; a case about currency should not share a build with unit overrides |
| w-giw-epic-ticket-plan-values-win | VARIANCE | one run 1/3 judge votes on a reply that takes the plan's values; grader already clarified twice | left |

### Still over budget — where the calls went

| case | biggest remaining waste | fix |
|---|---|---|
| w-epic-force-when-owner-decided (3) | 2–3 calls reading `validate_epic_plan.py` source to learn `--force` | flags listed beside the command (git-epic ca8c981) |
| w-epic-retire-part-of-goal-warns-only (4) | `--help` for flags, then reading references after the answer | same |
| w-giw-wt-new-dirty-ok (4), w-giw-wt-stale-branch-point (3) | `skt ticket new --help` | usage line incl. `WT_DIRTY_OK=1` (git-issue-workflow e5404f9) |
| w-skt-not-installed-is-not-not-synced (3) | loaded skt:skill-manager (not skt:skt), read ticket.py for the coordinate, retried `install` with `--home` variants | install-vs-sync and "install has no --home" in the skill-manager skill (skt 39a999d) |
| w-sm-sync-skt-when-absent (3) | one run hunted the fixture with `find` | same doc; the other run was 2 calls |
| w-sm-cold-shim-means-build (3), w-sm-drift-ack-once (3), w-sm-sync-retired-name-redirects (4) | verifying and retrying after the sandbox refused the write, re-running `skt check` after a sync | none — the prompt already says "issue once"; this is the agent's verification habit, left visible |
| w-misc-plugin-repo-finalize-sh (4) | finalize.sh resolves its dependency skills under a HOME the sandbox does not have | ENV, recorded |

Round 5 reruns only the nine cases these follow-up fixes touch.
