# Addendum to OHV-8: the owner-approved real-home repair at finalization

This addendum records what happened on 2026-09-14 at 12:26 EDT, after OHV-8's evaluation (PR #369, sealed) was merged.

**Owner decision:** "Fix both with the epic build now" (root and this project home).

**Build:** `skill-manager 0.27.2+gbe8b48ddbe9e`, the epic tip after OHV-8 (`build.txt`).

**Backups:** kept outside the repository at `/Users/hayde/IdeaProjects/.ohv-final-home-fix-backup-2026-09-14` (chmod 700).

**Guard:** each home was repaired only if a read-only `home repair --json` reported exactly the expected findings. Anything else would have left that home untouched.

## What was repaired

| home | before (epic build) | `--fix` | after |
| --- | --- | --- | --- |
| root `~/.skill-manager` | verify 1, repair 1: 3 FROZEN_HOME_PATH_IN_SHIM (`bin/cli/{computeq,helm-deploy,monitoring}`), the half-rewritten shape a released 0.27.2 `--fix` left at 11:50:45 EDT | rc 0 | verify 0, repair 0, no findings; each shim runs from root's own cache via `${SKILL_MANAGER_SHIM_HOME}` |
| project `skill-manager/.skill-manager` | verify 1, repair 1: 3 DANGLING_AGENT_LINK (`.claude/.codex/.gemini/skills/skill-manager`) and 1 ORPHANED_PROJECTION_RECORD (`installed/skill-manager.projections.json`) | rc 0 | verify 0, repair 0, no findings |

## Re-readings after the repair

These are second readings. They do not replace OHV-8's rows; both are reported in the epic PR.

| Goal | Clause | OHV-8 reading (15:55Z) | After repair (16:26Z) | Target |
| --- | --- | --- | --- | --- |
| GOAL-no-own-home-path | (1) own-home spellings, root/project | root 3, project 0 | **root 0, project 0** (`no_own_home_path.json`, met: true) | 0 |
| GOAL-no-own-home-path | (2) copied-home probe | root 2 of 3, project 3 of 3 | **root 3 of 3, project 3 of 3** (`copied_home.*.json`) | 3 of 3 |
| GOAL-one-verdict | (2) unreported damaged facts | 0 / 0 (both homes still damaged, but reported) | 0 / 0, **and both homes clean** | 0 |

## Not repaired: GOAL-one-record clause (3), stale record versions (DEF-OHV-182)

This clause is still missed: root 4, project 1.

OHV-3's fix refreshes a record's version only during a `sync`. The dry runs in `sync-dry-run/` show that a single-unit sync does much more than refresh one record. On root, `sync <unit>`:

- git-syncs the unit;
- retires superseded units;
- rebuilds the install plan and reinstalls runtime tools and CLI dependencies for **all 27 installed units**;
- re-registers MCP dependencies with the live gateway;
- reinstalls 3 plugins in the harness marketplace.

That is broader than the approved "guarded sync to refresh the stale versions", so it was not run. It goes back to the owner.

## Caveat that outlives this repair

The root was overwritten twice on 2026-09-14 by commit-diff-context-parent test graphs running builds without OHV-9. It will be overwritten again until:

- OHV-9 is released, and
- commit-diff-context-parent#262 is fixed.

This is tracked in haydenrear/skill-manager#378 (DEF-OHV-180).
