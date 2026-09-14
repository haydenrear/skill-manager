# OHV-9 (#367) — a skill-script install never writes through a link into another home

Branch `feature/OHV-9`, from the epic at `926cd655`, with `4578ce78` (plan placeholder fix) merged in.
Commits: `6b017b94` (a)+(b) and unit tests; `d2f77877` graph node; `2a430bb6` evidence (the CI run's head).

DEF-OHV-011. On 2026-09-14 the operator's root `bin/cli/{computeq,helm-deploy,monitoring}` were rewritten
through a CDC test project home whose same-named entries were symlinks to the root's shims. deploy-helm's
installer wrote its launcher with `cat >"$launcher"`, which follows a link. Nothing here was touched on any real
home: every reproduction below is in scratch directories.

## Reproduced first, failing then passing

`src/test/java/dev/skillmanager/cli/installer/SkillScriptWriteThroughTest.java`, run alone on the epic tip
before any production change (`unit/a-before-fix.txt`), then after (`unit/a-and-b-after-fix.txt`):

| case | before | after |
| --- | --- | --- |
| a `cat >"$SKILL_MANAGER_HOME/bin/cli/tool"` installer in a child does not rewrite the parent's shim (backend) | **FAIL**: parent's shim became `# child-built` | PASS |
| the same through `InstallerRegistry.installOne` over a **sanctioned** mirror (the parent claims the child — the CDC shape `refuseAForeignDestination` walks past) | **FAIL**: parent rewritten | PASS |
| a detached link the script did not replace is restored with its exact target | FAIL (script saw the link present) | PASS |
| a failing script still gets the link restored | pass (trivially: nothing was detached) | PASS |
| a script that writes the other home's file by its own path fails the install, naming that home and path | FAIL (install succeeded) | PASS |
| a script that recreates the link back out fails as a foreign write | FAIL (`ln: File exists`, no home named) | PASS |
| a link resolving inside this home (`../../venvs/...`) is left in place | pass | PASS |

## (a) SkillScriptBackend

`ForeignBinLinks` (new, `cli/installer`), called around `Shell.runToLog` in `SkillScriptBackend.install`:

1. **detach**: every `bin/cli` entry that is a symlink whose chain resolves outside this home's real root is recorded
   (name, raw target, resolved target, stat of the resolved file) and unlinked. The chain is read hop by hop, so a
   DANGLING link into another home is still detached (`Fs.realOrNormalized(entry)` alone stops at `bin/cli` and calls
   it inside — unit case). `bin/cli` itself resolving outside is refused (`requireContainerInside`).
2. the script runs; **restore** is in a `finally`: an absent name gets its link back exactly; a name the script made
   into a link out of the home again is a foreign write (the original link is put back); anything else is the child's
   own artifact and stays.
3. the resolved target is stat'ed again — size, mtime (ns), file key; content never read. A difference is a foreign
   write.
4. `requireNoForeignWrite` throws before the exit-code check, naming the home (`LaunchEnv.looksLikeStoreRoot`
   ancestor) and the path.

Consequence, stated: a skill-script that actually runs in a child home over a sanctioned mirror now leaves the
child owning its own artifact, where before it silently rewrote the parent's. A routine sync that the backend
skips (fingerprint match, `ALREADY_PRESENT`) detaches nothing.

## (b) Every other writer of bin/cli

Unit cases: `BinCliWritersDoNotFollowLinksTest` (scratch parent/child; each asserts the parent's file is
byte-identical).

| writer | how it writes `bin/cli` | can it follow an existing link? | disposition | proof |
| --- | --- | --- | --- | --- |
| `SkillScriptBackend` | forked script (e.g. `cat >`) | **yes** — measured, red before | detach / restore / stat guard | `SkillScriptWriteThroughTest`, 7 cases |
| `PipBackend` | forked `uv tool install` with `UV_TOOL_BIN_DIR=bin/cli` | not provable here — a process this code does not control, and no hermetic uv in the unit suite | **same guard** around the fork | the guard's cases above; stated as the limit |
| `NpmBackend` | Java links each `npm/<unit>/bin/*` into `bin/cli` (npm itself writes its prefix, not `bin/cli`) | **no**: the entry is deleted first (a delete removes a link, not its target), then `createSymbolicLink`, copy fallback onto an absent path | routed through `ForeignBinLinks.placeLink` | placeLink case; copy-fallback case |
| `BrewBackend` | same, from `$(brew --prefix)/bin` (brew writes its cellar) | **no**, same reason | `placeLink` | same two cases |
| `TarBackend` | `deleteIfExists` + `Files.copy(REPLACE_EXISTING, COPY_ATTRIBUTES)` | **no**: delete first; `REPLACE_EXISTING` also replaces the link | routed through `ForeignBinLinks.placeCopy` | tar case |
| `InstallerRegistry.takeOwnershipOfShim` / `restoreForeignShim` | delete the link / create one only where nothing exists | **no** | unchanged | ownership case |
| `InstallerRegistry` → `HomeLinks.relativizeShims` | delete + recreate links whose target is INSIDE the home | **no**, and a foreign link is skipped | unchanged | relativize case |
| `SkillScriptBackend.reportFrozenShims` | `Files.writeString` on a shim the script just wrote | only through a link, and it skips links; after the guard the entry is the child's own file | unchanged | registry case asserts the child's shim is token-form |
| `LauncherShims` (`bin/cli/skill-manager`, `bin/launch/*`) | `Files.writeString` | **yes** — unit case red on the original code (`unit/b-launchershims-before-fix.txt`): the parent's entrypoint got the child's pin | unlink a link, then write (`writeOwnFile`) | LauncherShims case |
| `CliShimPruner`, `CliDependencyCleaner` | delete only | no | unchanged (DEF-007's guards already there) | — |
| `ChildHomeMaterializer.mirrorExistingShim`, `HomeCloner` | CREATE the child→parent links | n/a — they make the shape this ticket defends against; out of scope, not changed | unchanged | context |

## (c) Graph node

`test_graph/sources/home-verdicts/ChildInstallWritesOnlyItself.java`, `home.verdicts.child.install.writes.only.itself`,
in `home-verdicts` and in both laws' `dependsOn`. Under the fixture's scratch root only: parent with real
`bin/cli/wt-tool` and a neighbour entry; child whose `bin/cli/wt-tool` links to it; a `child-homes/` claim so the link
is sanctioned; a unit whose skill-script writes `cat >"$SKILL_MANAGER_HOME/bin/cli/wt-tool"` with its paths expanded.
Installs with the real CLI (`install <dir> --yes --no-bind-default`, gateway URL pointed at an unresolvable remote host
so no gateway venv is built). Asserts: control (the link resolves to the parent's shim), install exit 0, **every file
of the parent home byte-identical** (sha256, links by target), the child holds a real shim, token-form (no spelling
of the child home, `${SKILL_MANAGER_SHIM_HOME}` present), naming no form of the parent, and it runs the child's tool.
Nothing is published and the scratch homes are deleted, so no `IntentionalDamage` declaration is needed and the
fixpoint/membership laws see only the fixture's clean home.

Local runs (`graph/child-install-node-red-then-green.json`):

| run | tree | node | graph |
| --- | --- | --- | --- |
| `20260914-144523` | this branch | passed, 9/9 assertions | 18/18 nodes passed, both laws green (208 s) |
| `20260914-144857` | this branch with `SkillScriptBackend.java` from `9fc819ca` (pre-fix) | **failed**: parent byte-identical x2, child owns a real shim, token-form, names no parent, runs its tool | rc 1 |

Control and install exit 0 pass in both, so the red run fails on the leak itself, not on setup.

## (d) deploy-helm helper

PR: https://github.com/haydenrear/deploy-helm/pull/63 (the unit's source `haydenrear/deploy-cdc` redirects to
`haydenrear/deploy-helm`; base `main`, branch `fix/ohv-9-launcher-no-follow`, commit `cff6efb`). Adds
`rm -f "$launcher"` before `cat >"$launcher"` in `skill-scripts/helpers/install-console-script.sh`. Not published, no
home copy edited. Scratch proof (`d-helper-proof.txt`):

```
old: parent now = child
new: parent now = parent; child is link? no; child = child
```

## Validation

- `jbang RunTests.java`: ALL PASSED (145 s), including `SkillScriptWriteThroughTest` 7/7 and `BinCliWritersDoNotFollowLinksTest` 7/7. Disk before: 85 GiB free.
- `uv run --with pytest pytest specs/program_model/tests -q`: 11 passed.
- `python skills/test_graph/scripts/run.py home-verdicts`: green locally (above).
- Read-only on the real root: `./skill-manager home repair --home ~/.skill-manager --json` (this build, no `--fix`): clean, 0 findings, 2 s — no DEF-OHV-140 slowness (`root-home-repair-readonly.json`).
- `home close-out --home wt-ohv-9/.skill-manager --into skill-manager/.skill-manager`: exit 0, "holds nothing that removing it would destroy" (`close-out.txt`).
- CI `graph_set=full`: run 34858642285 (https://github.com/haydenrear/skill-manager/actions/runs/34858642285) on `2a430bb6`: graphs_executed 26, graphs_passed 26, graphs_failed 0; 30 jobs success, 1 skipped. home-verdicts 18/18 nodes (the new node included); project-child-home 13/13, plugin-smoke 28/28, home-clone 16/16, checkout-home 8/8, home-integrity 19/19. Deferred by the selector as on every run: browser-auth, refresh-flow, password-reset, hyper-experiments. 26 not 27: this ticket adds a node to home-verdicts, not a graph. (`ci-34858642285-graphs-executed.json`)

## Goal contribution

- **GOAL-a-home-writes-only-itself** (direct): (1) no → yes (unit reproduction + graph node); (2) no → yes (changed
  target and link-back-out fail the install naming the other home); (3) unmeasured → yes: guarded (skill-script, pip)
  or shown unable to follow, with a unit case each (npm, brew, tar, registry, relativize), plus LauncherShims found
  and fixed.
- **GOAL-no-own-home-path** (guard): the child's shim is token-form (graph node + registry unit case).
  `python3.12 scripts/measure_goal_no_own_home_path.py`: root 0; project 0, met (`goal-no-own-home-path.json`).

## Deferred findings

None.
