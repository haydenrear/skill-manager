# HIS-4 probes — the synthetic reproduction, before and after

Everything here was built in a scratch directory. **The operator's root home
and project home were never written to**; the only commands run against them
were `git ls-tree`, `git cat-file`, `git status` and `ls`, all read-only, to
establish the shape the fixture had to imitate.

`repro.sh <scratch-dir>` is the whole probe. It builds a root home, a provider
unit, a consumer unit whose store links are TRACKED at mode `120000`, a project
whose child home materializes both, an upstream that moves, and then runs
`sync`. `MODE=A|B|C|D` selects which upstream change and whether an agent edit
is present:

| mode | what upstream does | why |
| --- | --- | --- |
| `A` | ordinary content change | the plain case: does a materialized unit sync at all |
| `B` | the managed-bindings **migration** — stops tracking the scaffold link, keeps only the `.gitignore` | the real units' actual history |
| `C` | **re-points** a store link the unit's `.gitignore` does NOT cover | the shape that reaches the stash/pop conflict |
| `D` | as `C`, plus **an agent edits `SKILL.md` in the child copy** | the negative control |

## What the operator's homes actually look like (read-only, 2026-08-21)

```
$ git -C ~/.skill-manager/skills/deploy-helm ls-tree HEAD test_graph/
100644 ... test_graph/.gitignore          <- 10 entries, and NONE of the three links
...
$ git -C ~/.skill-manager/skills/deploy-helm cat-file -p 29353bce | tail -6
# TEST-GRAPH-MANAGED-BINDINGS-BEGIN
# Generated runtime links; provider-bindings.json is the durable record.
/build-logic
/sdk
/standard-nodes
# TEST-GRAPH-MANAGED-BINDINGS-END
```

So the managed-bindings block **is committed upstream today** and the links are
**not tracked today**. The measured index stages at mode `120000` therefore came
from a baseline commit that *predates* that migration — which is why the fixture
tracks the link with `git add -f` and then has upstream move. Written down
because it is the one piece of archaeology that is easy to get backwards, and an
earlier draft of the ticket did get it backwards.

## BEFORE — the defect, reproduced at project tier

Build `dc97f2a` (epic tip), `MODE=C`, verbatim:

```
--- child git status               (after `project resolve` — CHM-5 has run)
 D shared-docs
 D test_graph/build-logic

### sync in the CHILD home
exit=7
✗ scaffolded-unit has extra local changes (working tree edits, or commits ahead of
  the installed baseline) — sync would overwrite them.
✗   re-run with: ... sync scaffolded-unit --merge

### second sync
exit=7        <- identical. The unit is never syncable again.
```

Then the **printed remedy**, which is the thing the acceptance is about:

```
### the PRINTED REMEDY: sync --merge
exit=8
✗ scaffolded-unit: merge conflict in 0 file(s):
✗   resolve in <store>, then `git add` + `git commit`; back out with `git merge --abort`.
✗     - MERGE_CONFLICT: stash pop conflict after merging ... — local changes preserved at stash@{0}

--- status            (empty)
--- ls-files -u       (empty)          <- nothing to `git add`
--- MERGE_HEAD? no                     <- nothing to `git commit`
--- HEAD f52ffb7c                      <- the merge LANDED
--- stash  stash@{0}: On main: skill-manager-sync
--- record  gitHash 3dd070e5           <- LINK 3: the baseline never moved
            errors [{"kind": "MERGE_CONFLICT", ...}]
```

`HEAD f52ffb7c` against `record 3dd070e5` is link 3 reproduced exactly — the
same shape as the operator's `eab28837` against `c72d03a6`. And the remedy
printed for it names `git add` + `git commit` over a tree with no unmerged
paths and no `MERGE_HEAD`: **it cannot clear the state it is printed for.**

### The other pre-fix damage, which the issue did not name

`--merge` does not always conflict. When it "succeeds" it **silently undoes the
materialization**:

| mode | `test_graph/build-logic` in the child home after `--merge` |
| --- | --- |
| `A` | **reverted to a symlink** — the child home stopped being independent |
| `B` | **deleted outright** — upstream had untracked it, so the merge took the directory with it |

Neither was reported. This is `carryOverUnownedTrees`'s rule ("not compared, not
copied, **not destroyed**") broken on the git surface rather than the digest
surface, and it is why the fix escrows the trees across the merge instead of
merely excluding them.

## AFTER — same probe, same fixture, the fix as the only variable

| | A | B | C | D (agent edit) |
| --- | --- | --- | --- | --- |
| `sync` exit — **before** | 7 | 7 | 7 | 7 |
| `sync` exit — **after** | **0** | **0** | **0** | **7** (correct: real edit) |
| record `gitHash` == upstream HEAD | yes | yes | yes | — |
| `MERGE_CONFLICT` recorded | none | none | none | none |
| stash left behind | none | none | none | none |
| `build-logic` shape after | **DIRECTORY** | **DIRECTORY** | **DIRECTORY** | **DIRECTORY** |
| second sync | 0 | 0 | 0 | — |

`MODE=D` is the control that matters: the agent's edit still refuses the sync
(exit 7), the edit is still on disk afterwards, and the remedy the product
printed for THAT refusal (`sync --merge`) exits 0 **and keeps the edit**. The
exclusion narrows the question; it does not answer "clean".

## What is NOT reproduced here

The measured index shape — stages 1 and 3 at mode `120000` with stage 2 absent —
was **not** reproduced. What this fixture produces is a stash-pop conflict with
`ls-files -u` empty and no `MERGE_HEAD`, which matches the operator's state in
every respect an operator can act on (a `MERGE_CONFLICT` record, an abandoned
stash, a stranded baseline, a remedy that does nothing) but not in the exact
index stages. Deriving those stages needs the unit's real pre-migration history,
which was cleared by hand on 2026-08-20 and is not recoverable. Recorded as
DEF-013 rather than claimed.
