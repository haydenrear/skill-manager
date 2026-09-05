---
skill-imports: []
---

# Skill imports

`skill-imports` are semantic edges from one markdown file to a specific
file inside an installed unit: a skill, plugin, doc-repo, or harness.
They let an agent discover shared instructions lazily without copying
those instructions into every unit.

Imports are frontmatter-only. Inline import syntax is not supported.

```markdown
---
skill-imports:
  - unit: skill-manager
    path: references/mcp.md
    reason: Explains how MCP servers are exposed through the virtual gateway.
    section: mcp-dependencies
---
```

## Fields

- `unit` is required and must name an installed unit. The older `skill`
  key is still accepted for compatibility, but the value may name any
  installed unit kind.
- `path` is required and must point to a regular file inside that unit.
- `reason` is required. It explains why the edge exists and helps the
  agent decide whether to traverse it.
- `section` is optional and advisory. It is a navigation hint, not a
  validated anchor.

## Semantics

An import means: this file depends on or extends behavior documented in
the referenced file. It is not a text include, it is not an execution
dependency, and it does not automatically install the target.

If the target must be installed transitively, declare it separately as a
manifest reference using an explicit unit coord:

```toml
skill_references = [
  "github:owner/shared-unit",
]
```

Do not add a manifest reference just because a markdown import points at
an already-installed or separately bundled unit. Plugins can declare
install-time references at the plugin level or in the contained skill
that owns the dependency. Doc-repos and harnesses may import markdown
from any installed unit; their install-time composition is handled by
the unit or harness manifest.

## Onboarding-bundled units

The units installed during onboarding — `skill-manager`, the `skt`
plugin, and `skill-dev` — are present in the store of a home **that was
onboarded**. A markdown `skill-imports` edge that points at one of them
needs no matching `skill_references` entry in the importing unit's TOML.
Reserve `skill_references` for units that must be fetched transitively.

**That is a property of the home, not of the units, and the tiers do not
inherit it.** A project or worktree home holds exactly what its parent
held when it was cloned — see `## Homes Come In Tiers, And Every Tier Is
A Copy` in `references/projects.md`. A home cloned from one that never
installed the bundled set holds none of it, and every import pointing at
those units dangles there.

Measured on this CLI's own repository, 2026-08-24, in the two homes the
`skt` skill reports on: the `spec-double-compiler` installed in each
declared imports at `skill-manager: references/cli.md` and `skt:
references/skills.md`, and **neither target existed in either home**,
though both existed in the operator's root home. An agent standing there
is told by a frontmatter edge that an authority exists, and cannot open
it — and nothing reports the dead edge (see Validation).

So declaring an onboarding-bundled unit in a checkout's
`skill-project.toml` is not redundant: it is what makes the import
resolvable in that checkout's own home. `project resolve` refuses the
whole resolve with "references missing unit" until an *imported* unit is
declared — unit `skill_references` are followed automatically, markdown
`skill-imports` are not.

## Validation

Install, publish, and sync validate every markdown file under the unit
root. Validation checks that each target unit exists, each target path
stays inside that unit directory, and the target file exists. Failures
are explicit and actionable; there are no silent skips for malformed
imports.

**Those three verbs are the whole of it.** `home clone` — and so every
project and worktree home produced from one — copies a unit whose
imports were valid in the source home into a home where they may not be.
Nothing re-checks them there, and the agent reading that frontmatter gets
no signal that the edge is dead. A validated import is a statement about
the home the unit was installed into, not about the home you are standing
in.

## How A Unit Name Is Addressed

This is the rule, stated once, because it was previously only implicit in
two different readers and they do not agree.

### Two mechanisms, two keys

| | `skill-imports` | `skill_references` |
| --- | --- | --- |
| lives in | markdown frontmatter, any `.md` under a unit root | the unit's own root manifest (`skill-manager.toml`, `skill-manager-plugin.toml`) |
| addresses a unit by | **name** | **coordinate** (`github:owner/repo`, a path, a registry ref) |
| resolved by | `MarkdownImportValidator`, against **this home's** store | `Resolver`, at install time, by fetching |
| means | this file extends behaviour documented over there | install that unit too |
| recorded in `installed/*.json` | no | no |

**The name and the coordinate are not the same key, and the mapping between
them is not obvious.** `github:haydenrear/skill-manager-skill` installs a
unit named `skill-manager`; `github:haydenrear/skill-publisher-skill`
installs one named `skt`. The repository name is not the unit name. The only
place the two are joined is each unit's `installed/<name>.json`, whose
`origin` field records the coordinate it came from — and nothing in the
product performs that join.

### The four branches, in order

A `unit:` name is resolved by searching the home's store in exactly this
order, first hit wins:

```
plugins/<name>     harnesses/<name>     docs/<name>     skills/<name>
```

**None of them descends into a plugin's contained skills.** A skill at
`plugins/<plugin>/skills/<skill>` is not addressable by name at all — an
import naming it is reported as `references missing unit`, in the very home
where the file is sitting on disk.

### One name, one copy

A unit name resolves to exactly one copy in a home. Two consequences:

- A plugin carrying a contained skill whose name already exists as a
  standalone unit in that home is **refused**, not merged. Two copies of one
  name is not a version conflict to be reconciled; it is an ambiguity the
  four-branch search would silently resolve by directory order.
- Moving a unit between shapes — standalone skill to plugin-contained skill —
  keeps the name and moves the copy. Both existing at once is exactly the
  state the refusal exists to prevent, which is why a migration has to retire
  the old copy in the same operation that installs the new one, and not
  before or after.

### The reverse edge is computed, never stored

"What in this home imports X" has no answer on disk: neither mechanism is
recorded in `installed/*.json`, which holds only name, version, gitHash,
kind, origin, installSource, errors and installedAt. The answer is recomputed
from the unit trees on demand.

That is deliberate. A stored index is a second record of the edges, and a
second record can disagree with the units it describes — a home is a *copy*,
so an index copied with it describes the home it was built in, not the one
you are standing in.

Two properties any such computation must have, because the edges are not a
tree:

- **transitive** — the whole chain, not the direct importers. Units here
  depend on each other through intermediaries.
- **cycle-safe** — mark a name before expanding it. `skill-imports` edges do
  form loops, and a walk that does not mark returns to the same unit forever.
