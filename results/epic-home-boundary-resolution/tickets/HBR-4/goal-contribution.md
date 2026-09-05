# HBR-4 — goal contribution

**Goal:** `GOAL-an-agent-in-its-own-home-can-work`
**Contribution:** direct · **Decided by:** HBR-5

| | |
| --- | --- |
| Metric | does `bin/launch/claude -p 'reply OK'` in a freshly bootstrapped worktree home return a result |
| Baseline | fails — `Not logged in · Please run /login`, churned for 0s (#263, 2026-08-27) |
| Target | succeeds |
| Expected effect | a worktree-home launch returns a result instead of `Not logged in` |
| Local signal | `bin/launch/claude -p 'reply OK'` in a fresh worktree home returns non-empty |
| **Observed** | **`OK`** — see `demonstration.md`. Baseline re-measured on this branch first, through the same shim and the same home, so the pair is a controlled before/after and not a comparison against a report. |

This is advisory evidence from the ticket, not the goal's verdict. HBR-5 owns
the verdict, and DEF-HBR-006 records the one thing it still needs: a graph node
that asserts the launch authenticated. This repository's own suite cannot —
see below.

## What changed

One variable, added to the launch environment as its lowest-precedence layer:

```
CLAUDE_SECURESTORAGE_CONFIG_DIR=
```

`LaunchEnv.sharedCredentialEnv()`, applied in `LaunchEnv.of` beside
`PackageCaches.sharedEnv`. Every launch goes through `exec`, so it reaches
every home already on the machine the moment they run a current build — no
descriptor rewrite, no re-bootstrap. That placement is the same argument HBR-2's
`remediation_reach` makes about the bootstrap probe.

## The cause was not what either prior account said

Both were tested and neither holds. `exec` never overrides `HOME`, so the
keychain is reachable; and an `oauthAccount`-bearing `.claude.json` seeded into
the redirected config directory still prints `Not logged in`. The cause is that
`CLAUDE_CONFIG_DIR` silently **renames the credential slot** — the keychain
service name on macOS, the credentials file elsewhere. `cause.md` carries the
CLI's own derivation and the three-cell measurement.

## What happened to `requireClaudeRedirected()`

**Nothing. It is unchanged, and no opt-out was added to it.**

That is the design, not an omission. The guard protects the *config* axis —
where skills load from — and #263 is a fault on the *credential* axis. They
looked like one problem only because redirecting the first silently moved the
second. The Claude CLI separates them, so skill-manager can too, and the
isolation invariant never has to be traded.

The tempting fix was the opposite: an `--inherit-claude-config` on `exec` that
buys credentials by giving up skills isolation. Two cases in
`LaunchCredentialAxisTest` pin the guard against exactly that, and the reasoning
is written into the guard's own javadoc so the next reader meets it before the
idea.

## The trade that WAS made

**Credentials are shared across homes; config is not.** A ticket agent
authenticates as the operator, out of one keychain slot, and a token refresh it
performs writes that shared slot. Chosen over:

- **per-home credentials** — skill-manager models no agent login anywhere (agent
  homes are siblings of the store, `home clone` does not carry them), so a
  per-home slot means an interactive `/login` per worktree, which the `-p` path
  this exists to serve cannot perform. "Isolated" would mean "empty", which is
  today's behaviour under a better name;
- **not redirecting `CLAUDE_CONFIG_DIR`** — which buys credentials with the
  skills isolation the whole class exists for.

Opting back out is `home describe --set-env CLAUDE_SECURESTORAGE_CONFIG_DIR=…`
and needed no new option, because the default is deliberately the
lowest-precedence layer.

## Where the assertions stop, deliberately

`LaunchCredentialAxisTest` asserts the **bytes of the launch environment** and
that the guard did not move. It does not assert that an agent authenticated:
that needs the real binary and a real login, which is a machine fact. A unit
test that shelled out to it would be red for reasons unrelated to this code.

The failure mode with teeth is therefore covered where it lives — the empty
string looks like a placeholder and invites being "tidied" into a path, which
restores #263 in full and is asserted against. The residual risk (the vendor
drops the variable) is DEF-HBR-006, and only a graph node can catch it.

## Scope held

Two findings deferred rather than absorbed: DEF-HBR-005 (codex and gemini have
the same defect, with no equivalent vendor seam) and DEF-HBR-006 (the coverage
boundary above). Neither was fixed here.
