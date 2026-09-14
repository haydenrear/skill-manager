# OHV-4 (#341): a shim never spells its own home, and a half-rewritten one is reported

Branch `feature/OHV-4`, from `origin/epic/one-home-one-verdict` at `48d23c51`, merged forward to `c65a99d2` (wave-2 review, docs only) as `25b095ca`. The slice is schedule revision 2's: shims only.

## Root cause (DEF-OHV-001)

Two rules combined into a trap.

- `ShimHomeContract.selfDerivingRewrite` returned `null` ("already rewritten") for any shim whose body contained `SKILL_MANAGER_SHIM_HOME`.
- `HomeRepair.scanFrozenShims` reported FROZEN_HOME_PATH_IN_SHIM only when that rewrite was non-null.

Anything that put the token on one line first left the other lines frozen, and the shim was then exempt from both verdicts. Two paths do that:

- an installer that half-adopts the recipe;
- `HomeRepair`'s FOREIGN_PATH_IN_SHIM fix, which maps another home's path into this one and then calls the rewrite.

deploy-helm's `install-console-script.sh` writes both lines literal (`export MONITORING_DEPLOY_CDC_ROOT="$SKILL_DIR"`, `exec "$venv_dir/bin/$tool"`). A single rewrite of that would clear both, so the root shims' identical mtime (`Sep 13 11:41:50`, later than the Sep 9 install logs) points to a later bulk writer. The FOREIGN fix path fits, but this is **not proven**: nothing on disk records which command wrote them.

## (a) Detect on content

- **Change:** `ShimHomeContract.frozenHomeLines(home, shim)` is the detector. It reports a literal spelling of this home on:
  - any non-comment line after the shebang of a **shell** script;
  - an `exec`, `export` or shell-assignment line of **any other** text file.

  "Spelling" means every `PathSpellings.of` alias, matched as a whole path prefix, the home root included. `HomeRepair.scanFrozenShims` reports on that alone. `repairable` is a separate question: whether the rewrite has a result. A shape the rewrite refuses is reported with `repairable=false` and a remedy that says so.
- **Deliberately not reported** (in the detector's javadoc and in `HomeRepair.Kind.FROZEN_HOME_PATH_IN_SHIM`):
  - **Venv-internal shebang** (`#!<home>/venvs/.../python`). The kernel reads a shebang literally and it cannot hold a token, so no `--fix` could ever clear the finding. `venvs/` is a toolchain root that a clone never carries and re-provisioning regenerates. `absolutePathTokens` never tokenized a shebang, so this is not a narrowing. A copied home carrying one is the re-provisioning question (`home verify`'s unresolved references and `build`), not this one.
  - **A spelling only in a `#` comment** (coordinator instruction, #341 "Watch for"). Prose about a path is not a reference to one. Since OHV-2, `verify` fails on every repair finding, so a comment-only match would turn verify red on a shim that runs correctly. Contract: no finding, so `home repair --fix` is a no-op and the shim stays **byte-identical**. The installer's rewrite still re-anchors such a comment on a fresh install, as before OHV-4.
- **Tests:**
  - `ShimSurvivesACopyTest`, 8 OHV-4 cases: the half-rewritten shim reports exactly its exec line; the home root on an assignment line; a sibling home sharing the prefix is not this home; venv shebang not reported; comment-only not reported; a non-shell exec line is reported but not rewritten; another spelling (`/var` vs `/private/var`) of the same home.
  - `DamagedHomeIsRepairableTest`: `damageEveryKind` plants the half-rewritten variant (`damageHalfRewrittenShim`), and the every-kind guard asserts its own subject. There is also a comment-only case (not a finding, `--fix` byte-identical).
- **Node:** `home.verdicts.half.rewritten.shim`: repair names it, and verify exits 1 and names it.

## (b) The rewrite re-anchors exec lines

- **Change:** `selfDerivingRewrite` no longer treats the token's presence as "done":
  - It replaces every remaining whole-prefix spelling, longest first, on every line after the shebang, comments included (the pre-OHV-4 trigger, which BLOCKER 2 pins).
  - It adds the preamble only when no assignment of the token precedes the first rewritten line.
  - It refuses a result that still spells the home, and it refuses to rewrite the token's own assignment.
- **Idempotent:** the second pass finds no spelling and returns `null`.
- **Tests:** `ShimSurvivesACopyTest` (one assignment and no second preamble; clean after; a second pass is a no-op; the rewritten shim runs its tool from a `cp -R` copy with the source's tool deleted). `DamagedHomeIsRepairableTest` (`--fix` re-anchors the exec line, then a separate detect is clean, then a second `--fix` leaves the bytes unchanged). `BLOCKER 2` still passes.
- **Node:** `home.verdicts.half.rewritten.shim` after `--fix`: a separate detection is clean; verify is clean; no spelling of the home survives; `exec "${SKILL_MANAGER_SHIM_HOME}/cache/…"`; exactly one assignment; the shim runs; a second `--fix` exits 0, is clean and changes no byte.

## (c) The skill-script writer

- **Change:** `SkillScriptBackend.reportFrozenShims` gates on the rewrite having something to re-anchor, not on the old token scan, and warns only on detector lines. A skill-script install therefore never leaves the half-rewritten shape.
- **Tests:** `SkillScriptBackendTest`, two cases through `InstallerRegistry.installOne`. One installer uses deploy-helm's unquoted-heredoc shape (both lines literal); the other half-adopted the recipe (token export, literal exec). After install, no line spells the home, the exec line derives the token, there is one assignment, and the shim runs its tool.

## Real homes (read-only; no `--fix`)

`home verify --home <h>` rc, then `home repair --home <h> --json` findings. Files are under `real-homes/<label>/`.

| home | released 0.27.2 | raw build before (`25b095ca`) | raw build after (this change) |
| --- | --- | --- | --- |
| root `~/.skill-manager` | verify 0; repair 0 findings (78 examined) | verify 0; repair 0 (310 examined) | **verify 1; repair 3: FROZEN_HOME_PATH_IN_SHIM `bin/cli/computeq`, `helm-deploy`, `monitoring`, all repairable** |
| project `skill-manager/.skill-manager` | verify 0; repair 0 | verify 1; repair 4 (3 DANGLING_AGENT_LINK, 1 ORPHANED_PROJECTION_RECORD; OHV-2's) | unchanged: the same 4 |
| meta-orchestrator | verify 0; repair 8 (6 FROZEN, 2 UNSTAMPED_PM_TREE) | verify 1; the same 8 | unchanged: the same 8 |

The root's 3 half-rewritten shims went from unreported to reported by both verdicts. That is GOAL-one-verdict's expected effect: root 4 → 3 unreported facts.

**The root `--fix` is not run. It is the owner's decision.** Backups of the three shims (sha256 verified) are in `/private/tmp/claude-501/-Users-hayde-IdeaProjects-skill-manager/9972f0f0-9e78-40bb-8155-34bfc5133ca5/scratchpad/ohv-4-backups/root-bin-cli/`. Command, with this branch's build:

```
/Users/hayde/IdeaProjects/wt-ohv-4/skill-manager home repair --home /Users/hayde/.skill-manager --fix --json
```

Proof that this does what it should on the exact shape, on a scratch home and not the root: `harness/synthetic/summary.txt`. The repair reported `computeq` as repairable; `--fix` put `exec "${SKILL_MANAGER_SHIM_HOME}/cache/skill-script-deploy-helm-computeq/venv/bin/computeq" "$@"` in place and the shim still runs. A second `--fix` is clean, repair and verify both exit 0 afterwards, and the comment-only shim is byte-identical.

## Goal harnesses (read-only)

| harness | before (`25b095ca`) | after (this branch, real homes) |
| --- | --- | --- |
| `measure_goal_no_own_home_path.py` | root 3 (computeq, helm-deploy, monitoring; 3 half-rewritten, 3 unreported); project 0; met false | root 3, 3 unreported; project 0; met false |
| `measure_goal_a_home_survives_being_copied.py` | 2 of 3; (b) offenders computeq, helm-deploy, monitoring | 2 of 3; the same offenders |

**No movement on the real homes is expected before the root `--fix`**, for two reasons. Both harnesses read the bytes, and `measure_goal_no_own_home_path.py` judges "reported" with **each home's own pinned CLI** (`home_observations.cli_for`), which on root is released 0.27.2, not this branch. Clause (1)'s 3 → 0 and the probe's 2 → 3 of 3 happen when the owner runs the command above with the epic build.

**The harness still separates reported from unreported correctly.** It gained two categories the detector deliberately does not report, `shebang_only_out_of_scope` and `comment_only_not_a_reference`, and neither counts as "unreported". Proof on the synthetic home, pinned to this build:

- before `--fix`: 3 entries: `computeq` reported, 0 unreported, `console-script` shebang-only, `commented` comment-only;
- after: 2 entries, 0 unreported, the same two out-of-scope entries, `computeq` gone.

Fleet context, clause (3), no threshold: **38 of 61** homes have a `bin/cli` entry that spells their own path (`harness/after.no_own_home_path.fleet.json`). The kickoff measured 51 of 61 at `71c51464`. The fleet itself changed in between (homes synced and rebuilt), and this count is the harness's substring read, so it includes shebang-only and comment-only entries. It is context, not movement attributable to this ticket, and no fleet home was repaired.

## Timing (the wave-2 review's question)

| measurement | value |
| --- | --- |
| `run.py home-verdicts`, untouched worktree `25b095ca`, local | **BUILD SUCCESSFUL in 18m 14s** (wall 1095 s) |
| its 50 CLI processes (envelopes, run `20260914-050417`) | every one 14.1–14.3 s, any command; sum 711 s |
| `home repair --json`, clean scratch home, this build | **14.24 s, 14.20 s** |
| the same with `OTEL_SDK_DISABLED=true` | **0.95 s, 0.96 s** |
| `--version`, this build | 14.15 s |
| released 0.27.2 `home repair --json` / with OTEL disabled | 13.74 s / 0.43 s |

**Cause found, not fixed (outside the slice): DEF-OHV-140** (`deferred.yaml`). Every CLI process waits about 14 s on its OTLP export. `CliObservability` defaults the endpoint to `http://localhost:4318`, which Docker Desktop's backend holds open (LISTEN, pid 3528) while the engine is unresponsive: connections neither refuse nor answer (`curl` rc=28 after about 8 s). home-verdicts' 7 CLI calls per shape node account for its growth faster than its node count. Whether the ~1 min at OHV-0 was the refused-port state before Docker hung is plausible but unproven: Docker was not restarted.

home-verdicts after this change: see Graphs.

## Graphs

Local runs, macOS, Docker down, `GRADLE_OPTS=-Dorg.gradle.daemon=false COMPOSE_PROJECT_NAME=skill-manager`, one graph per lock acquisition. CI is the graph evidence (below); these are the pre-dispatch checks the assignment asked for.

| graph | run | result | notes |
| --- | --- | --- | --- |
| home-verdicts, untouched (`25b095ca`) | `20260914-050417` | BUILD SUCCESSFUL, 18m 14s | the timing baseline |
| home-clone, detector widened, declarations NOT yet updated | `20260914-053211` | **FAILED**: 14 passed, 1 failed (`home.fixpoint.law`), 1 skipped | exactly the predicted break: `FROZEN_HOME_PATH_IN_SHIM bin/cli/hc-tool` undeclared in the fixture home, its project clone and the credential copy. hc-tool's `SM_HOME="<home>"` is step 3's planted shape, and nothing else was unexplained |
| home-clone, `hc-tool` declared in all three homes | `20260914-054820` | **BUILD SUCCESSFUL, 16/16 passed**, 14m 42s | `home.fixpoint.law`: homesChecked 3, homesDamagedOnPurpose 3, homesRepaired 0. Header reads `25b095ca`; the commits were made during the run and the files under test are byte-identical to `0b97bd71` |
| home-verdicts, this change (`0b97bd71`) | `20260914-060315` | **BUILD SUCCESSFUL, 13/13 passed**, 20m 27s | `home.verdicts.half.rewritten.shim` passed all 15 assertions: repair exits 1 and names FROZEN_HOME_PATH_IN_SHIM on `bin/cli/half-tool`; verify exits 1 and names it; `--fix` then a separate detection is clean; verify is clean after; the fixed shim spells no form of its home; the exec line derives the token; one assignment of it; the shim runs; a second `--fix` is a no-op. The +2m 13s over the baseline is one more shape node, which makes 8 CLI processes at about 14 s each (DEF-OHV-140) |
| checkout-home (`0b97bd71`) | `20260914-062332` | **BUILD SUCCESSFUL, 8/8 passed**, 9m 25s | the assignment's watch-for; no declaration change was needed |

plugin-smoke and home-integrity need Docker or the gateway, so they are CI-only here.

## Unit tests

- The touched suites (ShimSurvivesACopyTest 14, ShimHomeContractTest 2, SkillScriptBackendTest 7, DamagedHomeIsRepairableTest 29, HomeUnresolvedGateTest 4): 56 passed, 0 failed.
- `uv run --with pytest pytest specs/program_model/tests -q`: 11 passed.
- `jbang RunTests.java` (full repository suite, at `0b97bd71`, `OTEL_SDK_DISABLED=true` for speed): **ALL PASSED**, 1566 `[PASS]`, 0 `[FAIL]`, rc 0. 88 GiB free before and after.

## Close-out

`skill-manager home close-out --home /Users/hayde/IdeaProjects/wt-ohv-4/.skill-manager --into /Users/hayde/IdeaProjects/skill-manager/.skill-manager` (released CLI on PATH): **exit 0**, "holds nothing that removing it would destroy" (`close-out.txt`). No unit was edited in the ticket home; no `home sync` was run.

## Deferred

DEF-OHV-140: the per-process OTLP export wait (above). No other findings.
