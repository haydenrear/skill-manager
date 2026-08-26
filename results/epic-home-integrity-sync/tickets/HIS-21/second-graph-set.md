# HIS-21 — the second graph set, and why it was run

The declared set is `home-integrity` and `home-sync`. This is the other five,
run because **the DEF-104 widening changes what `home verify` says**, and
`home verify` is not a command this ticket owns:

- `HomeFixpointLaw` runs it over **every home its 24 graphs produce**, and
- where it refuses, the law **parses the remedy out of the refusal and RUNS
  it** and then re-verifies.

So a false positive on a legitimately inherited wrapper would not have shown up
as this ticket failing. It would have shown up as graphs nobody was looking at
going red, with a remedy being executed against their homes on the way. The
five below are the ones that clone homes or materialize child homes with
mirrored shims — i.e. the ones that carry the shape the sanction is about.

| graph | why this one | result | run |
| --- | --- | --- | --- |
| `checkout-home` | clones a home into a project checkout and verifies the clone | **PASS** 8/8 | `20260824-201419` |
| `ticket-lifecycle` | `wt new` / `wt close` build a child home with mirrored shims and run close-out over it | **PASS** 14/14 | `20260824-201539` |
| `project-child-home` | `ChildHomeMaterializer.mirrorExistingShim` writes the shims `sanctionedParentShim` exists for | **PASS** 13/13 | `20260824-201923` |
| `harness-smoke` | the child home with REAL CLI deps — the measured source of the historical `FOREIGN_HOME bin/cli/pycowsay` false positive quoted in `HomeCommand` | **PASS** 14/14 | `20260824-202032` |
| `home-clone` | the clone path end to end, including the descriptor and the toolchain re-provision | **PASS** 14/14 | `20260824-202232` |

`graphs_executed=5 graphs_passed=5 graphs_failed=0` —
`second-graph-set.json` beside this file.

**The per-graph node counts are read from the envelopes, not from the sweep's
own summary**, because `sweep.py`'s `passed` is one boolean per graph and this
epic has already filed one instance of a driver conflating *skipped* with
*failed* (REG-003). Counted directly:

```
20260824-201419: 8 nodes {'passed': 8}
20260824-201539: 14 nodes {'passed': 14}
20260824-201923: 13 nodes {'passed': 13}
20260824-202032: 14 nodes {'passed': 14}
20260824-202232: 14 nodes {'passed': 14}
```

## One thing this run did that nobody asked it to — DEF-111

`sweep.py` defaults `--out` to `results/epic-home-integrity-sync/sweep`, and
writes `sweep.json` there after **every** graph. Running the five graphs the
ticket brief instructs (`sweep.py --only <graphs>`) therefore **overwrote the
26-graph round-1 record in place**:

```
graphs_selected: 26 -> 5
graphs_executed: 26 -> 5
graphs_passed:   23 -> 5
results: 26 entries -> 5
```

Found by `git status`, not by anything in the tooling. Recovered with
`git show HEAD:<path> > <path>` — possible only because round 1 had been
committed. **Nothing would have said so otherwise**: the clobbered file is
valid JSON with the same schema, and `graphs_executed: 5` reads as a completed
small run rather than as a destroyed large one.

Filed as **DEF-111**. The round-1 record in
`results/epic-home-integrity-sync/sweep/sweep.json` is byte-identical to HEAD
again; this ticket's own numbers live in `second-graph-set.json` beside this
file and nowhere near the canonical path.

## Caveat on ordering

Two small production edits landed **between** graph 3 and graph 4 of this run:
`GatewayConfig.FILE`/`URL_ENV` were made public, and `home describe`'s gateway
line gained the `$SKILL_MANAGER_GATEWAY_URL` arm. Both change only the wording
of `home describe`'s **human** output and the visibility of two constants.
No node in any of the five reads that wording — checked: the only two graph
sources that mention gateway ownership (`OnboardingCloneIsHonest`,
`HomeTripwireWorkload`) read `gateway.properties` and `home clone` output, and
`HomeMembershipLaw` reads `home describe --json`, which is untouched. Recorded
rather than re-run, and named here so a reviewer can disagree.


---

# Re-run after the review of PR #256

MAJOR-1 changed what `home verify` enumerates — it now follows a symlinked
`bin/cli`, which `home repair` always did — so the whole set was run again,
plus `home-sync`, which the first pass covered under its own `run.py`.

**This time with `--out`**, which is DEF-111's own lesson applied: the canonical
`results/epic-home-integrity-sync/sweep/sweep.json` is byte-identical to HEAD
after this run (`git status` clean on that path), and these numbers live here.

| graph | result | run | nodes (from the envelopes) |
| --- | --- | --- | --- |
| `home-sync` | **PASS** 230.0s | `20260824-215327` | 19/19 |
| `checkout-home` | **PASS** 68.0s | `20260824-215710` | 8/8 |
| `ticket-lifecycle` | **PASS** 214.7s | `20260824-215822` | 14/14 |
| `project-child-home` | **PASS** 63.0s | `20260824-220156` | 13/13 |
| `harness-smoke` | **PASS** 94.6s | `20260824-220259` | 14/14 |
| `home-clone` | **PASS** 103.6s | `20260824-220434` | 14/14 |

`graphs_executed=6 graphs_passed=6 graphs_failed=0`, **82 nodes, 82 passed** —
counted from the envelopes rather than from the sweep's per-graph boolean, for
REG-003's reason.

`harness-smoke` and `project-child-home` are the two that matter most here:
they are the graphs whose child homes carry real mirrored shims, and the
MAJOR-1 widening is exactly the change that could have started calling those
foreign. It did not.

**And `home-integrity` was re-run separately: 18/18** (`run.py home-integrity`),
carrying the new MAJOR-1 and MAJOR-2 assertions.

## The frame this file used was wrong, and DEF-112 records the right one

The table above and its predecessor are still the graphs most LIKELY to be
affected. They are not the population the change can reach: **24 graphs carry
`HomeFixpointLaw`/`HomeMembershipLaw`**, and the reviewer of #256 was right to
say so. Between this ticket (7) and that review (9), **16 of the 24 are run and
green**; 2 skip the law behind a pre-existing upstream failure; and **6 are
unrun by anyone** — `smoke`, `plugin-smoke`, `skill-dev-smoke`,
`source-tracking`, `onboard`, `project-profiles`. Named in DEF-112 so HIS-6
inherits the list rather than the search.
