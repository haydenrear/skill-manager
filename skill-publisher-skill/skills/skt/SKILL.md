---
name: skt
description: 'Skill-lifecycle orientation and change management for skill-manager homes, via the `skt` CLI. Use at SESSION START to learn what is loaded and where you are standing — which skill-manager skills and plugins are installed, which support change management, which have a NEW VERSION AVAILABLE and how to pull/sync them, which home tier this session writes (root ~/.skill-manager, project <repo>/.skill-manager, or a ticket worktree home), and whether you are inside an epic, a ticket, or an active spec workflow. Use whenever you edited a skill in your home and need it to survive — sync it up a tier, publish it to its own repo — or see "please sync with root to publish changes globally". Use for the worktree ticket lifecycle: `skt ticket new/close` creates and tears down a ticket worktree WITH its own Skill Manager home, and `skt ticket list/sweep` enumerates the ticket worktrees of an epic and retires them in one safety-gated pass instead of a hand-rolled `git worktree remove --force` loop. Trigger on: "what skills are loaded", "am I in a ticket/epic", "update this skill", "new version", "sync skills", "publish my skill edit", "start/finish a ticket", "clean up/retire/sweep worktrees", "reclaim disk space from worktrees", session startup orientation.'
---

# skt

One CLI for the questions every session has and nobody used to answer:
*what is loaded, where am I standing, is anything stale, and how does my
skill edit survive this worktree?*

```bash
skt status            # startup report: units, plugins, home tier, epic/ticket state
skt check             # new-version-available, recorded unit errors, stale artifacts
skt sync <unit>       # pull a unit to its latest pushed source
skt ticket new <T>    # ticket worktree + its own Skill Manager home, one command
skt ticket close <T>  # teardown through the close-out gate
skt ticket list       # every ticket worktree here, and what blocks retiring it
skt ticket sweep      # retire many at once — dry run unless you pass --yes
skt publish [<unit>]  # a home-edited skill -> up one tier -> its own git repo
```

`skt` is on `PATH` in every home **that installed this plugin**
(`<home>/bin/cli/skt`). `skt --help` is authoritative for syntax.

**A home does not inherit it.** Project and worktree homes are copies of the
home above them, so a home cloned from one that never installed `skt` has no
`bin/cli/skt` and no `plugins/skt/` — and then none of this page is loaded in
that session either, which is why you are unlikely to be reading this when it
matters.

That is not hypothetical, and the fix is one command. **As measured on
2026-08-24**, the skill-manager repository's project home and a ticket worktree
cloned from it each held four skills, no plugins and no `skt` — while the
`skill-project.toml` that home is meant to realize declared the plugin. A single
`skill-manager project resolve` against the worktree home installed `skt` and
`skill-manager` into it and the gap closed. **So if you are reading this from a
home that has `skt`, that measurement is history and not a description of where
you are standing** — which is the whole shape of the problem: a home is a copy
taken at an instant, and a sentence about one home is not a sentence about
another.

If `skt` is not there, nothing is broken and nothing is lost — you are simply
one tier down from where it was installed. Every `skt` verb is a wrapper:

| instead of | run |
| --- | --- |
| `skt status` / `skt check` | `skill-manager list`, `skill-manager home describe --json` |
| `skt sync <unit>` | `skill-manager sync <unit> --git-latest` |
| `skt publish <unit>` | `skill-manager home sync` then `skill-manager unit publish` (the two legs below) |
| `skt ticket new/close` | `<home>/skills/git-issue-workflow/scripts/wt new\|close <TICKET>` — for a home that installed that unit; the same caveat applies to it |

To get the plugin itself into this checkout's home, declare it in the
checkout's `skill-project.toml` and run `skill-manager project resolve`
against **that** home.

## When `skt check` says a unit is NOT stale

A unit whose installed hash disagrees with its remote tip is usually
behind it. Sometimes the home already knows better: the installer
records `errors[*].kind` when it leaves a store in a state it could not
finish, and for `MERGE_CONFLICT`, `NO_GIT_REMOTE` and
`NEEDS_GIT_MIGRATION` that record *is* the explanation for the
disagreement. `skt check` reads it first and emits a `unit-error`
notification in place of the pull prompt:

```
deploy-helm is not stale — its store is mid-merge (MERGE_CONFLICT):
unmerged paths remain. Local work is preserved at stash@{0}.
Syncing re-runs the merge that made them.
  resolve with: git -C <home>/skills/deploy-helm status
```

**Do not answer this with `skt sync` or `skill-manager sync --merge`.**
`--merge` is documented as the flag that *sets* `MERGE_CONFLICT`, and
the state clears only when the store has no unmerged files left. The
stash the message names is somebody's uncommitted work and any reset of
the store destroys it. Resolve in the store directory, `git add` +
`git commit`, and the next command clears the error by itself.

## Startup disclosure

In Claude Code this plugin's `SessionStart` hook injects `skt status`
into every session automatically and performs the one bounded live
refresh of the check cache; the `PostToolUse` hook is cache-only — it
surfaces "new version available" notifications from that cached result
and never runs a check itself (every hook injection appends a line to
`<home>/logs/skt/hook.log`).
Harnesses without a hook runtime (codex, gemini) get the projected skt
skill plus an instruction snippet instead — the honest per-harness
matrix is `../../references/harness-capabilities.md`.

`skt ticket new/close` wraps git-issue-workflow's `wt`; the raw path
form still works everywhere and is the fallback when skt is not
installed:

```bash
"${SKILL_MANAGER_HOME:-$HOME/.skill-manager}/skills/git-issue-workflow/scripts/wt" new <TICKET>
```

## Retiring an epic's worktrees at the end: `list` and `sweep`

An epic keeps every ticket worktree standing until integration is done
and then retires them all at once. **Do not hand-loop
`git worktree remove --force`** — that is `rm -rf` with extra steps: it
deletes each worktree's Skill Manager home, which is gitignored, so the
loss appears in no diff, no PR and no fan-out.

```bash
skt ticket list                       # read-only: what is standing, and what blocks it
skt ticket sweep                      # the plan. Changes NOTHING without --yes
skt ticket sweep --yes                # retire every worktree that passes its own gate
skt ticket sweep --epic <slug> --yes  # one epic's worktrees only
```

Run it **from the primary checkout**. `sweep` refuses the worktree it is
running in and never touches the primary.

Each worktree is measured *again* immediately before it is removed, and
any one of these makes it **skipped, not removed** — reported, with the
pass carrying on:

- uncommitted changes, or a stash entry made on that worktree's branch;
- commits not pushed, or not contained in the epic/target branch;
- a non-clean `skill-manager home close-out --home <worktree-home>
  --into <primary-home>` verdict — the same gate `skt ticket close` runs.

Removal is `git worktree remove`; `--force` is never passed. Exit `0`
means the pass completed (skips included — a skip is the gate working),
`1` means something failed, `9` means the destination home is `frozen`
and the pass was abandoned.

The summary reports a **free-space delta**, measured with `statvfs`
before and after, and no per-worktree size. These homes are cloned
copy-on-write, so `du` bills every shared block to every copy and
over-reports by roughly 30x — a home `du` called 1.1 GB cost 33.7 MB of
real space.

## The three-tier home model, in one table

| Tier | Path | Updated by | Your obligation |
| --- | --- | --- | --- |
| root | `~/.skill-manager` | operator installs; `skt sync` | publish local edits globally (`skt check` prompts here) |
| project | `<repo>/.skill-manager` | cloned from root; refreshed via `wt`/`skt ticket` imports | pull-side by default — but an edit made *here* owes the same two legs as any other |
| worktree | `<worktree>/.skill-manager` | cloned from project at `ticket new` | get edits OUT before teardown (`skt publish`; the close gate refuses otherwise) |

Homes are real copies, never symlinks. An edit inside one is **in no git
diff** — `skt publish` is how it survives: `home sync` moves it one tier
up (and no further), `unit publish` is the only route to the skill's own
repository and to other machines.

## Related skills in this plugin

- `unit-authoring` (sibling skill): authoring installable units —
  SKILL.md frontmatter, `skill-manager.toml`, `plugin.json`,
  dependencies, distribution. The deep schemas live in this plugin's
  `../../references/` pages.
- `skill-manager` (separate unit): install/bind/project/home plumbing;
  its CLI help is authoritative for those.
