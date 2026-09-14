# OHV-6 (#352) — a home's plugin marketplace has one identity, and every agent registration agrees with it

Branch `feature/OHV-6`, from the epic at `42486f86`, with `10df4440` (the wave-3 review, `results/` only) merged in.
Code commits: `83651bfc` (the slice) and `dc4f6356` (keep a repaired file's trailing newline; found by the root simulation).

## What was measured before any code

Everything below was run against scratch config dirs only, and the four global agent files had the same sha256 before and after.

| CLI | behaviour | consequence |
| --- | --- | --- |
| Claude Code 2.1.270 | `plugin marketplace add <path>` on a path already registered as `skill-manager`, after marketplace.json was renamed, prints "already on disk" and **keeps the old name** | shape 1 cannot heal by re-adding; `update <new>` fails with `Marketplace '<new>' not found` (the #352 error, reproduced) |
| Claude Code 2.1.270 | `plugin marketplace remove <name>` also drops that name's `enabledPlugins`, `extraKnownMarketplaces` and `installed_plugins.json` rows | a remove-and-re-add must re-enable the plugins itself |
| codex-cli 0.154.0 | `plugin marketplace add <path>` on a renamed path **adds a second table**; `marketplace remove <old>` leaves `[plugins."p@old"]` enabled | a stale enablement survives the CLI's own removal |
| skill-manager | `RefreshHarnessPlugins.reinstall` holds only the named plugins for `sync <plugin>` | removing a stale enablement silently drops every other plugin; so shape 1 is repaired by RENAMING |

## (a) Registration reconciled by identity and path

- **Change:** `HarnessPluginCli.Claude.ensureMarketplaceAdded`:
  1. It parses `list --json`, with a text fallback, and matches on exact name and path.
  2. It removes this path's registration under another name, and this identity if registered at another path.
  3. It runs `add`, then re-lists. The outcome is judged by what is registered, not by add's exit code.
  4. It installs, under the identity, the plugins that were enabled under the removed name.

  `Codex.ensureRegisteredAt` handles a same-path table under another name the same way: `plugin remove p@old`, `marketplace remove old`, `add` if needed, then re-read `config.toml`, then `plugin add p@identity`.
- **Tests:** `HarnessPluginCliTest` covers:
  - shape 2's substring trap (`skill-manager` inside `skill-manager-919db26e`): the add runs, and nothing foreign is removed;
  - Claude shape 1: remove, add, re-list, install `skt@identity`, and nothing else touched;
  - an add that keeps the old name, which is a failure naming both identities;
  - Codex shape 1;
  - the text-listing parser;
  - the existing Codex stale-path tests, whose fake runner now writes the config the way `add` does.
- **Real homes:** see the per-home table below. Every `sync skt` reported 0 AGENT_SYNC_FAILED.

## (b) The generated name is derived, never trusted from disk

- **Change:** `HomeCloner.rederiveMarketplaceIdentity` runs in `home clone`, after the state re-anchor and before the drift baseline, and at the end of `home sync`. When a manifest exists and names another identity, it regenerates the marketplace through `PluginMarketplace.regenerate()`, which is the manifest's one writer. A home with no manifest is left without one.
- **Tests:** `HomeCloneTest` "a clone's marketplace manifest names the CLONE, not its source"; `HomeSyncTest` "home sync leaves the destination's manifest naming the destination".
- **Node:** `home.verdicts.copied.marketplace.identity`.

## (c) Another home's marketplace, and the other three shapes, are `home repair` findings with a `--fix`

- **Change:** `MarketplaceRegistrations` is a new class that reads, judges and edits one entry. `HomeRepair` gains four kinds:

  | kind | #352 shape | --fix |
  | --- | --- | --- |
  | `MARKETPLACE_REGISTERED_UNDER_ANOTHER_NAME` | 1 | re-points the registration and every enablement from that name in the same agent's config at the identity (removes instead where the identity entry already exists) |
  | `MARKETPLACE_IDENTITY_UNREGISTERED` | 2 (its disk trace: enabled under the identity, not registered at this path) | registers the identity the way that agent's `marketplace add` does; unrepairable when there is no manifest |
  | `MARKETPLACE_IDENTITY_COPIED` | 3 | regenerates the marketplace |
  | `FOREIGN_MARKETPLACE_REGISTRATION` | 4, both Codex and Claude (DEF-OHV-005) | removes that one entry; an enablement of a plugin this home's own manifest carries is re-pointed instead, so the plugin is not dropped |

  Each finding's detail names the identity and path it expected and what it found. `apply` re-reads and re-judges the config before writing, so a finding an earlier action consumed is refused. It never acts on stale prose.

- **Which files each home's detector reads** (the agent dirs are structural, `AgentHomes.homeRootFor(store)`, never the environment):
  - `<homeRoot>/.claude/plugins/known_marketplaces.json`: registrations;
  - `<homeRoot>/.claude/settings.json`: `extraKnownMarketplaces` (registrations) and `enabledPlugins`;
  - `<homeRoot>/.codex/config.toml`: `[marketplaces.*]` and `[plugins."p@m"]`.

  For a project home `<homeRoot>` is the checkout. These are the files `CLAUDE_CONFIG_DIR`/`CODEX_HOME` name when skill-manager drives the CLIs for that home, and Claude's project scope. For the root home they are the user-level `~/.claude/...` and `~/.codex/config.toml`.

- **Deliberately not read:**
  - `settings.local.json`;
  - `installed_plugins.json` and `plugins/cache/`, the CLIs' install cache and not registrations. After a shape-1 migration the old `p@old` rows stay there; the next sync writes `p@identity` rows beside them;
  - directories that `CLAUDE_CONFIG_DIR`/`CODEX_HOME` redirect to;
  - harness-instance agent dirs;
  - Gemini;
  - every other checkout's config.

- **One exemption:** a Claude `known_marketplaces.json` entry for another home that nothing in the same config enables. It loads nothing, and in a user-level config it is how Claude records a marketplace a checkout declared. The root's `~/.claude/plugins/known_marketplaces.json` holds about 8 such project-home entries, and they are not reported.

- **Tests:**
  - `MarketplaceRegistrationsTest`, 8 cases: the root DEF-OHV-005 config yields exactly the 4 foreign entries; shape 1 judge and migration in place; shape 2 judge and register; TOML cut and rename keep every other byte; path aliases; trailing newline.
  - `DamagedHomeIsRepairableTest`: the `Kind.values()` guard now plants all four (`damageMarketplaceIdentity`); "the four marketplace-identity shapes, repaired by touching only what they name" (exact subjects, exact surviving TOML, second `--fix` a no-op); "a foreign enablement of a plugin THIS home carries is re-pointed, not dropped".

## (d) Every verdict and sync names the identity it expected and the one it found

- Findings: `expected <identity> at <marketplace>, found <name> …`.
- Driver results: `already registered: <identity> at <path>`, `expected <identity> at <path>, found <name> at <path>: removed`, and `migrated p@old -> p@identity`.
- Sync facts now carry the success message (`✓ claude: marketplace-add — …`).
- `AGENT_SYNC_FAILED` reads `claude plugin install <p>@<identity> (this home's marketplace, at <path>): …`.
- `home refresh-plugins` notes carry the same text.

## The four home-verdicts nodes

Each is planted in the scratch subject's own `.claude`/`.codex` (never the operator's, never the sandbox's). Each asserts that repair exits 1 naming the kind and subject, that verify exits 1 naming it, that `--fix` then a separate detection is clean, and that verify is clean afterwards. Each also checks what the fix must not touch.

| node | plant | extra checks |
| --- | --- | --- |
| `home.verdicts.marketplace.under.another.name` | known + extraKnown `skill-manager` at the subject's path; `hv-plugin@skill-manager`, `other@claude-plugins-official` enabled | registration re-pointed, enablement migrated, unrelated marketplace/plugin untouched |
| `home.verdicts.marketplace.identity.unregistered` | `hv-plugin@<identity>` enabled; only `<identity>-old` (at the neighbour) registered | identity registered at this home; enablement kept; `-old` untouched |
| `home.verdicts.copied.marketplace.identity` | manifest names the neighbour's identity | manifest names the subject's derived identity (independent oracle in `HomeVerdictsSupport.identityOf`) |
| `home.verdicts.foreign.marketplace.registration` | Codex `[marketplaces.skill-manager]` at the neighbour plus its plugin; Claude enablement plus registration of the neighbour's identity | foreign table and plugin gone; operator setting, own marketplace, own plugin, `github@openai-curated` survive byte for byte; Claude enablement gone |

Local run: `python skills/test_graph/scripts/run.py home-verdicts` gave 17/17 nodes passed in 208 s (report `20260914-124153`), laws included.

## CI

**Run 34846521245**, `graph_set=full` on `dc4f6356`: `graphs-executed.json` reads **26 selected / 26 executed / 26 passed / 0 failed**, all 31 jobs green. The ticket's graphs:

| graph | nodes |
| --- | --- |
| home-verdicts | 17/17, the four new nodes included |
| plugin-smoke | 28/28 |
| home-clone | 16/16, no IntentionalDamage declaration edits needed |
| home-integrity | 19/19 |

Deferred as on every run: browser-auth, refresh-flow, password-reset, hyper-experiments.

The branch tip adds only a `results/`-only merge of the epic (`10df4440`) and this evidence commit, so its code is what the run tested.

An earlier run, 34846096713 on `83651bfc`, was cancelled after about 1 minute: the root simulation found the trailing-newline defect fixed in `dc4f6356`.

## Real homes

**Builds:**
- baseline: released `skill-manager 0.27.2` (`baseline/released/`) and raw `0.27.2+g42486f86f2e0` (`baseline/raw-42486f86/`);
- the OHV-6 build's read-only verdicts before any write: `baseline/ohv6-build-prefix/`;
- writes: this worktree's raw build.

**Backups** were taken before any write, with sha256 manifests. Each holds `installed/`, `bin/` and `plugin-marketplace/`, the checkout's `.claude/settings.json`, `known_marketplaces.json`, `installed_plugins.json` and `.codex/config.toml`, and the four global files. Location: `/Users/hayde/IdeaProjects/wt-epic-one-home-one-verdict/results/epic-one-home-one-verdict/tickets/OHV-6-backups/<label>-20260914T084442/`. Not committed.

**Driver:** `realhome.py`, recorded under `real-homes/<label>/`. It runs verify, repair, `--fix`, repair, verify, then `sync skt --yes` with `SKILL_MANAGER_HOME`, `CLAUDE_HOME`, `CLAUDE_CONFIG_DIR`, `CODEX_HOME` and `GEMINI_HOME` all set to that checkout, then repair and verify again. A Python guard compares the four global files against the backup sha256 after the fix and after the sync, restoring everything and stopping on any change.

| home | verify / repair, released 0.27.2 | marketplace findings, OHV-6 build | `--fix` repaired (all kinds) | after fix | `sync skt`: AGENT_SYNC_FAILED | recorded errors after | global files |
| --- | --- | --- | --- | --- | --- | --- | --- |
| tla-spec-dev | 0 / 1 | shape 1 ×5 (Claude), shape 4 ×4 (Codex: the root's marketplace) | 16 (+6 frozen shims, 2 pm stamps, 3 dangling links) | 0 / 0 clean | **0** (3 recorded before) | none | unchanged |
| meta-orchestrator | 0 / 1 | shape 1 ×5 (Claude and Codex) | 10 | 0 / 0 clean | **0** (2 recorded before) | none | unchanged |
| meta-harness | 0 / 1 | shape 4 ×9 (Claude and Codex: the root's marketplace) | 51 (incl. 24 dangling agent links, 11 orphaned records from OHV-2's kinds) | 0 / 0 clean | **0** | none | unchanged |
| wt-229-library-attestation | 0 / 1 | shape 3 (manifest `skill-manager-919db26e`) | 4 | 0 / 0 clean | **0** | none | unchanged |
| root `~/.skill-manager` | 0 / 0 | shape 4 ×7 (Claude and Codex: commit-diff-context-parent's) | **not written** (owner) | n/a | n/a | n/a | n/a |
| project `skill-manager/.skill-manager` | 0 / 0 | none | not written | n/a | n/a | n/a | n/a |

What changed on disk (`real-homes/<label>/agent-files.diff`):
- **tla-spec-dev:** `.claude` `skill-manager` → `skill-manager-0fd46eec` in known and extraKnown, plus 3 enablements. The sync then wrote the three `@skill-manager-0fd46eec` rows into `installed_plugins.json`. `.codex` lost the root's `[marketplaces.skill-manager]` and 3 `@skill-manager` plugin tables; its own `@0fd46eec` tables were already there.
- **meta-orchestrator:** the same, and the sync enabled `skt@skill-manager-88340088` in Claude for the first time.
- **meta-harness:** the root's registration and 3 enablements were removed from `.claude` and `.codex`. `andrej-karpathy-skills` is not in its own manifest, so nothing of its own was dropped.
- **wt-229:** the manifest is now `skill-manager-3132fc05`, and the sync created its `.claude`/`.codex` registrations under that name.

Other things the sync did:
- tla-spec-dev: `cli: 1 failed` (slm-agent's private `slm` CLI, unrelated to plugins);
- tla-spec-dev: the gateway registered `runpod` at `global-sticky` scope, a sync side effect outside the four files checked.

**Goal harness** (`baseline/measure_before.json` → `real-homes/measure_after.json`, `--fleet`): root shape 4 (unchanged, pending the owner), project none; fleet shape 1/3/4 = 0/16/11 (any 25 of 62) → **0/13/8 (any 19 of 62)**. Shape 1 reads 0 on both sides only because the harness looks in the wrong file (DEF-OHV-160).

## Root home: commands awaiting the owner

Not run. The exact expected effect was produced by `root-home/`: copies of the four files in a scratch `$HOME`, the root's paths re-spelled onto it, the real `--fix` run there, and the result mapped back. It gave 7 findings, 7 repaired, 0 failed, and a clean detection afterwards (`sim-detect.json`, `sim-fix.json`, `sim-after.json`).

```bash
# 1. back up (durable path)
B=/Users/hayde/IdeaProjects/wt-epic-one-home-one-verdict/results/epic-one-home-one-verdict/tickets/OHV-6-backups/root-$(date +%Y%m%dT%H%M%S)
mkdir -p "$B" && cp -p ~/.claude/plugins/known_marketplaces.json ~/.claude/plugins/installed_plugins.json ~/.claude/settings.json ~/.codex/config.toml "$B"/ && shasum -a 256 "$B"/* > "$B"/SHA256SUMS
# 2. confirm the findings are exactly the seven
/Users/hayde/IdeaProjects/wt-ohv-6/skill-manager home repair --home ~/.skill-manager --json
# 3. fix
/Users/hayde/IdeaProjects/wt-ohv-6/skill-manager home repair --home ~/.skill-manager --fix --json
# 4. next plugin sync of the root (expected: 0 AGENT_SYNC_FAILED)
/Users/hayde/IdeaProjects/wt-ohv-6/skill-manager sync skt --yes
```

Expected diff of each global file (`root-home/expected-global-diffs.patch`):
- `~/.claude/plugins/known_marketplaces.json`: the `skill-manager-919db26e` entry is removed. The ~8 unenabled project-home entries stay (the exemption above).
- `~/.claude/settings.json`: `enabledPlugins` loses `cdc-agent-substrate-plugin@skill-manager-919db26e` and `skt@skill-manager-919db26e`, and `extraKnownMarketplaces` loses `skill-manager-919db26e`. Nothing else changes, including the trailing newline.
- `~/.codex/config.toml`: `[plugins."cdc-agent-substrate-plugin@skill-manager-919db26e"]`, `[plugins."skt@skill-manager-919db26e"]` and `[marketplaces.skill-manager-919db26e]` are removed. Every other byte stays.
- `~/.claude/plugins/installed_plugins.json`: unchanged. Any `@skill-manager-919db26e` rows are cache and stay.

## Validation

- `jbang RunTests.java`: ALL PASSED, three runs: after the slice, after the carried-plugin re-point, and after the newline fix.
- `uv run --with pytest pytest specs/program_model/tests -q`: 11 passed.
- Close-out: `skill-manager home close-out --home /Users/hayde/IdeaProjects/wt-ohv-6/.skill-manager --into /Users/hayde/IdeaProjects/skill-manager/.skill-manager` exited 0 with "holds nothing that removing it would destroy" (`close-out.txt`). No unit in the ticket home was changed.

## Deferred

DEF-OHV-160 (`deferred.yaml`): the goal harness reads shape 1 from the global `known_marketplaces.json` only.
