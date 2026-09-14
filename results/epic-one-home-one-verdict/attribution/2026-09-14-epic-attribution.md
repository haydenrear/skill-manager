# Bug attribution: epic `one-home-one-verdict` (#337), with the TLA+ model update

Written 2026-09-14 against `epic/one-home-one-verdict` at `833ae0d7`, after the epic closed. The owner asked for two things before the epic PR (#371) merges: model the epic's changes in TLA+, and write its full attribution. The standard is spec-double-compiler's `references/bug_attribution.md`.

- **Machine-readable rows:** [`2026-09-14-epic-attribution.yaml`](2026-09-14-epic-attribution.yaml). It holds every CATCH row with channel, class, area, anchor, origin, why it escaped, fix and pin, plus the REACH, BLIND and bin records.
- **TLC transcripts:** [`tlc/`](tlc/). One `.out` per configuration, `results.json`, and `generate_and_run.py`, which wrote the configurations and ran them.
- **Where the rows belong:** there is no `specs/deferred_findings.yaml` on main, so per §4 the rows live here. `deferred/backlog.yaml` was not edited.

## 1. The record in numbers

**59 rows:** the 25 backlog findings, 8 goal defects the backlog never held, 12 defects found and fixed inside tickets, 11 of the epic agent's own process errors, and 3 errors in this record itself.

Counted from the YAML's per-row `kind` and `class` fields by script, not by hand.

| | product | instrument | process | total |
| --- | ---: | ---: | ---: | ---: |
| backlog | 16 | 7 | 2 | 25 |
| goal defects not in backlog | 4 | 4 | 0 | 8 |
| in-epic | 3 | 8 | 1 | 12 |
| epic agent process | 0 | 0 | 11 | 11 |
| this record | 0 | 0 | 3 | 3 |
| **total** | **23** | **19** | **17** | **59** |

| class (§4.1 lookup) | backlog | goal defects | in-epic | process | this record | total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| automated | 7 | 6 | 5 | 2 | 0 | **20** |
| hand | 16 | 2 | 6 | 9 | 2 | **35** |
| reading | 2 | 0 | 1 | 0 | 1 | **4** |

**The instrument produced nearly as many defects as the product.** 19 instrument rows against 23 product rows. Read as product quality, the total would be inverted (§6a). Every instrument row's `area` names the instrument.

**Hand catches outnumber automated ones, 35 to 20, and the backlog is where the gap is: 16 to 7.** The product defects the epic set out to fix were found mostly by instruments written for the occasion (`operator-running-own-instrument`). The standing detectors were silent, and that silence was the defect. The goal defects outside the backlog run the other way, 6 automated to 2 hand, because most were red CI graphs.

## 2. The model update

### What changed in `specs/program_model`

| file | change |
| --- | --- |
| `HomeVerdictsInternal.tla` | **new**: 6 disjoint policy groups, 23 actions, 21 invariants, 2 finding invariants, 6 reachability probes, 16 policy constants |
| `HomeVerdictsInternal.cfg` | **new**: the healthy configuration |
| `HomeVerdictsInternal_regression_*.cfg` | **new, 19**: each flips one constant back to shipped pre-fix behaviour and must fail |
| `HomeVerdictsInternal_finding_*.cfg` | **new, 2**: the healthy model against a stronger reading it does not satisfy |
| `HomeVerdictsInternal_probe_reach.cfg` | **new**: must report 6 violations under `-continue` |
| `HomeIntegrityInternal.tla` | a header pointer to the sibling module; no semantic change |
| `README.md` | a "Home verdicts" section |

**Why a sibling module, not more groups in `HomeIntegrityInternal`.** That module interleaves all its groups, so its state space is their product (24,000 distinct states). Here `Init` picks one group per behaviour, so the state space is their sum (578). Nothing is lost, because the groups are disjoint and each invariant reads only its own group. The header states that argument and the condition that keeps it true.

**No case adapters.** `HomeIntegrityInternal` has none either. `case_adapters.toml` maps only `SkillManager.tla`'s CLI disclosure actions, so the `@command` annotations document production paths and nothing generates cases from them. The executable pins remain the home-verdicts nodes and unit tests named in each group's header.

**Not changed:** `External.tla`, `Internal.tla`, `SkillManager.tla`, `Core.tla`, `ClaimantRefresh*`, and their configurations. None had an action where these behaviours live. §7 records `External.WritesThroughOneHomeReachNoOtherHome` as a BLIND against DEF-OHV-011.

### The six groups

| group | epic behaviour | constants (healthy → pre-fix) |
| --- | --- | --- |
| verdicts | (a) verify fails iff repair counts a finding; the new detector kinds; the /var alias; the build stamp | `VerifyComposition` COMPOSES_REPAIR → OWN_WALK_ONLY / COMPOSES_WITHOUT_EXEMPTION; `DetectorReach` → STORE_ONLY; `DetectorSpellings` → GIVEN_AND_REAL; `VerdictStamp` → UNSTAMPED |
| shims | (c) no own-home path; the copied home still works | `ShimDetection` CONTENT → REWRITE_AVAILABLE; `ShimRewriteScope` EVERY_LINE → TOKEN_MEANS_DONE |
| prune | (b) prune fixpoint; reaping on uninstall and retirement | `PruneRerecord` → REBUILD_FROM_INDEX; `TeardownReaping` → LEAVE_ROWS / REAP_UNCONDITIONALLY |
| records | OHV-3 (c) record version | `RecordVersionPolicy` → HASH_ONLY |
| marketplace | (d) one marketplace identity per home | `MarketplaceMatch` → SUBSTRING; `ManifestIdentity` → TRUSTED_FROM_DISK; `MarketplaceRepair` → NONE |
| writes | (e) a home writes only itself | `BinWritePolicy` → WRITE_THROUGH; `LauncherWrite` → FOLLOW_LINK; `DetachRestore` → DETACH_ONLY; `ForeignWriteCheck` → NONE |

### TLC results

Each configuration was run on a scratch copy of the directory. The runner is `/Users/hayde/.skill-manager/bin/cli/tlc2` (tla2tools via spec-double-compiler), invoked as:

```
tlc2 -workers 1 -config <cfg> HomeVerdictsInternal.tla        # probe: add -continue
```

`specs/program_model/run_tlc.sh HomeVerdictsInternal.tla <cfg>` is equivalent for every configuration except the probe. `results/epic-one-home-one-verdict/attribution/tlc/generate_and_run.py` rewrites all 23 configurations and re-runs them; its scratch and `tlc2` paths are this machine's.

| cfg | historical defect / role | expected | actual | distinct states | trace depth |
| --- | --- | --- | --- | ---: | ---: |
| `HomeVerdictsInternal.cfg` | healthy | no error | **no error** | 578 (3,370 generated) | graph depth 7 |
| `_regression_verifyownwalk` | DEF-OHV-002 (9a547b20, f95a0f98) | VerifyFailsOnEveryCountedRepairFinding | **violated** | 357 | 3 |
| `_regression_blinddetectors` | DEF-OHV-002 detectors | EveryPlantedFactIsFoundByRepair | **violated** | 366 | 3 |
| `_regression_aliasspelling` | #343 (c0aae68e) | EveryPlantedFactIsFoundByRepair | **violated** | 362 | 3 |
| `_regression_noexemption` | IE-01, the over-fix inside OHV-2 | VerifyPassesWhenRepairCountsNothing | **violated** | 371 | 3 |
| `_regression_unstamped` | #338 | AVerdictNamesTheBuildThatProducedIt | **violated** | 13 | 2 |
| `_regression_tokenmeansdone` | DEF-OHV-001 writer (43e5fb99) | AFreshlyWrittenShimNamesNoHome | **violated** | 16 | 2 |
| `_regression_foreignfixhalfrewrite` | DEF-OHV-180 second write (43e5fb99) | ARepairedShimNamesNoHome | **violated** | 380 | 3 |
| `_regression_rewritegateddetector` | DEF-OHV-001 as shipped (ffa2108b + 43e5fb99) | AShimSpellingItsOwnHomeIsReported | **violated** | 381 | 3 |
| `_regression_rebuildfromindex` | #292 fixpoint (468daf8f) | PruneStaysPruned | **violated** | 35 | 2 |
| `_regression_leaverows` | DEF-OHV-003 / DEF-HBR-003 (468daf8f) | RowsProvenAbsentAtRemovalAreReaped | **violated** | 35 | 2 |
| `_regression_reapunconditionally` | #292's reverted first fix (252c6c48 / c4f7dff8) | ALinkStillOnDiskKeepsItsRow | **violated** | 35 | 2 |
| `_regression_hashonly` | DEF-OHV-004 (39e42838) | ASyncedRecordAgreesWithItsCheckout | **violated** | 39 | 2 |
| `_regression_substring` | #352 shapes 1/2 (61e9553b) | ASyncRegistersUnderItsOwnIdentity | **violated** | 417 | 3 |
| `_regression_trustedmanifest` | #352 shape 3 | ASyncRegistersUnderItsOwnIdentity | **violated** | 416 | 3 |
| `_regression_nomarketplacerepair` | DEF-OHV-005 / shape 4 | AfterRepairAndSyncNothingForeignIsEnabled | **violated** | 798 | 4 |
| `_regression_writethrough` | DEF-OHV-011 / 180 first write (9c8480fb, 3cbcba85) | AnOwnEntryWriteStaysInItsHome | **violated** | 435 | 3 |
| `_regression_launcherfollow` | IE-07 LauncherShims (c94e791b, e65962ef) | AGeneratedLauncherIsWrittenIntoItsOwnHome | **violated** | 438 | 3 |
| `_regression_nostatcheck` | design pin, not a shipped defect | AWriteThroughADetachedLinkIsRefused | **violated** | 436 | 3 |
| `_regression_norestore` | design pin, not a shipped defect | ALinkTheInstallerDidNotReplaceIsPutBack | **violated** | 436 | 3 |
| `_finding_prunedresidue` | new finding, §9 | NoProjectionRowOutlivesItsLink | **violated** | 411 | 3 |
| `_finding_outrightpath` | OHV-9's stated limit | AnyWriteIntoAnotherHomeIsRefused | **violated** | 344 | 2 |
| `_probe_reach` (`-continue`) | vacuity check | 6 probe violations | **all 6** (12 violating states) | 578 | — |

**23 of 23 as expected.** The existing `HomeIntegrityInternal.cfg` was re-run after its header edit: no error, 24,000 distinct states, 158,801 generated (`tlc/HomeIntegrityInternal.out`). `uv run --with pytest --with pyyaml pytest specs/program_model/tests -q`: 11 passed.

**Checked by reading, not only by the name TLC printed.** Every regression trace was read for the shape its header claims (vacuity ledger, mechanism A). Two headers were wrong and are corrected (AT-OHV-02):
- `foreignfixhalfrewrite`: TLC's shortest trace takes the FROZEN finding's `--fix`, not the FOREIGN path DEF-OHV-180 took. Both are depth 3 and both call the same rewrite.
- `rebuildfromindex`: TLC prints the removal's own reap at depth 2. The whole-home prune from #292 fails the same way at depth 3.

Three configurations list fewer invariants than the rest, and each header says which were dropped and why: the same constant fails a neighbour at the same or a shallower depth.

### What the models do not hold

- **`ACommentOnlySpellingIsNotAFinding` has no regression configuration.** Whether 0.27.2 reported comment-only shims was not established, and the invariant's comment says so.
- **Everything in the healthy model's BLIND row (§7):** concurrency, shell dialects, venv shebangs, pip/uv, Gemini, unit-store rows, and cross-group interactions.
- **Not modelled at all:** OHV-5's CI provisioning, OHV-7's sweep, the goal harnesses, onboarding, and telemetry. They sit in the `UNMODELED` bins in §8.

## 3. Defects the epic fixed

Origins come from `git log -S` / `-G` and `git blame` on `origin/main` (71c51464). PR numbers come from `gh api .../commits/<sha>/pulls`. Merge SHAs are the PRs' merge commits into the epic branch. Full rows are in the YAML.

| id | defect | origin | why it escaped | fixed by | pinned now by |
| --- | --- | --- | --- | --- | --- |
| DEF-OHV-002 | verify 0 while repair reports damage (50/61); no agent-link or projection-record detector | 9a547b20 (#130) verify walk; f95a0f98 (#244) repair walk never composed; detectors never existed | each command tested alone; no fixture had links outside the store | OHV-2 #364 `48d23c51` | home-verdicts `verify.names.every.repair.finding`, `dangling.agent.link`, `orphaned.projection.record`; `_regression_verifyownwalk`, `_blinddetectors` |
| DEF-OHV-001 | half-rewritten shim exempt from both verdicts | 43e5fb99 (#323) token-means-done; ffa2108b (#335) detector gated on rewrite | fixtures planted only fully frozen shims | OHV-4 #365 `42486f86` | `half.rewritten.shim`; ShimSurvivesACopyTest; `_rewritegateddetector`, `_tokenmeansdone` |
| GD-292 | prune re-record restores what it pruned (59→59) | 468daf8f (#207); first fix 252c6c48 reverted c4f7dff8 | one-pass tests | OHV-3 #361 `3d6d4cd4` (a) | artifact-dag `uninstall.prunes.the.subgraph`; `_rebuildfromindex`, `_reapunconditionally` |
| DEF-OHV-003 | uninstall/retirement leave ledger rows | 468daf8f (#207) | no uninstall test read the census | OHV-3 (b) `3d6d4cd4` | artifact-dag; ArtifactPruneTest; `_leaverows` |
| DEF-OHV-004 | record version stale while hash current | 39e42838 (#45) withGitMoved; 14e05bb2 early return | sync tests checked the hash only | OHV-3 (c) `3d6d4cd4` | RecordVersionRefreshTest; `_hashonly` (no graph node) |
| GD-338 | verdicts name no build | none: BuildIdentity was `--version`-only since 7ae6eccc | nothing required it | OHV-1 #360 `8bd883f3` (+ skt#46, unmerged) | VerdictsNameTheirBuildTest; `_unstamped` |
| GD-343 | /var reference missed when home given as /private/var | c0aae68e (#228), 43e5fb99 (#323) | reverse spelling untested; Linux cannot express it | OHV-5 #362 `f38a9298` | HomeVerifyPathSpellingTest; `_aliasspelling` (no graph node, DEF-OHV-185) |
| GD-344 | fixpoint law fails on planted damage | 25906875 (#162) | no intentional-damage declaration existed | OHV-5 `f38a9298` | home-clone / checkout-home `home.fixpoint.law` |
| GD-345 | three graphs cannot pass on a fresh runner | 231b9221, a8f17c03 (#141), 1dbfcc64 (#151) | graph matrix gated off on CI (never ran) | OHV-5 `f38a9298` | CI run 34862952922, 26/26/0 |
| GD-297 | digest hashes a live gateway's log | 5ca3cb07 (#130) | no gateway attached when written | OHV-5 `f38a9298` | home-clone nodes, OTEL unset |
| GD-357 | `run.py --all` stops at first red, counts stale reports | a4f9f262 (#8) | no wrapper tests; CI graphs gated off | OHV-7 #363 `b218322c` | `test_run.py::test_multi_graph_continues_and_stale_reports_are_not_counted` |
| GD-352 | marketplace shapes 1–3 | 61e9553b (#165), f14c2117; trusted-manifest reader UNTRACED | single-marketplace fake driver; CLI keep-old-name behaviour unmeasured | OHV-6 #366 `7357b8ab` (+ dc4f6356) | 3 home-verdicts nodes; HarnessPluginCliTest; `_substring`, `_trustedmanifest` |
| DEF-OHV-005 | another home's marketplace enabled in root's configs | detector never existed; the writing event UNTRACED | HomeRepair had no marketplace kinds | OHV-6 `7357b8ab` | `foreign.marketplace.registration`; `_nomarketplacerepair` |
| DEF-OHV-011 | skill-script install wrote through a bin/cli link into root | 9c8480fb (#97), 3cbcba85 (#287); CDC#262; deploy-helm `cat >` | no test put a foreign link at bin/cli; External model BLIND | OHV-9 #368 `5ab5770b` (+ deploy-helm#63 open) | `child.install.writes.only.itself`; SkillScriptWriteThroughTest; `_writethrough` |
| IE-07 | LauncherShims wrote a child's pin into the parent | c94e791b, e65962ef (#130) | single-home tests | OHV-9 `5ab5770b` | BinCliWritersDoNotFollowLinksTest; `_launcherfollow` |

## 4. The 25 backlog findings

| id | channel (class) | anchor | origin | disposition | pin |
| --- | --- | --- | --- | --- | --- |
| DEF-OHV-001 | shipped instrument (A) | HVI.ObserveShim | 43e5fb99, ffa2108b | ticketed OHV-4 | graph + unit + 2 cfgs |
| DEF-OHV-002 | own instrument (H) | HVI.ObserveWithVerifyAndRepair | 9a547b20, f95a0f98 | ticketed OHV-2 | graph + unit + 2 cfgs |
| DEF-OHV-003 | shipped instrument (A) | HVI.Uninstall | 468daf8f | ticketed OHV-3 | graph + unit + 2 cfgs |
| DEF-OHV-004 | own instrument (H) | HVI.SyncAlreadyUpToDate | 39e42838 | ticketed OHV-3 | unit + cfg; no graph |
| DEF-OHV-005 | own instrument (H) | HVI.RepairFixMarketplace | UNTRACED (event not on disk) | ticketed OHV-6 | graph + unit + cfg |
| DEF-OHV-006 | doing the work (H) | UNMODELED/skt-ticket-sweep | UNTRACED (other repo, not attempted) | carried skt#47 | none |
| DEF-OHV-007 | doing the work (H) | UNMODELED/goal-harnesses | 6291de75 | carried #375 | none |
| DEF-OHV-008 | doing the work (H) | UNMODELED/test-hygiene | UNTRACED (fixture not identified) | carried #372 | none |
| DEF-OHV-009 | shipped instrument (A) | UNMODELED/epic-workflow-skills | UNTRACED (other repo) | carried git-epic-workflow#19 | none |
| DEF-OHV-010 | doing the work (H) | UNMODELED/test-hygiene | 42d00f81 (low confidence) | carried #372 | none |
| DEF-OHV-130 | own instrument (H) | UNMODELED/census-path-served-shims | UNTRACED (not attempted) | carried #374 | goal rescoped |
| DEF-OHV-131 | own instrument (H) | HVI.LegacyRemoval | 468daf8f | carried #374 | admitted state; see §9 |
| DEF-OHV-120 | census (R) | UNMODELED/epic-workflow-skills | UNTRACED (other repos) | carried | none |
| DEF-OHV-121 | test-graph (A) | UNMODELED/test-hygiene | cad0b830 | carried #372 | none |
| DEF-OHV-140 | own instrument (H) | UNMODELED/cli-telemetry-export | 9cd229af | carried #373 | none |
| DEF-OHV-160 | doing the work (H) | UNMODELED/goal-harnesses | 7d2eef26 (inside this epic) | carried #375 | none |
| DEF-OHV-011 | doing the work (H) | HVI.SkillScriptInstall | 9c8480fb, 3cbcba85 | ticketed OHV-9 | graph + unit + cfg |
| DEF-OHV-180 | shipped instrument (A) | HVI.SkillScriptInstall (+ RepairFixShim) | as 011 and 001 | real home fixed; carried #378, CDC#262 | 2 cfgs; recurrence unpinned |
| DEF-OHV-181 | shipped instrument (A) | UNMODELED/real-home-operations | plan scope | fixed inline | none (operations) |
| DEF-OHV-182 | shipped instrument (A) | HVI.SyncAlreadyUpToDate | as 004 | fixed inline | unit + cfg |
| DEF-OHV-183 | own instrument (H) | UNMODELED/onboarding | 9f7784ef (medium) | carried #376 | none |
| DEF-OHV-184 | own instrument (H) | UNMODELED/onboarding | 860ca0da / e65962ef never wired (low) | carried #376 | none |
| DEF-OHV-185 | census (R) | HVI.ObserveWithVerifyAndRepair | OHV-0/OHV-5 scope | carried #377 | unit (macOS) + cfg; no node |
| DEF-OHV-186 | own instrument (H) | UNMODELED/project-resolve-errors | 93ed6e84 (4c7d6bab) | carried #376 | none |
| DEF-OHV-187 | doing the work (H) | UNMODELED/goal-harnesses | 038167de | carried #375 | none |

## 5. Defects found and fixed inside the epic

Twelve rows, none in the backlog, because each was fixed inside the ticket that found it.

| id | what | channel | fixed | pin |
| --- | --- | --- | --- | --- |
| IE-01 | composing repair into verify without the exemption broke every worktree clone | the-suite | OHV-2 | ChildHomeShimIsolationTest; `_noexemption` |
| IE-02 | HomeUnresolvedGateTest's fixture wrote the frozen shape | the-suite | OHV-2 | that test |
| IE-03 | IntentionalDamage parsed only "do not resolve" | test-graph (CI 34802282392) | OHV-2 | `home.fixpoint.law` |
| IE-04 | `pm/uv` declaration green in one graph, red in the other | test-graph | OHV-2 `6eb77ce4` | `home.fixpoint.law` |
| IE-05 | hc-tool undeclared once detection read content | test-graph (predicted) | OHV-4 | `home.fixpoint.law` |
| IE-06 | repaired agent config lost its trailing newline | own instrument (root simulation) | OHV-6 `dc4f6356` | MarketplaceRegistrationsTest |
| IE-07 | LauncherShims followed a link into the parent | own instrument (audit unit case) | OHV-9 | unit + `_launcherfollow` |
| IE-08 | RepairReport matched by JSON key order | independent-review | OHV-0 | `clean.home` parser self-check |
| IE-09 | OHV-7's planned signal would not show continuation | doing the work | OHV-7 | none (plan) |
| IE-10 | PATH symlink cannot start `./skill-manager` | doing the work | OHV-8 run 2 | none |
| IE-11 | release_regression passed no `SKILL_MANAGER_HOME` | doing the work | OHV-8 | none |
| IE-12 | harnesses judged each home by its own pin | doing the work | OHV-8 | none |

## 6. Root-cause chain: the operator's root shims (DEF-OHV-011, DEF-OHV-180)

Each link below is a necessary cause: remove any one and the root is not written. The right-hand column says what now holds each link and what does not.

| # | link | evidence | held now by |
| --- | --- | --- | --- |
| 1 | **commit-diff-context-parent's test fixture parents a test project home on the operator's real `~/.skill-manager`** (`cdcWorktreeOverlayIsolation`, then `skill-manager-worktree-lifecycle` on `epic/library-sharing`) | `goals/GOAL-one-marketplace-identity/2026-09-14-root-fix/leak-cause/cdc-project-home-bin-cli.ls.txt`; `tickets/OHV-8/leak-2026-09-14T1531/disk-observation.txt` | **nothing in this repository**. CDC#262 is open |
| 2 | **the child home mirrors the parent's shims as symlinks** (`ChildHomeMaterializer.mirrorExistingShim`, the sanctioned parent-store mirror of HIS-7 / #223), so `bin/cli/{computeq,helm-deploy,monitoring}` point into root | same `ls` | by design; unchanged (wave-5 §4 proposes not linking units the child will install) |
| 3 | **deploy-helm's skill-script writes its launcher with `cat >"$launcher"`**, which follows a symlink | `install-console-script.sh`; `tickets/OHV-9/d-helper-proof.txt` | deploy-helm#63 (open): `rm -f "$launcher"` first |
| 4 | **`SkillScriptBackend` ran the script with foreign links in place** (9c8480fb, #97), and `InstallerRegistry.refuseAForeignDestination` walks past a sanctioned mirror | `SkillScriptWriteThroughTest` red before the fix (`tickets/OHV-9/unit/a-before-fix.txt`) | **OHV-9** `ForeignBinLinks` detach, restore and stat; `_regression_writethrough` |
| 5 | **nothing saw the write:** `binStamps` stamps links with `NOFOLLOW_LINKS`, and `reportFrozenShims` skips symlinks (3cbcba85, #287) | code read; OHV-9 README | OHV-9's `requireNoForeignWrite` names the other home. `home repair` on the root reports the result as FOREIGN_PATH_IN_SHIM, both builds |
| 6 | **the fix was unreleased.** At 11:31 EDT a CDC graph on `epic/library-sharing` ran a skill-manager without OHV-9 and wrote the root again | DEF-OHV-180; the shims' mtimes | **nothing until release** (#378) |
| 7 | **another session repaired the root with released 0.27.2.** `FOREIGN_PATH_IN_SHIM --fix` mapped the foreign path onto root's own and called the rewrite, which saw the token and stopped (43e5fb99). DEF-OHV-001's half-rewritten shape was back | `external-*.log` (11:50:45); `real-homes-run2/` | OHV-4's every-line rewrite; `_regression_foreignfixhalfrewrite` |
| 8 | **0.27.2's detector exempted that shape** (ffa2108b), so the released build called the root clean | `real-homes-run2/`: released 0 findings, epic build 3 | OHV-4's content detector; `_regression_rewritegateddetector` |
| 9 | **repaired** with the epic build, owner-approved, 12:26 EDT | `goals/2026-09-14-final-home-fix/` | — |

**Two faults in this repository are now held by code and a model** (links 4–5, 7–8). **Two sit outside it** (1 and 3). **One is a release gap that no code change here closes** (6). The model makes the two in-repo halves one chain: `_regression_writethrough` is the write through the link, `_regression_foreignfixhalfrewrite` is the released `--fix` that half-rewrote the result. They are two configurations, not one trace, because the groups are deliberately disjoint.

## 7. REACH and BLIND (§5, §6)

Full records are in the YAML. The rows that matter most:

- **REACH, verify iff repair.**
  - Enforced on `home verify` only.
  - **Unenforced:**
    - `artifacts list`'s projection `agreement` column. DEF-OHV-002 was first seen as verify against `artifacts list`, and `artifacts list` is still not composed with `HomeRepair`.
    - `home drift` and `skt check`.
    - `HomeFixpointLaw`'s text parser.
- **REACH, a home writes only itself.**
  - Enforced on every writer of `bin/cli`.
  - **Unenforced:**
    - a write by absolute path with no detached link (`_finding_outrightpath`);
    - a new name linked out of the home;
    - writes outside `bin/cli`;
    - a rewrite that preserves size, mtime and file key;
    - concurrent installs.
  - Directories other than `bin/cli` were not enumerated, so the list is UNDECIDED beyond this.
- **REACH, own-home path.**
  - Enforced on `bin/cli` and `bin/mcp`.
  - **Unenforced:**
    - venv shebangs (declared);
    - comment-only spellings, including a heredoc line starting with `#`;
    - **`#!/bin/sh` and `#!/bin/dash` shims, which the rewrite breaks** (§9).
- **BLIND, `External.WritesThroughOneHomeReachNoOtherHome`:** green, and could never have caught DEF-OHV-011, because no External action has a writer that follows a link.
- **BLIND, `HomeIntegrityInternal.ADamagedHomeIsNeverReportedClean`:** models one observer, so it could not catch two observers disagreeing.
- **BLIND, home-verdicts 18/18:**
  - cannot plant the /var shape on Linux;
  - its shims are bash, so the dash failure is invisible;
  - it runs the epic build, so a pre-OHV-9 writer is out of reach.
- **BLIND, `HomeVerdictsInternal.cfg`:** listed in full in the YAML. No concurrency, no shell dialects, no cross-group interaction.

## 8. Anchors, bins, conservation (§7a–7c)

| anchor | rows |
| --- | ---: |
| HVI.ObserveWithVerifyAndRepair | 5 |
| HVI.SkillScriptInstall | 2 |
| HVI.SyncAlreadyUpToDate | 2 |
| HVI.RepairFixMarketplace | 2 |
| HVI.ObserveShim, LauncherShimsWrite, Uninstall, PruneHome, LegacyRemoval, SyncMarketplace | 1 each (6) |
| **anchored** | **17** |
| UNMODELED/agent-process (RECORD-ONLY) | 14 |
| UNMODELED/test-graph-apparatus (RECORD-ONLY) | 8 |
| UNMODELED/goal-harnesses (RECORD-ONLY) | 6 |
| UNMODELED/test-hygiene (RECORD-ONLY) | 4 |
| UNMODELED/epic-workflow-skills (RECORD-ONLY) | 3 |
| UNMODELED/onboarding (UNDECIDED) | 2 |
| census-path-served-shims (MODELABLE), cli-telemetry-export (UNDECIDED), project-resolve-errors (UNDECIDED), real-home-operations (RECORD-ONLY), skt-ticket-sweep (DEFERRED: other repository) | 1 each (5) |
| **unmodeled** | **42** |

```
findings before: 59 (unanchored)    findings after: 59    (17 -> HomeVerdictsInternal actions, 42 -> 11 UNMODELED bins)
```

`HVI.ObserveWithVerifyAndRepair` carries 5: one headline defect, two spelling and stamp defects, the over-fix, and a pin gap. One module-level escape is not a concentration (§7a). **The escape to watch is the apparatus.** 18 of the 42 unmodeled rows are test-graph, harness or test-hygiene defects, close to the 23 product rows the epic was for.

**PRICE: none.** Schedule revision 2 refused the one-reader and one-writer refactors with no `declared_before`. §7 says a refused proposal keeps one, so it is recorded here as missing, not reconstructed.

## 9. Found while modelling: suspicious, not fixed

1. **The shim rewrite breaks `#!/bin/sh` and `#!/bin/dash` shims.**
   - **Measured here:** `ShimHomeContract.selfDerivingRewrite`'s preamble is `SKILL_MANAGER_SHIM_HOME="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../.." && pwd)"`, and `isShellShebang` accepts `sh` and `dash`. A scratch shim run under `/bin/dash` printed `Bad substitution` and then `exec: //cache/t/tool: not found`, rc 127. The same body under bash ran its tool.
   - **Origin:** 43e5fb99. On macOS `/bin/sh` is bash, so no local home breaks. On Debian or Ubuntu, `/bin/sh` is dash.
   - **Why it matters more now:** OHV-4 widened where the rewrite runs. `--fix` now rewrites shims the old detector exempted, and the installer rewrites half-adopted ones.
   - **No test or graph covers it:** fixtures are bash.
2. **OHV-3 can still produce DEF-OHV-131's shape** (`_finding_prunedresidue`).
   - **The path:** a post-OHV-3 uninstall leaves an agent link dangling, and correctly keeps the projection row. `home repair --fix` then removes the link (DANGLING_AGENT_LINK, OHV-2). No later `artifacts prune` can prove the row gone, because the evidence rode only in the removal's own `PruneOrphanArtifacts` effect.
   - **Effect on GOAL-one-record clause (1):** it counts "ledger-only rows produced by a removal after OHV-3", so this path can move it.
   - **Status:** modelled, not reproduced on a real home.
3. **`PipBackend`: a failing `uv tool install` skips `requireNoForeignWrite`.** `Shell.mustWithEnv` throws, `finally` restores the links, and the foreign-write check never runs. `SkillScriptBackend` reads a return code and checks first; pip does not. Low impact, because the links were detached, but the two backends disagree about ordering.
4. **The unscoped reading of GOAL-a-home-writes-only-itself clause (2) is false of the delivered guard** (`_finding_outrightpath`). A script that writes the parent's file by absolute path, with no link to detach, installs successfully. ForeignBinLinks' javadoc states the limit; no test or graph pins it, and the goal text does not carry the scope.
5. **`artifacts list` is still a second verdict on projections, not composed with `home repair`** (REACH above). That is the disagreement DEF-OHV-002 was first measured as.
6. **Already named by the epic's own reviews, and consistent with the model:**
   - a new bin/cli name linked out of the home is not guarded;
   - a rewrite preserving size, mtime and inode;
   - concurrent detach and restore;
   - a crash between detach and restore;
   - `#` inside a heredoc read as a comment.

## 10. Untraced, and what was tried

| row | tried |
| --- | --- |
| DEF-OHV-005 (the event that wrote CDC's marketplace into root's configs) | no audit or log records it; a user-level file is out of `git log -S` reach |
| GD-352 (reader trusting a manifest name from disk) | `git blame` on PluginMarketplace and HomeCloner; no single commit |
| DEF-OHV-001 (which bulk writer left the root shims at Sep 13 11:41:50) | OHV-4 fits the FOREIGN fix path; nothing on disk proves it (OHV-4 README) |
| DEF-OHV-006, 009, 120 | not attempted: the code is in skt, git-epic-workflow and git-issue-workflow |
| DEF-OHV-008 | the kickoff `ps` only; the fixture that started the gateway was not identified |
| DEF-OHV-130 | not attempted |
| DEF-OHV-010, 184 | origins are low confidence: the earliest commit carrying the prefix or class, not a bisected leak |

## 11. The epic agent's own process errors

The eleven are in the YAML as PE-01..11. Ten come from the kickoff attribution (`2026-09-13-kickoff-baseline.md`; its zero-count "read-only verb" row is not a defect and is not counted); PE-11 is from the wave-3 review.
- Five are shell traps: zsh word-splitting twice, zsh NOMATCH, bash 3.2, and a misread `gh` JSON shape.
- A safety check was run but not used as a gate.
- PR titles were missing the Conventional Commits prefix.
- A placeholder was left in a harness.
- The disk was exhausted by parallel worktrees.
- One script parsed stdout and stderr as one stream.
- Root backups were kept in a scratchpad a reboot wiped.

**This record's own three (AT-OHV-01..03):**
- a `====` line mid-module that would have ended the TLA module before its actions;
- two configuration headers that claimed a counterexample path TLC did not print, corrected after reading the traces;
- a zsh `=word` expansion that aborted one read.

`2026-09-13-migration-0.27.md` is the 0.27 release's attribution, written before this epic started, and is not recounted here.
