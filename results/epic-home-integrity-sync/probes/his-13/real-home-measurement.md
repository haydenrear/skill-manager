# The measurement that is not a fixture

Run 2026-08-23 on `feature/159-his-13` rebased onto epic tip `0f1a9a4`, with the
checkout's own CLI (`./skill-manager`, not the 0.24.0 shim on PATH — DEF-068/DEF-006)
and all four home variables pinned at this worktree.

## Subject 1 — this worktree's OWN home, read-only

```
$ ./skill-manager home repair --home <wt>/.skill-manager
✗ 1 finding(s) in <wt>/.skill-manager, of 30 entries examined
✗   PRUNED_INHERITED_ENTRY bin/cli/tofu — is declared here, is gone, and the
      parent store /Users/hayde/.skill-manager this home records still holds it
✗       repair: re-link it at /Users/hayde/.skill-manager/bin/cli/tofu
exit 1
```

**Verified genuine before believing it**, because a detector's first real finding
is exactly where a false positive hides:

```
artifacts.lock.toml   id = "cli-shim:brew/opentofu", owner = "deploy-helm",
                      outputs = ["bin/cli/tofu"]          <- this home DECLARES it
<wt>/.skill-manager/bin/cli/tofu                          <- absent
~/.skill-manager/bin/cli/tofu -> /opt/homebrew/opt/opentofu/bin/tofu
                                                          <- the recorded parent HAS it
home.provenance.json  parentStores: ["/Users/hayde/.skill-manager"], and it
                      still re-derives (home verify says so, below)
```

This answers §8's own "unsure #3" — *whether `PRUNED_INHERITED_ENTRY` will fire
in the wild*. It fired on the first real home it was pointed at, and the home was
not constructed to make it.

**Not repaired.** The worktree home is the epic agent's to reconcile, and the
close-out gate had already passed on it.

## Subject 2 — a `cp -a` copy of that home, which is the shape the epic warned about

`HomeCloner.sanctionedParentShim`'s javadoc names the case this class cannot
help: *"a byte copy taken with `cp -a`, an rsync, a clone by an older
skill-manager … repairing such a home so it stands on its own record is
HIS-13's."* So the subject is literally that.

```
$ cp -a <wt>/.skill-manager  <probe>/home2/.skill-manager
$ cp -a <wt>/.claude         <probe>/home2/.claude
```

### The two instruments, on the same home, at the same moment

```
$ ./skill-manager home verify --home <probe>/home2/.skill-manager
✓ every reference in … resolves, and no path in it reaches any other Skill
  Manager home except the 5 sanctioned parent-store shim(s) above
exit 0

$ ./skill-manager home repair --home <probe>/home2/.skill-manager
✗ 5 finding(s)
exit 1
```

**Exit 0 and exit 1 about one home, one minute apart.** Four of the five are
`MISANCHORED_AGENT_LINK` — the copy's `.claude/skills/{test-graph,
spec-double-compiler, …}` still resolve into the SOURCE home's store, which is
precisely what `cp -a` leaves behind and precisely what `verify` cannot see,
because `verifyRoots` walks the store and the agent directories are the home's
other axis.

That disagreement is filed as **DEF-070**. It is not a defect in either command;
it is two questions, and the epic has to decide which one `home verify` is for.

### Detect -> repair -> detect, three processes

```
$ ./skill-manager home repair --home <probe>/home1/.skill-manager          exit 1, 5 findings
$ ./skill-manager home repair --home <probe>/home1/.skill-manager --fix    ✓ repaired 5 of 5
                                                                           exit 0
$ ./skill-manager home repair --home <probe>/home1/.skill-manager --fix    ✓ repaired 0 of 0
                                                                           ✓ nothing … is damaged
                                                                             (22 entries examined)
$ ./skill-manager home repair --home <probe>/home2/.skill-manager          exit 0 after its own fix
```

Idempotent on a real 489 MB home, not only on a fixture.

### The source home was not touched

Re-run after every repair above:

```
$ ./skill-manager home repair --home <wt>/.skill-manager
✗ 1 finding(s) … PRUNED_INHERITED_ENTRY bin/cli/tofu
```

Unchanged — same one finding, and `<wt>/.claude/skills` still carries its
bootstrap mtime. A repair that had "helpfully" fixed the home its subject's links
pointed into would have cleared this.

## Cleanup

`<probe>/` was deleted immediately (`rm -rf`), and the disk was checked before
and after. Nothing under `~/.skill-manager`, `~/.claude`, `~/.codex`,
`~/.gemini` or the project home was written at any point.
