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

---

# Run 6 — the first run whose failures are the product's

Fixture rebuilt properly: the workspace is now made by `build-env.sh` with the
product's own commands (`git init`, then `skill-manager home clone`), and the
hook only copies it in. Score 0.50, Bash 18x, $0.88.

The count is still high, and this time **the trace says why, and it is not the
harness.** `skt ticket new` was reached at call 8 and failed three times:

```
1) usage: skt ticket [-h] [--base BASE] [--path PATH] …        exit 1
2) error: cannot resolve base 'aad7f036bb5ab2310a1b3a0904d385a0c37dd751'
   fix:   git fetch origin, then pass --base <existing-ref>     exit 1
3) error: working tree is not clean — an epic worktree pins its base
          from a clean slate                                    exit 1
```

Everything after call 8 is the agent diagnosing those, including probing whether
it may write into `.git` at all.

## Finding 1 — mine, and fixed

(3) is the fixture. The home was copied in *after* the initial commit, leaving
`.skill-manager/` untracked, so the tree was not clean. A real epic checkout
gitignores its per-checkout home; the fixture now does too and the workspace
comes up clean on `epic/demo-epic`.

Same shape as the very first thing that happened in this session, when a
leftover `skill-dev-skill/` blocked `skt ticket new` for exactly this reason.

## Finding 2 — product, and the single-CLI-update kind

`--base <sha>` is refused with **"cannot resolve base"**, and the remedy offered
is `git fetch origin, then pass --base <existing-ref>`. The SHA was valid and
present *locally*; the repository simply has no `origin`. So the advice cannot
be followed, and an agent that does exactly what the error says gets nowhere.

Two candidate fixes, both small:

* resolve `--base` against the local object database before asking for a ref,
  and only mention `git fetch` when the object genuinely is not present; or
* keep the refusal and change the remedy to name the local case —
  *"pass a ref that exists here, or `--base HEAD`"*.

This is precisely the class the suite was built to find: **an agent is stopped
by a message it cannot act on, and one CLI change unblocks it.**

## Finding 3 — product, smaller

The agent's first invocation was a usage error. `skt ticket` takes
`{new,close,info,list,sweep}` then `ticket_id`, and the agent guessed a
different form. Worth checking whether `skt ticket --help` leads with the shape
of a call rather than with the option list.

## What the suite has now proved it can do

Three actionable findings from one $0.88 run, two of them product defects with
one-line fixes, and the third an eval-config bug that the trace distinguished
from the other two. That distinction is the whole value: without the trace, all
three read as "the agent could not provision a worktree".
