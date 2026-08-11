---
name: skt
description: 'Skill-lifecycle orientation and change management for skill-manager homes, via the `skt` CLI. Use at SESSION START to learn what is loaded and where you are standing — which skill-manager skills and plugins are installed, which support change management, which have a NEW VERSION AVAILABLE and how to pull/sync them, which home tier this session writes (root ~/.skill-manager, project <repo>/.skill-manager, or a ticket worktree home), and whether you are inside an epic, a ticket, or an active spec workflow. Use whenever you edited a skill in your home and need it to survive — sync it up a tier, publish it to its own repo — or see "please sync with root to publish changes globally". Use for the worktree ticket lifecycle: `skt ticket new/close` creates and tears down a ticket worktree WITH its own Skill Manager home. Trigger on: "what skills are loaded", "am I in a ticket/epic", "update this skill", "new version", "sync skills", "publish my skill edit", "start/finish a ticket", session startup orientation.'
---

# skt

One CLI for the questions every session has and nobody used to answer:
*what is loaded, where am I standing, is anything stale, and how does my
skill edit survive this worktree?*

```bash
skt status            # startup report: units, plugins, home tier, epic/ticket state
skt check             # new-version-available + (root home only) sync-with-root prompts
skt sync <unit>       # pull a unit to its latest pushed source
skt ticket new <T>    # ticket worktree + its own Skill Manager home, one command
skt ticket close <T>  # teardown through the close-out gate
skt publish [<unit>]  # a home-edited skill -> up one tier -> its own git repo
```

`skt` is on `PATH` in every skill-manager home (`<home>/bin/cli/skt`).
`skt --help` is authoritative for syntax.

## Startup disclosure

In Claude Code this plugin's `SessionStart` hook injects `skt status`
into every session automatically, and a `PostToolUse` hook surfaces
"new version available" notifications when the throttled check fires
(every hook injection appends a line to `<home>/logs/skt/hook.log`).
Harnesses without a hook runtime (codex, gemini) get the projected skt
skill plus an instruction snippet instead — the honest per-harness
matrix is `../../references/harness-capabilities.md`.

`skt ticket new/close` wraps git-issue-workflow's `wt`; the raw path
form still works everywhere and is the fallback when skt is not
installed:

```bash
"${SKILL_MANAGER_HOME:-$HOME/.skill-manager}/skills/git-issue-workflow/scripts/wt" new <TICKET>
```

## The three-tier home model, in one table

| Tier | Path | Updated by | Your obligation |
| --- | --- | --- | --- |
| root | `~/.skill-manager` | operator installs; `skt sync` | publish local edits globally (`skt check` prompts here) |
| project | `<repo>/.skill-manager` | cloned from root; refreshed via `wt`/`skt ticket` imports | none — pull-side only |
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
