# HIS-14 — goal contribution

Issue #232, closing **DEF-029** and the agent half of **#145**. Branch
`feature/232-his-14`, wave 5, promotion order 10, predecessor HIS-11.

Goal: **GOAL-one-home-one-answer**, contribution `direct`.
Declared expected effect: *"two spellings of 'which home is this command about'
→ one; the agent axis stops resolving against whatever the shell happened to
export."*
Guard: **GOAL-no-destructive-recovery** — must not weaken the sanctioned
parent-store sharing HIS-10 landed.

## What this delivers

**`--home <X>` now means "this command is about X" on both of a home's axes, or
it refuses and names the variable it could not set.**

A home has two axes. `SKILL_MANAGER_HOME` says where the UNITS live;
`CLAUDE_CONFIG_DIR` / `CODEX_HOME` / `GEMINI_HOME` say where the AGENT CONFIGS
live. `--home` set the first. The store half went where it was told and the
agent half went to whatever the shell exported — which, unset, is the
operator's real `~/.claude`, `~/.codex` and `~/.gemini`.

The correct shape was already in the codebase, in exactly one place:
`HomeCommand.homeEnvPrefix`, whose own javadoc states the rule that was broken.
That is why `home verify`'s remedy was unaffected while every remedy built on
`--home` was not. **This is the epic's signature defect once more: two spellings
of one decision, where one is right and the other is the one every other remedy
uses.**

So the fix is not a new rule. It is the removal of the second spelling.

| | before | after |
| --- | --- | --- |
| where the four variables are written down | `HomeCommand.homeEnvPrefix`, alone | `AgentHomes.binding(store)`, alone |
| what `homeEnvPrefix` does | builds them | **renders** `binding` |
| what `--home` does | sets `SKILL_MANAGER_HOME` on one store object | **applies** `binding`, for the whole invocation |
| how many verbs implement it | 7, none of them | 1 applier, every verb that declares the flag |

`AgentHomes.binding` is four entries in one order. `homeEnvPrefix` renders it
into `env NAME=… NAME=… <cli>`; `SkillManagerCli.bindNamedHome` installs it as
the thread-local overrides `AgentHomes.resolve` consults ahead of the real
environment. `SkillStore.defaultStore()` already resolved through that same
lookup, so binding it makes **every** reader in the process — the store, the
projector, the MCP writers, the two harness drivers that spawn `claude` and
`codex` children — answer with the home the operator named. One answer.

### Why the applier is central and not per-verb

Because "N call sites, one rule" is the shape this epic keeps paying for, and
because it is *why this defect existed*: HIS-12's per-verb remedy contract is
correct about which verb carries `--home`, and it could be correct about that
while `--home` itself bound half. The binding is a property of the FLAG, and the
flag is read in one place. `sync`, `project sync`, `home drift`, `home shims`,
`home close-out`, `unit publish` and `exec` are covered because they declare the
option — not because they were listed. A future verb that declares it is covered
the day it is added.

The applier runs **ahead of `tryReconcile`**, deliberately. That call projects
the ambient home's units into the ambient agent directories before the parsed
command executes. After the binding the ambient home IS the named home, so a
`--home` invocation reconciles the home it named; before it, a command carrying
`--home` still wrote into a home nobody mentioned.

### The refusal

Setting a variable is not the same as confining a write. An agent directory that
symlinks OUT of the home writes wherever the link points, however the variable
is set — the shape the epic measured twice on the operator's own root home
during HIS-7. `AgentHomes.unbindable` finds those and the CLI refuses with
`UNBINDABLE_HOME_EXIT_CODE` (13), naming each variable and where it actually
lands, rather than running with a binding that is true of the environment and
false of the filesystem. Only directories that EXIST are judged, and a symlink
that stays inside the home is fine.

## Before → after, measured with the real CLI

Full transcript: `probes/his-14/def029-repro.md`. Reproduced against a **decoy**
whose `$HOME` is a scratch directory, in DEF-029's own environment
(`SKILL_MANAGER_HOME` unset).

**Before**, `sync <unit> --merge --home <project>`:

```
✓ units.lock.toml: wrote 1 unit(s) → <project>/.skill-manager/units.lock.toml
✓ agents: 1 unit(s) linked into claude, codex, gemini
  ADDED claude (<decoy>/.claude.json)  ADDED codex (<decoy>/.codex/config.toml)
  ADDED gemini (<decoy>/.gemini/settings.json)

<decoy>/.claude/skills/<unit> -> <project>/.skill-manager/skills/<unit>
<decoy>/.codex/skills/<unit>  -> same
<decoy>/.gemini/skills/<unit> -> same
```

**After**, same command, same environment: the three symlinks and three config
edits are gone, `<decoy>/.claude`, `.codex` and `.gemini` are empty, and
`<project>/.claude/skills/<unit>` is the link.

**And the printed remedy, executed.** Not read — run. The refusal's own
`re-run with:` line, pasted into `sh -c` in a shell whose `$HOME` is the decoy:
the named home merges and projects, the decoy is untouched, and
`<decoy>/.claude.json` does not exist.

## The regression fixture, and what makes it non-vacuous

`src/test/java/dev/skillmanager/cli/HomeBindsBothAxesTest.java`, 10 cases,
registered in `RunTests.java`.

Every case runs **with the ambient agent variables naming a different home**,
and asserts on that other home's BYTES — a recursive before/after snapshot that
records symlink targets, because the damage was symlinks. The two structural
guards against a passing-but-empty check:

- a **FLOOR** case proving a projection into a home's agent dirs is observable
  at all, so "nothing appeared over there" cannot pass by nothing having
  happened anywhere;
- `otherHomeIsUntouched` **refuses a before-snapshot with fewer than six
  entries**, so an unbuilt fixture is a failure rather than a silent pass.

The **root tier** is a synthetic root-shaped fixture: a temp directory that is
the fixture's `$HOME`, holding `.skill-manager` beside `.claude` / `.codex` /
`.gemini`. **No test in this ticket writes the operator's real agent homes** —
that is the thing that caused this, and it is guarded by construction rather
than by care.

## Vacuity checks — four, all recorded verbatim

`probes/his-14/vacuity-checks.txt`. Each disables the fix, runs the suite, and
pastes the message.

| | disabled | reddens |
| --- | --- | --- |
| **V1** | the CLI applier (`--home` binds the store axis only) | 4 of 10 |
| **V2** | `AgentHomes.binding` reduced to `SKILL_MANAGER_HOME` | 6 of 10 |
| **V3** | `homeEnvPrefix` builds its own list again, drifting by one variable | the rendering case, **and** HIS-12's inherited guard |
| **V4** | the `unbindable` refusal | the refusal case; the companion stays green, as it must |

**Both of the traps the ticket named fired, and both were fixture defects I
fixed rather than findings I explained away.**

*Trap (b) — an ambient environment that already names the target.* The shell
case PASSED under V2 on its first run. Its subprocess **removed**
`CLAUDE_CONFIG_DIR` / `CODEX_HOME` / `GEMINI_HOME`, and with all four unset the
agent axis falls back to the store axis — so a binding that pinned only
`SKILL_MANAGER_HOME` still landed everything in the right place and the case
could not see a half-fixed product. The fixture now **exports** the three
variables naming the other home, which is both the discriminating case and the
case an operator is actually in: every per-checkout home exports them.

*Trap (b), again, in the rendering case.* That assertion derives its expected
string FROM `AgentHomes.binding`, so under V2 both sides shrink together and the
containment still holds. What reddens is the explicit
`assertEquals(4, binding.size())` — a line that exists **only because this check
was run**.

*Trap (a) — reddening on a precondition.* The shell case's first V2 run failed
on its own floor guard (`the other home has content to be damaged (3 entries)`)
rather than on the claim, because the other home was three empty directories. It
now builds a populated other home — a store plus the three config files the real
damage edited — and reddens on the claim itself.

## HIS-12's source-scan guard

**Green, unchanged, and not weakened.** The scan enforces that every remedy
naming a class-2/3 verb carries `--home` in the same expression; this ticket
changes what `--home` MEANS, not which remedies carry it, and adds no remedy
site. V3 shows the two guards are independent readers of one drift: disabling
the shared source reddens the new rendering case AND the inherited
`no remedy pins SKILL_MANAGER_HOME without the agent-home variables`.

## GOAL-no-destructive-recovery — the guard

Not weakened. `home-integrity` passes 15 of 15 including
`home.integrity.readers.agree.about.one.clone` and
`home.integrity.every.shim.resolves`, which are the nodes HIS-10's
sanctioned-parent-store sharing lives behind. The binding changes which home a
command resolves against; it changes no predicate about what a home may share.

## Validation

| signal | result |
| --- | --- |
| `jbang RunTests.java` | **ALL PASSED** (`HomeBindsBothAxesTest` 10/10) |
| `uv run pytest specs/` | **38 passed** |
| `home-integrity` (assigned) | **passed — 15 of 15 nodes, `complete: true`**, run `20260822-141230` |
| **`home-sync` (CORE)** | **passed — 18 of 18 nodes, `complete: true`**, run `20260822-141853` |
| **`ticket-lifecycle` (CORE)** | **passed — 13 of 13 nodes, `complete: true`**, run `20260822-142637` |
| TLC | **N/A** per the plan: this ticket states no new invariant; HIS-5 carries the model work |

**Why those two graphs and not others**, named with the reason the plan asks
for: my diff changes `SkillManagerCli`'s execution strategy, which every CLI
invocation passes through, and it changes what a printed `--home` remedy DOES
when run. `home-sync` and `ticket-lifecycle` are the two graphs that **read a
printed remedy and execute it** — `home-sync`'s `remedyArgs`,
`ticket-lifecycle`'s `running_the_remedy_opens_the_gate` — so they are the
fixtures that exercise the sources I edited. `ticket-lifecycle` also owns
`global.home.untouched`, the node closest to the defect: it is the assertion
that would have caught DEF-029 had the leak happened inside a graph rather than
in a hand-run probe.

Both came back green, and `ticket-lifecycle`'s node closest to this defect is
green assertion by assertion:

```
ticket.lifecycle.global.home.untouched              passed
    the_operators_real_agent_homes_did_not_move     passed
    the_agent_config_registrations_are_unchanged    passed
    the_leak_oracle_detects_a_repointed_agent_skill_link   passed
    the_config_check_covers_the_sibling_claude_json_file   passed
```

The last two are that node's own vacuity guards — the oracle proves it can SEE
a repointed agent link and a `.claude.json` beside the config dir, which are
precisely the two shapes DEF-029 produced. HIS-12's write-up says this node
would have caught the leak had it happened inside a graph rather than in a
hand-run probe. It is the right node, it can see the damage, and it is green.

`run.py --all` was not run; it belongs to HIS-6, which owns the one terminal
sweep.

## The operator's real agent homes

Digested **before the first command of this session** and re-digested after
everything above. Every digest matches except `~/.claude.json`, which was not in
the before-set — see below.

```
$ probes/his-14/check-real-homes.sh probes/his-14/real-homes.before.sha256
  ~/.claude/settings.json                     unchanged
  ~/.claude/plugins/known_marketplaces.json   unchanged
  ~/.codex/config.toml                        unchanged
  ~/.gemini/settings.json                     unchanged
  ~/.claude/skills   (names+targets)          unchanged
  ~/.codex/skills    (names+targets)          unchanged
  ~/.gemini/skills   (names+targets)          unchanged
  ~/.claude.json                              NOT IN THE BEFORE-SET
```

**Two corrections to what this section said in the first round, both from the
review of #234, and both are about the evidence rather than the product.**

*The probe published the operator's machine.* `snapshot-before/` and
`snapshot-after/` were byte-for-byte **copies** of the four config files plus
`ls -la` listings of the operator's installed skills, committed to a public
repository. No credentials — but `JAVA_HOME`, every project path on the machine
with its trust level, MCP endpoints, and hook command paths under `~/.orca/`.
They are gone from the branch's **history**, not merely from its tip, and
`check-real-homes.sh` now hashes in place and copies nothing. A digest is
strictly better evidence than a copy, because same-or-different is the only
thing this section ever claimed. **A probe written to prove nothing leaked
leaked something itself**, which is the same class of defect as the one this
ticket is about: the instrument was not held to the property it measures.

*The set omitted the file the reproduction names.* `~/.claude.json` — where a
`CLAUDE_CONFIG_DIR`-unset Claude reads its `mcpServers`, and one of the three
files DEF-029 edited — was not in the before-snapshot. It is in the set now, and
for THIS session there is no before-digest to compare it against, so **no claim
is made about it here.** What covers it instead is
`ticket.lifecycle.global.home.untouched`'s own
`the_config_check_covers_the_sibling_claude_json_file`, green on this branch,
and the fact that every CLI run in this ticket was driven with `$HOME` pointed
at a scratch directory. It is also rewritten continuously by the live agent
session hosting the ticket, which is why a naive before/after on it would have
produced a false positive rather than evidence.

## How the goal metric moves

| scenario | reader | before | after |
| --- | --- | --- | --- |
| `sync <u> --merge --home <X>`, ambient naming another home | where the units land | X | X |
| same | where the agent links land | **the ambient home** | X |
| same | where the MCP entries land | **the ambient home** | X |
| `home verify`'s printed prefix vs `--home` | which four variables | two spellings, one of them half | **one map, rendered and applied** |
| a remedy pasted into a shell that names another home | what it edits | **two homes** | one |
| a home whose `.claude` symlinks out of it | what happens | silently writes the link target | **refuses, exit 13, names `CLAUDE_CONFIG_DIR`** |

HIS-6 owns the terminal measurement; this contributes the `--home` reader and
the agent axis of every other one.

## Deferred — DEF-036 … DEF-040

- **DEF-036** — `HomeDescriptor.envFor` is a third construction of the same
  environment (five variables to the binding's four). They agree by derivation,
  not by enforcement. Folding them changes `home.runtime.json`'s wire format.
- **DEF-037** — `install`, `uninstall`, `bind`, `rebind`, `unbind`, `env run`
  and `harness instantiate` still take no `--home`, so they can only be aimed
  with the four-variable prefix. Not a regression; a gap that only exists now
  that `--home` works.
- **DEF-038** — the binding is thread-local and a child process inherits the
  real environment. The two harness drivers pass the bound value down
  explicitly, which is measured, but nothing enforces that the next one will.
- **DEF-039** — `tryReconcile` still reconciles the AMBIENT home on invocations
  that name none, READ_ONLY ones included, outside `CommandHomeAccess`'s gate.
- **DEF-040** — `exec` now states its home twice, through `--home` and through
  `LaunchEnv`. They agree; they are two derivations.

## What I am unsure about

- **Exit code 13 is new.** It is free today (12 is `HomeSync`), and it follows
  the `HOME_MISMATCH_EXIT_CODE` precedent that a refusal is not a failure. But
  it is a new number on a shared surface and no consumer knows it yet.
- **The refusal's blast radius.** It fires only when an existing agent directory
  resolves outside its home, and only on invocations carrying `--home`. I
  believe that is nobody's working configuration; a home that symlinks
  `.claude` elsewhere on purpose would now be refused rather than served, and
  the message tells them to run against the home the link points at. If that is
  wrong, it is wrong loudly, which is the right way round.
- **`CLAUDE_HOME` is deliberately absent from the binding**, because
  `CLAUDE_CONFIG_DIR` is consulted first and adding it would put one directory
  in the map twice. That precedence is now asserted (`an ambient CLAUDE_HOME
  naming another home does not survive the binding`) rather than trusted — but
  it is a precedence, not an absence, and a reader who greps for `CLAUDE_HOME`
  in the binding will not find it.
- **A `--home` invocation now reconciles the home it named, where before it
  reconciled the ambient one.** `tryReconcile` runs on every invocation,
  read-only ones included, so `home verify --home <X>` used to write agent
  symlinks into whatever home the shell named and now writes them into X. That
  is strictly what the acceptance asks for — no byte outside X — but it is a
  write on a verb whose name says it reads, and `home-integrity` passing 15/15
  is the evidence that it changes no verdict rather than an argument that it
  cannot. DEF-039 is the general form.
- **`AgentHomes.binding` normalizes to an absolute path.** The printed prefix
  therefore absolutizes a relative `--home`, where before it printed what it was
  given. Strictly better, and no test depended on the old spelling, but it is a
  change to remedy text.
- **The `tla-spec-dev` ticket open/close was not run.** `validation.tlc` is
  `N/A` for this ticket, the plan's `status` transitions are written by the epic
  agent at merge (`chore(epic): … delivered`), and the only `tla-spec-dev` on
  PATH resolves through the ROOT home's `bin/cli` pin — which this ticket exists
  to stop people driving other homes with. Flagged rather than done.

## Files

Production: `AgentHomes` (`binding`, `bind`, `unbindable`, `variableFor`,
`snapshotOverrides`, `restoreOverrides`), `SkillManagerCli` (`bindNamedHome`,
`UNBINDABLE_HOME_EXIT_CODE`), `HomeCommand.homeEnvPrefix` (now a renderer),
`ExecCommand.refreshHome` (restore, not clear).

Tests: `HomeBindsBothAxesTest`, registered in `RunTests.java`.
Probes: `results/epic-home-integrity-sync/probes/his-14/`.
