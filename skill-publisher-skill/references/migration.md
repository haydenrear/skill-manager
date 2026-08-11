# Migrating from skill-publisher to the skt plugin

The repository `haydenrear/skill-publisher-skill` used to ship the
`skill-publisher` **skill** (pure authoring docs, rarely triggered). It
now ships the `skt` **plugin**: the lifecycle CLI, session hooks, and
two contained skills — `skt` (orientation, notifications, worktree
lifecycle) and `unit-authoring` (the former skill-publisher content,
preserved). The constituent/checkout *directory name* is historical and
does not change; only the installed **unit** renames.

## The four transition states

| State | What works | What to do |
| --- | --- | --- |
| **Neither installed** | The resolved-path fallbacks in every routed doc (`wt` by path, the hand-run epic pair, the manual currency loop) carry the whole flow | Install skt when ready; nothing is broken meanwhile |
| **Legacy `skill-publisher` only** | Same as above; the legacy skill still answers authoring questions | `skill-manager remove skill-publisher`, then install skt (below) |
| **Both installed** | Everything works; no trigger collision (the authoring description lives on the contained `unit-authoring` skill, not on a competitor to skt) | Remove the legacy unit at leisure — it is dead weight, not a hazard |
| **skt only** (target) | Startup disclosure, notifications, `skt ticket`/`publish`, plus `unit-authoring` for authoring | — |

## Install order across the home tiers

Copies flow **down** (root → project → worktree) and never update
themselves, so migrate top-down:

```bash
# 1. Root home (the operator's ~/.skill-manager):
skill-manager remove skill-publisher            # if present
skill-manager install github:haydenrear/skill-publisher-skill

# 2. Each project home, EITHER by re-syncing from the root's marketplace
#    state, OR directly in that home:
SKILL_MANAGER_HOME=<repo>/.skill-manager skill-manager remove skill-publisher
SKILL_MANAGER_HOME=<repo>/.skill-manager skill-manager install github:haydenrear/skill-publisher-skill

# 3. Worktree homes: nothing to do — new worktrees clone the project home,
#    and bootstrap now registers the home's plugins (hooks load) on clone.
```

Requirements: python ≥ 3.11 on PATH (the CLI wrapper probes and bakes
it); a skill-manager CLI new enough to carry per-home marketplace names
and `home refresh-plugins` — older CLIs install the plugin fine but warn
that hooks will not load in cloned homes until the CLI upgrades.

## What depends on the old name

Fixed with this epic: the outer `skill-project.toml` (now
`[plugins.skt]`), spec-double-compiler's frontmatter dependency,
meta-orchestrator's reference paths (`plugins/skt/references/...`),
git-issue-workflow's push-back table. If a private doc of yours points
at `$SKILL_MANAGER_HOME/skills/skill-publisher/...`, the same files are
at `$SKILL_MANAGER_HOME/plugins/skt/...`.

## The rule the routed docs follow

Every doc that leads with an skt command keeps its resolved-path or
hand-run fallback **verbatim**. A home without skt is a supported state,
not a broken one — the eval suite's migration matrix (W4) holds the docs
to that.
