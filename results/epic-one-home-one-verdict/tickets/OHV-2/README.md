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

Local run (validation-reports `20260914-031226`, started before the last two
source edits, so the final-tree rerun below is the one that counts): nodes 1–8
passed. That includes the three flipped shapes (`TODAY_home_verify_exits_1`,
`verify.exit` = 1) and `verify.names.every.repair.finding`
(`repair.findings` = 5, `verify.unnamed` = 0). Nodes 9–12: _pending_.
Final-tree rerun: _pending_.

CI run 34802282392 (`graph_set=full`, `af7e4b52`): `skill-manager unit tests
(RunTests.java + spec models)` **success** on Linux, `virtual-mcp-gateway
pytest` success.

Final-tree local rerun on `af7e4b52` (validation-reports `20260914-033054`,
clean tree): **all 12 nodes passed**, `BUILD SUCCESSFUL in 18m 7s`.

### CI run 1: 34802282392 (`graph_set=full`, `af7e4b52`), 26 selected / 26 executed / 24 passed / 2 failed

`home-verdicts` passed on Linux. **`home-clone` and `checkout-home` failed,
both in `home.fixpoint.law`,** for one reason, and it is this change's:

- `home.clone.fixture.built` plants legacy shapes on purpose. Step 4's
  `bin/cli/hc-venv-tool` execs `<home>/venvs/…` literally, which is a
  `FROZEN_HOME_PATH_IN_SHIM`. Step 5's `pm/uv/0.0.0` has no platform stamp,
  which is an `UNSTAMPED_PM_TREE`. The fixture home and both its copies
  (`home.cloned.into.project`, the credential copy) carry them.
- Verify now names both. `IntentionalDamage.unexplained` only understood the
  "do not resolve" section, so both became unexplained refusals. The law ran
  the FIRST printed remedy (`build …`, the unresolved one), and the re-verify
  still refused (job 103847224069, `home.fixpoint.law` inline log).

**Fix (test-side; in scope as an IntentionalDamage declaration change):**
- `IntentionalDamage.repairSubjects` parses verify's repair section.
- `unexplained` excuses a declared repair finding and its `repair:` line, and
  excuses the section's header and remedy only when every finding under them
  is declared.
- `HomeFixpointLaw` counts repair subjects as "reported", so a declared entry
  that stops being reported still fails the law.
- The three declarations add `pm/uv/0.0.0`, and the fixture home adds
  `bin/cli/hc-venv-tool` (it resolves there, so it appears only as a repair
  finding).
- Parser checked against real output (`real-homes/after/project.verify.err`):
  4 subjects parsed; all declared → nothing unexplained; one subject undeclared
  → its finding, `repair:`, header and remedy lines; none declared → 10 lines.

### CI run 2: _pending_

Local `home-clone` on the fixed tree: _pending_. `checkout-home` reuses
home-clone's fixture nodes, so the same declarations cover it; CI decides.

## Blast radius

See [blast-radius.md](blast-radius.md). No caller outside this repo runs
`home verify` and gates on its exit code, so it landed as specified. Callers
inside the repo that changed:
- the home-verdicts TODAY assertions;
- `HomeUnresolvedGateTest`'s fixture;
- the `--against` parent-shim exemption;
- `IntentionalDamage`, the fixpoint law's reported set, and the three
  home-clone declarations (found by CI run 1).

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
  0 failures. **Second full run, on the tree committed as `afaabc8d`: exit
  0, 1555 passed, 0 failed, "ALL PASSED"** (88 GiB free before and after).
- `uv run --with pytest pytest specs/program_model/tests -q`: 11 passed.

## Deferred

DEF-OHV-120 in [deferred.yaml](deferred.yaml): git-epic-workflow and
git-issue-workflow docs still describe verify as resolution-only.

## Close-out

`skill-manager home close-out --home /Users/hayde/IdeaProjects/wt-ohv-2/.skill-manager --into /Users/hayde/IdeaProjects/skill-manager/.skill-manager`
(PATH build `~/.skill-manager/bin/cli/skill-manager`) → **exit 0**,
"✓ … holds nothing that removing it would destroy". No unit was edited in the
ticket home, and no `home sync` was run. Output: `close-out/`.
