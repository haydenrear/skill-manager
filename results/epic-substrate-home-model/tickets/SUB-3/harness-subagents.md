# SUB-3 — How each harness provisions sub-agents, and what skill-manager must expose

Ticket: [#278](https://github.com/haydenrear/skill-manager/issues/278) ·
Epic: `substrate-home-model` · Goals: `GOAL-every-machine-local-assumption-is-named`,
`GOAL-support-without-driving` (both *enabling*)
Surveyed at `cd9b1db` (epic tip at branch time), 2026-08-29.

## What this is, and what it is not

This epic **deploys nothing** to Kubernetes or Substrate, and neither skill-manager
nor `skt` gains any ability to drive Substrate — or, for that matter, to drive a
harness. Every requirement below is phrased as something skill-manager **exposes**
and a harness or a bootstrap script **consumes**. Nothing here proposes that
skill-manager learn to launch, schedule, or configure Claude Code, Codex, Gemini,
or an ACP wrapper.

Nothing in this ticket changes production code. Reading, measuring, and recording only.

This survey builds on `results/epic-substrate-home-model/tickets/SUB-0/survey.md`
(merged at `cd9b1db`) and does not re-derive its nine-surface inventory. Where it
touches a SUB-0 row it says which, and where it **refines or contradicts** one it
says that too — see [§7](#7-what-this-refines-in-sub-0).

---

## The finding in one paragraph

The expectation in the ticket — *"Claude sub-agents will still provision
skill-manager homes in worktrees, just inside a container, so little changes"* — is
correct about the container and **wrong about the sub-agent**. Sub-agents do not
provision homes at all. skill-manager's entire home-binding contract is applied
**once, to a process, at `exec` time** (`commands/ExecCommand.java:132-143`), and
every harness surveyed runs its sub-agents *inside that already-launched process*,
inheriting its environment with no second pass. Claude Code then adds a twist that
is not hypothetical and does not need a container: it puts its isolated sub-agents
in a **git worktree it creates itself**, at `<repo>/.claude/worktrees/agent-<id>`
— a path that is *inside the home's own Claude agent directory* — and it can give
that sub-agent neither a different `CLAUDE_CONFIG_DIR` nor a different
`SKILL_MANAGER_HOME`. The result is measured below: a home that is a `worktree`
home when asked from its own checkout is a `project` home when asked from the
sub-agent's, and `skt publish` from that sub-agent targets the **operator root
home** instead of the project home.

---

## 1. The structural claim, and its evidence

Three facts, each read from code or documentation, that together determine every
per-harness answer.

### 1a. The launch contract is a one-shot on a process

`skill-manager exec` is "the one place the launch contract is applied, and the
generated `bin/launch` shims are thin wrappers over it"
(`commands/ExecCommand.java:29-31`). What it does — export the descriptor's env,
put the home's `bin/` first on `PATH`, **remove every other home's `bin/` from
`PATH`**, and refuse an unredirected `CLAUDE_CONFIG_DIR` — is `LaunchEnv`
(`launch/LaunchEnv.java:127-171`, `:196-212`, `:400-417`). The last line of every
generated shim is

```sh
exec "$cli" exec --home "$home" -- @SKILL_MANAGER_AGENT@ "$@"
```

(`launch/LauncherShims.java:309`, template at `:274-310`), and the env is handed to
exactly one `ProcessBuilder` (`commands/ExecCommand.java:223-226`).

There is no re-entry point. `grep -rn "subagent\|sub-agent\|child session" src/main/java`
returns **one** hit, and it is prose in a javadoc (`store/HomeScaffold.java:30-32`).
`CLAUDE_CODE_CHILD_SESSION` appears nowhere in `src/`, in `skills/`, or in any unit
in this repo; its only repo-wide occurrence is an evaluation script's *strip list*
(`results/epic-home-integrity-sync/evaluation/disclosure-cost/drive.py:35-38`).

### 1b. skill-manager has no "make a home here, from the home above me" command

Checked exhaustively across `commands/HomeCommand.java` and `commands/ProjectCommand.java`:

| command | what it would have to be told |
| --- | --- |
| `home clone` | `--to` is `required = true` (`HomeCommand.java:65-67`); `--from` defaults to the *ambient* home (`:96`), which is `$SKILL_MANAGER_HOME` else `~/.skill-manager` (`store/SkillStore.java:92-98`) — never "the home above me" |
| `home sync` | both `--from` and `--to` are `required = true` (`HomeCommand.java:1281-1285`) |
| `home close-out` | both `--home` and `--into` are `required = true` (`HomeCommand.java:1377-1383`) |
| `project resolve` | discovers the *child* end from `--project-dir` (`ProjectCommand.java:96-98`, layout at `bindings/ChildHomeHarnessInstaller.java:148-156`) but takes the parent from the environment, and needs a `skill-project.toml` to exist |
| `home describe/policy/shims --init` | "lay out the home first if it is not one yet" (`HomeCommand.java:821-822`, `:880-881`, `:1040-1041`) — an **empty** home, derived from nothing |

`ProjectCommand.java` contains **zero** `required = true` declarations; `HomeCommand`
has exactly the five above. The ancestor walk that would answer "the home above me"
exists only in Python — `skill-publisher-skill/src/skt/homes.py:34-38` — and in
`LaunchEnv` for a *shim's own location* (`:283-287`), not in any provisioning command.

That gap is filled out-of-tree. `skt ticket new` in epic mode shells out to
**`<home>/skills/git-issue-workflow/scripts/bootstrap-home.sh --root <path>`**
(`skill-publisher-skill/src/skt/ticket.py:114`, located at `:51-56`) and calls **no**
`skill-manager` subcommand for the home at all. `bootstrap-home.sh` is **not in this
repo** — `find` for it returns nothing; it is referenced by `ticket.py:55,105,111-112,114,122`,
by `skill-project.toml:20`, by `skill-publisher-skill/skills/skt/SKILL.md:57,71`, and
asserted against by twenty-odd `test_graph/sources/**` nodes that locate it in an
*installed* home.

### 1c. The harness cannot re-bind a sub-agent

From the Claude Code documentation, confirmed by the guide agent against
`code.claude.com/docs/en/{sub-agents,worktrees,hooks,plugins-reference,env-vars}.md`:

- Sub-agents may carry a custom `skills` set, system prompt, and permissions.
  **There is no documented mechanism to give a sub-agent a different
  `CLAUDE_CONFIG_DIR`, plugin set, or MCP set than its parent.**
- `SubagentStart` / `SubagentStop` hooks exist and receive
  `{session_id, cwd, agent_id, agent_type, hook_event_name, …}`. **A hook cannot
  modify the sub-agent's environment or working directory** — it runs in its own
  subprocess; `cwd` is reported, not settable.

So the one lever a sub-agent-aware provisioner would need — *set this sub-agent's
`SKILL_MANAGER_HOME` and `CLAUDE_CONFIG_DIR`* — is not offered by the harness, and
skill-manager's isolation is built on exactly those two variables plus `PATH`
(`launch/LaunchEnv.java:27-59`, `store/HomeDescriptor.java:102-124`).

---

## 2. Claude Code — the measured case

### 2a. How it launches a sub-agent, and what that sub-agent inherits

Two shapes, and only the second is interesting.

**Ordinary sub-agent.** Same process, same cwd, same environment. Tool calls run as
subprocesses of the same `claude` process with `CLAUDECODE=1` and
`CLAUDE_CODE_CHILD_SESSION` set (documented in `env-vars.md`). Nothing about the
home changes, and this is the case for which "little changes" is simply true.

**Isolated sub-agent (`isolation: "worktree"`).** Claude Code runs
`git worktree add` and puts the worktree at **`.claude/worktrees/<name>/`** relative
to the repository root (documented in `worktrees.md`); the sub-agent's cwd is that
worktree root. Gitignored files are copied in only if a `.worktreeinclude` lists
them. Claude Code holds a `git worktree lock` while the agent runs. Worktrees with
changes stay on disk until a `cleanupPeriodDays` sweep.

**Measured, in this very session** (this ticket agent *is* such a sub-agent, at
`/Users/hayde/IdeaProjects/skill-manager/.claude/worktrees/agent-a378f4c71ee2a6a65`):

```
$ printenv SKILL_MANAGER_HOME ; echo rc=$?     -> rc=1   (unset)
$ printenv CLAUDE_CONFIG_DIR  ; echo rc=$?     -> rc=1   (unset)
$ printenv CLAUDE_HOME        ; echo rc=$?     -> rc=1   (unset)
$ printenv SKT_ROOT_HOME      ; echo rc=$?     -> rc=1   (unset)
$ ls -d .skill-manager .claude
ls: .claude: No such file or directory
ls: .skill-manager: No such file or directory
$ cat .git
gitdir: /Users/hayde/IdeaProjects/skill-manager/.git/worktrees/agent-a378f4c71ee2a6a65
$ git rev-parse --git-common-dir
/Users/hayde/IdeaProjects/skill-manager/.git
```

So: a **linked worktree**, with **no home**, and **no home variable set**. And:

```
$ command -v skill-manager
/Users/hayde/.skill-manager/bin/cli/skill-manager
$ command -v skt
/Users/hayde/.skill-manager/bin/cli/skt
```

Both resolve into the **operator's root home**. `bin/cli/skill-manager` then does
`export SKILL_MANAGER_HOME="$home"` (`launch/LauncherShims.java:663`, measured at
line 165 of the generated file), so every `skill-manager` command this sub-agent
runs operates on `~/.skill-manager`. The HIS-9 self-guard does **not** fire, by
design: it refuses only when an inherited `SKILL_MANAGER_HOME` *differs*, and "an
unset or empty value is not a request and still binds this home"
(`LauncherShims.java:608-614`).

`bin/cli/skt` is not even a shim — it is a `skill-script` wrapper with two absolute
host paths baked in, exactly the class `LaunchEnv`'s javadoc calls out at `:32-46`:

```sh
exec "/opt/homebrew/bin/python3.14" "/Users/hayde/.skill-manager/plugins/skt/src/skt/cli.py" "$@"
```

### 2b. PATH: measured, nine bin directories from three different homes

`LaunchEnv.effectivePath` exists to strip foreign homes' `bin/` from an inherited
`PATH` (`:196-212`, `isForeignHomeBin` at `:244-305`), and its comment at `:262-282`
records that the four-level `<store>/plugin-marketplace/plugins/<name>/bin` shape was
the specific shape that used to slip through. Replaying `looksLikeStoreRoot`
(`:326-330`) and `agentDirOwnedByAHome` (`:357-373`) over this sub-agent's actual
`PATH`:

```
PATH entries (deduped): 38
entries owned by SOME skill-manager home: 9

HOME: /Users/hayde/.skill-manager
    store    /Users/hayde/.skill-manager/bin/cli
    agentdir /Users/hayde/.claude/plugins/cache/claude-plugins-official/jdtls-lsp/1.0.0/bin
    agentdir /Users/hayde/.claude/plugins/cache/claude-plugins-official/rust-analyzer-lsp/1.0.0/bin
    store    /Users/hayde/.skill-manager/plugin-marketplace/plugins/andrej-karpathy-skills/bin
    store    /Users/hayde/.skill-manager/plugin-marketplace/plugins/cdc-agent-substrate-plugin/bin
    store    /Users/hayde/.skill-manager/plugin-marketplace/plugins/skt/bin

HOME: /Users/hayde/IdeaProjects/commit-diff-context-parent/.skill-manager
    store    .../commit-diff-context-parent/.skill-manager/plugin-marketplace/plugins/cdc-agent-substrate-plugin/bin
    store    .../commit-diff-context-parent/.skill-manager/plugin-marketplace/plugins/skt/bin

HOME: /Users/hayde/IdeaProjects/skill-manager/.skill-manager
    store    .../skill-manager/.skill-manager/plugin-marketplace/plugins/skt/bin
```

**Three distinct Skill Manager homes**, one of them belonging to an unrelated
repository, contributing nine `bin/` directories to one sub-agent's `PATH`. Every
one of them is what `isForeignHomeBin` is written to remove.

Where they come from is checkable. Only one is the operator's shell:
`/Users/hayde/.zshrc:26` is `export PATH=/Users/hayde/.skill-manager/bin/cli:$PATH`,
and it is the sole `plugin-marketplace`/`skill-manager/bin` line in any rc file. The
other eight arrive as a contiguous trailing block of *plugin* bin directories —
Claude Code puts each enabled plugin's `bin/` on the `PATH` used for Bash tool calls
(`plugins-reference.md`; the docs say *prepended*, my measurement has them trailing
because the login shell prepends after). The plugin list comes from
`~/.claude/plugins/known_marketplaces.json`, which on this machine registers **eight**
skill-manager marketplaces:

```
skill-manager             -> /Users/hayde/.skill-manager/plugin-marketplace
skill-manager-919db26e    -> /Users/hayde/IdeaProjects/commit-diff-context-parent/.skill-manager/plugin-marketplace
skill-manager-0fd46eec    -> /Users/hayde/IdeaProjects/tla-spec-dev/.skill-manager/plugin-marketplace
skill-manager-77af5a71    -> /Users/hayde/IdeaProjects/skill-manager-integration-repository/.skill-manager/plugin-marketplace
skill-manager-082063ca    -> /Users/hayde/IdeaProjects/acp-cdc-ai-python/.skill-manager/plugin-marketplace
skill-manager-fd6e57d4    -> /Users/hayde/IdeaProjects/skill-manager/.skill-manager/plugin-marketplace
skill-manager-f332f28a    -> /Users/hayde/IdeaProjects/meta-harness/.skill-manager/plugin-marketplace
claude-plugins-official   -> (github)
```

skill-manager is the writer of those rows: `project/HarnessPluginCli.java:288-291`
runs `claude plugin marketplace add <marketplaceRoot> --scope user`, and `:453-457`
runs `claude plugin install <name>@<mkt> --scope user`, with the destination decided
by ambient resolution — `claudeEnv()` at `:337-338` is
`CLAUDE_CONFIG_DIR = AgentHomes.claude().configDir()`, which falls back through
`agentHomeRoot()` (`agent/AgentHomes.java:254-262`) to `$HOME` whenever
`SKILL_MANAGER_HOME` is unset. **Which is exactly the state measured in §2a.**

*Read vs inferred:* the registry contents, the `--scope user` flag, and the ambient
resolution are all read. That the eight rows were produced by this path specifically
is **consistent with** all three and is the only writer of that file I found, but I
did not reproduce a registration (it writes a real home), so I record it as
strongly-supported inference, not measurement.

`installed_plugins.json` is the same shape one level down: it keys project-scope
installs by **absolute `projectPath`**, and several of the recorded paths are
`/private/var/folders/.../skt-eval-*` temp directories that no longer exist.

### 2c. The tier divergence — measured on a scratch fixture

This is the sharpest result in the ticket, and it does **not** need a container.

Reproduced twice: once against this live session, once on a clean scratch fixture
that reproduces Claude Code's documented worktree convention with no real home
involved.

**Fixture A — a project checkout, plus a Claude-Code-shaped agent worktree:**

```
$SP/repo/.skill-manager/{installed,skills}        # a project home
$SP/repo/.claude/worktrees/agent-deadbeef         # git worktree add, CC's convention
```

```
sub-agent cwd      : $SP/repo/.claude/worktrees/agent-deadbeef
skt find_home      : $SP/repo/.skill-manager
checkout_root      : $SP/repo/.claude/worktrees/agent-deadbeef
is_linked_worktree : True
classify_tier      : project
_parent_home       : (None, 'operator root home not found at /nonexistent-root/.skill-manager')
```

Note `is_linked_worktree: True` **and** `classify_tier: project`. SUB-0 §2 measured
the container break as a *loss of git plumbing* — a clone that is not a worktree, or
an orphan whose gitdir is gone. This case keeps the git plumbing entirely and still
misclassifies, because `classify_tier` is a **conjunction**
(`skill-publisher-skill/src/skt/context.py:53-61`):

```python
if is_linked_worktree(root) and _inside(home, root):
    return "worktree"
return "project"
```

and `_inside` (`context.py:64-69`) is one-disk containment of the *home* in the
*checkout*. A sub-agent worktree is a linked worktree whose home is one level **up**,
not inside. So the second conjunct alone is enough to break the tier.

**Fixture B — the epic's actual shape: a ticket worktree, correctly bound, that
spawns an isolated sub-agent.**

```
$SP/main/.skill-manager                                  # project home
$SP/wt-ticket/.skill-manager                             # ticket worktree home (linked wt of main)
$SP/wt-ticket/.claude/worktrees/agent-cafe               # CC sub-agent worktree inside it
SKILL_MANAGER_HOME=$SP/wt-ticket/.skill-manager          # as bin/launch/claude would export
```

```
SKILL_MANAGER_HOME : $SP/wt-ticket/.skill-manager
sub-agent cwd      : $SP/wt-ticket/.claude/worktrees/agent-cafe
skt find_home      : $SP/wt-ticket/.skill-manager     <- correct: the ticket worktree home
checkout_root      : $SP/wt-ticket/.claude/worktrees/agent-cafe
is_linked_worktree : True
classify_tier      : project                          <- it IS a worktree home
_parent_home       : ('$SP/fakeroot/.skill-manager', None)

same home, evaluated from the ticket worktree itself:
  classify_tier    : worktree
  _parent_home     : ('$SP/main/.skill-manager', None)
```

**One and the same home is classified two different tiers, with two different
parents, depending only on which checkout the question is asked from.** The
sub-agent's answer names the **operator root home** as the parent.

The consequence is not decorative. `skt publish` resolves its sync target with
exactly this call (`skill-publisher-skill/src/skt/publish.py:38-67`) and then runs

```python
[cli, "home", "sync", "--from", str(home), "--to", str(parent), "--merge"]   # publish.py:133
```

So `skt publish` from a Claude Code isolated sub-agent inside a correctly-provisioned
ticket worktree would run
`home sync --from <ticket worktree home> --to <operator root home> --merge`,
**skipping the project home entirely and writing the operator's root home two tiers
up**. I did not execute it — it writes a real home — but both legs are read
directly from the code above and the parent resolution is measured.

### 2d. The worktree lands *inside* the home's agent directory

`AgentHomes.agentDirsUnder(homeRoot)` is `{<root>/.claude, <root>/.codex, <root>/.gemini}`
(`agent/AgentHomes.java:288-292`), so for a project home at `<repo>/.skill-manager`
the Claude agent directory is `<repo>/.claude`. Claude Code's documented worktree
location is `<repo>/.claude/worktrees/<name>`. Replaying
`LaunchEnv.agentDirOwnedByAHome` (`:357-373`) on a source file in this sub-agent's
own working tree:

```
path                       : .../skill-manager/.claude/worktrees/agent-a378f4c71ee2a6a65/src/main/java/dev/skillmanager/launch/LaunchEnv.java
agentDirOwnedByAHome(path) : /Users/hayde/IdeaProjects/skill-manager/.claude
```

**Every file in the sub-agent's checkout is classified as living inside the project
home's Claude agent directory.** `git worktree list` in this repo shows six such
worktrees under `.claude/worktrees/`, beside the `../wt-<ticket>` worktrees that
`skt ticket new` creates:

```
/Users/hayde/IdeaProjects/skill-manager                                            [epic/home-boundary-resolution]
/Users/hayde/IdeaProjects/skill-manager/.claude/worktrees/agent-a284804adafc12655  [feature/sub-0]
/Users/hayde/IdeaProjects/skill-manager/.claude/worktrees/agent-a378f4c71ee2a6a65  [feature/sub-3] locked
/Users/hayde/IdeaProjects/skill-manager/.claude/worktrees/agent-a5831ac5417e71cd5  [feature/hbr-0]
...
/Users/hayde/IdeaProjects/wt-159-his-13                                            [feature/159-his-13] prunable
```

**Two worktree conventions, one repository, and only one of them gets a home.**

*What I checked and did not find:* the only consumer of `agentDirOwnedByAHome` is
`project/ProjectionOwnership.foreignAgentTree` (`:154`), whose guard compares
`agentDir.getParent()` against `AgentHomes.agentHomeRoot()`. With
`SKILL_MANAGER_HOME` pointed at the project home those are equal, so the "another
home's agent tree" refusal would *not* protect a path inside the sub-agent worktree.
I could **not** show that reachable: a default-agent projection target is
`<agentDir>/skills/<unit>` (`project/ClaudeProjector.java:56-58`), never
`<agentDir>/worktrees/...`, so no ledger row names one today. Recorded as a
structural observation, not a hazard.

### 2e. The corroboration from a plugin that already lives here

The `cdc-agent-substrate` plugin — installed in this repo's chain and firing its
`SubagentStart` hook in this very session — has already met this and written it
down. `skills/cdc-agent-substrate/SKILL.md:60-61`:

> Treat automatic adoption of Claude-created isolated worktrees as outside this
> MVP **unless their creation is routed through the Skill Manager lifecycle**.

It is the only plugin in the chain registering a `SubagentStart` hook
(`hooks/hooks.json`, `SessionStart` matcher `startup|resume|fork` at 540s and
`SubagentStart` with no matcher at 20s). The `skt` plugin — the one that would
orient a sub-agent to its home — registers **`SessionStart` and `PostToolUse` only**
(`skill-publisher-skill/hooks/hooks.json`). And `skt-session-start.sh`'s own logging
returns early when `SKILL_MANAGER_HOME` is unset
(`log_line()`: `[ -n "${SKILL_MANAGER_HOME:-}" ] || return 0`), which is the
sub-agent's state.

### 2f. Claude Code — requirement list

| # | requirement | met today? |
| --- | --- | --- |
| C1 | A sub-agent must be able to learn which home it is bound to, without an env var it does not have | **no** — `skt find_home` walks to the *project* home, `SkillStore.defaultStore()` answers the *root* home; they disagree (§2a, §2c) |
| C2 | The tier of a home must not depend on which checkout asks | **no** — measured, §2c |
| C3 | A checkout created by the harness must be able to get a home derived from the one above it, in one command | **no** — §1b; the only such command is `bootstrap-home.sh`, out of tree |
| C4 | The launch `PATH` guarantee must survive the harness's own plugin `PATH` injection | **no** — nine foreign entries measured, §2b |
| C5 | Registering a home's marketplace must not accumulate absolute host paths in a config directory outside that home | **no** — eight rows measured, §2b |
| C6 | Gitignored home state must reach a harness-created worktree by a path that re-anchors it | **partly** — `.worktreeinclude` copies gitignored files verbatim, which is exactly the `rsync`/image-build case `home verify` warns about (`HomeCommand.java:166-168`); no `.worktreeinclude` exists in this repo |
| C7 | A sub-agent must be identifiable as one, so a hook can decide | **yes, harness-side** — `SubagentStart` carries `agent_id`/`agent_type`/`cwd`; skill-manager consumes none of it |

---

## 3. Codex

**How it runs sub-agents:** could not be determined from anything in this repo or in
the installed units. There is no Codex sub-agent concept modelled anywhere:
`grep` over `src/main/java` returns no `subagent`, and the `cdc-agent-substrate`
plugin — which ships a `.codex-plugin/plugin.json` alongside its Claude one —
detects a sub-agent purely from the **`hook_event_name` field of the hook payload**
(`hooks/cdc-worktree.py:178,199-202`), with no per-harness branch, no env sniffing,
and no `CLAUDE_CODE_CHILD_SESSION` read. That plugin's `.codex-plugin/plugin.json`
long description asserts *"Session startup may idempotently ensure the worktree
daemon; subagents only reuse its binding"*, so Codex does deliver a comparable
event; I did not verify that against Codex's own documentation and do not claim it.

**What skill-manager gives Codex today:**

- `CODEX_HOME` → `<homeRoot>/.codex` (`agent/CodexAgent.java:44-46`, `AgentHomes.java:52,303`)
- skills only — `project/CodexProjector.java:49-54` returns empty for `UnitKind.PLUGIN`
- `config.toml`: the gateway entry as `[mcp_servers.virtual_mcp_gateway]`
  (`mcp/McpWriter.java:368-383`) and the marketplace as `[marketplaces.<name>].source`
  (`project/HarnessPluginCli.java:489-528`)
- `codex plugin marketplace add|remove` and `codex plugin add <n>@<mkt>`
  (`HarnessPluginCli.java:417-460`), with `CODEX_HOME` exported to the subprocess (`:467-478`)
- `AGENTS.md` for doc-repo import directives (`bindings/DocRepoBinder.java:158-170`)
- a `bin/launch/codex` shim (`launch/LauncherShims.java:113`)

**Two concrete gaps found:**

- **The launch redirect refusal is Claude-only.** `ExecCommand.call` invokes
  `launch.requireClaudeRedirected()` and nothing else
  (`commands/ExecCommand.java:139`); `LaunchEnv` declares exactly one such guard
  (`:400-417`). `skill-manager exec -- codex` and `-- gemini`, and therefore
  `bin/launch/codex` and `bin/launch/gemini`, launch with **no** equivalent refusal
  when `CODEX_HOME` / `GEMINI_HOME` resolve outside the home. `Confinement` does
  treat all three as axes (`sandbox/Confinement.java:158-160`), so the asymmetry is
  in the launch gate, not in the model.
- **Codex uninstall is a documented no-op** — `HarnessPluginCli.java:463-466` returns
  `"[codex: uninstall via /plugins UI]"`. So a Codex home accumulates marketplace
  registrations with no removal path at all, which is C5 without even the Claude
  half of a remedy.

**Codex requirement list:** C1–C4 as for Claude (same mechanism, same absence), plus

| # | requirement | met today? |
| --- | --- | --- |
| X1 | A launch that leaves `CODEX_HOME` outside the home must be refused, as the Claude one is | **no** |
| X2 | A marketplace registration must be removable | **no** — documented no-op |
| X3 | Startup orientation must reach a Codex sub-agent | **no, by design** — `skill-publisher-skill/references/harness-capabilities.md:11` records Codex's disclosure mode as **"instruction"**: *"no hook runtime; the projected skt skill plus the AGENTS.md snippet tell the agent to run `skt status` first."* An instruction a sub-agent never reads is not a binding. |

---

## 4. Gemini

Thinner still, and honestly so.

- `GEMINI_HOME` → `<homeRoot>/.gemini` (`agent/GeminiAgent.java:41-43`)
- skills only; plugin projection is *deliberately* unsupported —
  `GeminiAgent.java:15-18`: "plugin projection is deliberately unsupported until
  Gemini extension mapping is modeled"; `project/GeminiProjector.java:16-24` returns
  empty for `UnitKind.PLUGIN`
- MCP config is `<geminiHome>/settings.json`, JSON-merged (`mcp/McpWriter.java:337,427-433`)
- `GEMINI.md` for doc-repo imports (`bindings/DocRepoBinder.java:~167`)
- a `bin/launch/gemini` shim, and **no** `HarnessPluginCli` driver at all — the class
  has `Claude` and `Codex` only

`references/harness-capabilities.md:12` records Gemini as *"skills projection only
(no plugin runtime)"*, instruction-mode disclosure.

**Gemini sub-agents: could not be determined.** I found nothing in this repo, in the
installed units, or in the plugins that describes a Gemini sub-agent mechanism, and I
did not go to Gemini's own documentation because the ticket scopes others as
"recorded as found". Recorded as unknown rather than guessed. Requirements C1–C4 and
X1 apply by construction (same launch path); the plugin-side ones do not, because
there is no plugin runtime to bind.

---

## 5. ACP — the one harness that *can* be handed an environment

No ACP code exists in this repo: **zero** hits for `agent-client-protocol`,
`claude-agent-acp`, or `codex-acp`. The `acp` hits in `src/main/java` are incidental
mentions of the skill's disk footprint (`artifacts/ArtifactPrune.java:299`,
`bindings/ChildHomeMaterializer.java:542-543`, `store/HomeCloner.java:706,849`).

The `acp-cdc-ai-python` skill is installed in the operator's root home
(`/Users/hayde/.skill-manager/skills/acp-cdc-ai-python/`). From its `SKILL.md` and
`references/harness-parameters.md`:

- It is an **OpenAI-compatible HTTP server in front of an ACP agent**, routing by
  model prefix: `CLAUDE_*` → `claude-agent-acp` → `claude code`; `OPEN_AI_*` →
  `codex-acp` → `codex`; `GEMINI_*` → `gemini --acp`; `OLLAMA_*` → `claude-agent-acp`
  re-pointed at a local daemon.
- `POST /v1/harness-greeting` creates a session from `model`, `messages`,
  **`working_directory`**, **`env`**, and `mcp_servers` — `working_directory` becomes
  the ACP cwd and `env` is a **process environment override**.
- Child processes are spawned through `setsid` so `killpg` reaps the wrapper plus the
  vendor CLI it forks; state lives in `<project-root>/.acp-server/{server,agents}.json`.
- It installs no vendor CLI: *"the spawned wrappers inherit the launcher's PATH."*
- Its manifest declares `cli_dependencies` `brew:claude`, `brew:codex`,
  `npm:@google/gemini-cli`, `brew:ollama` — it is the one unit that pulls all three
  vendor CLIs into a home.
- Its own trust statement: *"no cryptographic receipt, executable attestation,
  sandbox or provider-egress boundary, or multi-user isolation … treat all episode
  inputs — especially `workingDirectory`, `env`, and `mcpServers` — as trusted
  configuration."*

**This is the only surface surveyed where a caller can hand a launched agent an
arbitrary environment**, i.e. can set `SKILL_MANAGER_HOME`, `CLAUDE_CONFIG_DIR`,
`CODEX_HOME`, `GEMINI_HOME` per session. It is therefore the only one where
skill-manager's existing contract composes without a new mechanism — **and the skill
never names those variables.** The thing skill-manager would have to expose is not
code; it is the *already-existing* `home.runtime.json` `env` block
(`store/HomeDescriptor.java:102-124`) as a documented, machine-readable payload that
an ACP caller can drop into `env` verbatim.

That is a support-not-drive shape: skill-manager publishes the env block for a home,
somebody else's launcher consumes it. `home describe --json` already prints it. What
is missing is the *statement* that this is the contract, and a check that it is
complete — see GAP-5.

| # | requirement | met today? |
| --- | --- | --- |
| A1 | Per-session env override that can carry the four home axes | **yes, harness-side** — `env` on `/v1/harness-greeting` |
| A2 | A published, machine-readable env payload for a home | **yes** — `home describe --json` / `home.runtime.json` `env` |
| A3 | That payload stated as the contract, and asserted complete | **no** — see GAP-5 |

---

## 6. The gaps skill-manager cannot currently meet

The ticket asks for **at least one**. Five, ordered by how hard they are to argue with.

### GAP-1 — A home's tier is a function of the asking checkout, not of the home

**Measured**, fixture B in §2c. `$SP/wt-ticket/.skill-manager` is `worktree` (parent:
the project home) from its own checkout and `project` (parent: the **operator root
home**) from a Claude Code sub-agent worktree inside it. `skt publish` sends
`home sync --from … --to <parent> --merge` at
`skill-publisher-skill/src/skt/publish.py:133` with that parent.

Cause: `classify_tier` (`context.py:53-61`) conjoins `is_linked_worktree(root)` with
`_inside(home, root)`, and a sub-agent worktree is a linked worktree whose home is
one level up. Java has no three-way classifier to disagree with it (SUB-0 §2), so
there is no second opinion.

This is **not** a container finding. It reproduces on the host, at `cd9b1db`, on
Claude Code's own documented worktree convention.

### GAP-2 — There is no command that provisions a home for a checkout the harness just created

**Read**, §1b. Every home-to-home command requires both ends to be named; the one
end-discovering command (`project resolve`) needs a `skill-project.toml` and still
takes its parent from the environment. The command that does the job —
`bootstrap-home.sh` — is not in this repo, is located by walking to
`<home>/skills/git-issue-workflow/scripts/` (`skt/ticket.py:51-56`), and is what
`skt ticket new` shells out to (`ticket.py:114`).

Container consequence, and it is the direct one for this epic: an image that carries
a checkout and a home does **not** thereby carry the ability to make a second home
for a second checkout. That capability lives in a unit that must have been installed
into the home first. Nothing states that as a requirement of the image.

### GAP-3 — The launch `PATH` guarantee is a one-shot, and the harness overwrites it

**Measured**, §2b: nine `bin/` directories from three homes, including a home in an
unrelated repository, on one sub-agent's `PATH`. `LaunchEnv.isForeignHomeBin` would
strip all nine; it ran, if at all, before Claude Code loaded its plugins.

skill-manager exposes no way to ask, from inside a running session, *"is my `PATH`
still the launch `PATH`?"* — `LaunchEnv.effectivePath` is package-private
(`:196`), `exec --print-env` prints what a launch **would** be rather than auditing
what a session **has**, and `home verify` checks the home's contents, not the
process. The one instrument that could catch this does not exist.

### GAP-4 — Marketplace registration accumulates absolute host paths outside every home, with no reverse

**Measured** (the registry) plus **read** (the writer). Eight skill-manager
marketplaces in `~/.claude/plugins/known_marketplaces.json`, each an absolute host
path to a different project home's `plugin-marketplace/`; `installed_plugins.json`
keys project-scope installs by absolute `projectPath`, several of which are deleted
temp directories.

skill-manager writes those rows (`HarnessPluginCli.java:288-291,453-457`, both
`--scope user`) and has **no reader and no remover**: `grep -rn "known_marketplaces" src/main/java`
returns nothing, and the Codex side's uninstall is a documented no-op (`:463-466`).

In a container every one of those eight paths is dangling. More to the point for
SUB-0's boundary rule (`store/HomePaths.java:13-19` — every absolute path a home
carries points outside it): this is a set of absolute paths **naming homes**, held
*outside every home*, that no home-relocation mechanism can see. `home clone`'s
isolation oracle cannot report it, `HomeCloner.remapPath` cannot re-root it, and
`home verify` does not look at it.

### GAP-5 — The four home axes are not a stated, checkable interop contract

**Read.** `home.runtime.json`'s `env` block is exactly
`SKILL_MANAGER_HOME`, `CLAUDE_CONFIG_DIR`, `CLAUDE_HOME`, `CODEX_HOME`, `GEMINI_HOME`
(`store/HomeDescriptor.java:102-124`, derived at `:192-201`). That five-key map is
precisely what an ACP caller's `env`, a container's `ENV` lines, or a `docker run -e`
would need — and precisely what `HomeScaffold.java:30-32` records a sub-agent getting
*half* of, at real cost:

> the same class of bug as the incident in this epic where a subagent running an
> unrelated command with **only `SKILL_MANAGER_HOME` redirected** projected 15
> symlinks into the operator's three real agent homes.

The resolution rule was fixed for that case (`AgentHomes.agentHomeRoot()`,
`:254-262`, derives the agent roots from `$SKILL_MANAGER_HOME`), so the *derivation*
no longer splits. What is still missing is the statement that **all five travel
together or none do**, and any check that they did. `bootstrap-home.sh` reads them
back out of `home describe --json` and rebuilds them
(`bootstrap-home.sh:692,710-726`, read in the installed home) — out-of-tree, and the
only implementation.

---

## 7. What this refines in SUB-0

| SUB-0 claim | this ticket |
| --- | --- |
| §2, tier derivation: the container break is *"a container gets a checkout by `git clone`, not `git worktree add`"*, plus the orphaned-worktree case where `_git` swallows failure | **Refined, and the diagnosis widened.** Git plumbing is not required to fail. Fixture A keeps `is_linked_worktree: True` and still classifies `project`, because `_inside(home, root)` is the second conjunct. The tier breaks on the **host**, today, on Claude Code's documented worktree convention — no container needed. |
| §2: *"the `worktree` tier is unreachable in a container that clones"* | **Sharpened.** It is also unreachable in a *sub-agent worktree on the host*, and there the failure is worse: the home is correct and bound, the checkout is a genuine linked worktree, and the tier is still wrong. |
| §7, agent projections, disposition **needs-contract**: *"a home's projections are valid only where the home root and its three sibling agent dirs sit at the paths the descriptor names"* | **Confirmed and extended.** The sibling `.claude` also hosts the harness's own worktrees (`<repo>/.claude/worktrees/`), so `agentDirOwnedByAHome` classifies whole foreign checkouts as inside the home's agent tree (§2d). The contract SUB-0 asks for must say what an agent directory may contain, not only where it sits. |
| §7's second gap: *"`rsync`ing or `docker cp`ing a home instead of cloning it skips `remapPath` entirely"* | **A third vector named.** `.worktreeinclude` is a harness feature that copies gitignored files into a new worktree; `.skill-manager` is gitignored (`.gitignore:81`). Using it to carry a home is a verbatim copy that skips `remapPath` exactly as `rsync` does. No `.worktreeinclude` exists in this repo. |
| §8's closing line: *"Not carried: agent login state. The `.claude`/`.codex`/`.gemini` dirs are siblings of the store and outside `home clone`'s source tree entirely"* | **Confirmed and made concrete.** `~/.claude/plugins/known_marketplaces.json` is agent-directory state that names **eight** Skill Manager homes by absolute path, is written by skill-manager (`HarnessPluginCli.java:288-291`), and is readable/removable by neither. That is the #263 axis with a measured artefact. |

**Nothing in SUB-0 was refuted.** Its facts held everywhere I re-checked them
(`AgentHomes.java:288-292`, `LauncherShims.java:113`, `HomeCommand.java:1281-1285`,
`HomePaths.java:13-19`, `SkillStore.java:92-98`). Two of its claims got wider than it
drew them, which is recorded above rather than as a contradiction.

---

## 8. What I could not determine

Written down as findings, not rounded to guesses.

1. **Whether Claude Code's sub-agent controller is a separate OS process.** The docs
   state that *tool calls* run in subprocesses carrying `CLAUDE_CODE_CHILD_SESSION`;
   they do not state the controller's execution model. Every conclusion here depends
   only on the measured environment of tool calls, which is the thing that matters,
   but the claim "same process" is **not** asserted.
2. **How Codex and Gemini launch sub-agents.** Nothing in this repo, in the installed
   units, or in the plugins says. The `cdc-agent-substrate` plugin ships a Codex
   manifest asserting subagent semantics, which implies Codex delivers a comparable
   event, but I did not verify that against Codex's documentation and the ticket does
   not ask me to.
3. **Whether the eight `known_marketplaces.json` rows were produced by
   `HarnessPluginCli`.** All the mechanism is read; the registration itself was not
   reproduced, because it writes a real home. Strongly-supported inference.
4. **Whether Claude Code prepends or appends plugin `bin/` to the Bash `PATH`.** The
   docs say prepend; my measurement has them trailing, which the login shell's own
   prepends explain. Either way nine foreign entries are present, which is what GAP-3
   turns on.
5. **What `.worktreeinclude` does to a `.skill-manager` directory in practice.** Not
   run — it would copy a real home. The documented behaviour (copy gitignored files
   into the worktree) plus `.gitignore:81` is the whole basis for the C6 row.
6. **Whether any container image today provisions a home at all.** SUB-0 already
   answered this for the tree (`Dockerfile.server` builds the registry-server fatjar
   and never creates a home); I found nothing to add.

---

## 9. Reproducing the measurements

All fixtures write only into a scratch directory. **No real Skill Manager home is
written by any of them.** The `~/.claude/plugins/*.json` and `~/.skill-manager/bin/cli/*`
observations are reads.

**The tier divergence (GAP-1), fixture B — the sharp one:**

```sh
SP=$(mktemp -d)
mkdir -p "$SP/main"; git -C "$SP/main" init -q
git -C "$SP/main" -c user.email=a@b -c user.name=t commit -q --allow-empty -m init
mkdir -p "$SP/main/.skill-manager/installed" "$SP/main/.skill-manager/skills"
git -C "$SP/main" worktree add -q "$SP/wt-ticket" -b feature/t1
mkdir -p "$SP/wt-ticket/.skill-manager/installed" "$SP/wt-ticket/.skill-manager/skills"
git -C "$SP/wt-ticket" worktree add -q "$SP/wt-ticket/.claude/worktrees/agent-cafe" -b agent-cafe
mkdir -p "$SP/fakeroot/.skill-manager"

cd "$SP/wt-ticket/.claude/worktrees/agent-cafe"
SP="$SP" PYTHONPATH=<repo>/skill-publisher-skill/src \
SKT_ROOT_HOME="$SP/fakeroot/.skill-manager" \
SKILL_MANAGER_HOME="$SP/wt-ticket/.skill-manager" python3 - <<'PY'
import os; from pathlib import Path
from skt import homes, context, publish
SP = os.environ["SP"]; s = lambda p: str(p).replace(SP, "$SP")
cwd = Path.cwd(); h = homes.find_home(cwd); root = context.checkout_root(cwd)
print("classify_tier (from sub-agent) :", context.classify_tier(h, root))
print("_parent_home  (from sub-agent) :", publish._parent_home(h, cwd))
r2 = Path(SP) / "wt-ticket"
print("classify_tier (from worktree)  :", context.classify_tier(h, context.checkout_root(r2)))
print("_parent_home  (from worktree)  :", publish._parent_home(h, r2))
PY
# classify_tier (from sub-agent) : project
# _parent_home  (from sub-agent) : ($SP/fakeroot/.skill-manager, None)
# classify_tier (from worktree)  : worktree
# _parent_home  (from worktree)  : ($SP/main/.skill-manager, None)
```

**The `PATH` audit (GAP-3)** replays `LaunchEnv.looksLikeStoreRoot` (`:326-330`) and
`agentDirOwnedByAHome` (`:357-373`) in Python over `$PATH`; it is a pure read and the
script is reproduced inline in §2b's result. Run it from any Claude Code session.

**The marketplace registry (GAP-4):**

```sh
python3 -c 'import json;d=json.load(open("'"$HOME"'/.claude/plugins/known_marketplaces.json"));
[print(k,"->",v["installLocation"]) for k,v in d.items()]'
```

---

## 10. Goal contribution

Both of this ticket's goals are **enabling**, decided by SUB-6.

`GOAL-every-machine-local-assumption-is-named` — the ticket's `local_signal` is
*"at least one harness requirement that skill-manager cannot currently meet, named
concretely."* Five are named in §6, each with `file:line` evidence, two of them
(GAP-1, GAP-3) measured rather than read. The `expected_effect` — *"a per-harness
requirement list feeding SUB-4's gap list"* — is §2f (C1–C7), §3 (X1–X3), §4, §5
(A1–A3).

`GOAL-support-without-driving` — same signal, same list. The baseline number that
must not move is **Substrate-driving APIs = 0**. This ticket adds no production code
of any kind; `git diff --stat` against `cd9b1db` shows this document and one backlog
append. Every remedy suggested above is a thing skill-manager *publishes* — an env
block, a tier answer, a `PATH` audit, a marketplace de-registration — consumed by a
harness or a bootstrap script that skill-manager does not run.

The rows most likely to move under SUB-4 are GAP-1 (it is a five-line change in
`classify_tier` with a large blast radius, and SUB-2 owns what the tiers *should*
mean) and GAP-2 (which is really the question of whether `bootstrap-home.sh` should
be a `skill-manager` subcommand — a decision, not a defect).

Three findings outside this slice are filed as `DEF-SUB3-001..003` in
`results/epic-substrate-home-model/deferred/backlog.yaml`.
