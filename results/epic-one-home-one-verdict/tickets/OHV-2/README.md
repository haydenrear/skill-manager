# OHV-2 (#339) — home verify fails on what home repair finds, and both see agent links and projection records

Branch `feature/OHV-2`, cut from `origin/epic/one-home-one-verdict` at `3d6d4cd4`.
Slice at schedule revision 2: (a) verify composes repair's detection, (b) a
`DANGLING_AGENT_LINK` kind, (c) an `ORPHANED_PROJECTION_RECORD` kind with a
`--fix` prune.

## (a) `home verify` fails on every `home repair` finding, and names each

- **Change:** `HomeCommand.VerifyCmd` runs `HomeRepair.detect(home)` after
  its own checks and before the isolation verdict. Each finding is printed as
  `✗   <KIND> <subject> — <detail>` with a `repair:` line under it, which is
  the spelling `home repair --json` uses. The command exits 1 if anything
  remains. When some findings are fixable it prints the remedy in the one
  spelling `HomeFixpointLaw` parses:
  `complete it with: <env> skill-manager home repair --home <h> --fix, then re-run this check`.
  On a clean home it prints `` `home repair` finds no damage in <h> (N entries examined) ``.
- **One exemption:** a `FOREIGN_PATH_IN_SHIM` finding on a link that verify's
  own isolation walk sanctioned as a parent-store shim is printed but not
  counted. With `--against <source>`, verify inherits the source's sanction
  (HIS-7 / #223, a worktree copied from a sanctioned project home), while
  repair judges the copy alone. Without the exemption,
  `ChildHomeShimIsolationTest` "a COPY of a sanctioned child inherits the
  sanction" went red. That is every ticket-worktree clone.
- **Graph:** new node `home.verdicts.verify.names.every.repair.finding`. It
  plants five kinds in one home, requires verify to exit 1 and to name every
  `(kind, subject)` that repair's JSON reports, requires the printed remedy to
  be `home repair --home <subject> --fix`, and requires both readers to be
  clean after the fix. Control: verify exits 0 on the unplanted home. The
  three `TODAY_home_verify_exits_0` assertions (`frozen.shim`,
  `misanchored.agent.link`, `unstamped.pm.tree`) are flipped to
  `TODAY_home_verify_exits_1` / `…_names_the_shape`.
- **Unit:** `DamagedHomeIsRepairableTest` "OHV-2: dangling links …" runs
  `VerifyCmd` in process and asserts exit != 0 and each finding named.
  `HomeUnresolvedGateTest`'s fixture now writes the self-deriving shim shape
  (the old literal shape is a `FROZEN_HOME_PATH_IN_SHIM` that verify now
  correctly refuses on). The gate still fires on the same missing path.

## (b) `DANGLING_AGENT_LINK`

- **What:** a symlink in an agent directory that points into this store and
  resolves to nothing.
- **Where it looks, from what the home records:**
  1. the home's structural agent dirs (`HomeRepair.agentDirsOf`:
     `<homeRoot>/.claude|.codex|.gemini` × `skills|plugins`);
  2. every directory the projection records name: each binding's
     `targetRoot` and each projection's `destPath` parent, orphaned records
     included, since a retired unit's record is where its surviving links are
     written down.
- **Not covered:** a directory no record names any more; agent dirs moved by
  `CLAUDE_CONFIG_DIR` / `CODEX_HOME` / `GEMINI_HOME` (this class never reads
  the environment, by design, HIS-14); agent subdirs other than those two
  lists; and a dangling link into another home.
- **`--fix`:** removes the link only when it sits in the home's own agent
  dirs (the write confinement is the home's two axes) and no
  `installed/<unit>.json` still records the unit. A link in a record-named dir
  is reported with `rm <abs>` as its remedy. A still-recorded unit gets
  "re-install it here".
- **Graph:** `home.verdicts.dangling.agent.link` plants
  `.codex/skills/hv-gone -> <store>/skills/hv-gone`.
- **Unit:** planted in `damageEveryKind()` (the `Kind.values()` guard), plus
  "OHV-2: dangling links …". That test covers the own-dir link (repairable,
  removed), the record-named link (reported, absolute subject, left in
  place), and a dangling link into another home (not reported, untouched).

## (c) `ORPHANED_PROJECTION_RECORD`

- **What:** `installed/<unit>.projections.json` with no `installed/<unit>.json`
  and no `skills|plugins|docs|harnesses/<unit>` directory. The unit-dir clause
  keeps the prune off a live unit's ledger. It excluded none of the 69 orphans
  on this machine.
- **`--fix`:** deletes that record file and nothing else, after re-checking
  that the unit has not come back.
- **Graph:** `home.verdicts.orphaned.projection.record`.
- **Unit:** planted in `damageEveryKind()`. "OHV-2: dangling links …" asserts
  the gone record is deleted and a record whose unit directory exists is
  left alone.

## home-verdicts node list

`home.verdicts.fixture`, `clean.home`, `frozen.shim`,
`foreign.path.in.shim`, `misanchored.agent.link`, `unstamped.pm.tree`,
**`verify.names.every.repair.finding`**, **`dangling.agent.link`**,
**`orphaned.projection.record`**, plus `home.fixpoint.law` and
`home.membership.law` (12 nodes including `env.prepared`).

Local run: _pending_. CI: _pending_.

## Blast radius

See [blast-radius.md](blast-radius.md). No caller outside this repo runs
`home verify` and gates on its exit code, so it landed as specified. Callers
inside the repo that changed: the home-verdicts TODAY assertions,
`HomeUnresolvedGateTest`'s fixture, and the `--against` parent-shim
exemption.

## Real homes (read-only; no `--fix`)

Before: released `/opt/homebrew/bin/skill-manager` 0.27.2. After: this
worktree's raw build `0.27.2+g3d6d4cd4ce6e` (feature/OHV-2, pre-commit).
Raw outputs are in `real-homes/before/` and `real-homes/after/`.

| home | before verify / repair | after verify / repair | after findings |
| --- | --- | --- | --- |
| project `/Users/hayde/IdeaProjects/skill-manager/.skill-manager` | 0 / 0 (43 examined) | **1 / 1** (75 examined) | 3 `DANGLING_AGENT_LINK` (`.claude|.codex|.gemini/skills/skill-manager`), 1 `ORPHANED_PROJECTION_RECORD` (`installed/skill-manager.projections.json`), all repairable |
| root `~/.skill-manager` | 0 / 0 (78) | 0 / 0 (310) | none. The #339 comment's `skill-dev-skill` links are no longer on disk. |
| `tla-spec-dev-2/.skill-manager` | **0** / 1 | **1** / 1 | 1 `FROZEN_HOME_PATH_IN_SHIM`, 2 `UNSTAMPED_PM_TREE` |

Against `expected_effect`: project home 4 → 0 facts unreported by
verify/repair for the shapes they own (the target was 4 → 2; census phantoms
and a stale record version are OHV-3's). tla-spec-dev-2 moves from
verify-0-while-repair-damaged to agreeing. 3 new planted shapes are pinned.

## Tests

- `jbang RunTests.java`: first full run 1552 passed / 3 failed. The failures
  were `HomeUnresolvedGateTest` ×2 (fixture wrote frozen shims) and
  `ChildHomeShimIsolationTest` ×1 (`--against` sanction), both fixed above.
  Focused rerun of those suites plus every verify-calling suite: 157 cases,
  0 failures. Second full run: _pending_.
- `uv run --with pytest pytest specs/program_model/tests -q`: 11 passed.

## Deferred

DEF-OHV-120 in [deferred.yaml](deferred.yaml): git-epic-workflow and
git-issue-workflow docs still describe verify as resolution-only.

## Close-out

_pending_
