# Wave 4 review: `one-home-one-verdict`

**Range:** `10df4440` (the wave-3 review commit) → `7357b8ab`.

**What landed:**
- **OHV-6** (#352): PR #366, merged as `7357b8ab`.
- **Owner-approved 13-finding `home repair --fix` on the root home.**
- **A new root-home leak, found and traced to its cause.**

Under `review_policy` each wave gets an artifact and there's no stop until finalization. The diff is in `diff.stat` / `diff.patch`; OHV-6's per-home real-home logs are excluded from the patch but committed under `tickets/OHV-6/`. Root-fix evidence is under `goals/GOAL-one-marketplace-identity/2026-09-14-root-fix/`.

**What the wave was for:** GOAL-one-marketplace-identity, where a home's marketplace has one identity and every agent registration agrees with it.

**CI:** run 34846521245, `graph_set=full` on `dc4f6356`: **26 selected / 26 executed / 26 passed / 0 failed**. The epic tip differs from `dc4f6356` only under `results/` and in the plan's status lines.

### Churn by surface

| surface | lines |
| --- | ---: |
| evidence | 1703 |
| production (`MarketplaceRegistrations.java` 660, `HarnessPluginCli.java` 255, `HomeRepair.java` 199) | 1205 |
| unit tests | 532 |
| test_graph (four marketplace nodes) | 458 |
| plan | 2 |

- **Shape:** the largest production change of the epic, and almost all of it is a new reader/writer for Claude's and Codex's config files.
- **Surprise:** the ticket found that Claude's own CLI keeps an old marketplace name when you re-add the same path. So shape 1 could never self-heal, and only a rename fixes it.

## 1. Hot spots

| # | Location | Why it's hot | Question to ask |
| --- | --- | --- | --- |
| 1 | `MarketplaceRegistrations.java` (660 lines, new) | Edits Claude's JSON and **Codex's TOML as text** in homes' own configs. For the root home, those are `~/.claude` and `~/.codex` | Inline tables, CRLF, comments inside a `[marketplaces.*]` table: covered? |
| 2 | Root identity derived from Java's `user.home` | A run with `HOME` redirected but not `user.home` would read the root's own entries as another home's | Should the root identity come from the store path, not `user.home`? |
| 3 | `FOREIGN_MARKETPLACE_REGISTRATION --fix` | Removes or re-points entries in shared agent files | The root run removed exactly the expected keys (checked programmatically). Is there a test where the foreign home carries the same plugin? |
| 4 | `SkillScriptBackend` (untouched this wave; **the leak's cause**) | See §3 | OHV-9 |

## 2. Implicit decisions and guardrail overrides

| Decision | Where | Alternative not taken | Cost to reverse |
| --- | --- | --- | --- |
| Shape 1 is fixed by renaming the registration and its enablements, not by removing it | OHV-6 (a) | Remove and re-add, which silently drops a home's other plugins because `sync <plugin>` reinstalls only the named one | Medium |
| A Claude `known_marketplaces.json` entry for another home that nothing enables is not reported (about 8 on root) | OHV-6 (c) | Report it | Low |
| Files read per home: its own `.claude/settings.json`, `.claude/plugins/known_marketplaces.json`, `.codex/config.toml`. Not read: `settings.local.json`, `installed_plugins.json`, env-redirected dirs | OHV-6 (c) | Read everything under the agent dirs | Low |

| Override | Where | Judgement |
| --- | --- | --- |
| **Real homes repaired beyond marketplace kinds:** `--fix` repairs every finding, so on meta-harness it also removed 24 dangling agent links and 11 orphaned projection records, and elsewhere fixed frozen shims and pm stamps | `tickets/OHV-6/real-homes/*/summary.json` | Genuine repairs. Backups were taken, and global agent files were unchanged at every step. It was broader than "marketplace validation" |
| **Global agent-file backups nearly committed:** OHV-6 put backups of homes' `.claude`/`.codex` files (which can hold credentials) under `tickets/OHV-6-backups/`, untracked, in the epic worktree | epic agent | Moved out of the repository to `/Users/hayde/IdeaProjects/.ohv6-realhome-backups-2026-09-14` before any commit; never committed |
| **Merge guard tripped twice:** plan-file change after CI (epic-tip merge), and secret-scan false positives (`password-reset`, `[RUNPOD_API_KEY]` names without values) | epic agent | Both inspected by hand before merging |
| **Root-home writes (owner-approved):** 13 findings fixed with the OHV-6 branch build | goal evidence | See §3. Guard was exactly 13 expected; post-fix, Python checked each global file lost only the expected keys; shims resolve into root's cache; restore path armed and unused |

No skips added, no goal target changed in range, close-out exit 0.

## 3. The root-home leak: found, repaired, cause traced

**Timeline, 2026-09-14:**
- **08:14:** OHV-4's root fix. The three shims move to token form.
- **08:37:** commit-diff-context-parent's `cdcWorktreeOverlayIsolation` graph starts in `wt-240-packed-publication`.
- **08:40:** its `cdc.skill-manager-worktree.provisioned` node builds a project home whose `bin/cli/{computeq,helm-deploy,monitoring}` are **symlinks to `/Users/hayde/.skill-manager/bin/cli/<tool>`**.
- **08:41–08:42:** deploy-helm's install in that project home runs `cat >"$launcher"` through each symlink and **overwrites the root's shims**, pointing them into the test report directory.
- **~10:10:** my read-only pre-fix check on root reports 6 `FOREIGN_PATH_IN_SHIM` besides the 7 marketplace findings. The released 0.27.2 and the epic build report the same, so it isn't an OHV-6 regression.
- **~10:20:** the owner approves "fix all 13 and also find the cause and fix the cause". The fix runs with guards: root `repair`/`verify` exit 0, 0 `AGENT_SYNC_FAILED`.

**Why nothing caught it:** `SkillScriptBackend.binStamps` stamps links without following them, and `reportFrozenShims` skips symlinks. So a write through a link into another home is invisible to the backend, and nothing asks the other home.

**Cause fix:** new ticket **OHV-9** at plan revision 4, plus goal **GOAL-a-home-writes-only-itself**.
- The backend detaches foreign `bin/cli` links before a skill-script runs, restores the ones it didn't replace, and fails if a detached target changed.
- deploy-helm's helper gets `rm -f "$launcher"`.
- The test-isolation defect (a test project home parented on the operator's real root) is filed on commit-diff-context-parent.

## 4. Where the bugs probably are

| Location | Why | Cheapest settling experiment |
| --- | --- | --- |
| Any installer backend that writes into `bin/cli` through an existing entry | The leak's class, not only skill-scripts | Plant a foreign symlink named after an npm/brew tool and install |
| `MarketplaceRegistrations` TOML text edits | Hot spot 1 | Feed a Codex config with an inline-table marketplace |
| Other CDC graphs with the same fixture shape | The same test-isolation defect may exist elsewhere | Run home-tripwire's watcher over `~/.skill-manager` during a CDC graph run |

## 5. Next steps

- **Wave 5 (new): OHV-9.** GOAL-a-home-writes-only-itself (direct), GOAL-no-own-home-path (guard).
- **Wave 6: OHV-8**, the terminal evaluation. Now depends on OHV-9.

**Deferred findings added:**
- DEF-OHV-160 (OHV-6): the marketplace goal harness reads only the global Claude file, so it read shape 1 = 0 while two project homes had it. OHV-8 must read clause (3) with this in mind.
- DEF-OHV-011: the leak itself, ticketed to OHV-9.

**Goal trajectory:**
- **GOAL-one-marketplace-identity:** clause (1) 4 of 4 shapes planted, reported and cleared in home-verdicts; clause (2) root and project now clean (root's 7 fixed); clause (3) fleet shapes 1/3/4 from 0/16/11 to 0/13/8 by OHV-6's real-home fixes, noting DEF-OHV-160's undercount of shape 1.
- **GOAL-no-own-home-path:** root had regressed to 3 leaked shims after being met; fixed again, and OHV-9 removes the cause.
- **Other goals:** unchanged.

**Worktrees and headroom:** 11 epic worktrees (OHV-8 created), 91 GiB free, Docker healthy.
