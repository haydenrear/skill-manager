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

Production changes: `SkillStore` (`storeUnitDir` / `storeVersionDir` /
`skillDir` / `storedVersions` / `storeLatest` / `storeUnitVersion` / `removeUnit`
/ `migrateToContentAddressed`), `StoreCommand` (new), `SkillManagerCli` and
`CliMetadata` registration, `SkillReconciler` migration hook, and the two
unit-dir delete sites (`LiveInterpreter.removeFromStore`,
`ProjectChildHomeScaffolder.pruneOldUnits`) routed through `removeUnit` so a
skill's snapshots survive uninstall.

Spec change beyond the scaffolded `desired/`: `store` and `store add` added to
`CliTopLevelCommands` / `CliSubcommands` in `Core.tla`. The scaffolded model
named a CLI command it did not admit into the catalog.

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
→ spec-unit validation passed for 2 targets (17 + 16 cases).

The metadata contract in `tests/test_cli_metadata_program_model.py` was tightened
from `modeled_commands <= metadata.command_paths` to `==`. The old containment
check could not see a production command missing from the model — exactly the
`store` gap above. Verified it fails on drift (removing `"store"` from `Core.tla`
reproduces the failure) and passes when the model matches.

## Layer 3 — repo unit tests

`jbang RunTests.java` → **ALL PASSED**, 489 cases, 0 failures.

New suite `ContentAddressedStoreTest` (8 cases) pins the acceptance assertions:
snapshot path is content-addressed; two shas leave both snapshots and exactly one
latest; re-storing a sha refreshes; remove keeps snapshots; an empty slot is
pruned; `removeUnit(PLUGIN)` still deletes outright; snapshots are not mistaken
for installed units by `listInstalled`; migration moves a legacy flat skill under
`latest/` (carrying its `.git`) and is idempotent.

Six pre-existing project tests were updated: agent-home and child-store
projections now point one level deeper, at `skills/<name>/latest`.

## Layer 4 — real CLI, driven end to end

Against a throwaway `SKILL_MANAGER_HOME`:

```
install file:<dir>              → skills/demo-skill/latest/
store add demo-skill --sha sha-one   → ✓ stored demo-skill@sha-one (1 version(s) cached)
<edit working copy>
store add demo-skill --sha sha-two   → ✓ stored demo-skill@sha-two (2 version(s) cached)

skills/demo-skill/{latest,sha-one,sha-two}   .store-latest = sha-two
sha-one/SKILL.md → "version one"   sha-two/SKILL.md → "version two"

remove demo-skill              → ✓ removed demo-skill from store
skills/demo-skill/{sha-one,sha-two}   latest/ gone, .store-latest = sha-two

store add ghost --sha sha-x        → ✗ not installed: ghost        (exit 1)
store add demo-skill --sha sha-three → ✗ not installed: demo-skill (exit 1)
```

The last line is the interesting one: after removal the snapshots remain but the
unit is not installed, so `store add` rejects — snapshots do not make a unit
installed, matching the `cli_store_units` guard.

`skill-manager --help` lists `store`; `skill-manager store --help` lists `add`.

Note: driving `install` with only `SKILL_MANAGER_HOME` redirected still projects
into the real `~/.claude`, `~/.codex`, `~/.gemini` skills directories. The three
symlinks created during this run were removed. Future manual runs should redirect
the agent homes too.

## Layer 5 — test graph

The layout change reaches past the Java store, so three non-Java readers moved too:

- `skill-manager-skill/scripts/env.py` — skill discovery and the reported store
  path now go through a `store_skill_dir()` helper.
- `skill-dev-skill/src/skill_dev/cli.py` — `installed_dir` for a SKILL resolves to
  the working copy, which is where the git clone it makes worktrees from lives.
- `scripts/regenerate_spec_cases.py` and `test_graph/sources/spec/sm_spec_external_cases.py`
  — resolve the installed `spec-double-compiler`, falling back to the flat slot for
  a home that predates the migration.

33 test-graph node assertions moved to `skills/<name>/latest`. Presence assertions
gained `/latest`; absence assertions did not, because an unstored slot is pruned on
uninstall. Agent-home symlinks and plugin-internal contained skills were left alone.

One node needed more than a path edit. `SkillDevGraphSupport.runEditCycle` mapped a
store path into a skill-dev worktree by dropping exactly two path components
(`skills/<name>`); the extra `latest/` segment broke it. That is now
`unitRelativePath(home, storeFile)`, which drops the two store components and then a
leading `latest/` if present — so skills resolve to `SKILL.md` and a plugin's
contained skill still resolves to `skills/plugin-impl/SKILL.md`.

`skill-dev-skill` python tests: 7 passed.

Graph results (`COMPOSE_PROJECT_NAME=skill-manager ... run.py --all`), 18 green in
one sweep:

```
smoke  browser-auth  refresh-flow  password-reset  hyper-experiments  onboard
sponsored  source-tracking  git-latest-source-tracking  plugin-smoke
harness-smoke  doc-smoke  project-manifest  project-resolve  project-smoke
project-env  project-libs  project-profiles
```

`skill-dev-smoke` failed that sweep on the worktree-path bug above and passes in
isolation after the fix (BUILD SUCCESSFUL, 1m 18s).

### specExternalPhase did not run — pre-existing SDK gap

The ticket names `specExternalPhase` as `graph_after_unit_pass`, and it is the graph
that would exercise the generated `RunStoreUnitVersion` case against the real CLI.
It cannot run today:

```
ImportError: cannot import name 'EnvironmentRepository' from 'testgraphsdk'
```

`test_graph/sources/spec/*.py` import `EnvironmentRepository` and `SideEffect`; the
installed test-graph SDK exports only `NodeContext`, `ContextItem`, `NodeSpec`,
`NodeResult`, `ProcessRecord`, `procs`, and `node`. The failure is at the graph's
`--describe` step, four seconds in — before any cluster work — and is unrelated to
this ticket: those spec nodes were uncommitted work in the tree when SMVENV-001
opened, and first landed in `9db129e`. Running them needs a test-graph SDK that
exposes the environment-repository API.

Until then, `store add` is covered by the spec-unit adapter
(`StoreModelCaseAdapter._do_store_unit_version`), the 9 `ContentAddressedStoreTest`
cases, and the manual end-to-end CLI run recorded above.

### Environment notes

Running graphs from a worktree collides with the primary checkout on
`container_name: skill-manager-postgres`. Pin the owning compose project rather
than deleting the container:

```
COMPOSE_PROJECT_NAME=skill-manager GRADLE_OPTS="-Dorg.gradle.daemon=false" \
  python skills/test_graph/scripts/run.py --all
```

The sandbox lost outbound HTTPS partway through this session, which fails
`hyper-experiments` (github clone), and the CLI-dependency installs in `smoke`,
`plugin-smoke`, and `skill-dev-smoke` (`No route to host` from pip/npm). Those
are environmental; `smoke` and `plugin-smoke` both passed on the same node code
while the network was up.
