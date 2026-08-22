# Worktree ledger — epic/home-integrity-sync

Rule 14: worktrees stand until the epic ends, then all of them go in one sweep
once the default-branch merge is verified. Unit state merges **early**, at wave
close; worktrees are deleted **late**, together. Removal must never be the step
that carries the merge.

| worktree | branch | ticket | created | close-out gate | standing |
| --- | --- | --- | --- | --- | --- |
| `../wt-227-his-10` | `feature/227-his-10` | HIS-10 (#227) | 2026-08-21, wave 3 | **clean** | **stands** |
| `../wt-216-his-4` | `feature/216-his-4` | HIS-4 (#216) | 2026-08-21, wave 4 | **clean** | **stands** |
| `../wt-226-his-9` | `feature/226-his-9` | HIS-9 (#226) | 2026-08-21, wave 4 | **clean** | **stands** |
| `../wt-161-his-12` | `feature/161-his-12` | HIS-12 (#161) | 2026-08-21, wave 4 | **clean** | **stands** |
| `../wt-186-his-11` | `feature/186-his-11` | HIS-11 (#186) | 2026-08-22, wave 5 | **clean** | **stands** |
| `../wt-232-his-14` | `feature/232-his-14` | HIS-14 (#232) | 2026-08-22, wave 5 | **clean** | **stands** |
| `../wt-235-his-15` | `feature/235-his-15` | HIS-15 (#235) | 2026-08-22, wave 6 | ✗ **3 units blocked** — see below | **stands** |

All gates re-run 2026-08-21 after the wave-4 merges, verbatim: *"holds nothing
that removing it would destroy."* Every ticket in waves 3 and 4 delivered
**product code**, which the PR carries; none edited a skill unit, so nothing had
to reach the project home through `home sync`. That is the expected shape for
this epic and it is worth stating rather than assuming — the gate exists because
the opposite shape is silent.

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


## `wt-235-his-15` — the one gate that refused, and why it is not synced

```
✗ removed-upstream  skill:deploy-helm            — not in the source home
✗ would-create      skill:skill-manager          — the source holds it, the destination does not
✗ would-create      plugin:skt                   — same
✗ conflicted        skill:spec-double-compiler   — "the two git histories have diverged"
✗ 3 unit(s) … would be lost if it were removed now
```

**The gate is right and the answer is still "do not sync."** This is residue of
DEF-046's escape — the `project` family resolves from the working directory,
which a JVM cannot change, so an in-process driver re-realized the worktree home
against *this repository's* project manifest. `deploy-helm` went away;
`skill-manager` and `skt` arrived. Neither belongs in the project home, which
deliberately holds four units and not those.

**Checked before concluding**, because "no authored work" is exactly the claim
that must not be assumed:

| | |
| --- | --- |
| main checkout | `git status --porcelain` empty |
| project home `.materialization` | last written **2026-08-14** — not re-realized |
| `~/.skill-manager` | no writes in the window |
| worktree `spec-double-compiler` | **same commit** as the project home's (`436c78c`), clean tree |

So the "diverged histories" verdict is `.git` bookkeeping, not content — the
same shape as DEF-001, and the fix line the gate prints says so itself
(`then resolve: .git (history)`).

**Disposition:** discarded deliberately at the epic sweep, with DEF-047 as the
written reason. Recorded here rather than resolved quietly, because rule 14's
whole point is that a worktree is never deleted without one.

**And the gate found what three other instruments missed.** The ticket's probes,
its reviewer, and my own containment check all asked *"did it write where it
should not?"*. The gate asks *"does this home hold something the tier above does
not?"* — a different question, and the only one that sees a unit quietly going
away.
