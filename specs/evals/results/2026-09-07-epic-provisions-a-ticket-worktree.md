# epic-provisions-a-ticket-worktree — five runs, 2026-09-07

First eval suite ever run in this repository. Recorded in full because the
numbers are useless without it: **every movement so far has been a defect in the
harness, and none in a skill.**

| run | Bash | score | cost | what changed |
|---|---:|---:|---:|---|
| 1 | 14 | 0.25 | $0.92 | no toolchain hook |
| 2 | 10 | 0.50 | $0.67 | hook added — reported presence, not function |
| 3 | 9 | 0.50 | $0.63 | hook executed candidates, in the wrong environment |
| 4 | 8 | 0.50 | $0.55 | hook reports every candidate as a fallback chain |
| 5 | **18** | 0.25 | $1.19 | fixture hook added — **made it worse** |

Total $3.96 across five runs of one case.

## The one product finding, measured

Run 1 had no `skt` on `PATH`:

```
1. … command -v skt || echo "no skt on PATH"
11. head -60 "$S/scripts/bootstrap-home.sh"      ← read the script
12-14. reassembled its steps by hand
```

Run 2 had it, and that collapsed to one call:

```
10. skt ticket new DEMO-1 --path ./wt-demo-1
```

**A front door on PATH or it does not exist.** That is a one-line dev-loop fix
and it is worth more than the four runs that followed it.

## Why run 5 went backwards, and it is mine

The fixture hook builds a workspace with a `.skill-manager` holding
`installed/`, `skills/` and a `home.runtime.json`. That is **a plausible home,
not a working one** — no `bin/`, no policy, nothing a real command can use.

The effect is worse than having no home at all: run 4, with a bare repo, gave up
early. Run 5's workspace looked real enough to attempt the real command, which
then failed, and the agent spent calls 13-15 probing `TMPDIR` and write
permissions trying to work out why:

```
13. touch probe.tmp && echo "CWD WRITE OK" …
14. rm -f …/probe.txt && echo "RM OK"; touch …
15. for d in …/home …/home/.npm …/tmp /tmp/claude … (probing writability)
```

and calls 5, 6 and 16 back on `git-issue-workflow/scripts` — reading the
bootstrap script again, the exact behaviour the suite exists to catch, but
provoked by the fixture rather than by the skill.

**A fixture that is sloppier than the world invites the real command and then
fails it.** This is the third instance of that class in one day: the migration
work hit it with a fixture holding no git checkouts, and again with a unit
planted as a record with no lock entry. Same sentence each time — the state was
written down instead of being reached through the product's own operations.

## What the fixture actually needs

Either a **real home**, made the way homes are made — `home clone` from a source
home, or `bootstrap-home.sh` run by the hook before the agent starts — or the
case drops "with its own Skill Manager home" and measures worktree provisioning
alone. The first is truer and costs sandbox time; the second is cheap and
narrower. Not chosen yet.

## Calibration still open

`max: 3` has never been reached, and run 4's eight calls included one legitimate
orientation call and one git fallback. Whether orientation should count against
the budget is an owner decision, not something the harness should assume.
