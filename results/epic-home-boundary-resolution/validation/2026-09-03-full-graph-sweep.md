# Full test-graph sweep — epic tip, 2026-09-03

`python skills/test_graph/scripts/run.py --all`, then the graphs the first
failure cut off, run individually.

| result | count | graphs |
| --- | ---: | --- |
| **green** | **28** | every registered graph except the two below |
| red — known, deferred | 1 | `artifact-dag` (DEF-HBR-003) |
| not run — deliberate exclusion | 1 | `hyper-experiments` (#143, three third-party services) |

## One red, and it is the one already on the books

`artifact-dag` fails at `uninstall.prunes.the.subgraph`, on exactly the two
assertions DEF-HBR-003 records:

```
the_census_names_nothing_the_removed_unit_owned   failed
the_home_is_byte_comparable_to_before_the_install failed
```

Same node, same pair, unchanged from when HBR-1 found it on the epic base.
Confirmed still red at the tip; not a regression from this epic's work.

## One red this sweep FOUND and fixed

`onboard` failed at `onboard.completed` — `skill-dev install failed … exited
79`. 79 is HOME_MISMATCH: `skill-dev-skill/skill-scripts/install.sh` asked
`command -v skill-manager` FIRST, got the operator's ROOT home entrypoint, and
that shim binds its own home and refuses to act on the scratch home the graph
had just built. The refusal was right; asking the wrong binary was the bug.

Pre-existing — the refusal landed 2026-08-21 and is on main, and HBR-1 fixed
this shape in two installers in this tree without reaching this third one.
**It had never been caught because the graphs do not run on push or PR.** This
is the first full sweep of the epic, and it found it immediately.

Escalated rather than deferred, per the epic's policy, because it blocked the
epic's own validation. `onboard` after the fix: 15/15.

## Gradle stops at the first failure

So `--all` is not a full sweep once anything is red. Four graphs never ran —
`skill-dev-smoke`, `home-integrity`, `onboarding`, `sync-settles` — and were
run individually afterwards. All four green, `skill-dev-smoke` notably so,
since the fix above is in the unit it covers.

Read the count, do not infer it: a sweep that stops early looks like a sweep
that passed if you only read the tail.
