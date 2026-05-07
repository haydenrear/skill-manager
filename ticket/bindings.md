# Bindings: split store-install from target-bind, with a projection ledger

## TL;DR

Today `skill-manager install <coord>` does two things at once: it fetches
a unit into the store **and** projects it onto a target (the configured
agent's fixed `skills/` or `plugins/` dir). That worked while every unit
had one obvious destination. It doesn't generalize: harness templates
materialize into sandbox roots, doc-repos drop `CLAUDE.md` into project
directories, and an agent or user may want the same unit projected to
several roots at once.

This ticket splits those two phases explicitly and introduces a
**projection ledger** — a persisted record of every symlink (or
renamed-original backup) any unit has produced, keyed by destination —
so uninstall can walk an exact footprint instead of guessing from
agent config.

After this ticket:

- `install` means "land in the store + write the lock." Kind-uniform.
- `bind` means "create a persisted Binding from a unit (or one of its
  sub-elements) to a target root, and materialize the resulting
  projections."
- Coord grammar gains a sub-element form, `<unit>/<source>`, so a
  doc-repo can expose multiple addressable entries.
- Existing skill / plugin behavior is preserved via an opt-in
  `--bind-default` on `install` (the current default) which creates an
  implicit Binding to the configured agent's dir.

This is a precursor: harness templates (#47) and DocUnit / doc-repos
(#48) sit on top of bindings and cannot be specified well without
them.

## Motivation

Three concrete situations the current single-verb model can't express
cleanly:

1. **Multi-root projection.** A harness instance bundles plugins +
   skills + doc files under one sandbox root. The same underlying
   units may also be bound to a different sandbox for an A/B eval. One
   `install` per root is wrong; the unit lives in the store once.
2. **Project-local prompt files.** A doc-repo containing several
   `CLAUDE.md` variants is fetched once and bound into N project
   directories, possibly to different filenames per agent. The
   destinations are arbitrary user paths, not agent-config dirs.
3. **Reliable uninstall.** Today, removal walks fixed agent dirs and
   removes things named after the unit. Anything projected outside
   those dirs (project-local doc, harness sandbox, custom path) leaks.
   A persisted ledger fixes this — uninstall knows exactly which
   destination paths the unit owns.

## Concepts

| Term | What it is |
| --- | --- |
| **Binding** | A persisted, named association `(unit, sub-element?, targetRoot, conflictPolicy)`. The unit-of-management for "this unit is projected to this place." Has a `bindingId`. Listed, shown, unbound. |
| **Projection** | A concrete filesystem action a Binding produces: `(sourcePath, destPath, kind, conflictAction)`. One Binding may produce multiple Projections (e.g. a plugin Binding produces a symlink for `plugins/<name>` plus, eventually, allowlist entries — anything that mutates the target root). |
| **Projection ledger** | Per-unit persisted list of Projections currently in effect, owning the data needed to undo each one (including the path to a renamed-original backup, if any). Lives at `$SKILL_MANAGER_HOME/installed/<name>.projections.json`. |
| **Sub-element** | A named entry inside a unit's manifest that can be the target of a Binding on its own (e.g. one `[[sources]]` row of a doc-repo). Resolver still resolves to the unit; the sub-element is a binding-time selector. |
| **Default Binding** | The implicit Binding `install` creates today (skill → `~/.claude/skills/<name>`). Made explicit in the ledger; `--bind-default` controls whether `install` creates it. |

## Data model

```java
public record Binding(
    String bindingId,                       // ulid; stable across operations
    String unitName,
    UnitKind unitKind,
    String subElement,                      // null for whole-unit bindings
    Path targetRoot,
    ConflictPolicy conflictPolicy,
    String createdAt,
    BindingSource source                    // EXPLICIT | DEFAULT_AGENT | HARNESS | PROFILE
) {}

public record Projection(
    String bindingId,                       // back-pointer
    Path sourcePath,                        // absolute, in the store
    Path destPath,                          // absolute, on target
    ProjectionKind kind,                    // SYMLINK | COPY | RENAMED_ORIGINAL_BACKUP
    String backupOf                         // when kind=RENAMED_ORIGINAL_BACKUP, original dest
) {}

public enum ConflictPolicy {
    ERROR,                  // refuse if dest exists
    RENAME_EXISTING,        // move dest to "<dest>.skill-manager-backup-<ts>", record it
    SKIP,                   // leave dest alone, log and continue (no projection emitted)
    OVERWRITE               // delete dest; only via explicit flag, never default
}
```

`installed/<name>.projections.json` is the per-unit ledger:

```json
{
  "unitName": "my-prompts",
  "bindings": [
    {
      "bindingId": "01HA...",
      "subElement": "review-stance",
      "targetRoot": "/Users/me/code/foo",
      "conflictPolicy": "RENAME_EXISTING",
      "projections": [
        {
          "sourcePath": "/Users/me/.skill-manager/docs/my-prompts/review/CLAUDE.md",
          "destPath": "/Users/me/code/foo/CLAUDE.md",
          "kind": "SYMLINK"
        },
        {
          "destPath": "/Users/me/code/foo/CLAUDE.md.skill-manager-backup-20260506T142233Z",
          "backupOf": "/Users/me/code/foo/CLAUDE.md",
          "kind": "RENAMED_ORIGINAL_BACKUP"
        }
      ]
    }
  ]
}
```

Two Projection rows per binding when a rename happens — the symlink
itself, plus the backup of what was there before. Unbind walks both:
remove the symlink, then move the backup back.

## Coord grammar extension

Sub-element addressing:

```
coord       := kinded | bare | direct | local | sub-element
sub-element := unit-coord "/" name
```

Examples:

```
doc:my-prompts/review-stance        # one source from a doc-repo
plugin:repo-intel/quick-mode        # hypothetical plugin sub-element
harness:reviewer/claude             # hypothetical harness slice
```

The resolver still resolves to a unit. The sub-element segment is
parsed into `Binding.subElement` and validated by the unit's parser
(it must match a manifest entry of the right shape, or
`bind` errors at plan time).

Whole-unit bindings continue to work — `bind plugin:repo-intel --to <root>` is
the no-sub-element form.

## CLI surface

```
skill-manager install <coord>                                 # store-only by default? see compat below
skill-manager install <coord> --bind-default                  # store + default Binding
skill-manager bind <coord> --to <root> [--id <name>] [--policy <policy>]
skill-manager unbind <bindingId>
skill-manager bindings list [--unit <name>] [--root <path>]
skill-manager bindings show <bindingId>
skill-manager rebind <bindingId> --to <newRoot>               # convenience: unbind + bind atomically
```

`bindings` is the listing verb (plural — it's a query over the
ledger). `bind` / `unbind` / `rebind` are the mutation verbs.

`--policy` defaults: `RENAME_EXISTING` for doc-repo sub-elements (the
common case is "I had a CLAUDE.md, please don't lose it"), `ERROR` for
whole-unit bindings (a stray dir at `<root>/plugins/<name>` should not
be silently shadowed). `OVERWRITE` is opt-in only.

## Backwards compatibility

The current implicit-projection behavior is preserved. The shift is
internal: it goes through a Binding now.

- `skill-manager install <coord>` continues to land the unit in the
  store **and** create a `DEFAULT_AGENT` Binding to the configured
  agent's dir, exactly as today.
- The `DEFAULT_AGENT` Binding is recorded in the ledger and visible in
  `bindings list`. `unbind` works on it like any other.
- `install --no-bind-default` (new) skips creating that Binding —
  store-only — for callers (harness, profile sync) that want explicit
  control.
- `uninstall <name>` continues to remove the unit and tear down all of
  its bindings. The difference: it walks the ledger, not the agent
  config, so non-default bindings get torn down too.

No flag day. Existing users see no change in behavior; the ledger
backfills on first reconcile (see Migration below).

## Plan / effects model

Two new effects with compensations, slotted into the existing
executor:

| Effect | Compensation |
| --- | --- |
| `CreateBinding(binding)` | `RemoveBinding(bindingId)` |
| `MaterializeProjection(projection)` | `UnmaterializeProjection(projection)` (reverses by `kind`) |

`MaterializeProjection`'s handler dispatches on `ProjectionKind`:

- `SYMLINK` → `Files.createSymbolicLink(destPath, sourcePath)` after
  applying conflict policy. The compensation unlinks the symlink and,
  if a `RENAMED_ORIGINAL_BACKUP` projection accompanies it, restores
  the backup.
- `COPY` → file copy. Compensation deletes the copy + restores backup.
  Reserved for cases where symlinks are unavailable (e.g. some
  Windows filesystems); not the default path.
- `RENAMED_ORIGINAL_BACKUP` → emitted by the planner alongside a
  primary projection when the conflict policy is `RENAME_EXISTING` and
  `destPath` is occupied. The backup move is its own effect with its
  own compensation so the journal can roll it back independently.

The planner emits, for `bind`:

```
[ResolveUnit]                    # already exists, kind-agnostic
[CreateBinding]
[MaterializeProjection ×N]       # N = 1 for whole-unit symlink, more for sub-elements + backups
[WriteLedgerEntry]
```

For `unbind`:

```
[ReadLedgerEntry]
[UnmaterializeProjection ×N]     # in reverse order; backups restored last
[RemoveBinding]
[WriteLedgerEntry]               # ledger row deleted
```

Atomicity is the existing journal's job. A failed `bind` rolls every
projection back, including any rename-original move.

## Conflict handling, in detail

The `RENAME_EXISTING` flow is the one that matters for project-local
prompt files. Step by step:

1. Planner sees `destPath = /Users/me/code/foo/CLAUDE.md` exists.
2. Emits two projections: a `RENAMED_ORIGINAL_BACKUP` moving the
   existing file to `<destPath>.skill-manager-backup-<ts>`, then the
   `SYMLINK` projection pointing `<destPath>` at the store.
3. Both rows land in the ledger; the backup row carries `backupOf =
   <destPath>` so unbind can find it.
4. On `unbind`, the executor undoes the symlink first, then moves the
   backup back to `destPath`.

If the user has *also* edited the symlink target (i.e. they edited the
real file in the store, which is the whole point of symlinks), unbind
warns: their edits live on the source side and are about to become
unreferenced. Recommended workflow there is `unbind --keep-content`
which copies the current file content back to `destPath` instead of
restoring the backup, so the user keeps their edits.

## Bindings, harnesses, doc-repos — how they stack

Once bindings exist, the higher-level features collapse into them.

**Harness instance** (#47): a Harness Binding with `targetRoot =
<sandbox>` whose unit is the harness template. The
`MaterializeHarness` effect from #47 becomes a planner-time expansion
that emits one `CreateBinding` per referenced unit (each pointing at
the appropriate subdir of the sandbox) plus one `BindGatewayAllowlist`.
`harness instantiate` is just `bind harness:foo --to <sandbox>`.

**Doc-repo source** (#48): a doc-repo unit with multiple
`[[sources]]` entries. `bind doc:my-prompts/review-stance --to ~/code/foo`
is a sub-element Binding; the projection is a single symlink whose
filename comes from the source's declared `agent` (`CLAUDE.md` for
`agent = "claude"`).

**Skill / plugin** (today): the existing default behavior is now an
explicit `DEFAULT_AGENT` Binding. Nothing user-visible changes; the
machinery underneath is unified.

## Migration

On first run after upgrade, the reconciler walks installed units and
materializes their existing implicit projections into the ledger:

1. For each unit in `installed/<name>.json`, read the configured
   agents.
2. For each agent, locate the existing symlink (`~/.claude/skills/<name>`
   etc.). If present, write a `Binding` with `source = DEFAULT_AGENT`
   and a `Projection` describing the symlink. No filesystem changes.
3. If a symlink is missing where the install record says one should
   exist, log a warning — the user will need to re-bind. Don't try to
   recreate.

Idempotent: the reconciler can be re-run safely; it skips bindings
already in the ledger.

## `units.lock.toml` and bindings

Bindings are per-machine state by design. They are *not* recorded in
`units.lock.toml`, which is reproducibility-of-store-contents. A
project that vendors a lock and a profile that lists harnesses /
docs-to-bind together describe a reproducible workspace; bindings are
the local materialization of those declarations.

`bindings list` includes a `MANAGED-BY` column to make this clear:

```
ID          UNIT             SUB-ELEMENT       TARGET                            POLICY            MANAGED-BY
01HA...     hello-skill                        ~/.claude/skills/hello-skill      ERROR             default-agent
01HB...     my-prompts       review-stance     ~/code/foo/CLAUDE.md              rename-existing   user
01HC...     reviewer-harness                   ~/sandbox/reviewer                error             harness:reviewer
```

`MANAGED-BY` distinguishes bindings the user created from those
created by a containing operation (a harness instantiation, a profile
sync). User-managed bindings are stable across `harness rm` /
`profile sync`; managed bindings are torn down with their owner.

## Risks

- **Symlink fan-out.** A unit bound into many roots produces many
  symlinks. Acceptable — symlinks are cheap and the ledger gives us
  exact tracking.
- **Backup pollution.** `RENAME_EXISTING` accumulates backup files if
  users repeatedly bind/unbind. Mitigation: `bindings show <id>` shows
  the backup; `unbind` restores by default; explicit
  `bindings cleanup-backups <root>` removes orphans.
- **Sub-element parser drift.** A doc-repo author renames a source;
  existing bindings now point at a missing sub-element. Detect at
  upgrade time, surface in `bindings list` with `STATE = stale`,
  refuse to re-materialize until rebound.
- **Cross-machine bindings.** Bindings reference absolute target
  paths. A vendored ledger across machines is meaningless. Don't
  vendor it; profile-driven bindings re-derive paths per machine.

## Out of scope (this ticket)

- **Harness instantiate plumbing** — that's #47, sitting on top.
- **DocUnit / doc-repo manifest format** — that's #48; this ticket
  only specifies the *coord syntax* for sub-elements and the binding
  contract. The manifest schema lands with #48.
- **Profile-driven binding sync** — profile work owns the "bind these
  on every machine" behavior. This ticket gives it the bind primitive
  to call.
- **Bind to a remote target.** Bindings are local FS only.

## Implementation order

1. **Data model.** `Binding`, `Projection`, `ConflictPolicy`,
   `ProjectionKind`. Per-unit ledger file
   (`installed/<name>.projections.json`) + read/write.
2. **Coord grammar.** Sub-element parsing in the existing `Coord`
   parser. Validation hook callable per unit kind.
3. **Effects.** `CreateBinding`, `RemoveBinding`,
   `MaterializeProjection`, `UnmaterializeProjection` with
   compensations. Slot into the existing journal.
4. **`bind` / `unbind` / `bindings` / `rebind` CLI verbs.**
5. **Compat: `install --bind-default`.** Default Binding flow
   delegates to the new effect path. `install --no-bind-default` for
   store-only.
6. **`uninstall` walks the ledger.** Replace the agent-config-walking
   teardown with a ledger walk.
7. **Migration reconciler.** Backfill existing implicit projections
   into the ledger on first run.
8. **Tests.**
   - Layer 1 (test_graph): bind / unbind / rebind happy paths;
     `RENAME_EXISTING` round-trip; `unbind --keep-content`; multi-root
     binding; `uninstall` walks all bindings.
   - Layer 2 (unit): full sweep over conflict policies × pre-state ×
     failure injection; ledger atomicity contract; sub-element
     resolution; idempotence of migration reconciler.

## Why this ticket has to land first

#47 (harness templates) and #48 (doc-repos) both want to talk about
"this unit is projected to a target root other than the agent's fixed
dir." Without bindings, each of those tickets has to invent its own
ledger, its own conflict handling, and its own uninstall walk — and
the two implementations would diverge. With bindings, both reduce to
"emit the right Binding(s) and let the executor do its thing." The
abstraction pays for itself the first time it's reused.
