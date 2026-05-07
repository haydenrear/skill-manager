# CLAUDE.md / AGENTS.md as `AgentUnit`

## TL;DR

Treat agent-instruction documents (`CLAUDE.md`, `AGENTS.md`, and the
analogous files for other harnesses) as a first-class `AgentUnit`
kind — `DocUnit` — so they can be installed, versioned, referenced
from harness templates, and shared through user profiles the same way
plugins and skills are. Deferred from the harness-templates ticket;
scoped to land after harness templates are in.

## Motivation

A harness controls *what tools and skills an agent can see*. The other
half of agent behavior comes from its instructions — the
`CLAUDE.md` / `AGENTS.md` content. Today these are hand-managed files
with no install path, no version, no provenance, and no way to express
"this harness ships with this set of instructions." That's the
asymmetry this ticket closes: instructions become installable artifacts
on equal footing with code-shaped units.

Concrete drivers:

- **Per-agent instructions in a topology.** A reviewer agent and a
  planner agent want different `CLAUDE.md`s; right now the harness
  template can give them different tools but not different prompts.
- **Profile portability.** A user's preferred working-style
  `CLAUDE.md` should travel with their profile alongside their skills.
- **Versioning + sharing.** Doc units can be published, forked,
  upgraded, and depended on the way plugins are. No more copy-paste
  proliferation.

## Concept

`DocUnit` is the fourth permit on `AgentUnit`:

```java
public sealed interface AgentUnit permits PluginUnit, SkillUnit, HarnessUnit, DocUnit { ... }
```

A doc unit's manifest declares a set of agent-instruction documents
keyed by the agent id they apply to:

```toml
[doc]
name = "review-stance"
version = "0.2.0"
description = "Working-style instructions for code-reviewer agents."

[docs.claude]
file = "claude.md"            # rendered into <harness>/claude/CLAUDE.md

[docs.codex]
file = "agents.md"            # rendered into <harness>/codex/AGENTS.md
```

The unit is just files + a manifest. Resolution, planning,
compensations, lock entries — all the existing machinery — apply
unchanged. The only kind-divergence point is the projector: a doc unit
materializes into the appropriate filename in each agent's harness
root.

## Composition with harness templates

Harness templates gain doc-unit references the same way they reference
plugins and skills:

```toml
units = [
  "plugin:repo-intelligence",
  "skill:diff-narrative",
  "doc:review-stance",
]
```

When the harness is instantiated, the doc unit's projector writes
`CLAUDE.md` / `AGENTS.md` into the per-agent harness root. Multiple
doc units in one harness are concatenated in declaration order with a
visible separator; conflicts are flagged at plan time, not silently
overwritten.

## Open questions (to resolve in this ticket)

- **Concatenation vs. single-source.** Allowing multiple doc units per
  harness is more flexible but produces merge questions. Default
  proposal: concatenate in declaration order with section markers,
  emit a plan-time warning if two units write the same `[docs.<agent>]`
  key.
- **Project-local `CLAUDE.md` precedence.** A repo's checked-in
  `CLAUDE.md` should generally win over installed doc units. Likely
  rule: harness-installed docs render to the harness root; the agent's
  normal merge order (project CLAUDE.md → user CLAUDE.md) still applies
  on top.
- **Templating.** Should doc units support variables (e.g. interpolating
  the harness name, the topology role)? Probably yes, with a small
  fixed substitution set; full templating is out of scope.

## Out of scope

- Authoring tools beyond `skill-manager doc new`. Doc units are
  text-only; users edit them in their normal editor.
- Doc-unit-specific search ranking. They surface in `search` and
  `list` like any other unit.

## Implementation order

1. `DocUnit` permit + parser for the doc manifest.
2. `DocProjector` that renders to `<harness>/<agent>/CLAUDE.md` (or the
   agent's equivalent filename, taken from the projector).
3. Wire `doc:` coords through resolver/planner (mostly free given the
   `AgentUnit` widening already done).
4. Update harness templates to accept doc references and harness
   instantiation to invoke the doc projector.
5. Profile schema gains `profile.docs` for portability.
6. Tests: doc-unit install, harness-with-doc instantiation, multi-doc
   concatenation order, project-local-CLAUDE.md precedence.

## Dependencies

- `harness-templates.md` lands first — doc units are most useful as
  harness references, and the harness projector is the natural place
  to invoke `DocProjector`.
- Plugin marketplace work (`AgentUnit` sealed interface, projector
  abstraction, lock model) — already in flight; this ticket is a
  straightforward fourth permit on top.
