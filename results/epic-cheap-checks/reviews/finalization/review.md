# Finalization review: cheap-checks (#414)

Policy: `cadence: finalization-only`, so this is the only review artifact, and no wave artifacts were produced by design. Range `48a07c1d..980d5f09`. Diff: `diff.stat`, `diff.patch` (code only; `results/` and the plan left out). Merge order: `merges.md`.

## 1. Hot spots

| File | Tickets | Why look |
| --- | --- | --- |
| `source/GitOps.java` (+183) | CC-1, CC-5, CC-7 | `cleanCommit`, `cleanTree`, `refreshIndex`: every "git says nothing changed" answer starts here |
| `store/Fetcher.java`, `source/SparseInstall.java` (+173) | CC-5 | New install path: partial clone plus sparse checkout |
| `bindings/ChildHomeMaterializer.java` (+107) | CC-2, CC-4 | Sync scoping for installers and projection |
| `effects/{SkillEffect,LiveInterpreter,DryRunInterpreter}` | CC-2, CC-4 | Same scoping, carried through effects |
| `artifacts/ArtifactBackfill.java` | CC-1, CC-7 | Freshness records |

## 2. Decisions made, and guardrails overridden

Owner-decided (conversation, recorded in the plan header): identity is git; gitignored files are not identity; `sync --force-scripts` scoped as merged; no TLA+ workflow; `cheap-checks` stays local-only; two pre-existing red graphs fixed in the epic; manual acceptance agent-run.

Decided by agents, not put to the owner:

| Decision | Where | Reversal cost |
| --- | --- | --- |
| Git may only confirm CURRENT; any doubt falls back to bytes | `ArtifactFreshness.byInstallFingerprint` | Low: it is the conservative direction |
| Index refresh refused when the git directory is outside the checkout | `GitOps.refreshIndex` | Low |
| Stamped rows only after bytes are re-verified | `CliInstallRecorder.stampGitInputs` | Low |
| `HomeSyncStaleBaselineTest` setup changed: refresh removed the side effect it relied on; assertion unchanged | CC-7, #451 | Low |
| `KNOWN_GOOD` replaced `wt`, which only some published skill versions mention | CC-8, #456 | Low |

Guardrails overridden: none. No target was edited. No `cheap-checks` assertion or allowlist was changed to get green.

## 3. Where the bugs probably are (suspicions)

1. **An old home stays slow.** Observed, see `manual/acceptance.md`: 12.9 s against a pre-CC-7 destination. Settle: re-clone and re-time, already done for the fixed case.
2. **Sparse install against a host that ignores `--filter`.** The node covers the fallback; real hosts other than local git and GitHub are untested. Settle: install from another host.
3. **`gitInputs` depends on `skill-scripts/` being the unit's input.** A script reading a file outside that directory is not in the digest, as with the byte fingerprint before it. Settle: read one unit's install script that reaches outside.

## 4. What the epic agent applied, and what it still owes

- **Model delta applied:** none. No TLA+ workflow in this epic (`spec_workflow.state: none`).
- **Anchors placed:** none. No model; no finding was anchored. Findings are in `deferred/`.
- **Improvement-card row:** unmeasured. No predecessor epic was carded and this epic did not produce a sealed card.
- **Skill changes applied or declined:** tla-spec-dev-plugin#242 declares the `specs/.history` exclusion; open, unmerged, to merge once this epic ships. No other unit changed: every worktree close-out was safe with 0 blockers.
- **Model corrections owed by merged tickets:** none.

Recommendations: none beyond #454 and #455.

## 5. Next steps

**Goal table.** Readings are the `cheap-checks` graph on the integrated tip, sweep `20261109-175143`. Over the machine's load and CC-6's three fresh runs, see `goals/verdicts.json`.

| Goal | Clause | Kind | Baseline | Measured | Target | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| checking-costs-what-changed | (a) stale time | perf | 8.02 s | 3.34 s (CC-6 medians 3.36, 3.48, 4.31, 3.13) | < 6 s | met |
| | (a) files read, clean | perf | 26,170 | 0 (26,170 when dirty) | 0 | met |
| | (a) planted edits | perf | 3 of 4 arms | 3 of 3 | tracked, untracked, re-pointed link | met |
| | (b) close-out time | perf | 45.69 s | 3.53 s | < 5 s | met |
| | (b) close-out RSS | perf | 1,277 MB | 236 MB | < 300 MB | met |
| | (c) other installers re-run | perf | 2 of 2 | 0 of 2 | 0 | met |
| | (d) other plugins re-projected | perf | 3 calls of 11 | 0 of 8 | 0 | met |
| excluded-paths-keep-git | (a) files at excluded path | correctness | 2 | 0 | 0 | met |
| | (b) git operations | correctness | 7 of 7 | 7 of 7 | all 7 | met |
| | (c) commit keeps tree | correctness | yes | yes | yes | met |

Caveats: clause (b) holds for homes cloned by this build; see `manual/acceptance.md` for homes made earlier. One `artifacts stale` reading of 6.31 s appeared in CC-6's run 3; the clause is on the median.

**Deferred findings, all settled.**

| ID | Severity | Disposition |
| --- | --- | --- |
| DEF-CC6-001, -002 (`doc-smoke`, `onboarding`) | low | fixed in CC-8 |
| DEF-CC6-003 (nightly CI) | medium | fixed in CC-8, local-only |
| DEF-CC6-004 (close-out with one edit in a big plugin: 25-27 s) | low | carried, #454 |
| DEF-CC3-002, DEF-CC5-001, DEF-CC8-001 | low | carried, #455 |
| DEF-CC-EPIC-001 | medium | resolved: one node was DEF-CC6-002, the other did not reproduce |
| DEF-CC0-002, DEF-CC1-001, DEF-CC1-002, DEF-CC3-001 | | closed by measurement, CC-7, or owner ruling |
| DEF-CC7-001 | low | no action: a home's rows upgrade on its next sync |

**Standing worktrees:** 10, all safe, 2.8 TiB free on `/Volumes/dev`. Swept after the `main` merge is verified.

**Recommendation:** merge after reading `manual/acceptance.md`. Rebase-merge, or keep a `feat:` subject on a squash, so release-please sees the release.
