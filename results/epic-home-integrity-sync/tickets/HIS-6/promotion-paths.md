# Phase 3 — all four promotion paths, each with a case

**Ticket:** HIS-6 (#218). **Build under test:** `skill-manager 0.24.0+g17648705e67b`,
`17648705e67b (refs/heads/feature/218-his-6)` — the epic tip. Every transcript in
`evidence/promotion-paths-up.txt` came from that build and no other.

> **The owner's direction, in full, because a design that satisfies half of it has
> failed the ticket:** *"We need to be enforcing change management of the root skill
> manager from project home. However, we should still be able to update the root."*
> Both halves get a case below, and both pass.

## Why this is a synthetic three-tier stack and not the operator's homes

The rule this ticket is held to forbids writing the operator's root home and project
home. A promotion path cannot be exercised read-only — it is a write by definition —
so the stack is built from scratch inside this worktree:

```
scratch/his6/rootH/.skill-manager        the "root" tier
scratch/his6/proj/.skill-manager         the "project" tier
scratch/his6/proj/wt/.skill-manager      the "worktree" tier
```

Two throwaway git-backed units, `his6-probe` (the unit promoted) and `his6-control`
(the divergence control), plus `his6-direct` for the direct-root case.

**What that costs, stated rather than buried:** the tiers are structurally right and
the homes are real homes made by the real CLI, but they are small. A promotion that
only fails at 21 units or against a registered project is not reachable here. The
one measurement I have on a REAL tier is read-only and is reported in the scorecard
under `GOAL-one-home-one-answer`.

---

## Path 3 (DOWN): root → project — `home clone`

```
$ skill-manager home clone --from …/rootH/.skill-manager --to …/proj/.skill-manager
    copied:      44 dirs, 63 files, 0 links (61829 bytes; …)
    re-anchored: 0 links relativized, 2 records, 0 provisioned files
  ✓ cloned home … nothing in it resolves back to …/rootH/.skill-manager
    baseline:    recorded for 2 unit(s), so edits made here can be merged back
  EXIT=0
$ skill-manager home verify --home …/proj/.skill-manager       EXIT=0
```

`home verify` on the copy also prints the descent line — *"records that it was cloned
from …/rootH/.skill-manager, and names no parent store — so no foreign path in it is
sanctioned by that record"* — which is the clause-3 machinery of
`GOAL-one-home-one-answer` speaking without an operator-supplied flag. **PASS.**

## Path 4 (DOWN): project → worktree — `home clone`, and `bootstrap-home.sh`

```
$ skill-manager home clone --from …/proj/.skill-manager --to …/proj/wt/.skill-manager
    copied:      45 dirs, 71 files, 0 links (76790 bytes)
  EXIT=0
$ skill-manager home verify --home …/proj/wt/.skill-manager     EXIT=0
```

**PASS on the product path.** The *front door* is a separate question and it did not
pass — see DEF-119 in `findings.md`. Measured both ways against the REAL project home:

| arm | command | result |
| --- | --- | --- |
| CLI pinned to the epic tip | `SKILL_MANAGER_CLI=<wt>/skill-manager bash bootstrap-home.sh --root <dir>` | **exit 0**, `projected: 5 of 5`, home verified |
| CLI not pinned (what three eval agents did) | `bash bootstrap-home.sh --root <dir>` | **exit 1**, `clone verification FAILED — 5 path(s) reach outside this copy`, five `FOREIGN_HOME` on `bin/cli/{computeq,helm-deploy,monitoring,tla-spec-dev,tlc2}`, no agent homes created |

The product is right and the script picks the wrong build. `pick_cli()` prefers
`command -v skill-manager`, which on this machine is the ROOT home's pinned shim,
which execs the main checkout's build. **The epic's own worktree front door does not
run the epic's build**, and every ticket agent in this epic was handed the pin as a
hand-written brief line rather than getting it from the tool.

## Path 1 (UP): worktree → project — `home sync`, targeted and whole-home

```
$ skill-manager home sync --from <wt> --to <proj> --unit his6-probe
    unit:        his6-probe  (only this unit was reconciled; the rest of the home was not visited)
    updated      skill:his6-probe — …a sync fast-forwards it to the source copy
  ✓ reconciled …  (unit his6-probe only)                                   EXIT=0
  bytes at the destination: "WORKTREE EDIT v2 — promoted by HIS-6"          MOVED
```

**CONTROL, in the same run:** `his6-control` had been edited on BOTH sides, so it
genuinely differs. It was left alone by the targeted sync, and the subsequent
whole-home sync held it back rather than overwriting it:

```
$ skill-manager home sync --from <wt> --to <proj>
  ! home sync: skill:his6-control held back — locally modified; re-run with --merge …
    1 unchanged, 0 updated, 0 new, 0 merged, 1 held back, 0 conflicted, …
  ! 1 unit(s) were not reconciled and were left exactly as they were        EXIT=0
  destination bytes: "PROJECT-SIDE DIVERGENT EDIT — must be held back"      STILL HELD BACK
```

**PASS**, and it is the same measurement that decides `GOAL-no-spurious-holdback`
clause 2 at the CLI level.

> **One thing to notice about that transcript.** A sync that reconciled nothing and
> says so in words still exits **0**. So does a targeted sync whose one selected unit
> was held back. An agent that branches on the exit code reads "done"; the only
> honest reading is the counts line. Recorded as DEF-120.

## Path 2a (UP): project → root — the ENFORCED direction

This is the path that no graph and no ticket exercised until 2026-08-24 and that
failed on its first hand-run (DEF-103).

```
$ skill-manager home sync --from <proj> --to <rootH> --unit his6-probe
    unit:        his6-probe  (only this unit was reconciled; …)
    updated      skill:his6-probe — …
  ✓ reconciled …/rootH/.skill-manager (unit his6-probe only)               EXIT=0
  bytes at the ROOT home: "WORKTREE EDIT v2 — promoted by HIS-6"           REACHED ROOT
$ skill-manager home verify --home <rootH>                                 EXIT=0
```

**PASS.** A change authored in a worktree home reached the root home through the
project home, one unit at a time, with the root home still verifying clean.

## Path 2b: a DIRECT root update must remain possible

The failure mode the owner's second sentence guards against is a design that makes
the enforced path work by making direct root updates impossible. Two cases:

```
$ skill-manager install …/unitsrc/his6-direct --yes     (at the ROOT home)
  ✓ installed his6-direct                                                   EXIT=0
  his6-direct present at the root home                    A DIRECT ROOT UPDATE IS STILL POSSIBLE

$ skill-manager sync his6-direct --yes                   (at the ROOT home)
  ✓ synced 1 unit(s) — 1 merged, 1 local-only (no shared upstream)          EXIT=0
  bytes at the root home: "DIRECT ROOT UPDATE v2"                           LANDED
```

The second is specifically `skill-manager sync <unit>` **at the root home** — the
command `skt sync` shells out to, and the one DEF-103 broke. **PASS.**

---

## Verdict

| path | direction | verdict | decided by |
| --- | --- | --- | --- |
| worktree → project | up | **PASS** | targeted sync moved the bytes; the divergent control was held back |
| project → root | up | **PASS** | targeted sync reached the root home; root still verifies clean |
| root → project | down | **PASS** | `home clone` exit 0, verify exit 0, descent recorded |
| project → worktree | down | **PASS on the product path**, **FAIL on the front door** | `home clone` exit 0; `bootstrap-home.sh` exit 1 unless the CLI is pinned by hand (DEF-119) |
| direct root update | — | **PASS** | install and `sync <unit>` both land at the root tier |

**Four paths, five cases, one failure — and the failure is in the epic's own
scaffolding rather than in the product.** That distinction matters for the scorecard
and it does not soften the finding: three of four evaluation agents dispatched by
this ticket hit it, in a repository whose every ticket begins with that script.
