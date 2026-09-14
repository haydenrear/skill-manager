# Wave 1 review — `one-home-one-verdict`

Range `de4231386a9b` (plan revision 2) → ``3d6d4cd4``. Five tickets, merged in promotion order OHV-1 → OHV-7 → OHV-0 → OHV-5 → OHV-3. `review_policy`: an artifact per wave, no stop until finalization (owner, 2026-09-13). The merge record is `merges.md`; the diff is `diff.stat` and `diff.patch`.

**What the wave was for:** make every graph in the full set runnable and green on a fresh runner, give the epic a regression graph for home defects, and land the two record fixes (#292 and stale versions) that later waves build on.

**Epic-branch CI:** run 34798482174 (graph_set=full, epic tip `f38a9298`, after OHV-1/7/0/5): **26 selected, 26 executed, 25 passed, 1 failed** — `artifact-dag` only, OHV-3's, not yet merged. Main baseline was 25 / 19 / 6. **After OHV-3:** run 34798708463 on OHV-3's merged head `53d3e7e1`. Its code is identical to the epic tip `3d6d4cd4`; the tip differs only in OHV-3's README. **26 selected, 26 executed, 26 passed, 0 failed.** This is the first fully green `graph_set=full` run in this epic, against main's 25 / 19 / 6.

### Churn by surface

Added plus deleted lines over the range, from `diff.stat`:

| surface | lines |
| --- | ---: |
| evidence (`results/`) | 6297 |
| test_graph (graphs, laws, fixtures) | 1091 |
| production (`src/main`) | 687 |
| unit tests | 681 |
| test_graph runner (`skills/test_graph/scripts`) | 571 |
| goal harnesses (`scripts/`) | 568 |
| CI (`.github`) | 174 |
| plan | 10 |

**The shape:** more test graph and harness code than production code, which matches a wave that fixed CI and built instruments. **The surprise:** 687 production lines for what the plan called test-infrastructure work. They are `PathSpellings` (#343), the build stamp, and the prune/uninstall verdicts, all product changes and all correctly in scope.

## 1. Hot spots

| # | Location | Why it's hot | The question to ask |
| --- | --- | --- | --- |
| 1 | `test_graph/sources/common/HomeFixpointLaw.java` + `test_graph/sources/lib/IntentionalDamage.java` (OHV-5) | A law every graph runs can now skip a home. It skips only one that declares planted findings and reports exactly those, but it's a new way out of the strictest check in the suite | Can a home declare damage it doesn't have, or keep declaring it after a fix, and get skipped? The law fails when a declared finding stops being reported; is that tested by a node, or only reasoned about? |
| 2 | `src/main/java/dev/skillmanager/store/PathSpellings.java` (new, OHV-5), used by `HomeCloner` and `ShimHomeContract` | Every alias spelling now feeds `home verify`, clone re-anchoring and the shim rewrite on macOS, so one helper changes three code paths | Does the rewrite replacing the longest spelling first ever rewrite a path that belongs to a different home sharing a prefix? |
| 3 | `src/main/java/dev/skillmanager/artifacts/ArtifactPrune.java` (OHV-3) | Three branches gain row-only verdicts, plus "uninstall deletes the ledger it created". This is #292's file, and the last attempt was reverted for leaving an unreachable link | Is the list of kinds whose outputs are always inside the home (read from `ArtifactBackfill`) enforced anywhere, or will the next kind with an outside-the-home output reopen the trap? |
| 4 | `.github/workflows/ci.yml` + `.github/scripts/seed-agent-home.sh` (OHV-5) | CI now fabricates a 16-unit agent home under `$HOME` and fetches another repository at a pinned SHA. Three graphs go green because of that provisioning | Does the synthetic home cover the surfaces a real leak would hit (`~/.claude/plugins`, `settings.json`)? |
| 5 | `skills/test_graph/scripts/run.py` + `sweep-ledger.init.gradle` (OHV-7) | The local runner now keeps going past a red graph and counts only what it ran, via Gradle `beforeTask`/`afterTask` listeners | The listeners break if the configuration cache is ever enabled, and graph tasks are recognised by class name. Would a Gradle upgrade silently return to "stale reports counted"? |
| 6 | `test_graph/sources/home-verdicts/*` (OHV-0) | New regression graph (9 nodes). Its `TODAY_home_verify_exits_0` assertions pin today's disagreement on purpose | OHV-2 must flip three of those to 1 in the same change. Will a reviewer see a `TODAY_` flip as a regression? |
| 7 | `RunTests.java` (OHV-1 and OHV-3) | The only production-side file two wave-1 tickets both wrote. Disjoint conflict keys didn't cover the shared test registry | Should `RunTests.java` get its own conflict key in waves 2–4? All three of those tickets add tests |

## 2. Implicit decisions and guardrail overrides

### Implicit decisions

| Decision | Where | Alternative not taken | Cost to reverse |
| --- | --- | --- | --- |
| The build stamp is one string, `<release line> @ <build>`, first in text output, and a `build` field in JSON. `artifacts show`/`record` carry it too; the schema number stays 1 | OHV-1 `BuildIdentity` | A structured `{version, commit, ref}` object | Low while the only consumers are ours |
| `BuildIdentity.judgedFrom()` is a static override that also changes `--version` while set (tests only) | OHV-1 | Inject the build identity per command | Low |
| `home verify` stays text-only; no `--json` added | OHV-1 | Add JSON mode | A new ticket |
| The runner forces one worker so run directories can be matched before and after each task | OHV-7 | Per-task run ids emitted by the graph plugin | Medium: touches the test_graph plugin |
| Findings in home-verdicts are matched by parsing JSON (`RepairReport`) | OHV-0 (after the epic agent's review) | Text match on key order, which was the first cut | None |
| The harnesses count "missing outputs" excluding outputs the census marks `unknown` (18 absent → 15), and judge each home through its pinned CLI rather than the released binary | OHV-0 | Count all absent outputs; use the released binary | Low, but it changes the baseline's meaning: say so at evaluation |
| An intentionally-damaged declaration names homes and exact findings; the law fails if a declared finding stops being reported | OHV-5 | Exclude the graphs, or skip a directory | Medium |
| Uninstall treats a ledger as "created by this pair" when the file was absent at plan time and every row is still derivable after the prune | OHV-3, owner option 1 | Timestamps; a marker file | Medium |
| Sync rewrites `installed/<unit>.json` on the up-to-date path when the recorded version disagrees with the manifest | OHV-3 (c) | Refresh only on a real update | Low; consumers assuming "an up-to-date sync writes nothing" should be checked |

### Guardrail overrides, each named

| Override | Where to find it | Judgement |
| --- | --- | --- |
| **A vacuity floor met with synthetic data:** the tripwire's ">100 entries" check passes on CI because `seed-agent-home.sh` plants 16 generated units (169 entries), not a real install | `.github/scripts/seed-agent-home.sh` comment; run 34793308847 | Defensible, since the floor guards against an empty baseline and the seed is shaped like a real home. But the threshold is satisfied by construction. Alternative: seed from a real `skill-manager install` on the runner |
| **A law exemption:** `home.fixpoint.law` skips declared intentionally damaged homes | `IntentionalDamage.java`; the law's `homesDamagedOnPurpose` output | Counted and named, and fails if a declaration goes stale. Not silent |
| **A force-push refused:** the epic agent rebuilt OHV-7's branch as a merge instead | `merges.md` | No history rewritten |
| **A local graph run skipped:** OHV-3 (d)'s plugin-smoke couldn't run because local Docker was unresponsive; CI's Linux plugin-smoke is the evidence | OHV-3 README, PR #361 | CI is authoritative; recorded |
| **Real-home proof not obtained:** OHV-3 (c) is proven by `RecordVersionRefreshTest` only. The tla-spec-dev-2 sync was refused with exit 7 for extra local changes, but still re-registered the `runpod` gateway server and re-linked 18 units | OHV-3 README `real-homes/sync-acp.txt` | Carried to OHV-8 |
| **Real-home files deleted:** OHV-3's prune on tla-spec-dev-2 removed `bin/cli/skill-dev` and five stale harness-instance dirs under older verdicts. Backed up first | scratchpad `ohv-3-backups/` | Within the instructions; note it |
| **DCO action-required on every PR** | PR checks | Same as every merged PR of the previous epic; the branch is unprotected |
| **Mine: 14 worktrees removed without gating on `home close-out`** (13 refusals, all `eval-skill` residue) | kickoff attribution | Nothing lost, by luck |

`grep` for added `@Disabled`/`skip`/`xfail` over the range: none (the two matches are test data and a constant name). No `epic_goals` target or baseline changed in the range. Every close-out verdict: exit 0.

## 3. Where the bugs probably are

| Location | Why | Cheapest settling experiment |
| --- | --- | --- |
| `IntentionalDamage` declaration parser reading `home verify`'s ✗ lines (OHV-5) | Parses human-readable output. A verify wording change makes a declared home fall back to normal judgement. That fails loudly, but on the wrong graph | Change one ✗ line's wording locally and confirm home-clone fails with a clear message, not a confusing fixpoint error |
| `ArtifactPrune.decide` row-only verdicts (OHV-3) | Suspicion. The "always inside the home" kind list is a reading of `ArtifactBackfill`, not a type | Add a test that enumerates `ArtifactKind.values()` and fails for an unclassified kind, the same pattern as `DamagedHomeIsRepairableTest` |
| Uninstall deleting the ledger it created (OHV-3 (d)) | Hunk written in a reconcile-pressure window; verified locally only on artifact-dag, and plugin-smoke only on CI | Uninstall a unit in a home with a hand-made ledger row that isn't derivable; the ledger must survive |
| `home-verdicts` run time went from ~1 m to 12 m 32 s after merging OHV-1 (OHV-0 reconcile) | Unexplained. It could be the build stamp resolving something slow per invocation | Time `home repair --json` on a clean fixture home with the pre-OHV-1 and post-OHV-1 builds |
| `PathSpellings` on Linux (OHV-5) | The end-to-end alias test returns early on Linux; only the table-driven test covers it there | Run the table test with a synthetic root-symlink table resembling `/private` on Linux |
| OHV-7 `--all` never run end to end locally | Only the multi-graph form was exercised | `run.py --all` once, on a quiet machine, and compare the summary with `graphs-executed` |

## 4. Architectural changes worth making

| Observed | Shape it suggests | Door | Cost |
| --- | --- | --- | --- |
| The unit suite leaks about 41 GiB of temp fixtures a day, which filled the disk and killed local Docker (DEF-OHV-010) | One per-run temp root that the runner deletes, plus a suite-level check that fails on leaked entries | New issue on main (test hygiene) | Small–medium |
| `validate_epic_plan.py` can't retire one contributor to a shared goal (DEF-OHV-009) | A per-link `withdrawn_contribution` disposition | `git-epic-workflow` skill, `unit publish` | Small |
| `skt ticket sweep` skips containment by default, and can't recognise squash merges when asked (DEF-OHV-006) | Containment by PR-merged state or tree equivalence; refuse rather than skip | skt repository issue | Small |
| Agent prompts omitted the repo's PR-title lint and the "reconcile by merge, never force-push" constraint | The epic assignment block should carry the repository's PR title convention and reconcile method | `git-epic-workflow` renderer + `validate_assignment.py` | Small |
| Three instrument errors from zsh/bash in the epic agent's own shell work (word-splitting, NOMATCH, bash 3.2) | Epic scripts shipped as files run by an explicit interpreter, not inline shell | Scratch practice + memory (recorded) | None |
| A conflict-key miss on `RunTests.java` | Declare `tests/registry` as a conflict key whenever a ticket adds a test class | Plan amendment for waves 2–4 | None |

No unit edits were made in any ticket home: every close-out exited 0 with nothing held back, so nothing needs `unit publish`.

## 5. Next steps

**Ready now: wave 2** — OHV-2 (#339), whose only dependency, OHV-0, is merged. It serves GOAL-one-verdict (direct): `home verify` fails on what `home repair` finds; detectors for dangling agent links and orphaned projection records; flip home-verdicts' `TODAY_` assertions.

**Deferred findings (all pending):**

| ID | Found by | Severity | Summary | Blast radius | Recommendation |
| --- | --- | --- | --- | --- | --- |
| DEF-OHV-001 | kickoff | major | `home repair` exempts half-rewritten shims | OHV-4 | Already scheduled (OHV-4) |
| DEF-OHV-002 | kickoff | major | Dangling agent links invisible to `verify` | OHV-2 | Already scheduled (OHV-2) |
| DEF-OHV-003 | kickoff | minor | Retirement leaves ledger rows | OHV-3 | Fixed going forward by OHV-3; existing rows are DEF-OHV-131 |
| DEF-OHV-004 | kickoff | minor | Stale record `version` | OHV-3 | Fixed by OHV-3 (c), unit-proven |
| DEF-OHV-005 | kickoff | minor | Root Claude enables another home's marketplace | OHV-6 | Already scheduled (OHV-6) |
| DEF-OHV-006 | kickoff | major | skt sweep can't prove squash-merged work | skt | Keep batched, file on the skt repo at finalization |
| DEF-OHV-007 | kickoff | minor | `measure_goals.py --help` runs everything | scripts | Keep batched |
| DEF-OHV-008 | kickoff | minor | Leaked fixture gateway process | tests | Batch with DEF-OHV-010 |
| DEF-OHV-009 | revision 2 | minor | Validator can't retire one goal contributor | skill | Keep batched, file at finalization |
| DEF-OHV-010 | wave 1 | major | Unit suite leaks ~41 GiB of temp fixtures a day | every local run | **Promote to a new issue on main now:** it gets more expensive with every wave |
| DEF-OHV-130 | OHV-3 | minor | Census counts PATH-served shims as missing outputs | GOAL-one-record (1) | Decide at OHV-8; see goal trajectory |
| DEF-OHV-131 | OHV-3 | minor | Existing projection rows from past removals can't be proven absent | GOAL-one-record (1) | Decide at OHV-8; see goal trajectory |

**Goal trajectory (local signals, not the measurement):**

| Goal | Baseline | Wave 1 signal | Implication |
| --- | --- | --- | --- |
| GOAL-ci-green-fresh-runner | 25/19/6 | OHV-5 alone: 25/24/1 (artifact-dag); OHV-3 targets artifact-dag. OHV-7: local sweep continues past red, counts its own executions | Local signal met: 26/26/0 on code identical to the tip (run 34798708463), with executed == selected and no exclusions. OHV-8 decides on the final epic tip |
| GOAL-verdict-names-its-build | 0 of 5 | 5 of 5 locally, but `skt check` only reaches homes when skt#46 merges and releases | **Needs an owner decision:** merge and release skt#46 before evaluation, or the goal reads 4 of 5 |
| GOAL-one-record | root 15+14, project 7+0; fixpoint; versions 5/1 | Fixpoint fixed; project home prune leaves 7 missing (all PATH-served shims, DEF-OHV-130); tla-spec-dev-2 180→168 artifacts, 6 ledger-only kept (DEF-OHV-131) | **Clause (1)'s "0 and 0" target will be missed** as the census models it today. Decide now: rescope clause (1) to "no row whose owner is gone and whose bytes are gone", or add a ticket for PATH-served shims and past-removal rows |
| GOAL-one-verdict | 50/61 fleet disagreements; 4+4 | Instrument exists (home-verdicts, 4 shapes pinned); no movement yet (OHV-2 onward) | On schedule |
| GOAL-no-own-home-path | root 3 | Instrument reproduces 3; no movement yet (OHV-4) | On schedule |
| GOAL-one-marketplace-identity | root shape 4 | Instrument reproduces; no movement yet (OHV-6) | On schedule |

**More expensive if deferred:** DEF-OHV-010, since every wave's agent runs the suite; the GOAL-one-record clause (1) decision; the skt#46 release.

**Worktrees and headroom:** 6 epic worktrees standing (epic + OHV-0/1/3/5/7), about 470 MiB each for homes; 88 GiB free. An unrelated `skill-manager-wide-evals` worktree (feature/wide-small-evals) exists from another session and is not the epic's. Local Docker has been unresponsive since the disk filled (`docker info` gives no answer at wave close), so wave-2+ agents will iterate on graphs through CI unless Docker is restarted. An owner decision is pending.

**Recommendation (default if you just say go ahead):** dispatch wave 2 (OHV-2) now; open an issue on main for DEF-OHV-010; rescope GOAL-one-record clause (1) as above at the next plan amendment; hold skt#46 until finalization and decide it there.

## Outcome

Recorded 2026-09-13 by the epic agent. Under `review_policy` (gate false, owner 2026-09-13) the epic continues without a stop. Owner decisions still pending, carried forward and **not** assumed:
1. Merge and release skt#46 (`skt check` build stamp) before evaluation?
2. Rescope GOAL-one-record clause (1), or add a ticket for PATH-served shims and past-removal projection rows (DEF-OHV-130/131)?
3. Open an issue on main for DEF-OHV-010 (the unit suite's temp-fixture leak)?
4. Restart local Docker Desktop?

Next: wave 2, OHV-2 (#339).
