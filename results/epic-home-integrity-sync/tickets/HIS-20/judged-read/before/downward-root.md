# BEFORE — DOWNWARD, ROOT tier

- **Direction:** downward (root → project → worktree; how work is handed out)
- **Tier:** root — `SKILL_MANAGER_HOME=/Users/hayde/.skill-manager`
- **Corpus:** 193 md files, ~2.6 MB; the root home's `skills/**` and `plugins/**`
  front-door and reference markdown, copied read-only. 20 skills + 3 plugins,
  including `skt`, `skill-manager`, `git-issue-workflow`, `git-epic-workflow`.
- **Date:** 2026-08-24. Corpus state: the operator's real root home, before any
  documentation change.

---

**D-Q1.** Which tier is `/Users/hayde/.skill-manager`, what other tiers exist, and how would an agent determine which tier its session is writing?

```
ANSWER: It is the ROOT tier — `~/.skill-manager`, "where the operator installs". The other two tiers are project (`<repo>/.skill-manager`, one per repository, gitignored) and worktree (`<worktree>/.skill-manager`, one per ticket, gitignored). Copies flow downward: root -> project (via `project resolve` / `home clone`) -> worktree (via `home clone` at `ticket new`). An agent determines which tier its session writes by running `skt status`, which reports "which home tier this session writes (root ~/.skill-manager, project <repo>/.skill-manager, or a ticket worktree home)"; in Claude Code the skt plugin's SessionStart hook injects that report automatically. `skill-manager home describe --json` and `<skill-manager-skill>/scripts/env.sh --pretty` are the lower-level ways to see the active `SKILL_MANAGER_HOME`.
  Caveat stated in the corpus and worth carrying: "Any Skill Manager home can itself be a parent. Do not assume there is a distinguished global root."
SOURCE: skills/skill-manager/references/projects.md — "root       ~/.skill-manager              where the operator installs / project    <repo>/.skill-manager         one per repository, gitignored / worktree   <worktree>/.skill-manager     one per ticket, gitignored". plugins/skt/skills/skt/SKILL.md — "| root | `~/.skill-manager` | operator installs; `skt sync` | publish local edits globally (`skt check` prompts here) |" and "which home tier this session writes (root ~/.skill-manager, project <repo>/.skill-manager, or a ticket worktree home)". skills/skill-manager/references/projects.md — "Any Skill Manager home can itself be a parent. Do not assume there is a distinguished global root."
  INFERRED, not stated: that `/Users/hayde` is `$HOME`, i.e. that `/Users/hayde/.skill-manager` literally IS `~/.skill-manager`. The corpus never states this user's `$HOME`; it only ever writes the root tier as `~/.skill-manager`. (One corpus file does reference `/Users/hayde/IdeaProjects/tla-spec-dev`, which is consistent but not proof.)
CONFIDENCE: high (tier model), medium (that this specific path is the root tier, since $HOME is inferred)
HOW I FOUND IT: Grepped the corpus for "home tier"/"which tier", opened plugins/skt/skills/skt/SKILL.md ("The three-tier home model, in one table") and skills/skill-manager/references/projects.md ("Homes Come In Tiers, And Every Tier Is A Copy").
```

---

**D-Q2.** What does this tier inherit from the tier above it, and by what mechanism?

```
ANSWER: There is NO tier above root. Root is the top of the three-tier model — project homes are copies of root and worktree homes are copies of project; nothing copies INTO root. What fills that role for root is the unit's own git repository: root is populated by "operator installs" (`skill-manager install github:owner/repo`, `skill-manager onboard` for the bundled set) and refreshed by `skt sync <unit>` / `skill-manager sync` (which wraps `sync --git-latest`) pulling a unit to its latest pushed source. The reverse direction — getting a root-home edit out to that upstream — is `skill-manager unit publish <unit>`, which commits to `skill/<ticket>-<unit>` in the unit's own repository and opens a PR; that is "the only route that reaches a sibling project or outlives this machine". `skill-manager home sync` moves an edit exactly one tier up and therefore has no destination from root.
  Note the migration doc treats root as the top explicitly: "Copies flow down (root -> project -> worktree) and never update themselves, so migrate top-down", step 1 being "Root home (the operator's ~/.skill-manager)".
SOURCE: skills/skill-manager/references/projects.md — "Downward is easy: it is a copy, and `project resolve` / `home clone` do it. **Upward is the whole difficulty**"; and the `unit publish` row "Reaches | the tier above, on this machine | every project, on every machine". plugins/skt/references/migration.md — "Copies flow **down** (root → project → worktree) and never update themselves, so migrate top-down: # 1. Root home (the operator's ~/.skill-manager): skill-manager install github:haydenrear/skill-publisher-skill". plugins/skt/skills/skt/SKILL.md — "| root | `~/.skill-manager` | operator installs; `skt sync` | publish local edits globally |".
  INFERRED, not stated: the phrasing "root is the top / nothing copies into it" is my synthesis; no single sentence says "root has no parent". The corpus in fact cautions that "Any Skill Manager home can itself be a parent", so a home ABOVE root is not architecturally impossible — it is just not part of the documented three-tier machine layout.
CONFIDENCE: high
HOW I FOUND IT: Grepped for "one tier up"/"up a tier" and read the `home sync` vs `unit publish` table in skills/skill-manager/references/projects.md plus "Install order across the home tiers" in plugins/skt/references/migration.md.
```

---

**D-Q3.** What may I change here? What is legitimately editable in this home and what does editing it mean?

```
ANSWER: Legitimately editable in a home:
 1. Unit content in the store — `<home>/skills/<unit>/`, `<home>/plugins/<unit>/`, `<home>/docs/<name>/`, `<home>/harnesses/<name>/`. Editing a unit inside a home is a first-class, expected act ("An agent that improves a unit does it *inside a home*"), but the edit is in NO git diff, no PR and no fan-out. At the ROOT tier the stated obligation is "publish local edits globally" — i.e. `skt publish` / `skill-manager unit publish <unit> --ticket <t>`, which commits to `skill/<ticket>-<unit>` in the unit's own repo and opens a PR. `home sync` (one tier up) has no meaning from root.
 2. Home policy — `<home>/home.policy.toml`, via `skill-manager home policy [live|frozen]`. `live` = may be synced, upgraded, pushed back from; `frozen` = the home is evidence and is never modified in place.
 3. Drift acknowledgement — `skill-manager home drift --ack` (or `--ack-drift` on one `exec`).
 4. `~/.skill-manager/policy.toml` — editable but explicitly gated: "do not loosen `~/.skill-manager/policy.toml` without explicit instruction".
 5. `home.runtime.json` and `bin/launch/*` shims are regenerated by `home describe --write` / `home shims`, not hand-edited; `<home>/bin/cli/skill-manager` in particular is written by `home shims` alone ("`skill-manager home shims` writes it. `bootstrap-home.sh` does not.").
 NOT editable as a source of truth: generated project `.skill-manager/` state ("do not edit generated `.skill-manager/` state as the source of truth"), generated env files ("Update the manifest, then re-run the CLI instead of editing generated env files by hand"), and managed doc-bind import blocks (use `unbind`/`rebind`, "instead of deleting managed import blocks manually").
 What "editing it" means, in one sentence from the corpus: a home is gitignored/outside any repo, so an edit inside it is invisible to `git status` and to every diff, survives only where you deliberately move it, and is destroyed silently when the directory is removed.
SOURCE: skills/skill-manager/references/projects.md — "An agent that improves a unit does it *inside a home*. The home is gitignored, so that edit is in no diff, no PR, and no fan-out, and it is deleted with the directory."; "`policy [live|frozen]` | Declare a home mutable, or evidence."; skills/skill-manager/SKILL.md — "The generated files are projections of that manifest plus the resolved lock state; do not edit generated `.skill-manager/` state as the source of truth." and "do not loosen `~/.skill-manager/policy.toml` without explicit instruction." plugins/skt/skills/skt/SKILL.md — root tier obligation: "publish local edits globally (`skt check` prompts here)".
CONFIDENCE: high
HOW I FOUND IT: Read skills/skill-manager/SKILL.md and references/projects.md end-to-end; cross-checked the root-tier obligation column in plugins/skt/skills/skt/SKILL.md.
```

---

**D-Q4.** What must I NOT touch above me?

```
ANSWER: For a session standing at the ROOT tier there is nothing above it, so the corpus's "do not write the tier above" prohibitions do not apply to you — they are all written for agents standing in a project or worktree home. What the documents DO forbid or discourage, and which are relevant here:
 - `~/.skill-manager` is never a teardown target: "The global `~/.skill-manager` is never a teardown target. Nothing in this flow writes it; `bootstrap-home.sh` refuses outright if a target home would resolve to it." (This protects root from below.)
 - Do not write OTHER homes. `home sync --from` is "**never written**". A worktree agent is told: "Do **not** run `skill-manager home sync` into the project home" during a wave, because that home is one shared destination and concurrent tickets race there.
 - A **frozen** home is never modified — "not re-shimmed, not re-described, not re-baselined" — anywhere in the flow.
 - Never run `install`, `sync`, `bind`, `upgrade` or `project resolve` before a local home exists / while `SKILL_MANAGER_HOME` points somewhere you did not intend: "they write into whatever `SKILL_MANAGER_HOME` names, which until then is the operator's global home." From root this is the mirror hazard — a command you meant for a project home lands in root.
 - Do not loosen `~/.skill-manager/policy.toml` without explicit instruction; do not bypass a `BLOCKED`/`CONFLICT` plan with `--yes`.
 - Do not hand-loop `git worktree remove --force` or `git worktree remove` — "that is `rm -rf` with extra steps: it deletes each worktree's Skill Manager home, which is gitignored, so the loss appears in no diff".
 - `unit publish` opens a PR against the unit's trunk; pushing straight to the base is the named override `--direct`, not the default.
SOURCE: skills/git-issue-workflow/references/skill-homes.md — "The global `~/.skill-manager` is never a teardown target. Nothing in this flow writes it; `bootstrap-home.sh` refuses outright if a target home would resolve to it." and "A home that is **already frozen is never modified**". skills/skill-manager/references/projects.md — "`--from` is **never written**." skills/git-issue-workflow/references/epic-ticket.md — "Do **not** run `skill-manager home sync` into the project home." plugins/skt/skills/skt/SKILL.md — "**Do not hand-loop `git worktree remove --force`**". skills/skill-manager/SKILL.md — "do not loosen `~/.skill-manager/policy.toml` without explicit instruction."
  INFERRED, not stated: "there is nothing above root, so nothing above you is forbidden" — the corpus never addresses a root-tier agent's upward prohibitions directly.
CONFIDENCE: medium-high (the individual prohibitions are quoted verbatim; their application to a root-tier agent is my reading)
HOW I FOUND IT: Grepped for "operator's global"/"~/.skill-manager"/"never" across the corpus and read the teardown and policy sections of skills/git-issue-workflow/references/skill-homes.md.
```

---

**F-Q1.** Which environment variable(s) must I set to make a CLI command act on THIS home? Is `SKILL_MANAGER_HOME` alone sufficient?

```
ANSWER: No — `SKILL_MANAGER_HOME` alone is explicitly NOT sufficient, and the corpus says assuming it is "has cost this epic real time". You need, together:
 - `SKILL_MANAGER_HOME` — where install/sync/bind/upgrade/project resolve write;
 - `CLAUDE_CONFIG_DIR` **and** `CLAUDE_HOME` (they carry the same value) — because skills load from Claude's config dir, so an unredirected one "is not a warning, it is the wrong home"; `skill-manager exec` refuses a launch where they still resolve outside the home;
 - `CODEX_HOME` and `GEMINI_HOME` — the other agent homes. Concretely: `SKILL_MANAGER_HOME=<project>/.skill-manager CODEX_HOME=<project>/.codex CLAUDE_HOME=<project>/.claude GEMINI_HOME=<project>/.gemini`.
 - `SKILL_MANAGER_INSTALL_DIR` is in the same authoritative env set (`SmEnv.java` puts `SKILL_MANAGER_HOME`, `SKILL_MANAGER_INSTALL_DIR`, `CLAUDE_HOME`, `CLAUDE_CONFIG_DIR`, `CODEX_HOME`, `GEMINI_HOME` into child environments).
 - PATH ORDER, which no variable can fix: "`skill-script` CLI deps are generated shell scripts with a home's absolute path in the body. No variable redirects those, so the launch PATH puts this home's `bin/` first **and removes other homes' `bin/`**."
 The documented right answer is therefore NOT to export by hand: "**Launch agents through the shims** (`<home>/bin/launch/{claude,codex,gemini}`) (or through `skill-manager exec`) and you inherit the whole contract; export variables by hand and you get the part you remembered." A concrete measured failure of the half-measure: running `sync` with `SKILL_MANAGER_HOME` alone ends in a binding step that writes the operator's `~/.claude.json`, `~/.codex/config.toml` and `~/.gemini/settings.json`.
 One caveat specific to your situation: since this home IS `~/.skill-manager` (the root/operator home), "act on this home and not the operator's global one" has no distinct target here — they are the same home.
SOURCE: skills/git-issue-workflow/references/skill-homes.md § "Where the isolation actually comes from" — "`SKILL_MANAGER_HOME` alone is **not** enough, and assuming it is has cost this epic real time"; "**Launch agents through the shims** (or through `skill-manager exec`) and you inherit the whole contract; export variables by hand and you get the part you remembered."; and "Run it with the **agent-home variables set**, not with `SKILL_MANAGER_HOME` alone. `sync` ends in a binding step, and with `CLAUDE_CONFIG_DIR`, `CODEX_HOME` and `GEMINI_HOME` unset that step writes the operator's `~/.claude.json` …". skills/skill-manager/SKILL.md lines 77-82 for the four-variable launch block.
CONFIDENCE: high
HOW I FOUND IT: Read skills/git-issue-workflow/references/skill-homes.md § "Where the isolation actually comes from"; cross-checked the launch env block in skills/skill-manager/SKILL.md and references/projects.md.
```

---

**F-Q2.** There is a `skill-manager` executable on my `PATH`. Is that the right binary?

```
ANSWER: Not necessarily, and the corpus treats a bare `skill-manager` off PATH as the wrong default. Documented facts:
 - "Every remedy names a **resolved CLI path**, not a bare `skill-manager`. A bare `skill-manager` on a machine with an older release first on PATH exits 2 for the operator who copy-pastes it, and that is the machine this was found on." The named stale release is 0.19.2.
 - The newer `home clone|shims|policy|describe|close-out` and `exec` subcommands "are newer than the released CLI". The released 0.19.2 has no `exec` subcommand, so a PATH build produces "Unmatched arguments: 'exec'".
 - Resolution order (`HomeDescriptor.resolveCli`, and `bootstrap-home.sh`'s `pick_cli`): `$SKILL_MANAGER_CLI`; the running process's own command when it looks like a launcher; `<store>/bin/cli/skill-manager` (the home's pin); then a bare `skill-manager` off PATH — PATH is LAST.
 HOW TO TELL: probe the CLI's HELP TEXT, not its exit status. "The capability probe reads the CLI's help **text**, not its exit status … the released 0.19.2 answers `home close-out --help` by printing top-level usage and **exiting 0**. A status-only probe would accept a CLI with no `close-out` at all." The analogous probe for the scripts asks "whether `--help` names `--allow-unprojected`". Also note: inside an agent session launched by `skill-manager exec`, the PATH answer is itself a home's pin ("`exec` puts a pin first on an agent session's `PATH`"), possibly a home you have never heard of — so check what it resolves to, do not judge by name.
 IF IT IS NOT THE RIGHT ONE: use a resolved path — this home's own pin `<home>/bin/cli/skill-manager` (that slot "decides which build every launch from the home runs"; `skill-manager home shims` is its only writer) — or pin `SKILL_MANAGER_CLI` at a real skill-manager **launcher**. Critical trap: never point `SKILL_MANAGER_CLI` at a home's `bin/cli/skill-manager` shim; the shim body is `cli="${SKILL_MANAGER_CLI:-<absolute>}"` + `exec "$cli" "$@"`, so it "re-execs **forever** — no output, no exit, one process burning a core" (measured: 7:03 of CPU over 13:06 wall clock from one teardown). Scrub an inherited value rather than merely not setting one; decide per candidate by reading the candidate's own bytes ("does it *expand* `$SKILL_MANAGER_CLI`?").
SOURCE: skills/git-issue-workflow/references/skill-homes.md — "Every remedy names a **resolved CLI path**, not a bare `skill-manager`. A bare `skill-manager` on a machine with an older release first on PATH exits 2 …"; "The capability probe reads the CLI's help **text**, not its exit status … the released 0.19.2 answers `home close-out --help` by printing top-level usage and **exiting 0**."; "#### The pin resolves itself through `$SKILL_MANAGER_CLI`, so do not point that at it".
CONFIDENCE: high
HOW I FOUND IT: Grepped for "PATH" and "0.19.2" in skills/git-issue-workflow/references/skill-homes.md; read §§ "Which `skill-manager` a home uses", "The pin at `<home>/bin/cli/skill-manager`", and "How it degrades".
```

---

**F-Q3.** What does `bootstrap-home.sh` do, what does it do when the target already exists or is not in the expected state, and does it exit with any specific numeric code?

```
ANSWER: WHAT IT DOES — it gives one CHECKOUT (repo root or worktree) its own per-checkout Skill Manager home, in a fixed order: clone the home, export `SKILL_MANAGER_HOME` at the clone (re-asserted before every mutating step), then everything else. It produces `<checkout>/.skill-manager/` (the home, a clone of an existing home), `home.runtime.json`, `home.policy.toml` (`live` by default), `bin/launch/{claude,codex,gemini}` shims, and the `.claude`/`.codex`/`.gemini` agent homes; it materializes agent-home projections from the home's own `installed/<unit>.projections.json` ledger first, then `sync --skip-mcp` only for units the ledger cannot answer; it writes a per-checkout ignore rule into `$GIT_COMMON_DIR/info/exclude` for whatever the run left untracked; it verifies (never rewrites) the `<home>/bin/cli/skill-manager` pin. Invocation:
  `"${SKILL_MANAGER_HOME:-$HOME/.skill-manager}/skills/git-issue-workflow/scripts/bootstrap-home.sh" --root <checkout>`
  A success prints exactly FIVE stderr lines: `home:`, `projected: N of M into each of .claude .codex .gemini`, `verified: N skill(s) servable`, `launch:`, `log: /tmp/bootstrap-home-XXXXXX.log`. Flags named in the corpus: `--root`, `--source`, `--policy frozen`, `--onboard`, `--onboard-gateway`, `--allow-empty`, `--allow-unprojected`, `--no-project`, `--verbose`, `--quiet`, `--force`. `INTEGRATION_BOOTSTRAP_HOME` pins which copy of the script runs; `SKILL_MANAGER_CLI` pins which CLI it uses. With no `--root` it defaults to the nearest enclosing git toplevel.
 ALREADY EXISTS / NOT AS EXPECTED — it is designed to be re-run: an already-bootstrapped home makes it report "already bootstrapped", and it still verifies the CLI pin "on an already-bootstrapped home without `--force`, because the operator has no way to know the slot is wrong". A **frozen** home "is never modified — not re-shimmed, not re-described, not re-baselined — and `bootstrap-home.sh` reports the skip instead of 'repairing' it". A CLI pin whose target is gone is "a refusal, not a repair": it names the missing path and the re-pin command and writes nothing. Dangling links a clone left behind are reported as one counted `warning: N link(s) …` line and it moves on rather than refusing.
 It REFUSES (before creating anything) when: the checkout is a worktree whose project has no home yet; `--source` names anything other than the project home; the target home would resolve to the global `~/.skill-manager`; nothing answers `home clone`. It also prints `home shims`' own refusal verbatim — that refusal is **exit 127** from `RunningCli` when no CLI probe answers.
 NUMERIC EXIT CODES that are documented as bootstrap-home.sh's own:
  - **exit 5** — the home is EMPTY (a clone of an empty home: well-formed, right descriptor and shims, but serves an agent zero skills). It refuses rather than reporting it verified and names `skill-manager onboard`. It refuses instead of running onboard because onboard "clones from github and touches the gateway, which is a contended singleton". `--onboard` runs `onboard --skip-gateway`, `--onboard-gateway` includes the gateway, `--allow-empty` accepts it deliberately. For a WORKTREE, `--onboard` is refused outright — the remedy there is always the project home.
  - **exit 6** — the home exists and has skills but the agent cannot READ them: projected N < M, i.e. missing `.claude`/`.codex`/`.gemini` symlinks. It names every missing link plus the `sync --skip-mcp` that would create it. `--allow-unprojected` accepts one deliberately (still never as `verified:`); `--no-project` skips projection entirely.
 NOT bootstrap-home.sh's codes (do not confuse them): exit **3** and **7** are `wt new`'s (no project home yet / base branch behind its remote); exit **8** is the change-awareness gate in `skill-manager exec`; exit **4** is `close-change.sh` refusing a teardown; exits **0/1/2/9** are `skill-manager home close-out`'s verdicts; **127** is `home shims`/`RunningCli`.
SOURCE: skills/git-issue-workflow/references/skill-homes.md — "`bootstrap-home.sh` **refuses** such a home (exit `5`) rather than reporting it verified, and names `onboard`."; "A home an agent cannot read its skills from exits `6` and names every missing link plus the `sync --skip-mcp` that would create it"; "This runs on an already-bootstrapped home without `--force`, because the operator has no way to know the slot is wrong. A **frozen** home is never written, here as everywhere else."; "A successful `bootstrap-home.sh` prints **five lines** on stderr".
CONFIDENCE: high
HOW I FOUND IT: Grepped for "bootstrap-home" corpus-wide, then read skills/git-issue-workflow/references/skill-homes.md in full (it is the page that owns the script).
```

---

**F-Q4.** Is `tla-spec-dev --spec-root specs open ticket HIS-20` a real, working invocation? What is the documented syntax for opening and closing a ticket?

```
ANSWER: The SYNTAX is real and documented verbatim. The documented forms are:
    tla-spec-dev --spec-root specs open ticket <ticket-id>
    tla-spec-dev --spec-root specs close ticket <ticket-id>
  plus close variants `--accept-new` (overwrites ticket `current/` from `desired/` and skips the `current == desired` check), `--summary "..."`, `--result <evidence-path>`; and the sibling verbs `scaffold project --name <Name>`, `scaffold workflow <TICKET> "<title>"`, `run spec-unit-tests --ticket <id>`, `retire ticket <id>`. `--spec-root` "defaults to `specs`", so `--spec-root specs` is the explicit spelling of the default; use the same `--spec-root` on every command when the repo keeps specs elsewhere.
  Whether THIS invocation works depends on three things the corpus makes conditions, not guarantees:
  1. The ticket must already exist as a planned/scaffolded ticket. `open ticket` "creates `specs/tickets/TICKET-123/current`, `desired`, `results`, and copied Test Graph configuration when present" and is described as "To start work on a **planned** ticket". In epic mode the ID is a stable ID the epic owner planned once, embedded in `ticket.spec_id`, and the agent "must not scaffold another workflow, create an ad hoc ticket".
  2. The binary must be the right one. "`tlc2`, `pytest`, `jinja2` and `tla-spec-dev` are not 'the ones on PATH'. They are the ones in the home this checkout is bound to, and the wrappers are generated shell scripts with that home's absolute path baked into the body." Separately, several spec-double-compiler epic documents say flatly "**Never invoke `tla-spec-dev` from PATH** — it execs a stale installed clone" (that warning is written for agents working inside the tla-spec-dev repository itself).
  3. `HIS-20` — NOT ANSWERABLE FROM THE CORPUS. The string `HIS-` appears nowhere in these documents; every example uses `TICKET-123` or `<stable-ticket-id>`. I cannot confirm HIS-20 is a real ticket in any plan.
SOURCE: skills/spec-double-compiler/SKILL.md — "tla-spec-dev --spec-root specs open ticket TICKET-123 / This creates `specs/tickets/TICKET-123/current`, `desired`, `results` …" and "tla-spec-dev --spec-root specs close ticket TICKET-123 / The close command validates ticket-local `current == desired`, replaces project-level `specs/current` with ticket `desired/` …"; "The scaffold resolves all workflow directories under `--spec-root`, which defaults to `specs`." skills/git-issue/references/spec-workflow.md — "the ticket agent runs: `tla-spec-dev --spec-root specs open ticket <stable-ticket-id>`". skills/spec-double-compiler/references/runtime_requirements.md — "**`tlc2`, `pytest`, `jinja2` and `tla-spec-dev` are not 'the ones on PATH'**". skills/spec-double-compiler/EPIC-HANDOFF.md — "**Never invoke `tla-spec-dev` from PATH** — it execs the installed clone".
CONFIDENCE: high on the syntax; the HIS-20 identity is explicitly not answerable
HOW I FOUND IT: Grepped the whole corpus for "tla-spec-dev" and for "HIS-" (zero hits), then read the ticket lifecycle sections of skills/spec-double-compiler/SKILL.md and skills/git-issue/references/spec-workflow.md.
```

---

```
COMPLIANCE: I read only files inside the corpus directory. I ran no skill-manager/skt/skill-dev/tla-spec-dev/git/gh command, no WebSearch/WebFetch, and no skill. I created, modified, moved and deleted nothing anywhere; every command was find/grep/wc/du/sed/cat with no redirection. One incidental side effect I should name honestly: my very first `find` produced output large enough that the harness itself auto-persisted it to /Users/hayde/.claude/projects/.../tool-results/bwovrtbp3.txt. I did not request or write that file and did not read it back, but a file did get written outside the sandbox as a consequence of my command, and you should know that. I also did not read any program source code — only .md documents.
CORPUS NOTES: 193 files, ~2.6 MB. I listed all 193, read 7 files in full (plugins/skt/skills/skt/SKILL.md, skills/git-issue-workflow/references/skill-homes.md, skills/skill-manager/SKILL.md, skills/skill-manager/references/workflows.md, skills/skill-manager/references/projects.md, skills/skill-dev-skill/SKILL.md, skills/spec-double-compiler/references/runtime_requirements.md) and read targeted sections of ~12 more, plus corpus-wide greps on every load-bearing term (bootstrap-home, tla-spec-dev, exit codes, tier, operator's global, SKILL_MANAGER_*). The corpus felt COMPLETE for the home/tier/CLI questions — skill-homes.md is unusually thorough and is cross-referenced as the single source of truth by four other skills, which is a good completeness signal. Gaps I noticed: (a) no file states the operator's $HOME, so mapping /Users/hayde/.skill-manager onto "~/.skill-manager" is an inference; (b) there is no `skt --help` / `skill-manager --help` / `bootstrap-home.sh --help` transcript in the corpus, and several docs explicitly defer to that help as authoritative — so the exact flag surfaces are only known where prose happens to name a flag; (c) no ticket registry or plan file, so ticket IDs like HIS-20 are unverifiable; (d) the corpus is titled "corpus-before", which suggests a paired "after" state I was not given.
```

---

## Grader's note, added after scoring

**F-Q3 is scored WRONG, not partial**, and this transcript is the reason the fix
exists. The agent found `bootstrap-home.sh`'s exits **5** and **6** and reported
them with high confidence and a verbatim citation. The fact the epic actually
needed — DEF-018's **exit 79**, "refusing to run against a home you did not
name", emitted by `<home>/bin/cli/skill-manager` when `SKILL_MANAGER_HOME` names
a different home and *surfaced through* `bootstrap-home.sh` — appears in **zero**
of the four instructing units' markdown. Measured with a validated grep: `79`
matched 0 of those files while the control term `exit` matched 19 of them. The
agent could not have found it, and what it found instead reads as a complete
answer.
