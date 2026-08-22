# DEF-029, reproduced and closed — with the real CLI, against a decoy

Run on `feature/232-his-14`, macOS, 2026-08-22. **Nothing here goes near the
operator's real agent homes**: `$HOME` is a scratch directory that plays the
part `~` played on the day the damage happened, and the four config files that
were edited then were snapshotted *before this session ran any command* (see
`snapshot-before/`) and diffed afterwards (`snapshot-after/`, `check-real-homes.sh`).

The decoy is called `operator/` below. It is a real Skill Manager home with real
`.claude` / `.codex` / `.gemini` directories, and it is the home **nobody
names**. The home that IS named is `project/.skill-manager`.

## The environment

DEF-029's own words: *"`SKILL_MANAGER_HOME` unset, the root home's `bin/cli`
first on PATH"*.

```
HOME=<scratch>/repro/operator          # the decoy stands in for ~
SKILL_MANAGER_HOME  unset
CLAUDE_CONFIG_DIR   unset
CODEX_HOME          unset
GEMINI_HOME         unset
```

`his14-probe` is installed into `project/.skill-manager` first, with that home
named on both axes, so the store already holds it.

## BEFORE — epic tip behaviour (the command that caused the damage)

```
$ ./skill-manager sync his14-probe --merge --home <scratch>/repro/project/.skill-manager
✓ units.lock.toml: wrote 1 unit(s) → <scratch>/repro/project/.skill-manager/units.lock.toml
✓ synced 1 unit(s) — 1 local-only (no shared upstream)
✓ agents: 1 unit(s) linked into claude, codex, gemini
  ADDED    claude (<scratch>/repro/operator/.claude.json)   → http://127.0.0.1:51717/mcp
  ADDED    codex  (<scratch>/repro/operator/.codex/config.toml)  → http://127.0.0.1:51717/mcp
  ADDED    gemini (<scratch>/repro/operator/.gemini/settings.json) → http://127.0.0.1:51717/mcp
agent MCP configs: ADDED 3, UPDATED 0
```

and on disk, in the home nobody named:

```
operator/.claude/skills/his14-probe -> <scratch>/repro/project/.skill-manager/skills/his14-probe
operator/.codex/skills/his14-probe  -> same
operator/.gemini/skills/his14-probe -> same
```

Three symlinks and three edited config files. The store half went where it was
told; the agent half went to whatever `$HOME` happened to be. On 2026-08-21 that
`$HOME` was the operator's.

## AFTER — this branch, same command, same environment

```
$ ./skill-manager sync his14-probe --merge --home <scratch>/repro/project/.skill-manager
✓ units.lock.toml: wrote 1 unit(s) → <scratch>/repro/project/.skill-manager/units.lock.toml
✓ synced 1 unit(s) — 1 local-only (no shared upstream)
✓ agents: 1 unit(s) linked into claude, codex, gemini
```

```
$ ls -la operator/.claude operator/.codex operator/.gemini
  (all three: empty. no skills/, no config file, no .claude.json)

$ ls project/.claude/skills
  his14-probe -> <scratch>/repro/project/.skill-manager/skills/his14-probe
```

The `ADDED claude (…)` lines are gone because the entries were already in the
NAMED home's configs, written by the install — which is the point: the second
run had nothing left to add to the home it was about, and nothing to say about a
home it was never about.

## The printed remedy, EXECUTED

Not an assertion on its text. The refusal's own line, pasted into a shell whose
environment names the decoy:

```
$ ./skill-manager sync his14-probe --home <scratch>/repro/gitproj/.skill-manager
✗ his14-probe has extra local changes … — sync would overwrite them.
✗   re-run with: /Users/hayde/IdeaProjects/wt-232-his-14/skill-manager sync his14-probe --merge --home <scratch>/repro/gitproj/.skill-manager  (merges …)

$ sh -c "<that line, verbatim>"          # HOME=<scratch>/repro/operator
✓ units.lock.toml: wrote 1 unit(s) → <scratch>/repro/gitproj/.skill-manager/units.lock.toml
✓ synced 1 unit(s) — 1 merged, 1 local-only (no shared upstream)
✓ agents: 1 unit(s) linked into claude, codex, gemini

$ ls operator/.claude operator/.codex operator/.gemini    → all empty
$ ls operator/.claude.json                                → No such file or directory
$ ls gitproj/.claude/skills gitproj/.codex/skills gitproj/.gemini/skills
  his14-shell-probe   (all three)
```

This is the shape `HomeBindsBothAxesTest`'s last case automates, with the three
agent variables **exported** rather than unset — see `vacuity-checks.txt`, V2,
for why the unset spelling proved less than it looked like it did.

## The operator's four real files

```
$ probes/his-14/check-real-homes.sh
SAME  /Users/hayde/.claude/settings.json
SAME  /Users/hayde/.claude/plugins/known_marketplaces.json
SAME  /Users/hayde/.codex/config.toml
SAME  /Users/hayde/.gemini/settings.json
SAME  /Users/hayde/.claude/skills
SAME  /Users/hayde/.codex/skills
SAME  /Users/hayde/.gemini/skills
```

Snapshotted **before the first command of this session**, not after a surprise.
