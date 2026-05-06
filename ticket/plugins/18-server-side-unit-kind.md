# 18 — Server-side `unit_kind` column + API surface

**Phase**: F — Verification + ship
**Depends on**: 13 (client-side plugin publish bundle).
**Spec**: § "Manifest model" / "Search and list" — server-side
companion to ticket 14's CLI columns.

## Goal

Teach the registry server (`server-java/`) about plugin units. Land a
`unit_kind` column on the per-name ownership row, detect kind from
the bundle at publish time, and surface `unit_kind` in every API
response that lists/describes a unit.

## What to do

### Schema (no migration — pre-deployment rewrite)

Schema is JPA-managed (`spring.jpa.hibernate.ddl-auto=update`).
Adding a new field to the entity adds the column on first run
against the existing dev database. No legacy production data → no
SQL migration script needed.

### Entity / persistence

```
server-java/.../persistence/SkillName.java
```

Add `unit_kind` column. Keyed on the per-NAME row because a name
publishes either as a plugin or as a skill, never both — the kind
sticks with the name. (A future "republish under a different kind"
flow would require deleting the name first.)

```java
@Column(name = "unit_kind", length = 16, nullable = false)
private String unitKind;   // "skill" | "plugin"
```

### Publish service

```
server-java/.../publish/SkillPublishService.java
```

On both publish paths (github-pointer and tarball-upload), inspect
the bundle to detect kind:

- Tarball upload: extract to a temp dir, probe for
  `.claude-plugin/plugin.json` (PLUGIN) vs `SKILL.md` (SKILL) at the
  root. Reject if neither.
- GitHub pointer: fetch the published ref's archive (or shallow
  clone), apply the same probe.

Persist the detected kind on the `SkillName` row at first publish.
Subsequent versions of an existing name must match — refuse if a
later publish has a different kind (HTTP 409 with a clear message).

### API surface

```
shared/dto/SkillSummary.java  — add unitKind field
server-java/.../SkillIndexEntry.java — add unitKind field, persist to index.json
server-java/.../SkillRegistryController.java — surface in:
  GET /skills/{name}                     describe
  GET /skills                            list
  POST /skills/search (sponsored + organic) search
  POST /skills/register                  publish response (echo back)
  GET /skills/{name}/{version}/download  metadata header (X-Unit-Kind)
```

Client-side `SearchCommand` already extracts `unit_kind` from hits
(ticket 14) with a "skill" fallback for legacy responses — once the
server emits the field for every row, the fallback becomes a no-op.

### Tests

```
server-java/src/test/.../SkillPublishServiceTest.java
  - publish a plugin bundle → SkillName.unitKind == "plugin"
  - publish a skill bundle → SkillName.unitKind == "skill"
  - publish version 0.2.0 of an existing plugin name as a skill → 409

server-java/src/test/.../SkillRegistryControllerTest.java
  - GET /skills/<name> includes unit_kind in the response
  - GET /skills includes unit_kind on every summary
```

### test_graph

`plugin-smoke` becomes a real smoke target rather than a side graph.
Move its nodes into the default `smoke` graph (covered by ticket 17).

## Out of scope

- Multi-kind name rebinding (deferred — current model: name owns one
  kind for life).
- Plugin-specific search filters (`type=plugin`) — the field surfaces
  the data, callers can filter client-side for now.

## Acceptance

- Plugin publish + install round-trips end-to-end against the live
  registry server.
- `GET /skills/{name}` returns `unit_kind`.
- `plugin-smoke` test_graph folds into default smoke (handoff to
  ticket 17).
