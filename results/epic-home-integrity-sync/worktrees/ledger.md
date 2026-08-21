# Worktree ledger — epic/home-integrity-sync

Rule 14: worktrees stand until the epic ends, then all of them go in one sweep
once the default-branch merge is verified. Unit state merges **early**, at wave
close; worktrees are deleted **late**, together. Removal must never be the step
that carries the merge.

| worktree | branch | ticket | created | close-out gate | standing |
| --- | --- | --- | --- | --- | --- |
| `../wt-227-his-10` | `feature/227-his-10` | HIS-10 (#227) | 2026-08-21, wave 3 | **clean** — *"holds nothing that removing it would destroy"*, run `22b947c`+ against the project home | **stands** |

## Waves 1 and 2 created no worktrees

Both were worked on branches in the main checkout. Recorded as a guardrail
override in `reviews/wave-1.md`, and retro-justified in `reviews/wave-2.md`: the
worktree route was not merely skipped for convenience, **it would have failed** —
DEF-004, then DEF-006.

## How a worktree home must be bootstrapped in this epic

**Not through the plain front door.** `bootstrap-home.sh` resolves its CLI from
the root home's pin, and that pin is a *released* build — 0.23.0 before
2026-08-21, 0.24.0 after. Both predate this epic, so both refuse the clone
(DEF-006, re-measured after the release re-run and **still refusing**).

```bash
git worktree add <path> -b <branch> epic/home-integrity-sync
SKILL_MANAGER_CLI=/Users/hayde/IdeaProjects/skill-manager/skill-manager \
  COMPOSE_PROJECT_NAME=skill-manager \
  ~/.skill-manager/skills/git-issue-workflow/scripts/bootstrap-home.sh --root <path>
```

Measured 2026-08-21: **exit 0 in 38 s**, `4 skill(s) servable`, all five
inherited parent-store shims kept, bare `home verify` exit 0.

This is a **workaround** and is recorded as one: it makes the tool under test
bootstrap its own test environment, which is exactly the circularity the root
home's pin exists to avoid.

## Deletions so far

`wt-def006-probe` — a throwaway created 2026-08-21 solely to re-measure DEF-006
after the release re-run, removed the same hour with its home. It carried no
ticket, no branch worth keeping (`probe/def006`, deleted), and no unit edits.
Recorded because rule 14 says the epic is not finished while a worktree it
created is standing without a reason — and equally, a worktree it deleted needs a
reason on the record.
