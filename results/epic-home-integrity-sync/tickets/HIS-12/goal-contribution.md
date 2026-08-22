# HIS-12 — goal contribution

Issue #161 (also closes #187), joined to DEF-002 from this epic's kickoff and to
the bounded half of DEF-012. Branch `feature/161-his-12`, rebased onto epic tip
`a4a95cb`.

Goal: **GOAL-one-home-one-answer**, contribution `direct`.
Declared expected effect: *"the remedy surface joins the readers that agree: a
printed command names the home the operator was in."*

> **Rewritten after adversarial review of #229.** My first shape bound a remedy
> by putting `env SKILL_MANAGER_HOME=<X>` in front of the head token. It was
> measured and it lost: it took the CORE `home-sync` graph red, and I had not
> run that graph. The contract that survived is below; **the shape that lost is
> written out under "The claim I got wrong"** rather than quietly replaced.

## What this delivers

**A remedy is an instruction, and these ones now name the home you were in, in a
token nothing strips, run a build that can run them, and say so when they are
guessing.**

Four faces of one surface:

1. **#161** — `resolveCli` fell through to a raw `PATH` walk with no version
   check. #142 had made the printed remedy *absolute*, which made it **read**
   authoritative while leaving it exactly as wrong: that absolute path is the
   same binary the bare token resolved to. And step 2 of the documented
   precedence — "the running process's own command" — was **dead in every
   shipped launcher**, because every distribution `exec`s a JVM and the process
   basename is `java` or `jbang`. The javadoc stated a precedence the code did
   not have.
2. **DEF-002** — the same defect landing on the operator: a sync run against the
   **project** home printed a remedy naming the **root** home's pinned
   entrypoint.
3. **#187** — the same surface losing its type: `project sync` had no catch for
   `ProjectImportViolationException`.
4. **DEF-012, resolution half (bounded)** — a home entrypoint whose pinned build
   an upgrade deleted is still an *executable file*, so every reader that tested
   `-x` on it called that home's front door healthy.

## The contract: bind per VERB, not per CLI token

`needsHomeBinding` is gone from `CliSource`. Binding is a property of **what the
remedy asks the build to do**, in three classes:

| class | example | binds |
| --- | --- | --- |
| 1 — names its own target | `home sync --from <a> --to <b>` | **nothing** |
| 2 — takes `--home` | `home drift`, `home shims`, `unit publish`, `exec`, `home close-out` | `--home <X>` |
| 3 — had no `--home` | `sync <unit> --merge`, `project sync` | given `--home`, then `--home <X>` |

Each class-2 verb was **probed**, not assumed: there is no global `--home`.

**Why the binding cannot live in the head token.** Every consumer feels entitled
to replace it. `home-sync`'s `remedyArgs` documents itself as dropping token 0;
`close-change.sh`'s `run_cli` does the same. A binding carried there is silently
deleted by all of them — and because that strip is guarded by
`endsWith("skill-manager")`, an `env` prefix was not deleted but passed through
as **arguments**.

## The claim I got wrong

I argued the `/usr/bin/env` prefix *defended* the graph assertions that require
a remedy's head token to be absolute and executable. It **neutralizes** them:
`/usr/bin/env` is absolute and executable forever, whatever the resolution
behind it does, so three readers go permanently green. A bare `env` would have
turned them red and visible. **Of the two options, I chose the one that silences
the check.** With the head token a real per-home executable again, those readers
assert something.

I also had to drop the first replacement I reached for — printing
`<X>/bin/cli/skill-manager` as the head token — because **not every home has that
file**. Only `home shims` writes it; `sync` does not, and `home clone` copies
only what the source had. `home-sync`'s own project and worktree fixtures have an
empty `bin/cli`. The premise was false.

## Before → after, as measured output

### DEF-002 / #161 — the remedy a refused sync prints

Reproduced from scratch on the epic tip and re-measured on this branch, with the
same script (`probes/his-12/def002-repro.sh`): a project home with **no**
`bin/cli` of its own, a root home whose `bin/cli` is on `PATH`,
`SKILL_MANAGER_HOME` pointing at the project home, `SKILL_MANAGER_CLI` unset.

**Before** (epic tip):

```
✗   re-run with: <scratch>/root/.skill-manager/bin/cli/skill-manager sync probe-unit --merge
```

**After** (this branch):

```
✗   re-run with: <the running build> sync probe-unit --merge --home <scratch>/project/.skill-manager
```

The "before" line is not merely imprecise. `<root>/bin/cli/skill-manager`
exports `SKILL_MANAGER_HOME` from its **own** location and lets that win, so
following the printed instruction verbatim edits the root home — and nothing in
the transcript says so.

### #187 — `project sync`'s refusal

| | before | after |
| --- | --- | --- |
| `project resolve` exit | 11 | 11 |
| `project sync` exit | **1** | **11** |
| `project sync --json` exit | **1** | **11** |
| stderr | `dev.skillmanager.project.ProjectImportViolationException: project resolve refused: …` | `project sync refused — declared closure has 1 unresolved markdown skill-import(s); nothing was installed:` + per-violation lines |
| `--json` body | *(none — the exception escaped before any JSON was written)* | byte-identical to `resolve`'s |

Asserted as an **equality between the two commands' own output**, over one
fixture in one JVM, not against a string the test invented.

**The destructive half.** `ProjectSyncUseCase.rebuild` already captured a
`ProjectRealizationSnapshot` before `removeRealization`. **But my first assertion
of that held nothing**, and the review caught it: the fixture went straight to a
refused `--rebuild` over a project that had never resolved, so the previous lock
was empty, `removeRealization` removed nothing, and "byte-identical afterwards"
was true either way. Moving the capture after the teardown left it green. The
fixture now **resolves the project successfully first**, and the behavioural
assertion fails when the capture moves:

```
[FAIL] a refused --rebuild puts back a realization it really did tear down:
  expected <… child-homes/project_typed-rebuild-…/child-home.json=file:…,
             installed/typed-live.projections.json=file:… …>
  but was  <… child-homes=directory  (empty) …>
```

`LiveInterpreter` — the third `sync` caller, with no CLI in front of it — got the
typed branch too.

### The precedence, before and after

| step | documented before | executed before | now |
| --- | --- | --- | --- |
| 1 | `SKILL_MANAGER_CLI` | `SKILL_MANAGER_CLI` | `SKILL_MANAGER_CLI` |
| 2 | the running process's own command | **never fires** | `<home>/bin/cli/skill-manager`, skipped if its pin is gone |
| 3 | `<home>/bin/cli/skill-manager` | `<home>/bin/cli/skill-manager` | the running build, via `RunningCli` |
| 4 | `PATH` | `PATH` | `PATH`, reported **as** a fallback, with a caveat |

The dead step is **replaced, not deleted**: `RunningCli` already answered that
question correctly by reading the `SKILL_MANAGER_INSTALL_DIR` back-reference.

**What that ordering preserves, stated narrowly** — my first version claimed "no
existing home's descriptor changes which build it names", and that is false in
four shapes. It changes for a home with no `bin/cli`; a home whose pin an upgrade
deleted; a machine whose `PATH` `skill-manager` is a foreign home's entrypoint;
and a machine with no `skill-manager` on `PATH`. In each the previous answer was
a guess or a wrong home. The true claim is: **a home that ships a working CLI
still names that CLI.**

### DEF-012's resolution half, bounded

```
note: this home's own CLI entrypoint pins a build that is gone
(/opt/homebrew/Cellar/skill-manager/0.23.0/libexec/bin/skill-manager) — an
upgrade deleted it, and the shim cannot open. This remedy fell through to
<next candidate>. Re-pin the home with `<build> home shims --home <X>`.
```

That trailing `--home <X>` is the review's **H3**: the caveat's own instruction
was built from the binary alone, so a pasted `home shims` with no
`SKILL_MANAGER_HOME` re-pins the operator's **root** home — DEF-002 reintroduced
inside the fix for DEF-002, in the branch that fires exactly when the reader's
front door is already broken.

**Not taken:** how a pin is *written* (DEF-027) and whether `home verify` should
notice (DEF-012's detection half, HIS-13's).

## The rule, guarded mechanically

"Bind per verb" has N call sites and only the call site knows the verb, so the
binding cannot be centralised the way the CLI resolution was. A source scan
enforces it instead: any remedy interpolating a resolved CLI in front of a verb
that takes `--home` must carry one in the same expression.

**It was built because it was already needed, and it has now caught the thing it
was built for.** HIS-4 (#216) routed its new merge-conflict remedies through
`HomeDescriptor.cliInvocation` — correctly, and that closed one of my review
findings — putting three `<cli> sync <name>` lines, class 3 and unbound, in front
of this rule. I predicted the failure in DEF-026 before HIS-4 merged; when it
merged, the guard turned those lines into a build failure on the integrated tip:

```
[FAIL] every printed remedy that names a class-2/3 verb also names its home:
  expected <[]> but was <[ReportUseCase.java: + cli + " sync " + name + "`", …]>
```

Fixed here, because "whoever resolves the wave-4 merge" is this ticket — I am
last in promotion order, and a red guard cannot be left for later without
leaving the epic red. `mergeConflictRemedy` now builds one `syncRemedy` string
carrying `--home` and uses it at all three sites, instead of three
concatenations that each have to remember. HIS-4's spelling of the CLI — the
part that closed DEF-002 on that surface — is untouched. DEF-026 closed.

The class-1 negative (`home sync --from/--to`) is correctly skipped.

**The first version of the guard was unusable and is recorded rather than
elided.** It let the verb be any lowercase word, so `%s` matched nearly every log
format string in the project — about 180 false positives. A check that can only
be satisfied by weakening it is not a check. Pinning the verb list to the five
that take `--home` is what makes each match a real remedy site.

## Two more the review measured, and both were real

- **H4 — the symlink invariance was one-sided.** `isForeignHomeEntrypoint`
  resolved `owner` and `storeRoot` but never the **candidate**, so
  `/usr/local/bin/skill-manager -> <foreignHome>/bin/cli/skill-manager` — the
  most ordinary way to put a CLI on `PATH` — walked past the predicate and
  DEF-002 survived for that shape. There was no symlink anywhere in the test
  file, and my claim of spelling-invariance was **not supported**. Fixed, and
  the symlink case is now asserted in both directions plus through a symlinked
  home root.
- **H5 — `shellQuote` quoted only on a space.** A `$` in a home path was emitted
  raw and the reader's shell expanded it, so the remedy **bound a different
  home**. `'` gave `unexpected EOF`; `;`, `&`, newline and tab are an injection
  surface fed by a filesystem path. Now an allowlist, with **`/bin/sh` itself as
  the oracle** — the test round-trips nine hostile paths through a real shell
  rather than through a second model of one.

## What a reader gets today, and what it does not do — DEF-029 / HIS-14

**State this before anything else about the remedy: `--home` binds the STORE
axis and not the AGENT axis. HIS-14 (#232) closes the other half.**

A home has two axes. `SKILL_MANAGER_HOME` says where the UNITS live;
`CLAUDE_CONFIG_DIR` / `CODEX_HOME` / `GEMINI_HOME` say where the AGENT CONFIGS
live. The remedies this ticket ships carry `--home`, which pins the first. So a
reader who pastes one gets: **the right store, and agent projections resolved
against whatever their shell carries** — which, unset, is their real `~/.claude`.
`home verify`'s remedy is unaffected; it binds all four variables, which is why
it was written that way.

That is a real limitation of what I am shipping, not a footnote. It is
strictly better than what it replaces — a bare `sync <unit> --merge` behaved
identically and could name no home at all — but "better than nothing on one
axis" is not "binds the home", and the PR should not read as though it does.
The per-verb contract was the owner's decision; what this write-up owes is an
accurate description of the result.

Scheduled as **HIS-14 (#232)**, wave 5, promotion order 10, on the owner's
instruction that homes be isolated properly within this epic.

### How I found it

I pasted the printed remedy into a fresh shell to prove it works. It does, and
it edits the home it names — **and it linked the unit into the operator's real
`~/.claude`, `~/.codex` and `~/.gemini`.**

```
$ <build> sync probe-unit --merge --home <scratch>/project/.skill-manager
  ✓ units.lock.toml: wrote 1 unit(s) -> <scratch>/project/.skill-manager/…
  ✓ agents: 1 unit(s) linked into claude, codex, gemini        <-- WHICH claude?

$ ls -la ~/.claude/skills/probe-unit
  probe-unit -> <scratch>/…/skills/probe-unit          (and ~/.codex, ~/.gemini)
```

**`--home` binds one of a home's two axes.** `SKILL_MANAGER_HOME` says where the
UNITS live; `CLAUDE_CONFIG_DIR` / `CODEX_HOME` / `GEMINI_HOME` say where the
AGENT CONFIGS live, and they are a separate axis — which is issue #145, and
which `homeEnvPrefix`'s own javadoc states in those words. `home verify`'s
remedy binds all four and is unaffected; `--home` binds the first.

It is **not a defect the per-verb contract introduced** — a bare
`sync <unit> --merge` had the same behaviour and no way to name any home. What
the contract does is make the remedy *look* bound while binding half. I did not
change what `--home` means unilaterally: HIS-9 added it too, and two tickets in
this wave now depend on it. Filed as **DEF-029, blocking**, with the fix
(derive the agent roots from `--home`, as `HomeDescriptor.envFor` already does).

**The damage is repaired.** I removed the three dangling skill symlinks I
created. I did *not* edit `~/.claude/settings.json`,
`~/.claude/plugins/known_marketplaces.json` or `~/.codex/config.toml` — writing
an operator's real agent config is outside a ticket agent's remit, and the
attempt was correctly refused. The epic agent removed all three stale
registrations with the owner's approval and verified by diff that nothing else
changed.

**And one correction to my own record, because the next agent will repeat it.**
I called the backups at `scratchpad/his12-leak-backup/` "pre-change". They were
not: they were byte-identical to the damaged state, because I took them after
noticing the leak rather than before running the command that caused it.
Restoring them would have been a no-op. Snapshot before the experiment, not
after the surprise.

## How the goal metric moves

| scenario | reader | before | after |
| --- | --- | --- | --- |
| command run against the PROJECT home, root home's `bin/cli` on PATH | the sync refusal's remedy | names the **ROOT** home | names the **PROJECT** home |
| same, remedy pasted verbatim | the remedy's effect | edits the **ROOT** home | edits the **PROJECT** home |
| foreign entrypoint reached through a **symlink** on PATH | the remedy | names the **ROOT** home | names the **PROJECT** home |
| a home whose own pin an upgrade deleted | the remedy | names a front door that exits 127 | falls through, and says which build went missing |
| a home path containing `$` | the remedy's effect | binds **a third** home | binds the home it names |

HIS-6 owns the terminal measurement; this contributes the remedy reader.

## Validation

| signal | result |
| --- | --- |
| `jbang RunTests.java` | **ALL PASSED** |
| `uv run pytest specs/` | **38 passed** |
| **`home-sync` (CORE)** | **passed — 18 of 18 nodes, complete** (re-run on the rebased tree) |
| `home-integrity` (assigned) | **passed — 14 of 14 nodes, complete** |
| **`ticket-lifecycle` (CORE)** | **passed — 13 of 13 nodes, complete** (re-run after `a4a95cb`) |

**`home-sync` is H1, and it is the graph I owed and had not run.** With the
`env` prefix it was red: `remedyArgs` strips token 0 only when it
`endsWith("skill-manager")`, so the whole prefix reached the CLI as arguments —
`Unmatched arguments from index 0: '/usr/bin/env', …` — and **12 of 18 nodes
were skipped**, `home.fixpoint.law` among them. Under the class-1 rule
(`home sync --from … --to …` binds nothing) it is green, including
`home.sync.worktree.to.project`, the node that holds `remedyArgs`.

**`ticket-lifecycle` is where the remedy is read AND executed**, and last round
11 of its 13 nodes were skipped behind the failure that is now DEF-028. All 13
pass now, including the three that matter here:

```
every_remedy_the_refusal_prints_names_an_existing_executable   passed
every_remedy_names_a_resolved_cli_not_a_bare_skill_manager     passed
running_the_remedy_opens_the_gate                              passed
```

and the remedy those assertions read, captured verbatim from the refusal log:

```
✗   `<HOME>/bin/cli/skill-manager home drift --ack --home <HOME>`
```

The head token is a **per-home executable**. Under #229's first shape it was
`/usr/bin/env`, which satisfies "absolute" and "executable" forever — those two
assertions were passing then too, and meant nothing. `global.home.untouched`
also passes, which is the node that would have caught DEF-029 had the leak been
inside a graph rather than in my own hand-run probe.

`run.py --all` was not run; it belongs to HIS-6.

**What no graph covered:** DEF-029. `ticket.lifecycle.global.home.untouched`
passes, and it is the node that would have caught the leak — but the leak
happened in a remedy I pasted by hand outside any graph, against the real
ambient environment that a graph deliberately sandboxes. A sandbox that makes
the test safe also makes this defect invisible to it.

## Vacuity checks

Seventeen, each recorded verbatim in `probes/his-12/vacuity-checks.txt`, and
**re-run in full after the contract changed** — the earlier records described
code that no longer exists, so they were replaced rather than appended to. The
runner they name, `RunHis12.java`, **is in the repository**: a record naming a
file that does not exist cannot be re-run, which makes it a claim.

**V17 came back vacuous on its first run and the record of that is kept.** An
`endsWith("\\")` guard in `pinnedCliIn` could not be made to fail — every line
continuation it was meant to catch is already caught by the search for the
closing brace. A guard that cannot fail is not a guard, so it was **removed**
rather than kept for the look of it. That is the second time this ticket's own
vacuity pass has deleted something of mine; the first was V10 last round.

## The stale-javadoc pattern, and a check that costs nothing

The epic's DEF-021 counts this pattern across four tickets, and lists one of
mine: `locateCli`'s javadoc named `HomeDescriptorTest` as the checker of its
precedence; the checker is `HomeDescriptorCliRemedyTest`. Fixed, along with an
overstated claim (`cliInvocation` "resolves the CLI that IS running" — it is one
of four steps, and not the first) and two `{@link #toJson(Path)}` pointers naming
a signature this class has never had.

**Half of it is already a compiler flag. Measured, not proposed:**

```
$ javac -Xdoclint:reference   (fixture with a stale {@link})   →  error: reference not found  ×2
$ javac                       (as this repo builds today)      →  silent
```

Over `src/main/java` it reports **26 stale references in 15 files**. That is the
whole cost of turning it on; two of the 26 were mine and are fixed here.

**Its boundary, stated so the check is not oversold** — which would be this same
defect one level up. It resolves `{@link}`, `@see` and `@throws`. It cannot see
`{@code foo()}`, which is literal text, nor any prose claim. So it catches the
*pointer* family and none of the *claim* family. Appended to DEF-021.

## Deferred

- **DEF-025** — `project sync --rebuild` still validates the staged closure
  *after* the teardown and rolls back, rather than refusing before it. Needs the
  resolver's install phase split from its realize phase.
- **DEF-027** — a pin is still *written* as an absolute versioned Cellar path,
  so the next `brew upgrade` re-breaks it.
- **DEF-026** — HIS-4's three merge-conflict remedies were class 3 and unbound.
  **Closed:** predicted before HIS-4 merged, caught by the guard as a build
  failure when it did, fixed here as the wave-4 merge resolution.
- **DEF-028** — HIS-10's descent record read as a leak by `ticket-lifecycle`.
  **Closed: fixed upstream in `a4a95cb` from this finding**, before this PR.

DEF-002 is **closed** with its before/after pasted. DEF-012 stays **open** for
its detection half, and its two internal cross-references — which said
`DEF-014`, meaning my own finding, and would after renumbering have pointed at
**HIS-4's** unrelated DEF-014 — now say `DEF-027`. That is DEF-021's
stale-pointer pattern in a findings file rather than a javadoc.

Numbers are the owner's allocation of 2026-08-21, id for id: `020→025`,
`022→027`, `023→028`, plus `026` for the HIS-4 finding. `021` is the epic
branch's own.

## Known rebase conflict, and how to resolve it

`sync --home` and `project sync --home` are added by **both** this branch and
HIS-9 (#226), which promotes first. Compared field by field against
`origin/feature/226-his-9`: same flag, same type, same default, same store
resolution. **Keep either, delete the other; there is no behaviour to
reconcile.** Both javadocs say so at the option. Kept here rather than depending
on an unmerged branch so this branch builds and its assertions run.

## What I am unsure about

- **`isForeignHomeEntrypoint` keys on the `bin/cli/skill-manager` shape.** A home
  that pins its CLI elsewhere is not recognised. That shape is written by exactly
  one method and read by four, so I think it holds — but it is a structural
  assumption, and the symlink hole was in this same predicate.
- **The PATH caveat does not probe the found binary's version** (#161's option
  1). That costs a subprocess on a refusal path, and #61 says older builds answer
  unknown subcommands with usage and exit 0, so the probe is neither cheap nor
  reliable. Separable if wanted.
- **`RunningCli.locate(Function, String, Path)` and `HomeDescriptor.shellQuote`
  became public.** Both were package-private seams that cross-package callers and
  tests now need. Small widenings, but they are widenings.
- **`ticket-lifecycle` and `home-sync` were re-run after the rebase onto
  `a4a95cb`**; every other graph in the core set is unobserved under this change.
  The two I ran are the two that read remedy text.
- **DEF-029 was the thing I was least comfortable shipping**, and that
  discomfort is the only reason it was found: I ran the remedy instead of
  reading it. Filing it rather than fixing it was the right call and the owner
  has confirmed it — but a reader should know the remedy is half-bound until
  HIS-14 lands, which is why that is now stated at the top of this document
  rather than in a caveat at the bottom.

## Files

Production: `HomeDescriptor` (the cli-discovery section), `RunningCli`,
`LauncherShims` (`PIN_PREFIX`, `pinnedCliIn`, `danglingPinIn`), `DriftGate`,
`ProjectCommand`, `SyncCommand`, `LiveInterpreter`, and the remedy sites:
`HomeCommand`, `ExecCommand`, `ProjectSyncUseCase`, `ConsoleProgramRenderer`,
`HomeCloseOut`.

Tests: `HomeDescriptorCliRemedyTest`, `ProjectSyncTypedRefusalTest`.
Runner: `RunHis12.java`. Probes:
`results/epic-home-integrity-sync/probes/his-12/`.
