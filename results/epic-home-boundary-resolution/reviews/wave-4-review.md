# Wave 4 review — epic/home-boundary-resolution

The terminal wave. HBR-5 is the evaluation ticket, so most of what landed here
is measurement — and the measurement is where the interesting failures were.

## Goals

| goal | baseline | now | |
| --- | --- | --- | --- |
| an-agent-in-its-own-home-can-work | fails, "Not logged in" | succeeds | **MET** |
| a-failure-names-its-cause | 0 of 3 | 3 of 3 | **MET** |
| the-real-error-survives | absent, truncated | present | **MET** |
| a-home-runs-its-own-copy | 19 pairs (wrong, see below) | **0 in scope** | **MET in scope** |

Goal 1 reads **1** machine-wide, in a `meta-harness` home. Scope was set by the
owner: this repo, the root home, and worktrees from this repo. All three are 0.

## The hot spot: three of four goals were mismeasured, not unmet

This is the finding of the wave and it should change how the next epic is run.

- **Goal 1's walker asked the wrong question.** It tested whether a home held
  the unit *directory*; a shim runs a *command line*. `deploy-helm`'s execs a
  per-unit cache venv that `home clone` deliberately does not copy, so 53 of 55
  "unsanctioned crossings" were homes that genuinely could not run their own
  copy — the sanctioned fallback the metric claims to exclude. Real number: 2.
  The 2026-08-29 baseline of 19 came from the same code.
- **Goal 4 was measured against a vendored 0.3.0 snapshot** while the installed
  plugin was 0.8.1 and had been correct since HBR-3. I fixed it a second time
  before noticing.
- **Goal 3 was fixed by giw#25 and nobody re-ran it**, so it sat at its `0 of 3`
  baseline for six days.

All three share one cause: **a measurement whose target is implicit.** Fixed by
giving every goal a harness that names what it measures and refuses rather than
guesses (`scripts/measure_goals.py`, with `could not measure` as a state
distinct from `not met`).

## Where the bugs probably are

**The relay contract that does not exist.** Bug attribution puts error
reporting at the top: four defects, all one shape — a summariser picking one
line out of a child's output *by position*. Each was fixed in its own file.
There is no shared contract, so the next wrapper that summarises a child will
make the same bet. **This is the strongest architectural signal in the epic and
it is not what the epic was about.**

**`artifact-dag` is still red** (DEF-HBR-003), on `uninstall.prunes.the.subgraph`.
Untraced. It is the one graph nobody has explained.

**The graphs do not run on push or PR.** The first full sweep of this epic found
`onboard` broken by a third installer with the defect HBR-1 fixed in two. It had
been broken and invisible. Goal-validation-floor metric (a) reads 0 per push.

## Decisions made implicitly, now explicit

- **`PARENT_SHIM_SHADOWS_LOCAL_COPY` went from report-only to repairable.** The
  report-only stance rested on "the only repair deletes the sole entry point" —
  true only while the detector was wrong. The test guarding it forced the
  argument to be made rather than discovered.
- **`unsanctionedForeignHome` was NOT narrowed.** It gates clone, sync and the
  installers, and HIS-7 records that narrowing it once made `skt ticket new`
  unable to produce a ticket home at all. The new condition is reported beside
  it, not folded into it.
- **The migration notice went in `skt check`, not `skt status`.** `status` is
  "one file read and no subprocess" by design.

## Guardrails that fired on me

Worth recording, because each caught a real mistake: `ShimHomeContractTest`
(HBR-1), the `--fix`-leaves-it-reported test (#289), `WriteConfinement`'s
entry-vs-leaf distinction, skt's no-subprocess test, and the every-`Kind`
detection guard. Five for five.

## Recommended next

1. **One relay contract** — the top attribution class, currently unowned.
2. **Trace DEF-HBR-003** before it becomes permanent.
3. **Decide the graph-on-PR suspension** — it is costing a measured goal.
4. **#285 and #281** remain open and untraced.
