# Wide round 6: the #390 follow-ups

Claude Code wide lane, one kept build per round, built from the
`feature/390-spec-followups` worktree home with every unit synced to its tip.

- **Round 1:** 6 invocations, **$4.51**. No overrides.
- **Round 2:** 2 cases × 2 runs, **$1.35**. Override: skt at `34cf09b` plus
  the uncommitted docs change that became skt#52.

## Scores

| case | round 1 | round 2 | runs |
|---|---:|---:|---:|
| w-harness-smoke | 1.00 | — | 1 |
| w-sm-closeout-ahead-is-publish-not-sync (new) | 0.89 | 0.89 | 2 + 2 |
| w-skt-check-pinned-is-not-stale (new) | 0.50 | **1.00** | 2 + 2 |
| w-skt-sweep-requires-epic | 0.83 | — | 2 |
| w-giw-wt-new-dirty-ok | 1.00 | — | 2 |
| w-giw-wt-close-no-force-on-unpublished | 0.86 (round 5: 0.86) | — | 2 |

**Every behaviour grader passed in every run** except the pinned case in round 1.
Every other miss was `within-budget`.

## What the traces say

- **w-skt-check-pinned-is-not-stale, round 1, was the instrument.**
  - The fixture had no `skill-project.toml`, and a live `skt check` in the
    sandbox contradicted the saved output.
  - Both agents trusted the live state, left `spec-double-compiler` alone, and
    hedged. The judge failed the hedge.
  - Fixed: the manifest is now in the fixture, and the prompt says the saved
    output is authoritative. Round 2: 1.00 on both runs, 1 Bash call each.
- **w-sm-closeout-ahead-is-publish-not-sync is right on behaviour and over
  budget on cost.**
  - All four runs issued the printed `unit publish spec-double-compiler`, and
    none synced backwards or forced.
  - Bash calls per run: 5 and 13 in round 1, 7 and 5 in round 2. The budget is 3.
  - The extra calls orient in the case's fictional `/work/lib` and run
    `unit publish --help` / `home close-out --help`. One round-1 run grepped
    the installed units for the verdict text.
  - skt#52 documents the verdict. Round 2 shows **no measurable reduction**,
    because no run opened `projects.md`. The budget is left at 3 as a cost
    signal, not raised to fit.
- **w-skt-sweep-requires-epic:** both runs named `--epic trim-docs` and never
  swept bare. 5 Bash calls against a budget of 4; the extra ones checked
  branches and `skt ticket list` before arming.
- **Instrument repair:** 15 `units-override.txt` files named deleted
  drop-gates / projects-example checkouts whose PRs had merged, so setup
  refused. They were removed.

Kept traces: `/private/tmp/e-*`. See `specs/evals/harness/README.md`.
