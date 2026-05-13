# Coordinates and distribution

Skill-manager units are usually distributed as git repositories. The
registry is metadata/search, not the source of bytes for the normal
flow.

## Source of truth

Recommended publishing model:

1. Put exactly one installable unit at the git repo root.
2. Commit the unit files.
3. Push to GitHub or another git host.
4. Install with `skill-manager install github:owner/repo`,
   `skill-manager install git+https://...`, `skill-manager install
   git+ssh://...`, or a local path during development.
5. Optionally publish metadata to a registry for search/discovery when
   that unit kind is supported.

The root of the repo must contain the marker for the unit kind:

| Kind | Required root marker |
|---|---|
| Skill | `SKILL.md` and `skill-manager.toml` with `[skill]` |
| Plugin | `.claude-plugin/plugin.json` and optional `skill-manager-plugin.toml` |
| Doc-repo | `skill-manager.toml` with `[doc-repo]` |
| Harness | `harness.toml` with `[harness]` |

Do not put multiple top-level units in one repo today. The resolver
detects one unit from the source root, and nested unit files can confuse
local shape detection. If units must ship together, use a plugin or a
harness, or put each unit in its own repo and reference the others by
coord.

## Coord forms

The same coordinate grammar is used for install sources and transitive
references.

| Coord | Meaning |
|---|---|
| `github:owner/repo` | Clone `https://github.com/owner/repo.git`; kind detected from the root. |
| `github:owner/repo#branch-or-tag` | GitHub repo with an explicit ref. |
| `git+https://host/org/repo.git` | Direct git URL; kind detected from the root. |
| `git+ssh://git@host/org/repo.git` | SSH git URL; uses the host `git` CLI and user credentials. |
| `file:///abs/path/to/unit` | Local absolute path. |
| `file:./relative/path` | Local path relative to the referencing unit's root when used transitively. |
| `./relative/path` / `../relative/path` | Local path shorthand. |
| `skill:name` | Kind-pinned registry/name lookup for a skill. |
| `plugin:name` | Kind-pinned registry/name lookup for a plugin. |
| `doc:name` | Installed/registry doc-repo coord; bindable after install. |
| `doc:name/source-id` | One doc-repo source sub-element. |
| `harness:name` | Harness template coord. |
| `name` / `name@version` | Bare registry/name lookup; kind inferred by registry metadata. |

Prefer direct git coords (`github:` / `git+...`) for distribution unless
you specifically need registry search.

## References in manifests

Skills use `skill_references`:

```toml
skill_references = [
  "github:owner/base-skill",
  "git+ssh://git@github.com/org/private-skill.git#main",
  "file:./local-helper",
  "skill:published-helper@1.2.0",
]
```

Plugins use `references` in `skill-manager-plugin.toml`:

```toml
references = [
  "github:owner/shared-skill",
  "plugin:shared-plugin",
]
```

Harnesses use `units` and `docs` in `harness.toml`:

```toml
[harness]
name = "repo-review"
version = "0.1.0"

units = [
  "github:owner/reviewer-skill",
  "github:owner/repo-tools-plugin",
]

docs = [
  "github:owner/team-prompts",
  "doc:team-prompts/review-stance",
]
```

Doc-repo source sub-elements are used at bind/instantiate time. A
doc-repo manifest declares source ids; references select them with
`doc:<repo>/<id>`.

## Git versioning and sync

For git-backed installs, the installed record remembers origin/ref/SHA.
`skill-manager sync <name>` pulls upstream and re-runs install side
effects. When the source was a branch, sync can advance to the latest
commit on that branch. `--git-latest` skips registry-pinned SHA lookup
and fetches the install-time git ref directly.

Implications for authors:

- Commit before validating a git install.
- Tag or pin refs when you need reproducibility.
- Expect `sync` to update from the remembered git source, then re-run
  CLI/MCP/plugin marketplace/agent projection side effects.
- Keep one unit per repo root so sync can update the unit deterministically.

## Registry role

The registry is optional and metadata-oriented:

- `skill-manager search` discovers published metadata.
- Modern publish records git URL, git ref/SHA, owner, name, and version.
- Install-by-name resolves metadata and clones from git.
- The registry should not be treated as the canonical byte store.

During current authoring, validate distribution with direct git/local
install first. Use registry publish only when you need discoverability
for a supported unit kind.
