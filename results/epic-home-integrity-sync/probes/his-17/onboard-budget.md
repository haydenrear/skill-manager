# `onboard.skills.installed` — a budget below the graph's own floor

**#238's fourth red, and the ticket's instruction was to measure before
concluding, fold in only if it is the same cause, otherwise file it.** It is not
the same cause. It is filed as **DEF-065**, with the cause and a verified
remedy.

## Reproduced first

Run `20260823-164343`, unmutated tip:

```
> node onboard.skills.installed errored: node timed out after 10s across 3
  attempts; executor force-killed the subprocess (see capturedStdoutLog)
```

Six nodes skipped behind it, including `home.fixpoint.law` and
`home.membership.law`. **The captured stdout is 0 bytes** across all three
attempts — the node never produced a line, so it died before its body ran.

## `HomeLock` contention is eliminated, not merely doubted

`OnboardSkillsInstalled` spawns **no `skill-manager` process at all**. It reads
the filesystem and runs two `git remote get-url origin`. It takes no home lock,
so nothing about `HomeLock` can reach it. Measured on the graph's own home:

```
git -C <home>/skills/skill-manager     remote get-url origin   real 0.01s
git -C <home>/skills/skill-dev-skill   remote get-url origin   real 0.01s
```

## The measurement

`JBangExecutor` starts `jbang <node>.java …` and waits `timeoutMillis` on the
**whole process**, so the budget covers jbang resolution, JVM boot, SDK init,
OpenTelemetry setup and shutdown flush before the node body runs.

Idle machine, warm jbang cache, three consecutive runs of the node's own file:

```
jbang sources/onboard/OnboardSkillsInstalled.java   real 5.69 / 5.67 / 5.68
```

**5.68 s of the 10 s budget gone before a statement executes.** Under the graph,
with postgres, the registry, the gateway and sibling JVMs live, it is worse.
Per-node wall clock vs. body time, run `20260823-165545`:

| node | body | wall | budget |
| --- | --- | --- | --- |
| `onboard.skills.installed` | **41 ms** | **14.49 s** | 10 s |
| `onboard.agent.configs.written` | 23 ms | 14.35 s | 10 s |
| `onboard.gateway.healthy` | 151 ms | 13.31 s | 10 s |
| `onboard.seeded.by.server` | 157 ms | 13.29 s | 60 s |
| `env.prepared` | 24 ms | 14.65 s | — |
| `onboard.completed` | 10 512 ms | 25.27 s | — |

**The fixed per-node cost of this graph is ~13.3–14.7 s.** Three nodes carry a
10 s budget, which is **below that floor**: they cannot pass, whatever they do.
`onboard.skills.installed` does 41 ms of useful work and pays 351× that in
process overhead.

## Not caused by this epic

The 10 s was set on **2026-04-28** in `860ca0d`, four months before
`epic/home-integrity-sync` opened, and has never been re-measured while jbang,
the JVM, the SDK and the OTel exporter grew underneath it. Nothing in this epic
touches this node's path. `refresh-flow`'s `ShortAccessTokenTtl` sits at **5 s**
and is in the same position; that graph is excluded from every automatic set, so
nobody has met it.

## The remedy, verified rather than proposed

The three budgets raised to 120 s, `onboard` re-run, then reverted:

```
BUILD SUCCESSFUL in 4m 45s     run 20260823-165545     15/15 nodes pass
```

All fifteen nodes green, including the six that had been skipped behind the
timeout and both laws. **So there is no correctness defect here at all.**

## Why it is filed and not shipped

Raising three numbers would make a graph green and **hide the number that
matters**: a no-op node costs ~14 s, and at ~15 nodes × 29 graphs that is where
a ~7-minute sweep goes. That is the same "widen the fixture until it stops
saying anything" move this ticket refused twice elsewhere. The OTLP exporter is
also failing on **every node in every graph** (`Connection refused
localhost:4318`, with retries and a shutdown flush inside the budget), which is
a lever on the fixed cost rather than on any one budget.

Which of the two to pay — three budgets, or the 13 s floor — is HIS-6's call:
it owns the terminal sweep and the runtime number the goal is read against.
