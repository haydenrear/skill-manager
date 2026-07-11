# SMVENV-001 evidence — content-addressed skill store

Ticket: `skills/<name>/<sha>/` snapshots plus a moving latest pointer.
Branch: `feature/SMVENV-001`. Worktree: `../wt-SMVENV-001`.

## What landed

The model treats `StoreUnitVersion` as a **separate action on an already
installed unit** (`Internal.tla` `StoreUnitVersionImpl`, guard
`u \notin cli_store_units => Reject("UNIT_NOT_INSTALLED")`). `InstallUnitImpl`
and `RemoveUnitImpl` both leave `project_model` unchanged. The external adapter
binds the action to a new CLI surface, `skill-manager store add <unit> --sha <sha>`.

Implementation follows that shape — install and sync are untouched:

| Path | Meaning |
|---|---|
| `skills/<name>/latest/` | mutable working copy; install/sync write here in place, agent homes link here |
| `skills/<name>/<sha>/` | immutable snapshot, written only by `store add` |
| `skills/<name>/.store-latest` | the sha the working copy was last stored as |

`latest/` is a real directory rather than a symlink into a snapshot, because
`SyncGitHandler` fetches and merges into the working copy in place; a link into
an immutable snapshot cannot absorb a `git pull`.

### Deviation from the ticket prose

The objective says "every install/sync records the unit's content under
`skills/<name>/<sha>/`". The TLA+ says otherwise, and the TLA+ won. Two hazards
the model's shape sidesteps:

- The sha is **unknown at install time** for anything but a git source —
  `InstalledUnit.gitHash` is populated only for `Kind.GIT`, so `file:` and
  registry installs have no content address to record.
- `SyncGitHandler` **merges in place** inside the store directory. An immutable
  snapshot cannot be the thing `git pull` writes into.

## Layer 1 — TLC over the ticket `current/` model

All seven configs, no violations:

```
InternalVenvCases                Model checking completed. No error has been found.
InternalCliDisclosureCases       Model checking completed. No error has been found.
InternalCliStoreCases            Model checking completed. No error has been found.
InternalGatewayCases             Model checking completed. No error has been found.
InternalProjectCases             Model checking completed. No error has been found.
InternalServerRegistryCases      Model checking completed. No error has been found.
External.cfg                     Model checking completed. No error has been found.
```

`InternalVenvCases`: 109,621 states generated, 2,610 distinct, depth 11.
`VenvStoreVersionsAreContentAddressed`, `VenvStoreLatestIsStored`, and
`VenvStoreLatestUniquePerUnit` all hold.

## Layer 2 — spec-unit tests

`tla-spec-dev --spec-root specs run spec-unit-tests --ticket SMVENV-001`
→ passed for 2 targets (17 + 21 cases).

Two contracts were tightened, both because they were too weak to catch a real
bug this ticket introduced:

**`tests/test_cli_metadata_program_model.py`** went from
`modeled_commands <= metadata.command_paths` to `==`. The containment check
could not see a production command *missing from the model* — which is exactly
what happened: the scaffolded `desired/` named a `store add` CLI command but
never admitted `store` into `CliTopLevelCommands`. Verified the strict form
fails on drift and passes when the model matches.

**`tests/test_store_projection.py`** (new, 5 cases) pins `observe_home` — the
filesystem projector the external cases diff against — to the store layout.
It projects `store_versions` / `store_latest` off disk, and it pins the case
that was previously broken: after `store add` then `remove`, the slot survives
holding snapshots while the installed record is gone. The old projector counted
bare slots as installed units and raised
`store/installed/lock divergence` on that trace — a state the model says is
perfectly legal. Confirmed the pre-fix code fails this test rather than the test
passing vacuously.

## Layer 3 — repo unit tests

`jbang RunTests.java` → **ALL PASSED**.

New suite `ContentAddressedStoreTest` (9 cases): snapshot path is
content-addressed; two shas leave both snapshots and exactly one latest;
a snapshot records content but not the `.git` clone, while the working copy keeps
it; re-storing a sha refreshes; remove keeps snapshots; an empty slot is pruned;
`removeUnit(PLUGIN)` still deletes outright; snapshots are not mistaken for
installed units by `listInstalled`; migration moves a legacy flat skill under
`latest/` (carrying its `.git`), names the slots it moved, and is idempotent.

Six pre-existing project tests were updated: agent-home and child-store
projections now point one level deeper, at `skills/<name>/latest`.

## Layer 4 — real CLI, driven end to end

Against a throwaway `SKILL_MANAGER_HOME`:

```
install file:<dir>                   → skills/demo-skill/latest/
store add demo-skill --sha sha-one   → ✓ stored demo-skill@sha-one (1 version(s) cached)
<edit working copy>
store add demo-skill --sha sha-two   → ✓ stored demo-skill@sha-two (2 version(s) cached)

skills/demo-skill/{latest,sha-one,sha-two}   .store-latest = sha-two
sha-one/SKILL.md → "version one"   sha-two/SKILL.md → "version two"

remove demo-skill                    → ✓ removed demo-skill from store
skills/demo-skill/{sha-one,sha-two}  latest/ gone, .store-latest = sha-two

store add ghost --sha sha-x          → ✗ not installed: ghost        (exit 1)
store add demo-skill --sha sha-three → ✗ not installed: demo-skill   (exit 1)
```

The last line is the interesting one: after removal the snapshots remain but the
unit is not installed, so `store add` rejects — snapshots do not make a unit
installed, matching the `cli_store_units` guard.

### The migration silently hid every already-installed skill

Caught by building a pre-migration home by hand and running the real CLI at it.
The migration moves `skills/<name>/` → `skills/<name>/latest/`, but the agent
homes already hold symlinks pointing at the **slot**. After the move that link
still resolves — to a directory with no `SKILL.md` in it. The skill vanishes
from the agent, and nothing reports a problem: the binding backfill checks that
the link *file* exists (`NOFOLLOW_LINKS`), not that it still points at content,
and it skips any unit that already has a ledger row.

```
BEFORE   ~/.claude/skills/legacy-skill → skills/legacy-skill          SKILL.md? YES
AFTER    ~/.claude/skills/legacy-skill → skills/legacy-skill          SKILL.md? NO   ← broken
```

Fixed: `migrateToContentAddressed` now returns the slots it moved, and
`MigratedLinkRepair` repoints each agent's link at the working copy and rewrites
the stale `sourcePath` rows in the binding ledger. Re-verified against the same
fixture with all three agent homes populated:

```
reconcile: moved 1 skill(s) into the content-addressed store, repointed 3 agent link(s)

claude/.claude/skills/legacy-skill → skills/legacy-skill/latest   SKILL.md? YES
codex/skills/legacy-skill          → skills/legacy-skill/latest   SKILL.md? YES
gemini/skills/legacy-skill         → skills/legacy-skill/latest   SKILL.md? YES
```

Only symlinks are repointed. A projector that materialized a real directory
copied the content, so that copy is stale but readable, and replacing it would
throw away whatever the agent did to it.

## Layer 5 — test graph

The layout change reaches past the Java store. Non-Java readers that moved:

- `skill-manager-skill/scripts/env.py` — skill discovery and the reported store
  path go through a `store_skill_dir()` helper.
- `skill-dev-skill/src/skill_dev/cli.py` — `installed_dir` for a SKILL resolves
  to the working copy, which is where the git clone it makes worktrees from lives.
- `scripts/regenerate_spec_cases.py`, `test_graph/sources/spec/sm_spec_external_cases.py`,
  `specs/*/adapters.py`, and `specs/*/extract_manifest.sh` — all resolve the
  installed `spec-double-compiler`, preferring `latest/` and falling back to the
  flat slot for a home that predates the migration. The two `specs/` ones were
  hard failures: `extract_manifest.sh` exits 1 with "extractor was not found",
  and `adapters.py` silently put a non-existent directory on `sys.path`.

33 test-graph node assertions moved to `skills/<name>/latest`. Presence
assertions gained `/latest`; absence assertions did not, because an unstored slot
is pruned on uninstall. Agent-home symlinks and plugin-internal contained skills
were left alone.

One node needed more than a path edit. `SkillDevGraphSupport.runEditCycle` mapped
a store path into a skill-dev worktree by dropping exactly two path components
(`skills/<name>`); the extra `latest/` segment broke it. That is now
`unitRelativePath(home, storeFile)`, which drops the two store components and then
a leading `latest/` if present — so skills resolve to `SKILL.md` and a plugin's
contained skill still resolves to `skills/plugin-impl/SKILL.md`.

Docs describing the flat layout were corrected: `README.md` (prose + the layout
diagram), `skill-manager-skill/SKILL.md`, `skill-publisher-skill/SKILL.md`, and
two `skill-publisher-skill/references/` pages.

`skill-dev-skill` python tests: 7 passed.

## Known gaps at close

These are real and tracked as issues rather than silently closed over.

### specExternalPhase did not run

The ticket names `specExternalPhase` as `graph_after_unit_pass`, and it is the
graph that would exercise the generated `RunStoreUnitVersion` case against the
real CLI. It cannot run today:

```
ImportError: cannot import name 'EnvironmentRepository' from 'testgraphsdk'
```

`test_graph/sources/spec/*.py` import `EnvironmentRepository` and `SideEffect`;
the installed test-graph SDK exports only `NodeContext`, `ContextItem`,
`NodeSpec`, `NodeResult`, `ProcessRecord`, `procs`, and `node`. The failure is at
the graph's `--describe` step, four seconds in — before any cluster work — and is
unrelated to this ticket: those spec nodes were uncommitted work in the tree when
SMVENV-001 opened, and first landed in `9db129e`.

The case is otherwise *ready*: it has a binding, an adapter
(`store add <unit> --sha <sha> --yes`), and as of this ticket a projector that
reads `store_versions` / `store_latest` back off disk. It has simply never run.

### The store covers skills only

Plugins, doc-repos, and harness templates keep the flat layout, and `store add`
rejects them. The model does not distinguish kinds — `Units` is a kind-less
universe and `DocRepos` / `HarnessTemplates` are separate universes that
`StoreUnitVersion` structurally cannot reach — so this is under-specification, not
a spec violation. It blocks a coherent SMVENV-002, which pins "units".

### No graph coverage for `store add`

Zero nodes reference it. Covered by unit tests, the spec-unit adapter, and the
manual run above, but nothing exercises the real CLI against a real home in CI.

### The generated external cases are thin

`External.cfg` and `InternalVenvCases.cfg` both collapse `UnitB = UnitA`, so every
generated `RunStoreUnitVersion` case runs over a **single** unit and 3 shas —
`VenvStoreLatestUniquePerUnit` is the invariant most in need of a second unit, and
never gets one. `VenvCaseEnvelope` also freezes the entire gateway
(`gateway_tools = {}`), so the model cannot state anything about how a stored sha
relates to the tools the gateway serves.

### The store is inert

Nothing outside `SkillStore` reads a sha. Every consumer — agent projections, the
env materializer, the child-home scaffolder, `SkillScriptBackend`, MCP dependency
parsing — resolves through `SkillStore.unitDir`, which for a skill returns
`latest`. Snapshots are written and never read back. That is correct for this
ticket (pinning is SMVENV-002) but worth stating plainly.

## Environment notes

Running graphs from a worktree collides with the primary checkout on
`container_name: skill-manager-postgres`. Pin the owning compose project rather
than deleting the container:

```
COMPOSE_PROJECT_NAME=skill-manager GRADLE_OPTS="-Dorg.gradle.daemon=false" \
  python skills/test_graph/scripts/run.py --all
```

Driving `install` with only `SKILL_MANAGER_HOME` redirected still projects into
the real `~/.claude`, `~/.codex`, `~/.gemini` skills directories — `CLAUDE_HOME`
is the *parent* of `.claude/`, so the redirect needs `CLAUDE_HOME`, `CODEX_HOME`,
and `GEMINI_HOME` set too. Symlinks created during an early manual run were
removed.
