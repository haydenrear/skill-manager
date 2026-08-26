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
| `onboard.seeded.by.server` | 157 ms | 13.29 s | **15 s** + `retries(2)` |
| `env.prepared` | 24 ms | 14.65 s | — |
| `onboard.completed` | 10 512 ms | 25.27 s | — |

**CORRECTED after review of #242.** The first version of this table said
`onboard.seeded.by.server` had a 60 s budget. It has **15 s**, with
`.retries(2)`. That matters, because the per-node gaps recomputed from this same
run are **13.15–17.66 s, mean 14.65 s over 14 gaps** — so the floor **crossed
15 s twice in the run this document cites as evidence.**

**The fixed per-node cost of this graph is ~13.2–17.7 s.** **Four** nodes sit at
or under it, not three — and the fourth is worse than the other three, because
its retries turn a budget it will cross intermittently into **flake** rather
than a verdict. `onboard.skills.installed` does 41 ms of useful work and pays
351× that in process overhead.

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

## Shipped — and the first version of this page argued the opposite

**All four budgets are raised to 90 s in this PR.** The first version filed the
fix and reverted it, arguing that shipping three numbers would hide the ~14 s
floor. Review of #242 rejected that, and the argument against it is better:

* **`onboard` is in the CORE set.** Leaving a CORE graph red to preserve a
  datapoint costs the epic a signal it has already shown it does not otherwise
  get — *this entire ticket exists because nobody ran these graphs for four
  waves.*
* **The two concerns are separable.** Shipping the budgets does not delete the
  measurement; this page is the measurement, and **DEF-065 stays open** on the
  floor and on the OTLP retries.
* The revert also **left the fourth budget in place**, which was the one that
  fails intermittently rather than deterministically — the hardest kind to
  diagnose later.

90 s is deliberately **not** a measurement of any of these nodes; none of them
takes 90 s. It is headroom over a fixed cost nobody has attacked yet, and each
node's javadoc says exactly that so the number is not mistaken for a budget
somebody derived.

## What stays open — the number that actually matters

A no-op node costs ~14 s. At ~15 nodes × 29 graphs, that is where a ~7-minute
sweep goes. The OTLP exporter fails on **every node of every graph**
(`Connection refused localhost:4318`) with retries and a shutdown flush inside
the budget, and jbang startup is 5.68 s of it. **DEF-065 remains open on the
floor**, owned by HIS-6, which owns the terminal sweep and the runtime number
the goal is read against.
