# OUN-8 — terminal evaluation

The ticket whose whole slice is *deciding the goals on the integrated result*.
Performed 2026-09-11 and 2026-09-12, on the epic branch with `origin/main`
merged in — which turned out to matter more than anything else in this record.

## Verdict: 5 of 6

```
MET      GOAL-a-contained-skill-is-addressable            yes
MET      GOAL-migration-lands-on-one-skt                  both routes reach (0, 0, 1)
MET      GOAL-who-imports-this                            agrees with an independent walk, 52 of 52 homes
MET      GOAL-a-home-survives-being-copied-into-an-image  3 of 3, both homes
MET      GOAL-the-front-door-is-found                     6 of 6 eval cases
NOT MET  GOAL-one-name-one-copy                           0 live, 11 latent over 55 homes
```

`python3 scripts/measure_goals.py --epic one-unit-one-name`

One goal was **carried**, not measured: `GOAL-a-copied-home-knows-its-gateway-state`,
to `substrate-home-model` (#317), owner decision 2026-09-05, receipt at
`specs/.history/one-unit-one-name/retired-ticket-011-OUN-11/manifest.json`.

## The one NOT MET, read honestly

`GOAL-one-name-one-copy` reads **0 live, 11 latent**, and the number did not
move when OUN-13 landed. That is not "the fix had no effect".

The harness was measuring the rule OUN-13 replaced: it counted any claim set of
two roots as a pair, so a plugin and its own contained skill counted, and so did
two plugins carrying the same skill name. Under qualified addressing neither is a
pair — `x` and `p:x` are two names. What it counts now is the two things that
are still wrong: two standalone units sharing a name in root (nothing in the
product can produce it), and the same unit present twice.

**All 11 are `skill-manager`** — homes holding the standalone beside skt's
copy. The count was right by accident and is now right on purpose. Each is
cleared by `skill-manager sync skt` in that home; the migration is verified
working by both routes, and 52 homes have simply not been run through it.

## What this ticket built, because measurement needed instruments

| instrument | what it decides |
| --- | --- |
| `scripts/measure_goal_the_front_door_is_found.py` | the eval suite, joined to the scorecard instead of living in prose |
| `scripts/measure_goal_a_home_survives_being_copied.py` | **the goal the epic could not report at all** — see below |
| `scripts/release_regression.py` | three guard-vs-independent-observation cross-checks |
| `specs/evals/results/attribution.yaml` | 38 rows separating product defects from instrument defects |
| six eval cases under `specs/evals/` | the real front doors, graded by a Stop hook that writes where sandboxed Bash cannot |

## Three findings this ticket produced that are worth more than the verdict

**1. A declared goal had no harness.** `GOAL-a-home-survives-being-copied-into-an-image`
was active, served by OUN-9/10/12, named by this ticket — and
`measure_goals.py` registered no harness for it. The epic could not report a
goal it had declared and done work toward. Now measured, and MET at 3 of 3.

**2. The epic branch was 7 commits behind `main`,** and three of them were
OUN-9, OUN-10 and OUN-12 — the tickets serving that same goal, delivered on
main deliberately because a home was about to be baked into an image. Every
validation run on this branch before 2026-09-12 measured a tree without
`CREDENTIAL_ROOT_FILES`, `PmPlatform` or `HomeCopyEconomics` in it. Merging
main in immediately produced two integration defects (`DEF-OUN-021`,
`DEF-OUN-022`) that neither branch could have caught alone.

**3. The measurement changed the population it measured.** Building the
copied-home harness found 11 frozen shims and 6 unstamped `pm/` trees across
two real homes — the code was fixed, the existing homes were not, and nothing
said so. `home repair` reported 0 findings on a home holding 17.
`DEF-OUN-018` added the two detectors; both homes now repair to zero.

## Evidence

- Goal ledger: `python3 scripts/measure_goals.py --epic one-unit-one-name`
- Eval archive: `specs/evals/results/runs/<case>/*.json`, newest per case
- Attribution: `specs/evals/results/attribution.yaml` (38 rows)
- Deferred: `results/epic-one-unit-one-name/deferred/backlog.yaml` (24 findings)
- Full test graph: 24 of 25 green; `artifact-dag` red on #292, pre-existing
  since 2026-08-29 and not from this epic
- Unit suite: `jbang RunTests.java` — all passed
