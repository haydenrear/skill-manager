---
name: skill-publisher
description: 'Author and maintain installable skill-manager units: skills, plugins, doc-repos, and harnesses. Read this before editing any file inside a unit — SKILL.md, its frontmatter or description, skill-manager.toml, plugin.json, harness.toml, or a references/ page — not only when creating one from scratch. Use when making a directory installable by skill-manager, choosing a unit kind, scaffolding a unit, writing or reviewing unit manifests/TOML, adding CLI or MCP dependencies, wiring references, validating install/bind/instantiate round-trips, preparing optional registry metadata, or shipping an edit to an already-installed unit so it reaches $SKILL_MANAGER_HOME (commit, push, then `skill-manager sync`). Detailed schemas live in references for skills, plugins, doc-repos, harnesses, scaffolding, coordinates/distribution, dependencies, bindings/sync, and skill-script.'
skill-imports:
  - unit: skill-manager
    path: references/skill-imports.md
    reason: Defines semantic markdown imports used by authored unit scaffolds.
    section: semantics
---

# skill-publisher

Use this skill when the user wants to turn a directory of agent docs,
tooling, or project profiles into something **skill-manager can
install**, and whenever the user edits a unit that is already
installable.

The main job:

1. Pick the right unit kind.
2. Scaffold or copy a minimal example.
3. Author the unit-specific manifest.
4. Validate install and any projection behavior (`bind` or
   `harness instantiate`).
5. Distribute it: create a GitHub repo with a `LICENSE`, push, and
   install the durable copy from that git source — not from a local
   `file://` path. Favor this unless the user asks otherwise. Registry
   publish is optional metadata for supported shapes, not the primary
   distribution path. See `references/coords-and-distribution.md`.
6. Ship later edits into the store: commit, push, then
   `skill-manager sync <unit>`. An edit that is not pushed and synced is
   invisible to agents. See "Shipping edits to an installed unit" below.

The virtual MCP gateway is in scope: declaring `[[mcp_dependencies]]` in
a skill/plugin manifest is how an MCP server becomes registered. Running
or hosting a skill-manager-server registry is out of scope except for
the optional publish step.

## When to use

- The user has a directory and wants `skill-manager install` to handle
  it.
- The user wants to create or review a skill, plugin, doc-repo, or
  harness.
- **The user wants to edit an existing one.** Any change to `SKILL.md`,
  a frontmatter `description`, `skill-manager.toml`,
  `.claude-plugin/plugin.json`, `harness.toml`, or a `references/` page
  is unit authoring, and it is not done until the edit is pushed and
  synced.
- The user wants to scaffold a new unit.
- The user wants to add references, CLI deps, or MCP deps.
- The user wants to validate install, bind, sync, or harness
  instantiation.
- The user asks why an edit to a skill is not showing up for agents, or
  how a change reaches `$SKILL_MANAGER_HOME`.
- Something is wrong with publish/install/sync and you need to map the
  failure back to a manifest field.

## Unit Kinds

| Kind | Use when | Root marker | Details |
|---|---|---|---|
| Skill | One focused agent capability. | `SKILL.md` + `skill-manager.toml` with `[skill]` | `references/skills.md` |
| Plugin | Bundle skills with hooks, commands, agents, or shared deps. | `.claude-plugin/plugin.json` | `references/plugins.md` |
| Doc-repo | Version markdown sources bound into project `CLAUDE.md` / `AGENTS.md`. | `skill-manager.toml` with `[doc-repo]` | `references/doc-repos.md` |
| Harness | Everything an agent needs for a project role: reviewer, coder, release agent, etc. | `harness.toml` with `[harness]` | `references/harnesses.md` |

When in doubt, write a skill. Use a plugin for plugin runtime surface or
versioned bundles. Use a doc-repo for markdown that should bind into a
project. Use a harness for a full project-specific agent profile that
composes skills, plugins, docs, and MCP tool selections.

## Reference Map

Load only the reference needed for the current task:

- `references/scaffolding.md` — `skill-manager create`, current scaffold
  support, example starters, and initial validation.
- `references/skills.md` — bare skill layout, `SKILL.md` frontmatter,
  `[skill]` TOML, and skill validation.
- `references/plugins.md` — plugin layout, `.claude-plugin/plugin.json`,
  `skill-manager-plugin.toml`, contained skills, hooks/commands/agents,
  and plugin validation.
- `references/doc-repos.md` — `[doc-repo]`, `[[sources]]`, source ids,
  `agents`, bind coords, doc binding, and doc sync behavior.
- `references/skill-imports.md` — semantic markdown imports,
  frontmatter syntax, and when to add manifest references for imported
  skills.
- `references/harnesses.md` — `harness.toml` as a full
  project-specific agent profile, transitive refs, `harness
  instantiate`, `--project-dir` doc/`CLAUDE.md`/`AGENTS.md` binding,
  instance locks, sync, and rm.
- `references/coords-and-distribution.md` — coord grammar,
  `github:`/`git+`/`file:` sources, one unit per git repo root,
  references, git sync behavior, the edit → push → sync store loop, and
  registry metadata.
- `references/dependencies.md` — CLI and MCP dependency TOML examples
  and resolution behavior for pip/npm/brew/tar/skill-script and
  npm/uv/docker/binary/shell MCP load types.
- `references/bindings-and-sync.md` — install vs bind, projection
  ledgers, conflict policies, unbind/rebind, doc sync, and harness sync.
- `references/skill-scripts.md` — private CLI install scripts, env vars,
  fingerprinting, rerun semantics, and security model.
- `references/runpod-mcp-onboarding.md` — worked MCP onboarding case
  study with npm load type and host-env passthrough.

## Examples

Example starters live under `examples/`:

- `examples/skill/`
- `examples/plugin/`
- `examples/doc-repo/`
- `examples/harness/`

Copy one when the CLI does not scaffold that shape yet or when you need
a minimal known-good layout:

```bash
cp -r <skill-publisher-skill>/examples/skill <my-skill>
cp -r <skill-publisher-skill>/examples/plugin <my-plugin>
cp -r <skill-publisher-skill>/examples/doc-repo <my-doc-repo>
cp -r <skill-publisher-skill>/examples/harness <my-harness>
```

## Operating Workflow

1. Choose the unit kind from the table above.
2. Load the matching reference and `references/scaffolding.md`.
3. Ensure the unit is at the root of its own git repo. Do not put
   multiple top-level units in one repo today.
4. Use `references/coords-and-distribution.md` when adding references or
   deciding between `github:`, `git+`, `file:`, and registry/name coords.
5. Use `references/dependencies.md` when adding CLI or MCP deps.
6. Validate locally from outside the source directory. A `file://`
   install here is for dry-run/validation only, not the durable copy:

```bash
skill-manager install file:///abs/path/to/unit --dry-run
skill-manager install file:///abs/path/to/unit --yes
skill-manager show <name>
skill-manager list
```

7. For the durable install, create a GitHub repo with a `LICENSE`,
   push, and install from `github:owner/repo`. Favor this over leaving a
   `file://` install unless the user asks for a local-only install. The
   default publishing model lives in
   `references/coords-and-distribution.md`.

Extra validation:

- Skill/plugin: `skill-manager publish <dir> --dry-run` for the current
  registry package path.
- Skill/plugin with `skill-script:` deps: run a dry-run install from
  `file:///abs/path/to/unit`; use `install --force-scripts` or
  `sync <unit> --force-scripts` only when validating explicit replay
  behavior. Named sync replays scripts for that unit only, while
  no-name `sync --force-scripts` replays scripts for all installed
  units.
- Doc-repo: bind into a disposable project and inspect `docs/agents/`,
  `CLAUDE.md`, and `AGENTS.md`.
- Harness: instantiate with explicit `--claude-config-dir`,
  `--codex-home`, and `--project-dir`, then run `skill-manager harness
  list` and `skill-manager harness rm <id>`.

## Shipping Edits to an Installed Unit

Agents read units from the **store**
(`$SKILL_MANAGER_HOME/skills/<name>/`, `plugins/<name>/`,
`docs/<name>/`, `harnesses/<name>/`), not from the source repo you just
edited. Editing the source repo changes nothing an agent can see until
the bytes reach the store. A finished edit means synced, not saved.

The store copy for a git-backed unit is a checkout of a **remote** ref.
`skill-manager show <unit>` prints its store path, and
`$SKILL_MANAGER_HOME/installed/<unit>.json` records the `origin`,
`gitRef`, and `gitHash` that sync pulls from. So the loop is:

```bash
cd <unit-repo>
# ...edit SKILL.md / manifest / references...
git add -A && git commit -m "docs: ..."
git push origin main                    # sync pulls from the REMOTE
skill-manager sync <unit> --git-latest  # fetch gitRef, re-run side effects
```

Then confirm the store actually moved — do not assume sync succeeded:

```bash
skill-manager list          # SHA column should match the pushed commit
git rev-parse --short HEAD  # ...this one
```

Notes that trip agents up:

- **Push before sync — and check, because sync will not tell you.**
  Sync fetches the remembered `origin` at `gitRef`. A local commit that
  was never pushed is not upstream, so sync leaves the store on the old
  bytes. It still **exits 0 and prints a normal success report**,
  including MCP/CLI side effects, so a green sync is *not* evidence the
  bytes moved. The only proof is `gitHash` in
  `$SKILL_MANAGER_HOME/installed/<unit>.json` (or the SHA column of
  `skill-manager list`) matching your pushed `HEAD`.
- **The unit name is not the repo name.** Sync takes the installed unit
  name (`skill-manager sync skill-publisher`), while the remote is
  `github:owner/<repo>` — often spelled differently. `skill-manager
  list` gives the unit names.
- **Nested repos need two pushes.** When a unit repo lives inside a
  parent repo's tree, push the unit repo first; the parent commit only
  records the unit's files, and sync never reads the parent.
- **`--git-latest` when no registry is configured.** Plain
  `sync <unit>` may consult the registry for a published `git_sha`.
  `--git-latest` skips it and fetches the install-time `gitRef`
  directly, which is what you want for an unpublished edit. See the
  registry caution in `references/coords-and-distribution.md`.
- **Never edit the store copy in place.** The next sync overwrites it,
  and its provenance no longer matches `origin`.
- **Sync re-projects the agent symlinks.** A successful sync reports
  `✓ claude: synced <unit>` per configured agent, which is how the new
  frontmatter reaches each agent's skill directory. Claude Code re-reads
  a changed `description` in the running session; other agents may cache
  the skill list until restart. If a description change does not seem to
  take, restart the session before suspecting the manifest.

To iterate without pushing on every keystroke, use a working-tree sync
(`skill-manager sync <unit> --from <dir> --merge --yes`) or the
`skill-dev` worktree flow, then finish with a real commit + push + sync
so the store's provenance points at the remote again. Full semantics are
in `references/bindings-and-sync.md` and the "Git versioning and sync"
section of `references/coords-and-distribution.md`.

## Modeled CLI Workflow Coverage

These workflow ids are shared with the CLI metadata catalog and the
TLA+ program model. Use them to choose the right authoring path, then
run the help command for exact syntax instead of copying option tables
into this skill.

| Workflow id | Use when | Help |
| --- | --- | --- |
| `author-dependencies` | adding CLI, MCP, or reference dependencies to a unit | `skill-manager create --help` |
| `author-unit` | scaffolding a skill or plugin starter | `skill-manager create --help` |
| `install-local-unit` | validating an authored unit from disk | `skill-manager install --help` |
| `publish-unit` | checking registry package metadata | `skill-manager publish --help` |
| `skill-scripts` | authoring a private CLI installer under `skill-scripts/` | `skill-manager install --help` |

For `skill-scripts`, also check `skill-manager sync --help` before
using `--force-scripts`; named sync replays only the named target's
scripts.

## Boundaries

- This skill does not publish anything itself. It explains how; the
  agent runs `skill-manager publish`, `git push`, or `skill-manager
  sync` when ready. Pushing and syncing are outward-facing — confirm
  with the user before running them unless they already asked.
- This skill does not modify `~/.skill-manager/cli-lock.toml`,
  `policy.toml`, or `registry.properties`. Surface conflicts and let the
  user decide.
- This skill does not generate API keys or secrets. Prompt the user for
  required `init_schema` secrets; never invent them.
- This skill does not cover hosting skill-manager-server. Use the main
  skill-manager README for registry operations.
