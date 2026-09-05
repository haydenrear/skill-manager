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

### The five branches, in order

A `unit:` name is resolved by searching the home's store in exactly this
order, first hit wins:

```
plugins/<name>   harnesses/<name>   docs/<name>   skills/<name>   plugins/*/skills/<name>
```

**The contained branch is last, and the position is the rule, not an
implementation detail.** It means a contained skill can never shadow a
standalone unit of the same name: the ambiguity is refused at install time
(below), not resolved silently by search order. It also means **a plugin's
entry skill is the plugin** — `plugins/skt` is matched before
`plugins/skt/skills/skt`, so `skt` is one unit under one name.

When two installed plugins each contain a skill of the same name, resolution
takes the first in plugin-name order — deterministic, so it is testable, and
still an ambiguity that the collision gate refuses rather than resolves.

### One name, one copy — the rule, not yet the behaviour

> **Status.** Everything above this line describes what the product does
> today, including the fifth branch (a plugin-contained skill resolves by
> name, OUN-1) and the reverse-edge command (OUN-3). One thing in this section
> is still being built by epic `one-unit-one-name`: **the refusal does not
> exist yet** (OUN-2). It is written here so one statement of the rule
> precedes the code, rather than three readers each inventing their own.

A unit name should resolve to exactly one copy in a home. Two consequences:

- A plugin carrying a contained skill whose name already exists as a
  standalone unit in that home is **refused**, not merged. Two copies of one
  name is not a version conflict to be reconciled; it is an ambiguity the
  four-branch search would silently resolve by directory order.
- Moving a unit between shapes — standalone skill to plugin-contained skill —
  keeps the name and moves the copy. Both existing at once is exactly the
  state the refusal exists to prevent, which is why a migration has to retire
  the old copy in the same operation that installs the new one, and not
  before or after.

**One case is already on disk and is not a violation.** A plugin may carry a
contained skill with the plugin's own name — `skt` carries
`plugins/skt/skills/skt` — and that is one unit under one name, the plugin's
entry skill, not two claims on it. Measured 2026-09-05: 21 of this machine's
homes are in that shape, and a rule that did not say so would refuse the
`skt` plugin everywhere it is installed.

### The reverse edge is computed, never stored

"What in this home imports X" has no answer on disk: neither mechanism is
recorded in `installed/*.json`, which holds only name, version, gitHash,
kind, origin, installSource, errors and installedAt. Ask the product:

```
skill-manager deps --who-imports skill-manager
skill-manager deps --who-imports skill-manager --direct-only
```

It covers **both** mechanisms, follows the whole chain, names the file
carrying each edge — the question is usually asked by whoever is about to
break that line — and reports coordinates that name nothing installed here
rather than guessing at them.

**It recomputes on every run and stores nothing.** A stored index is a second
record of the edges, and a second record can disagree with the units it
describes — a home is a *copy*, so an index copied with it describes the home
it was built in, not the one you are standing in. The divergence is not
theoretical: the same question answers **8** in the operator root home and
**6** in this repository's project home, and both answers are correct for
their home.

Two properties the computation has, because the edges are not a tree:

- **transitive** — the whole chain, not the direct importers. Units here
  depend on each other through intermediaries.
- **cycle-safe** — a name is marked before it is expanded. The edges do form
  loops: in this machine's root home `git-epic-workflow` imports
  `git-issue-workflow` and `git-issue-workflow` imports `git-epic-workflow`
  back. A walk that does not mark returns to the same unit forever.

**The coordinate half needs the origin join.** A manifest reference names a
repository; the unit it installs may have a different name
(`github:haydenrear/skill-manager-skill` installs `skill-manager`). The
command resolves that through each unit's `installed/<name>.json` `origin`
field. A coordinate whose unit is not installed here cannot be resolved at
all, and is listed separately instead of being counted as an edge to a name
guessed from the URL.
