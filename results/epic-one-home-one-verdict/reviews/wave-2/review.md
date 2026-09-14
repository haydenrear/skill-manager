# Wave 2 review — `one-home-one-verdict`

Range: `8cfa0692` (the wave-1 review commit) → `48d23c51`. One ticket: OHV-2 (#339), PR #364, merged as `48d23c51`. Under `review_policy` there is an artifact per wave and no stop until finalization. The diff is in `diff.stat` and `diff.patch`.

**What the wave was for.** GOAL-one-verdict: `home verify` and `home repair` can no longer disagree, and the two shapes `verify` couldn't see (dangling agent links, orphaned projection records) are detected and pinned by graph nodes.

**CI.** Run 34805817917 (`graph_set=full` on `6eb77ce4`) selected 26 graphs, executed 26, passed 26, failed 0. The epic tip `48d23c51` differs from `6eb77ce4` only under `results/`, so the tip's code is what that run tested.

### Churn by surface

| surface | lines |
| --- | ---: |
| evidence | 1047 |
| test_graph | 481 |
| production (`HomeRepair.java` 248, `HomeCommand.java` 81) | 329 |
| unit tests | 150 |
| plan | 2 |

**Shape.** Production is mostly `HomeRepair`, as planned. **Surprise.** Two graph-side files outside the ticket's own graph needed changes: `IntentionalDamage.java` (91 lines) and the home-clone fixture. The first CI run showed why. The epic's fixpoint-law exemption from wave 1 parses `home verify`'s output, so widening `verify` broke it. That coupling is the hot spot below.

## 1. Hot spots

| # | Location | Why it's hot | Question to ask |
| --- | --- | --- | --- |
| 1 | `HomeCommand.VerifyCmd` → `HomeRepair.detect` | `verify` exits non-zero on any `repair` finding. On the kickoff fleet that flips 50 of 61 homes from exit 0 to non-zero | No script outside the repo gates on the exit code (`blast-radius.md`). Does any agent-facing doc tell agents to *act* on a non-zero `verify`? See DEF-OHV-120 |
| 2 | `test_graph/sources/lib/IntentionalDamage.java` | It now parses **two** human-readable sections of `verify`'s output: the ✗ lines and the repair section | Should `home verify` get `--json` (declined in OHV-1) so declarations stop parsing prose? |
| 3 | `HomeRepair` `DANGLING_AGENT_LINK` detector and its `--fix` | It finds agent dirs from the home's own `.claude/.codex/.gemini` plus the dirs its projection records name. `--fix` removes a link only from the home's own dirs, and only if no installed record claims the unit | It misses agent dirs moved by `CLAUDE_CONFIG_DIR`, other agent subdirs, and dirs no record names any more. Is that acceptable for the root home, whose agent dirs sit under `$HOME`? |
| 4 | The clone-link exemption in `verify` | A `FOREIGN_PATH_IN_SHIM` on a link the clone's own `--against` check sanctioned is excused, matched by exact string equality on the home-relative path | Does it hold on macOS `/var` vs `/private/var` spellings? |
| 5 | `home-clone` fixture step 5 now **stamps** `pm/uv/0.0.0` | Fixture behaviour changed to avoid a test-order defect (DEF-OHV-121). No node asserted on the unstamped tree, and the `git grep` is recorded | none; recorded for the next reader |

## 2. Implicit decisions and guardrail overrides

| Decision | Where | Alternative not taken | Cost to reverse |
| --- | --- | --- | --- |
| `verify` prints `home repair --home <h> --fix` as its remedy, in the form the fixpoint law runs | `HomeCommand` | A plain message with no runnable remedy | Low |
| `DANGLING_AGENT_LINK` has a `--fix`, though the slice named a fix only for orphaned records | OHV-2 (b) | Report only | Low |
| `ORPHANED_PROJECTION_RECORD` skips a record whose unit dir still exists. It skipped none of the 69 orphans on this machine | OHV-2 (c) | Delete every orphaned record | Low |
| `verify` excuses clone links its own `--against` check sanctioned | `HomeCommand` / `HomeCloner` | Report them as foreign | Medium |

| Override | Where | Judgement |
| --- | --- | --- |
| **Fixture changed instead of fixing the node that caused the problem:** home-clone now stamps its `pm/uv` tree, because `CopyCarriesNoForeignBinary` deletes the shared fixture's `pm/` | commit `6eb77ce4`; DEF-OHV-121 | Coverage verified unchanged by the grep; the real fix is deferred |
| **Two test fixtures edited** to stop writing hard-coded shim paths (they failed once `verify` reported them) | `HomeUnresolvedGateTest` and one other, per PR #364 | They were writing the defect the product now reports. That's the "a test that forbids the only correct current state" trap from the handoff, fixed in the right direction |
| **CI run 2 superseded and cancelled** | run 34804201355 | Not evidence; run 3 is |

No `@Disabled` or assumption skips were added, no goal target changed, and close-out exited 0.

## 3. Where the bugs probably are

| Location | Why | Cheapest settling experiment |
| --- | --- | --- |
| `HomeFixpointLaw` runs only the **first** remedy `verify` prints | *Suspicion, no reproduction.* A home with an unresolved reference **and** a repair finding may never get `home repair --fix` from the law | Plant both in a scratch home and run the law's remedy loop once |
| The clone-link exemption's exact string match | Alias spellings on macOS | Run `home clone` from a `/private/var`-given source and check `verify` on the copy |
| `DANGLING_AGENT_LINK` on the **root** home | The root's agent dirs are `~/.claude` etc.; the detector finds them via records. The root read clean after the change; is that true or blind? | Plant a dangling `~/.claude/skills/x` pointing into a scratch root-style home and run `repair` |
| `IntentionalDamage` repair-section parser | Parses prose (hot spot 2) | Change one word of the repair section header and confirm home-clone fails loudly |

## 4. Architectural changes worth making

| Observed | Shape | Door | Cost |
| --- | --- | --- | --- |
| Two layers now parse `verify`'s prose (the law's declarations, and the harnesses') | `home verify --json` with the same finding objects as `repair --json` | New ticket, or an OHV-4 add-on if its slice touches verify output | Small–medium |
| A test-graph cleanup node deletes shared fixture state (DEF-OHV-121) | Nodes own and delete only what they planted; shared fixtures are read-only after the fixture node | test_graph hygiene issue | Small |
| `home-verdicts` wall time: ~1 min at OHV-0 → 12.5 min after OHV-1 → 18.5 min after OHV-2 | Unexplained, and growing faster than the node count (9 → 12) | Measure before wave 3 adds nodes | Small |

## 5. Next steps

**Ready now: wave 3, OHV-4 (#341).** Its dependencies (OHV-0, OHV-2, OHV-5) are merged. It serves GOAL-no-own-home-path (direct) and GOAL-one-verdict (direct): content-based frozen-shim detection (DEF-OHV-001), exec lines re-anchored, and the half-rewritten-shim node.

**Deferred findings added this wave:** DEF-OHV-120 (skill docs describe `verify` as resolution-only; keep batched and land via the skill repos at finalization) and DEF-OHV-121 (the shared-fixture `pm/` cleanup; keep batched). No blocking findings.

**Goal trajectory (local signals):**

| Goal | After wave 2 |
| --- | --- |
| GOAL-one-verdict | Clause (3), verify 0 while repair reports: on the three real homes checked, the commands now agree on all three (tla-spec-dev-2 went 0/1 → 1/1). The fleet re-measure is OHV-8's. Clause (2): the project home's 4 unreported facts are now reported by both, while the root's 3 half-rewritten shims remain OHV-4's. Clause (1): 7 shapes pinned in home-verdicts; pending are the half-rewritten shim (OHV-4), #352's four (OHV-6), and /var (covered by a unit test, not yet a node) |
| GOAL-ci-green-fresh-runner | 26/26/0 held through the wave |
| Others | Unchanged since wave 1 |

**Pending owner decisions, carried forward and not assumed:** skt#46 release; GOAL-one-record clause (1); an issue on main for DEF-OHV-010; restarting Docker.

**Worktrees and headroom.** 8 epic worktrees (epic, OHV-0/1/2/3/4/5/7) and 88 GiB free. The unit suite's temp leak has regrown to 22,689 entries (1 GiB) from one ticket's runs. Docker is still unresponsive.

**Recommendation (the default).** Dispatch OHV-4 now, and ask its agent to time `home-verdicts` before and after its change so the slowdown gets a first measurement.
