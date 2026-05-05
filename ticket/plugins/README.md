# Plugin & marketplace integration — work tickets

Parent spec: [`../plugin-marketplace.md`](../plugin-marketplace.md). Read
that first; these tickets are implementation slices, not standalone specs.

Each ticket leaves the build green and tests green. Land in order — the
dependency chain is real (e.g. effect widening requires `AgentUnit`).

## Phases

| Phase | Tickets | Focus |
| --- | --- | --- |
| **A — Foundations** | 01–03 | `AgentUnit`, references, store rename |
| **B — Resolution** | 04–05 | Resolver, Planner |
| **C — Effects** | 06–09 | Widen effect surface to `AgentUnit`, then compensations |
| **D — Orthogonal features** | 10–12 | Lock, Projector, Policy |
| **E — Surface** | 13–14 | Publish, Search/list/show |
| **F — Verification + ship** | 15–16 | test_graph parallels, docs |

## Tickets

| # | File | Title | Depends on |
| --- | --- | --- | --- |
| 01 | [`01-agentunit-and-parsers.md`](01-agentunit-and-parsers.md) | `AgentUnit` foundation + `PluginParser` | — |
| 02 | [`02-unit-reference-and-coords.md`](02-unit-reference-and-coords.md) | `UnitReference` + coordinate grammar | 01 |
| 03 | [`03-installed-unit-and-store.md`](03-installed-unit-and-store.md) | `InstalledUnit` + `UnitStore` rename + migration | 01 |
| 04 | [`04-resolver.md`](04-resolver.md) | `Resolver` (pure coord → descriptor) | 02, 03 |
| 05 | [`05-planner-widening.md`](05-planner-widening.md) | Planner widening + heterogeneous DAG + cycle detection | 04 |
| 06 | [`06-effects-widen-leaves.md`](06-effects-widen-leaves.md) | Widen leaf effects (name-keyed) | 05 |
| 07 | [`07-effects-widen-list-typed.md`](07-effects-widen-list-typed.md) | Widen list-typed effects | 06 |
| 08 | [`08-effects-widen-orchestrators.md`](08-effects-widen-orchestrators.md) | Widen orchestrator + remaining string-keyed effects | 07 |
| 09 | [`09-compensations-and-journal.md`](09-compensations-and-journal.md) | Compensations + rollback journal + plugin uninstall re-walk | 08 |
| 10 | [`10-units-lockfile.md`](10-units-lockfile.md) | `units.lock.toml` + atomic flip + `sync --lock` | 09 |
| 11 | [`11-projector.md`](11-projector.md) | `Projector` interface + `ClaudeProjector` / `CodexProjector` | 08 |
| 12 | [`12-policy-install-gating.md`](12-policy-install-gating.md) | `policy.install` plan-print gates | 05 |
| 13 | [`13-publish-and-scaffold-plugin.md`](13-publish-and-scaffold-plugin.md) | Publish + `ScaffoldPlugin` for plugins | 11 |
| 14 | [`14-search-list-show-columns.md`](14-search-list-show-columns.md) | `kind` + `sha` columns; plugin contained-skills in `show` | 03, 11 |
| 15 | [`15-test-graph-plugin-parallels.md`](15-test-graph-plugin-parallels.md) | Test_graph parallel `*Plugin*` nodes + new fixtures | 11 |
| 16 | [`16-docs.md`](16-docs.md) | Update `skill-manager-skill/SKILL.md`, `README.md` | 15 |

## Test ownership per ticket

Layer-2 unit tests land **with their implementation ticket**. The
`_lib/` test substrate (fakes, fixtures, harness, matrix) is bootstrapped
in 01 and grown ticket-by-ticket as new contracts come online.

Layer-1 test_graph parallels are batched in 15 once the install path
is end-to-end functional, plus the plugin-specific nodes (e.g.
`PluginUninstallReWalkPreventsOrphan`) that wouldn't have been verifiable
earlier.

## Conventions

- **Branch naming**: `feature/plugins-NN-<slug>` (e.g. `feature/plugins-04-resolver`).
- **PR title prefix**: `plugins(NN): <title>`.
- **Each PR** must reference its ticket file in the description.
- **Don't merge out of order** — the dependency column is enforced by code, not policy.
