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

## Migrating an existing project home (the measured sequence)

Nothing migrates a home automatically: copies flow down at **creation**
time only, so an existing project home keeps the legacy unit until
someone runs this. New worktrees are the exception — they clone the
project home, so once the project home carries skt, every `wt new` /
`skt ticket new` after that is done for free.

**First**, if the repo's manifest declares the legacy unit, rename it —
this is a normal leaf commit, and resolve deadlocks without it:

```toml
# skill-project.toml:  [skills.skill-publisher]  becomes
[plugins.skt]
source = "github:haydenrear/skill-publisher-skill"
```

**Then**, in the home (order matters; each step's reason is a refusal
you will otherwise meet — the claim/duplicate-origin deadlock is
skill-manager#175, the validate-before-plugin ordering is #174):

```bash
export SKILL_MANAGER_HOME=<repo>/.skill-manager

# 1. If a project registration claims the legacy unit, unregister first —
#    uninstall refuses while the claim exists, and resolve refuses while
#    two installed units share the origin.
skill-manager project remove <project-name>       # only if registered

# 2. Retire the legacy unit (no-op if project remove already took it).
skill-manager uninstall skill-publisher

# 3. Install the plugin BEFORE resolving — resolve validates markdown
#    imports (unit: skt) before it installs declared plugins.
skill-manager install github:haydenrear/skill-publisher-skill

# 4. Re-resolve the project realization. --repair-vendored re-points
#    vendored symlinks (test_graph/sdk and friends) at this home.
skill-manager project resolve --project-dir <repo> --repair-vendored
```

**Marketplace seam** (homes registered before per-home marketplace
names): if plugin installs fail with `not found in marketplace
skill-manager-<fp8>`, the agent config still holds the old bare-name
record. Replace it once:

```bash
CLAUDE_CONFIG_DIR=<repo>/.claude claude plugin marketplace remove skill-manager
skill-manager home refresh-plugins
CLAUDE_CONFIG_DIR=<repo>/.claude claude plugin install skt@skill-manager-<fp8>
```

## Validating a home before continuing work

Cheap, read-only, safe mid-session:

```bash
skt status     # tier, policy, drift state, units, plugins — is skt live here
skt check      # staleness + unpublished-work notifications, with remedies
skill-manager list                       # skt appears as one plugin row
CLAUDE_CONFIG_DIR=<repo>/.claude claude plugin list   # skt@… enabled
```

Green looks like: `skt status` names the home with `policy: live` and no
`DRIFT PENDING`, `skt check` reports nothing (or only notifications you
recognize), and the plugin list shows `skt@skill-manager-<fp8>` enabled.

**Mid-session agents do not need to stop.** The migration matrix (W4)
measured all four transition states: a home with neither unit, legacy
only, or both installed carries the full ticket flow on the documented
fallbacks. Migrate at the next natural stopping point — after a close,
before a new ticket — never mid-edit. A session that was launched before
the migration only picks up the SessionStart hook on its next launch.

## Hand this to an agent

A paste-ready work order for migrating one repository:

> Migrate this repository's skill-manager project home from the retired
> `skill-publisher` skill to the `skt` plugin. Read
> `$SKILL_MANAGER_HOME/plugins/skt/references/migration.md` (or the same
> file in any home that already has skt) and follow "Migrating an
> existing project home" exactly — the step order avoids two known
> refusals. If `skill-project.toml` declares `[skills.skill-publisher]`,
> rename it to `[plugins.skt]` as a normal reviewed commit first. Finish
> by running the validation block and reporting its output. Do not touch
> the operator's root `~/.skill-manager`.
