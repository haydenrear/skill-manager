# Manual acceptance: cheap-checks

**Tested commit:** `980d5f09`. **Environment:** macOS 26.6.2, raw epic build, on `/Volumes/dev`, 2026-11-09.
**Tester:** the epic agent, by owner decision. The owner reviews this record and accepts or rejects it. No human exercised the product.
**Isolation:** scratch homes, with the store and all three agent homes redirected. The scratch homes were deleted afterwards. Raw output: `raw-run.txt`.

| # | Scenario | Expected | Actual | Result |
| --- | --- | --- | --- | --- |
| M1 | `home clone` of a real home (26,413-file plugin) | Clone completes, plugin checkout clean | 64.5 s, rc 0, 0 dirty paths | pass |
| M2 | `artifacts stale` on the clean clone, 3 runs | Under 6 s | 3.73, 3.40, 3.45 s | pass |
| M3 | Tracked edit to `skills/skt/SKILL.md`, then restore | One new stale row, gone on restore | 23 to 24 (11.88 s), back to 23 (3.58 s) | pass |
| M4 | Untracked file in the plugin, then remove | One new stale row, gone on removal | 23 to 24 (11.27 s), back to 23 (3.61 s) | pass |
| M5 | `sync tracing-observability` | Only that unit's installer runs; plugin untouched | 4.92 s; one new installer log, tracing's own; plugin commit unchanged, 0 dirty | pass |
| M6a | `home close-out` clean, both homes cloned by the epic build | Safe, under 5 s | 3.53, 3.46, 3.39 s, safe | pass |
| M6b | `home close-out` with an edited unit | Refuses, names the unit | rc 1, `1 unit(s) ... would be lost`, names `skill:tracing-observability` | pass |
| M7 | Install a unit declaring `excluded_from_install = ["specs/.history"]` | No files on disk; history intact; full git | 0 files on disk, 1 path in HEAD, 2 blobs missing; `log` (2 commits), `blame`, `diff`, `status`, `commit`, `push` all work; path still in tree after commit | pass |

The stale-check times above an edit (11-12 s) are the byte comparison a dirty checkout falls back to, as designed.

## Three readings investigated

1. **23 stale rows on a fresh clone: not from this epic.** The `main` build reports the same rows on the same home, apart from item 3 below. They are projections recorded against other agent homes (this test redirected them) and CLI shims whose targets the source worktree home does not hold (10 rows there on both builds). The project home reports 0 stale.
2. **Excluded unit `unit-store:manual-x` read stale in M7: correct.** The scenario had just made a commit in it, so the store had moved from the recorded commit. A fresh install of the same unit reads 0 stale.
3. **`main` flags `marketplace-entry:tla-spec-dev`, the epic does not: intended.** The plugin's only difference is the gitignored `skills/test-graph/project_sdk_sources/build-logic/build/`. The owner ruled at schedule revision 2 that a gitignored file is not unit identity.

## One limit found: homes cloned before this build stay slow

`home close-out` took **12.9 s** (3 runs) against a destination home cloned before CC-7 landed, over the 5 s target. On both homes cloned by the epic build it takes 3.4-3.5 s, and that is the reading the goal is decided on.

`git status` on the older home's plugin took 8.89 s, against 0.32 s on a refreshed clone. Close-out runs git with `--no-optional-locks`, so it never repairs the index itself. A home made before this release stays at that cost until it is re-cloned or a plain `git status` runs in the plugin once.

No regression. No defect found by this phase, so no repair or extra confirmation run was needed.
