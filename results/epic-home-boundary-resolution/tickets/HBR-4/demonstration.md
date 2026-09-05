# HBR-4 — the end-to-end demonstration

`GOAL-an-agent-in-its-own-home-can-work`, measured the way the goal states it:
**does `bin/launch/claude -p 'reply OK'` in a freshly bootstrapped worktree home
return a result?**

Both runs are through the **real generated shim**, against the **same home**,
with the **same command**. The only thing that changes between them is whether
this ticket's diff is applied — the second run is the first with
`git stash pop` in between and nothing else.

## The fixture

A scratch checkout under the session scratchpad. **No real Skill Manager home
was written**, and no running session's credential path was touched.

```
$ skill-manager home shims --home <scratch>/wt-demo/.skill-manager --init
✓ wrote 4 launcher(s) to <scratch>/wt-demo/.skill-manager/bin/launch
  pinned CLI: <scratch>/skill-manager        # a launcher for the feature/hbr-4 tree
```

## Baseline — the diff stashed

```
$ <scratch>/wt-demo/.skill-manager/bin/launch/claude -p 'reply OK'
Not logged in · Please run /login
```

Matches #263 exactly: it starts, it says this, it stops, and it exits 0.

## After — the diff applied

```
$ <scratch>/wt-demo/.skill-manager/bin/launch/claude -p 'reply OK'
OK
```

**fails → succeeds.** The goal's baseline and target, on its own metric.

## The launch environment that did it

```
$ skill-manager exec --home <scratch>/wt-demo/.skill-manager --print-env | grep CLAUDE
CLAUDE_CONFIG_DIR=<scratch>/wt-demo/.claude
CLAUDE_SECURESTORAGE_CONFIG_DIR=
CLAUDE_HOME=<scratch>/wt-demo/.claude
```

`CLAUDE_CONFIG_DIR` is still redirected into the worktree home. Nothing about
the isolation changed.

## Isolation held while it worked, which is the whole point

The claim is not "the agent authenticated". It is "the agent authenticated
**and stayed in its own home**". Two facts from the successful run:

**1. Its config, projects and session were written into the worktree home**, not
the operator's:

```
$ ls -la <scratch>/wt-demo/.claude/
-rw-------  39170  .claude.json
drwxr-xr-x         backups
drwxr-xr-x         projects
drwx------         sessions
```

**2. No new credential slot was created.** The launch read the operator's
existing one rather than minting a per-home one:

```
$ security dump-keychain | grep -i svce | grep -i 'Claude Code' | sort -u
    "svce"<blob>="Claude Code-credentials"
    "svce"<blob>="Claude Code-credentials-84a08daf"
```

Identical to the inventory taken before the run.

## Regression signal

- `jbang RunTests.java` — **ALL PASSED**, 1448 cases (1442 on the epic base at
  `331cda8`, plus this ticket's 6).
- `python skills/test_graph/scripts/run.py checkout-home` — **passed**, 8/8
  nodes, including `checkout.home.launch.isolated`. Envelope summary saved
  beside this file.
- `artifact-dag` is red on the epic base already and was not run; see
  DEF-HBR-003.
