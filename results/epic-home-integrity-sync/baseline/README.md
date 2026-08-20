# Baseline — epic/home-integrity-sync

Measured 2026-08-20 on the operator's real homes, at epic branch tip
`76434c7` (`epic/artifact-dag` `cb0369f` + `main` `e5ba7ce`), before any
ticket landed. These are the numbers the epic's goals are read against.

Do not regenerate these files. They are the "before" side of every
comparison the terminal evaluation ticket makes, and a regenerated
baseline measures the epic against itself.

## `root-home-drift.json` — GOAL-sync-quiet

A verbatim copy of `~/.skill-manager/home.drift.json` as it stood, 87,054
bytes, one pending record covering 7 units.

`DriftReport.render()` emits one line per changed file. Over this record
that is:

| unit | change | added | removed | modified | lines |
| --- | --- | ---: | ---: | ---: | ---: |
| deploy-helm | MODIFIED | 0 | 14 | 6 | 20 |
| git-epic-workflow | MODIFIED | 4 | 0 | 10 | 14 |
| git-issue | MODIFIED | 0 | 0 | 5 | 5 |
| git-issue-workflow | MODIFIED | 0 | 0 | 2 | 2 |
| plugin-repository | ADDED | 25 | 0 | 0 | 25 |
| skt | MODIFIED | 32 | 0 | 15 | 47 |
| spec-double-compiler | MODIFIED | 0 | 776 | 0 | 776 |
| **total** | | **61** | **790** | **38** | **889** |

**896 lines, 88,308 characters (~22,077 tokens)**, re-emitted on every
`project sync`, every `exec` launch gate, and every `home drift`.

> **On two numbers.** This file first recorded "889 lines ≈ 87,208 characters".
> 889 is the per-FILE line count in the table above and omits the 7 per-unit
> headers; 87,208 was an ESTIMATE (path length + 6 per line), not a render. The
> figures above are what the product's own renderer produces over this record,
> and they are the ones HIS-6 compares against. The estimate is superseded and
> left on the record rather than deleted, so it is visible which number moved
> and why — a baseline quietly edited after the fact is worth nothing.

One unit — `spec-double-compiler` — is 776 of the 889. Those 776 "removed
files" are the dereferenced in-unit symlink trees
(`test_graph/build-logic`, `test_graph/sdk`, `test_graph/standard-nodes`)
being read as deletions, which is why GOAL-sync-quiet and
GOAL-symlink-merge-settles are suspected to share a root cause rather than
being two independent numbers.

## `holdback-records.txt` — GOAL-no-spurious-holdback

Every per-unit materialization record across the four registered child
homes.

- **18** records total
- **16** name a `source` other than the root store, which makes
  `Disposal.recordIsAboutThisSource` false. The root home is *installed*
  into and carries no per-unit record of its own, so the
  `sourceHeldTheseBytes` showing is unavailable too, `disposable()` is
  false, and `ChildHomeMaterializer.copyUnit()` holds the unit back on
  every sync.
- **6** of those name a path that no longer exists at all — deleted ticket
  worktrees (`wt-108-cdc-mvp-023`, `wt-125-cdc-isf-001`) and, in one case,
  a `/private/tmp/.../scratchpad/onboard-eval/fresh-project/` directory
  from a past evaluation session.
- **18 of 18 are pristine by their own digest** (`sourceDigest ==
  contentDigest`). Nobody edited any of them. Every hold-back is a false
  positive, and the paragraph each one prints is protecting nothing.

## GOAL-symlink-merge-settles — a baseline that had to be cleared

At kickoff, `deploy-helm` and `spec-double-compiler` in the project home
were both stuck in `MERGE_CONFLICT`, blocking `skt sync` for both.

The state was diagnosed as phantom index entries: stages 1 and 3 present
at mode `120000` (symlink) for `test_graph/build-logic`, `test_graph/sdk`
and `test_graph/standard-nodes`, stage 2 absent, **no `MERGE_HEAD`**, and
`HEAD` tracking none of those paths. Residue of a `git stash pop`
conflict first recorded `2026-08-11T22:23:41Z`.

Rule 11 requires both home tiers current before a ticket is scheduled, so
this was cleared by hand (`git reset`, restoring the index from HEAD;
working trees untouched; backups under the session scratchpad). The
conflict did **not** recur on the next sync.

**The mechanism is unchanged**, so the baseline is recorded as the state
that was measured, not the state that is on disk now:

- `2 of 21` change-managed units in the project home, permanently stuck,
  with a printed remedy (`git add` + `git commit`) that cannot clear it
  because the working tree cannot hold both a directory and a symlink at
  one path.

HIS-4 must reproduce this synthetically rather than relying on the live
homes, which no longer exhibit it.

## GOAL-gate-settles

Not separately captured here: the pending record in `root-home-drift.json`
carries `"acknowledged": true` with an `acknowledgedAt` 79 seconds after
`detectedAt`, which is the shape #213 describes — the gate is acked and
the same report is still re-emitted by the next pass.
