# Doc-repos

A doc-repo is an installable unit whose main artifact is markdown for
agent project context. It installs into the skill-manager store, then
binds selected markdown sources into a project root as tracked copies
and managed imports.

Use a doc-repo when:

- The user wants reusable prompt/docs snippets for `CLAUDE.md` and/or
  `AGENTS.md`.
- The markdown should be versioned and distributed separately from a
  skill.
- The project should receive explicit bound copies under `docs/agents/`
  rather than loading a whole skill.

Do not use a doc-repo when the content is instructions for the agent to
load as a capability. That is a skill.

## Layout

```
team-prompts/
├── skill-manager.toml
└── claude-md/
    ├── review-stance.md
    └── build-instructions.md
```

`skill-manager.toml`:

```toml
[doc-repo]
name = "team-prompts"
version = "0.1.0"
description = "Reusable project prompt snippets."

[[sources]]
file = "claude-md/review-stance.md"

[[sources]]
file = "claude-md/build-instructions.md"
id = "build-instructions"
agents = ["claude"]
```

Rules:

- `[doc-repo].name` is the install name; it defaults to the directory
  name only if omitted.
- Each `[[sources]].file` must exist and must stay inside the repo root.
  Path traversal is rejected.
- `id` is optional and defaults to the file stem. The binding coord is
  `doc:<repo>/<id>`.
- `agents` defaults to `["claude", "codex"]`. `claude` adds an import to
  `CLAUDE.md`; `codex` adds an import to `AGENTS.md`.
- Only declared sources are bindable. Extra markdown files can live in
  the repo as drafts or supporting docs.

## Install and bind

Install stores the repo under `$SKILL_MANAGER_HOME/docs/<name>/`:

```bash
skill-manager install file:///abs/path/to/team-prompts --yes
# or
skill-manager install github:org/team-prompts
```

Bind every source into a project:

```bash
skill-manager bind doc:team-prompts --to /path/to/project
```

Bind one source:

```bash
skill-manager bind doc:team-prompts/review-stance --to /path/to/project
```

Binding writes:

```
<project>/docs/agents/<source-file-name>.md
<project>/CLAUDE.md     # if source agents include "claude"
<project>/AGENTS.md     # if source agents include "codex"
```

`CLAUDE.md` / `AGENTS.md` receive managed import lines such as:

```markdown
@docs/agents/review-stance.md
```

## Sync behavior

`skill-manager sync <doc-repo-name>` reconciles managed copies for every
recorded doc binding.

High-level outcomes:

- Upstream source unchanged and local copy unchanged: no write.
- Upstream source changed and local copy still matches the previously
  bound hash: rewrite the tracked copy.
- Local copy changed by the user: preserve it and report the drift.
- `--force`: clobber local edits with the upstream source.
- Source removed from the doc-repo: leave the destination in place and
  report the stale binding so the user can unbind or rebind.

Inspect bindings before changing them:

```bash
skill-manager bindings list --unit team-prompts
skill-manager bindings show <bindingId>
```

Remove one binding:

```bash
skill-manager unbind <bindingId>
```

Unbinding removes the tracked copy and the managed import line for that
binding. When the last managed import for an agent file is gone,
skill-manager cleans up the managed section it owns.

## Validation

Use a disposable project root:

```bash
tmp="$(mktemp -d)"
skill-manager install file:///abs/path/to/team-prompts --yes
skill-manager bind doc:team-prompts --to "$tmp"
test -f "$tmp/docs/agents/review-stance.md"
test -f "$tmp/CLAUDE.md"
test -f "$tmp/AGENTS.md"
skill-manager bindings list --root "$tmp"
```

For an in-repo starter, see `examples/doc-repo/` in this skill.
