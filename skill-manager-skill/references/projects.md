---
skill-imports:
  - unit: skt
    path: references/coords-and-distribution.md
    reason: Project-manifest unit refs use the same coord grammar as skill_references; the git-coord-only rule (no registry configured) is stated once there.
    section: coord-forms
---

# Skill Projects

Use this reference when a repository contains `skill-project.toml` or
`skill-manager-project.toml`, or when the user asks for a project-specific
agent harness.

## Mental Model

A skill project manifest is portable intent for one repository. It can
declare skills, plugins, doc-repos, harnesses, envs, libs, CLI deps, and
MCP deps. The generated files under the checkout are realized state, not
the source of truth.

Unit references in the manifest (skills, plugins, doc-repos, harnesses)
are resolved transitively at `project resolve` time, exactly like
`skill_references`. Use **git coords only** (`github:owner/repo`,
`git+…`, `file:…`); registry-name coords such as `skill:name` cannot
resolve because no registry is configured, and the coord names the repo,
not the installed unit — find it with `gh repo list <owner>`. The rule
and rationale live once in the skt plugin's
`references/coords-and-distribution.md` (imported above); do not restate
it here.

Project resolution also treats the checkout as a harness descriptor:

- The parent `$SKILL_MANAGER_HOME` records the registered project and
  project lock under `projects/<name>/`.
- `<project>/.skill-manager` is scaffolded as a child Skill Manager home.
- `<project>/.claude`, `<project>/.codex`, and `<project>/.gemini` are
  scaffolded as child-local agent homes.
- Resolved units are projected from the parent store into the child
  `.skill-manager` store.
- Parent child-home records claim the units while the project child home
  exists, so removals stay conservative.

Any Skill Manager home can itself be a parent. Do not assume there is a
distinguished global root.

## Homes Come In Tiers, And Every Tier Is A Copy

In practice a machine holds up to three:

```
root       ~/.skill-manager              where the operator installs
   |  copy  (project resolve, home clone)
project    <repo>/.skill-manager         one per repository, gitignored
   |  copy  (home clone)
worktree   <worktree>/.skill-manager     one per ticket, gitignored
```

Each tier is a **real copy, not a symlink**, and this is a deliberate design
decision rather than an implementation detail. A symlink farm makes the child and
the parent the same bytes; then "the agent's edit survived" and "the parent store
was not touched" become the same proposition, so neither can be violated and
neither can be checked. Copies make those two facts independent — which is what
lets two agents in two worktrees work at once without silently overwriting each
other's units.

Downward is easy: it is a copy, and `project resolve` / `home clone` do it.
**Upward is the whole difficulty**, and it is what the rest of this page is about.

The rule the mechanism enforces, and the one to reason from when a report
surprises you:

> A reconciliation may destroy bytes in the destination only where it can show
> the **source** passed through them.

Two consequences worth holding on to:

- **A materialization record is evidence about two different things.** Its
  content digest says "these are the bytes we wrote *here*" — about the
  destination alone. Its source/baseline fields say "this destination and *that*
  source last shared exactly this" — about a **pair** of homes. A record saying
  "I and the worktree last shared X" says nothing about whether the root home
  ever held X.
- **When unsure, conflict.** A merge base that is too old costs a spurious
  conflict a human resolves; one that is too new costs an edit nobody sees again.
  So `home sync` defaults to *hold back and report*, `--merge` reports conflicts
  rather than resolving them, and a conflicted unit writes **nothing**.

### Concurrency: what actually excludes what

Several worktrees can reconcile into one project home at the same time, so the
unit of exclusion is the **home**, not the unit: per-unit staging makes one
unit's swap atomic, but two whole-home syncs interleaved leave a destination
coherent per unit and incoherent as a home.

If you are writing code that needs that exclusion, the primitive is `HomeLock` —
a process-wide `ReentrantLock` keyed by the home path **plus** a `FileLock`. Both
are needed: a `FileLock` is held by the JVM rather than by the thread, so two
threads in one process do not exclude each other (the second gets
`OverlappingFileLockException` instead of waiting).

**`HarnessInstanceLock` is not a locking primitive.** Neither are `CliLock`,
`UnitsLock` or `SkillProjectLock`. They are lock *files* in the manifest sense —
durable JSON/TOML records of what is installed — with no exclusion semantics
whatsoever. Code that "reuses the existing lock" by reaching for one of those
gets no exclusion and no error.

And exclusion is not merging. Serialized syncs cannot corrupt a home, but the
second one is **held back and reported**: a result to read, not a step that
succeeded.

## Discovery

From a checkout, start with:

```bash
skill-manager project show <name>
skill-manager project list
<skill-manager-skill>/scripts/env.sh --pretty
```

`env.sh` reports the active `SKILL_MANAGER_HOME`, installed skills and
CLI shims, and, when run inside a skill project, passive project context:
manifest path, declared env names, child home path, and child-local agent
homes that exist.

## Register And Resolve

Use registration when you want the parent home to remember the manifest
intent:

```bash
skill-manager project register --project-dir <project>
```

Use resolve when dependencies should be installed, locked, bound, and
projected into the project child home:

```bash
skill-manager project resolve --project-dir <project>
```

**Order matters, and getting it wrong writes the operator's home.** `install`,
`sync`, `bind`, `upgrade` and `project resolve` all write into whatever
`SKILL_MANAGER_HOME` names, and `project resolve` additionally puts a child-home
record and a projection ledger into that store. Before a local home exists, that
store is the operator's global `~/.skill-manager`. So: create the local home
first, point `SKILL_MANAGER_HOME` at it, and only then resolve — never the other
way round.

After resolve, project-local agent launches should point at the child
home and child-local agent homes:

```bash
SKILL_MANAGER_HOME=<project>/.skill-manager
CODEX_HOME=<project>/.codex
CLAUDE_HOME=<project>/.claude
GEMINI_HOME=<project>/.gemini
```

Use CLI help for exact flags such as JSON output, gateway skipping, lib
resolution, and custom manifest paths.

## Vendored Paths: `[[vendored]]`

A vendored path is the one thing a project checkout carries that the checkout
cannot describe by itself: a file or directory inside the working tree whose
*content* belongs to an installed unit, and which is therefore expected to be a
link into this project's own home. `test_graph/sdk` is the canonical case — a
tracked symlink, so git stores the target **bytes**, which means whatever
`SKILL_MANAGER_HOME` happened to be when the scaffolder ran is what every later
clone of that repository gets.

Nothing in the manifest said those paths were supposed to point anywhere in
particular, so nothing could say they were wrong. This block is that statement:

```toml
[[vendored]]
name = "test-graph-sdk"
paths = ["test_graph/sdk", "test_graph/build-logic", "test_graph/standard-nodes"]
from_unit = "test-graph"        # installed unit name; content comes from here
from_subpath = "project_sdk_sources"   # optional; omit when the unit root is the source
on_invalid = "error"            # error (default) | warn
```

An array of tables, like `[[libs]]`. `paths` are project-root-relative, must not
be absolute, and must not escape the project root — a path that escapes is the
very defect the block exists to catch, so it cannot be spelled in the
declaration. `name` identifies the group in diagnostics; it is not a filesystem
name. `paths` is per-declaration because the sets genuinely differ between
repositories.

Two things about how it is checked:

- **Validation compares resolved physical paths, not link text.** A link whose
  text is relative (`standard-nodes -> sdk/../standard-nodes`) matches no string
  test yet resolves *through an absolute sibling link* straight into the
  operator's home. Any check that reads link text misses that shape entirely.
- **Validation always runs; only the writing is opt-in.** `project resolve`
  reports findings by default. `project resolve --repair-vendored` re-points the
  declared paths at this project's own `.skill-manager` — off by default because a
  vendored path may be a tracked symlink, so repairing one edits your working
  tree. `project sync` takes the same flag.

`on_invalid = "error"` fails the command; the default is deliberate — a declared
contract that cannot fail is a comment. Use `"warn"` only for a project
mid-migration.

## Moving An Edit Up A Tier: `home sync`

An agent that improves a unit does it *inside a home*. The home is gitignored, so
that edit is in no diff, no PR, and no fan-out, and it is deleted with the
directory. `home sync` reconciles one home against another by copy:

```bash
skill-manager home sync --from <worktree>/.skill-manager \
                        --to <repo>/.skill-manager [--merge] [--dry-run] [--json]
```

- `--from` is **never written**. `--to` is written unless `--dry-run`.
- Without `--merge`, a destination unit that has been edited is **held back and
  reported**, not overwritten. That is the default because holding back costs a
  conflict and overwriting costs an edit nobody sees again.
- `--merge` three-way merges such a unit against its recorded per-file baseline.
  Conflicts are **reported, never resolved**; local work is kept either way and a
  conflicted unit writes nothing.
- `--dry-run` computes and prints the whole report and writes nothing at all — no
  records, no lock file. Use it to ask the question without answering it.

`home sync` is local to this machine and moves an edit exactly one tier. It does
not reach any other project.

## Publishing An Edit: `unit publish`

```bash
skill-manager unit publish <name> [--ticket T] [--home H] [--child-home DIR] \
                                  [--base main] [--remote origin] \
                                  [--direct] [--no-pr] [--dry-run] [--json]
```

This commits the home's edits to that unit on `skill/<ticket>-<unit>`, pushes, and
opens a pull request against the unit's trunk. `--ticket` defaults to
`$SKILL_MANAGER_TICKET`; `--child-home` is for a unit materialized into a project
child home as its own checkout (see `project sync --checkout UNIT`); `--direct`
pushes straight to the base with no review; `--no-pr` pushes the branch only.

**`home sync` and `unit publish` are not alternatives.** They answer different
questions, and both are needed:

| | `home sync` | `unit publish` |
|---|---|---|
| Moves | home → home, one tier up | home → the unit's own git repo |
| Reaches | the tier above, on this machine | every project, on every machine |
| Answers | "will closing this worktree destroy the edit?" | "will anyone else ever get this improvement?" |

A chain-only design would need the same improvement merged up twice and would
still never reach a **sibling** project. If you improved a unit, `unit publish`
is the one you owe.

### What `unit publish` can publish, and what to do when it cannot

`unit publish` publishes a unit **this home installed**. It has no other source
for the bytes: it commits `<home>/skills/<name>/` or `<home>/plugins/<name>/`,
which exists only because that home installed the unit. And a child home holds
exactly what its parent held at clone time, so a worktree home cloned from a
project home that never installed `skt` cannot publish `skt`.

**The refusal does not say that.** Measured on a worktree home, 2026-08-24:

```
$ skill-manager unit publish skt --dry-run --ticket 247-his-20
✗ skt: not a git checkout at <home>/skills/skt — reinstall the unit from a
  git source, or materialize it into the child home with --checkout, to
  publish from it
$ echo $?
1
```

A name that is not a unit at all produces the **same** message with the name
substituted, so "not installed in this home" and "you typed it wrong" are
indistinguishable from the output; and `--checkout`, the remedy it names, is
not a flag `unit publish` accepts. Run `skill-manager list` before believing
either reading.

Two remedies, not interchangeable:

- The unit *should* be here — declare it in the checkout's `skill-project.toml`
  and run `skill-manager project resolve` against **this** home. That is the fix
  when a home is simply missing a unit its own manifest already declares.
- The unit should not be here, or the home must not change — publish by hand
  exactly as `unit publish` would: clone the unit's repository, branch
  `skill/<ticket>-<unit>` off its trunk, commit, push, open a pull request
  against the trunk. Same branch name, same base, same PR shape, so the result
  is indistinguishable downstream from the command's own.

### Finding a unit's repository: no command prints it

The unit name is not the repository name. `skill-manager list` shows `SOURCE
git` and a short SHA; `skill-manager show <unit> --json` carries `source`,
`sha` and `path` but **no** `origin`.

**Read `<home>/installed/<unit>.json`.** Its `origin` field is the reliable
answer, alongside `gitRef` and the `gitHash` the store is actually at.
`<home>/units.lock.toml` carries `origin` too — but not always. Measured
2026-08-24: the operator's root home had it for 29 of 29 units, and a worktree
home had it for 4 of 6, the two without it being exactly the units `project
resolve` had installed. Prefer the per-unit record; treat a lock entry with no
`origin` as a gap in the lock, not as a unit with no repository.

Worked examples of the mismatch, **including this unit's own**: `skill-manager`
is published from `skill-manager-skill`; `spec-double-compiler` from
`tla-spec-dev`; `test-graph` from `test_graph_skill`; `deploy-helm` from
`deploy-cdc`; the `skt` plugin from `skill-publisher-skill`. That list is
**illustration, not a registry** — which units a home holds differs per home, so
enumerating one home's inventory here would be a copy that goes stale the first
time a home differs. The lookup above is the answer; these are only enough
examples to show that guessing from the name does not work.

For a **plugin**, the unit is the plugin, so `unit publish` lands on the
plugin's repository and not on the repository of a skill contained in it.

### At the worktree tier, the two legs of `skt publish` are decided separately

`skt publish` is `home sync` one tier up, then `unit publish`. From a worktree
home the first leg writes the **project** home. A ticket brief that forbids a
ticket agent from writing the project home — the normal rule inside an epic,
because that home is one shared destination several tickets would race for —
forbids that leg, and running `skt publish` anyway violates it before the
publish leg is reached.

The two legs answer different questions (the table above), so this is not a
blocked path, it is a split one: run `skill-manager unit publish` on its own,
and say in the pull request that the edit has **not** been reconciled into the
project home and who owns doing it. Reporting it is the part that cannot be
skipped — an unreconciled worktree home is exactly what `home close-out`
refuses over, and whoever runs the teardown needs to know the answer is
"published, deliberately not synced" rather than "forgotten".

## Discarding A Child Home: `home close-out`

Removing a worktree deletes its home without asking, and succeeds exactly as
quietly whether the home held a week of work or nothing. Ask first:

```bash
skill-manager home close-out --home <worktree>/.skill-manager \
                             --into <repo>/.skill-manager [--json]
```

- `--home` is the home about to be removed; `--into` is the **project home its
  work has to reach first** — the one it was cloned from, not `~/.skill-manager`.
  Get the pair wrong and the verdict is about the wrong two homes.
- It **writes nothing** and is safe to run repeatedly.
- **Exit 0** means it has established there is nothing to lose.
- **Exit 1** is the blocked verdict, and the only exit that prints blockers: every
  blocking unit, its status, and a **literal remedy command** per unit —
  `home sync` for a fast-forward, `home sync --merge` for a merge or a conflict
  (with the conflicted files listed), `unit publish` for a git checkout carrying
  unpushed work that a file copy cannot carry.
- **Exit 2** (`NotAHomeException`) means the path presented as a home is not one.
  Nothing was assessed and nothing is printed. This exit exists because the gate
  used to answer `safe: true` for a `--home` that was the worktree **directory**
  rather than its `.skill-manager` — and that directory is exactly what
  `git worktree remove` takes.
- **Exit 9** (`FrozenHomeException`) means the destination home's policy is
  `frozen`, so the gate — a dry-run sync into it — was refused and **nothing was
  attempted**. Branch on this separately in any teardown script: `9` ("refused,
  nothing attempted") is not `1` ("this worktree still holds work").
- A `LINKED` unit **blocks**. The gate cannot say whose bytes a symlink's target
  is — it may point inside the worktree or outside it — and "cannot tell" has to
  block rather than clear. Resolve the link, then re-run.
- A **git-backed** unit (a store copy carrying its own `.git`) is judged by git
  before any record: a worktree home whose working tree is clean and whose every
  ref the project home already reaches holds nothing — including a home that is
  merely *behind* because the project home pulled a newer upstream since the
  worktree was cloned. **For a unit both homes hold**, `.git` is one thing in the
  verdict rather than a list of index and reflog files; a history that neither
  side contains is reported as the single conflict entry `.git (history)`, and
  its detail names the fix — bring
  the project home up to date (`skill-manager sync <unit>` there) when the
  worktree only pulled further, `unit publish` when the worktree committed
  something of its own.
- **That collapsing applies to the comparison path only.** A unit the
  destination does not hold at all comes back `status: new`, and its `files[]`
  is a raw walk — `.git`, `.git/index`, `.git/packed-refs`,
  `.git/logs/refs/heads/main` and eighteen `.git/hooks/*.sample` are each listed
  individually. Measured 2026-08-24 on a worktree home whose parent lacked two
  units. So do not read a `.git`-heavy file list as evidence that something
  happened to git's bookkeeping; read the `status` first.
- `--json` gives `.blockers[]` with `unit`, `status`, `conflicts[]` and `remedy`.

There is **no `--force` on this command**, deliberately: the CLI owns the verdict.
The override lives in the caller —
`git-integration-repo`'s `close-change.sh <ticket> --force` still runs the gate and
still prints every blocker, and only declines to stop. A named, loud override is
safer than the `rm -rf` an operator improvises when a gate has no escape hatch.

`close-change.sh` runs this gate before `git worktree remove` and refuses (exit 4)
on a non-zero verdict, which is what makes "push back before teardown" a mechanism
rather than a discipline.

## Project Envs

Project envs are declared in the manifest and materialized under
`.skill-manager/envs/<env>/` as uv projects. Use:

```bash
skill-manager env sync <env> --project-dir <project>
skill-manager env run <env> --project-dir <project> -- <command>
```

Generated `.skill-manager/env.md`, env `pyproject.toml`, vendor
checkouts, and tool shims are derived from the manifest and lock. Update
the manifest, then re-run the CLI instead of editing generated env files
by hand.

## The Rest Of The `home` Family

Run `skill-manager home <sub> --help` for flags; this is the routing map.

| Subcommand | Use when |
| --- | --- |
| `clone --to` | Make a new home from an existing one, skipping re-derivable `cache/`, and verify nothing in the copy still points at the original. This — not `project resolve` — is how a checkout an agent works in gets its home. |
| `verify --home --against` | Assert a home holds no absolute reference back to another home. |
| `policy [live\|frozen]` | Declare a home mutable, or evidence. A **frozen** home refuses sync, upgrade and push-back, and is never modified in place. |
| `describe [--json] [--write]` | The `home.runtime.json` interop descriptor: the env to export, the resolved CLI, the gateway, the installed-unit snapshot, the policy. |
| `shims` | Generate `bin/launch/{claude,codex,gemini}`. Launch through these rather than exporting variables: skills also load from the Claude config dir, and `skill-script` CLI deps are generated shell scripts with a home's absolute path in the body that no variable redirects. |
| `drift [--record] [--ack]` | Show/record/acknowledge unit changes. A launch refuses while a change is unacknowledged, so an agent cannot keep acting on a skill that moved underneath it. |

`describe`, `policy`, `shims` and `drift` take `--init` to lay out a home at a path
that is not one yet. Without `--init` they refuse — a mistyped path should not get a
home scaffolded at it.

A cloned home and a `project resolve` child home both want the path
`<project>/.skill-manager`, and they are not the same thing. Use the clone for a
checkout an agent works in; use `project resolve` when the parent home lives
somewhere else.

Resolving a checkout against that checkout's own home is the **per-checkout
layout**, not an error: units resolve in place instead of being copied into a
separate home, and the child-home record names this home as its own parent.
`project resolve` says so and proceeds. `--allow-same-home` is still accepted
and is no longer consulted; a page telling you to pass it is out of date.

### Launching an agent into a home: config is isolated, the login is shared

`bin/launch/claude` points the agent's `CLAUDE_CONFIG_DIR` at the home's own
`.claude` — skills, settings, projects, MCP servers, all of it. It also exports

```
CLAUDE_SECURESTORAGE_CONFIG_DIR=
```

an **empty value**, which is what tells the Claude CLI to read credentials from
the slot the operator's own logins already use. Without it, redirecting
`CLAUDE_CONFIG_DIR` also silently renames the credential slot — the keychain
service name on macOS, the credentials file elsewhere — and the agent starts,
prints `Not logged in · Please run /login`, and exits 0 having done nothing.

**The empty value is deliberate and must not be "fixed" to a path.** Only an
empty value selects the unsuffixed slot. Naming the operator's config directory
explicitly hashes that path into the slot name and asks for one that has never
been written, which fails exactly as an unset variable does.

So a launched agent is isolated in everything it *reads and writes* and shares
one answer to *who is logged in*. That is the intended trade: skill-manager
models no per-home login, and a per-home credential slot would mean an
interactive `/login` per worktree, which the `-p` path cannot perform. A token
refresh performed by a launched agent updates the shared slot, the same way two
ordinary sessions share it.

To give one home its own credentials instead — a service account, a different
subscription — override the lowest-precedence default and log in once inside it:

```bash
skill-manager home describe --home <store> --write \
  --set-env CLAUDE_SECURESTORAGE_CONFIG_DIR=<homeRoot>/.claude
```

Nothing else changes; the config directory already pointed there.

#### Never change the credential path of a session that is running

Not the shell it was started from, not the settings files it reads. Measured on
this repository (#263): an `apiKeyHelper` added to the project home-root's
`.claude/settings.json` took effect in the session **already running in that
checkout**. It lost its credentials mid-turn — and could not undo the edit,
because undoing it needed an API call, which needed the credentials it had just
removed. There is no recovery from inside; the operator has to fix the file
from another process.

The rule that follows: credential wiring is a property of a launch, not of a
live session. Set it in the environment `exec` hands a *new* child, never in a
file a running agent re-reads. That is why this lives in the launch env and not
in `settings.json`, and why the opt-out above is written to a descriptor that is
read at launch.

### One symptom worth recognising: a `skill-manager` that refuses to run

```
skill-manager: refusing to run against a home you did not name.
```

Exit **79**. The pin at `<home>/bin/cli/skill-manager` binds *that* home; when
`SKILL_MANAGER_HOME` names a different one, the pin refuses rather than
silently rebinding to its own. So 79 is not a broken install — it is the CLI
you invoked and the `SKILL_MANAGER_HOME` you exported disagreeing about which
home the command is for, said out loud instead of resolved by guess.

Name the CLI you meant: export `SKILL_MANAGER_CLI` at a real launcher, or
invoke the pin belonging to the home you are actually targeting. Never point
`SKILL_MANAGER_CLI` at a home's `bin/cli/skill-manager` shim — the shim
expands that same variable, so it re-execs itself forever. It surfaces most
often through a wrapper script that resolves its CLI off `PATH` and then runs
it under a `SKILL_MANAGER_HOME` you set, which is how the same command can
have worked yesterday and refuse today.

## Migrating A Home That Runs Another Home's Copy

A home can hold its own copy of a unit and still run the one above it. The
symptom is the one that wastes an afternoon: you edit a skill in the home you
are working in, nothing changes, and every check says the home is fine.

The cause is the entry point, not the skill. `<home>/skills/<unit>/` was
cloned correctly; `<home>/bin/cli/<tool>` is a **symlink into the parent** that
the clone copied along with everything else. The local copy sits unused.

### Find out

```bash
skill-manager home repair --home <home>
```

A home with the shape reports `PARENT_SHIM_SHADOWS_LOCAL_COPY`, naming the
entry, what it runs, and the copy it is bypassing. `home verify` will **not**
tell you: every path resolves, so a home in this state is "valid".

### Fix it

```bash
skill-manager home repair --home <home> --fix
```

That writes this home's own shim — the parent's command line with each path
remapped into this home — and only where the home already holds every path
that entry runs. The tool keeps working across the change; it just runs the
local copy afterwards.

### When it refuses, that is the right answer

Some mirrors **must** stay. `deploy-helm`'s entries exec a per-unit cache venv:

```
exec <home>/cache/skill-script-deploy-helm-computeq/venv/bin/computeq
```

and `home clone` deliberately does not copy `cache/`, `venvs/`, `tools/` or
`npm/`. So a cloned home holds `skills/deploy-helm` and not the venv that entry
runs, and the mirror into the parent is **the only way that tool runs at all**.
Rewriting it would name a venv that is not there and turn a working tool into
one that resolves nowhere. `home repair` does not raise those, and would refuse
the write if it did.

Provision the tool in the home first if you want it local; until then the
mirror is correct.

### New homes do not need this

Homes created by `home clone` from **0.25.2** onward get their own shims
wherever they can run them, so the shape is not inherited any more. Migration
is a one-time pass over homes that already exist, and it is **per project**:
each repository migrates its own homes and its own worktrees. Nothing fans out
across the machine, and a home belonging to another project is that project's
to fix.


## Cleanup

Use the owning CLI flow to remove generated state:

- `skill-manager project remove <name>` or
  `skill-manager project remove --project-dir <project>` for project
  registrations, child-home claims, generated child-store state, and
  project-managed bindings.
- `skill-manager harness rm <id>` for harness child homes.
- Re-run `skill-manager project resolve` after removing project
  dependencies from the manifest so stale child units and claims are
  pruned.
- Use `skill-manager bindings`, `unbind`, and `rebind` for doc bindings
  instead of deleting managed import blocks manually.

If `remove` says a unit is still claimed by a project or child home,
inspect the project lock and child-home record before deleting anything.
