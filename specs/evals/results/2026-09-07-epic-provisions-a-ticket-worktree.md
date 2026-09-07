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

---

# Runs 7-8, and a correction to how I was reading all of them

| run | Bash | cost | change |
|---|---:|---:|---|
| 7 | 15 | $0.79 | skt `--base` remedy + `--help` shape fixed |
| 8 | 27 | $1.44 | git-epic-workflow names where skt lives |

Both fixes were right and both numbers went the wrong way. The reason is that
**the case cannot succeed in this sandbox at all**:

```
git: error: couldn't create cache file '/var/folders/.../T/xcrun_db-…'
      (errno=Operation not permitted)
Preparing worktree (new branch 'feature/DEMO-1')            exit 3
```

`git` on macOS is an **xcrun shim** that writes a cache file into the system
TMPDIR. The eval sandbox denies that write, so `git worktree add` -- the
operation this case exists to measure -- dies after "Preparing worktree".
Every run since 4 has been an agent improvising against an impossible task, and
improvising differently each time.

## The correction

I quoted 14 -> 10 -> 9 -> 8 as a trend and attributed each drop to a fix. With
8, 18, 18, 15, 27 now in the same series, **the run-to-run variance is larger
than every effect I claimed to measure.** `plugin_evals.md` says so plainly and
I read past it:

> One run's score from one case is not evidence of much; if a number is going
> to be quoted, run it more than once.

I quoted single runs seven times. The three product fixes stand on their own
evidence -- a remedy naming a fetch the repository cannot do is wrong whatever
the eval scores -- but **the numbers did not establish them, and I presented
the numbers as if they had.**

## What the case needs before it measures anything

`git` must be usable in the sandbox. Candidates, none yet tried:

* a `TMPDIR` the sandbox permits, exported for the session, so the xcrun shim
  can write its cache;
* `XCRUN_NO_CACHE=1`, which the agent itself tried at run 5 -- worth testing
  as a session default rather than as a discovery;
* a real git binary that is not the Apple wrapper, if one is installed.

Until one of those holds, **this case is UNDECIDED, not failing**, and the
distinction matters: `file_exists` and `tool_used` have no undecided state, so
an environment that cannot run the task and a skill that cannot do it produce
the same score.

## What was actually learned, and it is not a number

Three product defects, each reachable only by watching an agent work:

1. `skt` refused `--base <sha>` with `git fetch origin` as the remedy, in a
   repository with no origin. **Fixed.**
2. `skt ticket --help` led with options rather than the shape of a call.
   **Fixed.**
3. `git-epic-workflow` gave the condition for using skt without saying how to
   test it; an agent checked `skills/`, where a plugin never is. **Fixed.**

258 passing skt tests saw none of them. That is the argument for evals as
evidence -- but the evidence is the TRACE, not the score.

---

# Runs 9-14: the environment, and where this stops

| run | Bash | cost | what changed |
|---|---:|---:|---|
| 9 | 19 | $1.15 | PATH in run.sh; launcher PATH separated from agent PATH |
| 10 | — | $0.00 | `execution.env` REFUSED: only `EVAL_*` keys allowed |
| 11 | 16 | $1.03 | env back in run.sh; hook stops advertising absolute paths |
| 12 | — | $1.24 | interrupted |
| 13 | 15 | $1.28 | git shim carrying TMPDIR — still failed: sandbox cannot read under the operator's home |
| 14 | 21 | $1.29 | build tree under `/private/tmp`; environment VERIFIED |

**The environment is now proved before any agent runs.** `verify_env` runs
`skt ticket new` for real in a throwaway corner of the fixture and fails setup
if it does not work. Five facts are encoded in it, each of which cost a failed
run to find and costs nothing to check:

1. **PATH is the only variable that reaches the sandbox** — `TMPDIR` and
   `SKILL_MANAGER_HOME` both came back `<unset>` inside a run, and
   `execution.env` refuses anything but `EVAL_*`.
2. Apple's `git` writes an xcrun cache into `TMPDIR` before doing anything, so
   a shim first on PATH carries it.
3. The home's `bin/cli/skill-manager` execs **jbang**, so jbang must be on the
   eval PATH or no home can be bootstrapped.
4. **The sandbox cannot read under the operator's home directory** — the same
   command works in a shell and fails in a run.
5. `/tmp` and `/private/tmp` are the same directory and not the same string to
   `home clone` (#330).

## One more product finding, from run 14

```
skt ticket new --help          →  exit 1, usage: skt ticket [-h] …
```

`--help` AFTER the verb is a usage error rather than help for `new`. The epilog
added at Finding 3 hangs off `skt ticket --help`, which is not what an agent
reaches for when it wants to know what `new` takes.

> **CORRECTION, 2026-09-07, on re-testing before fixing it — the exit code
> above is wrong and it is mine.** Re-run against `skt` at `cbf5061`, which is
> the exact commit this eval's home carried at run 14:
>
> ```
> $ out=$(skt ticket new --help 2>&1); echo $?
> 0
> ```
>
> It exits **0** and prints the flat `ticket` parser's help. There is no usage
> error and there never was; I recorded a code I did not measure, from one run,
> into a file whose whole job is to be evidence.
>
> **The defect is real and it is the second sentence, not the first.** Every
> flag of all five verbs, disambiguated in prose (`list/sweep:`, `epic mode:`),
> with nothing saying which ones `new` takes. That is worse than exit 1, not
> better: a wrong exit code announces itself, and an exit-0 non-answer does not.
> Fixed by per-verb help for all five verbs.
>
> Same failure as the fourteen Bash counts below and as the wrong root cause on
> #330: a single run read as a fact. The environment checks now block the class
> of defect that was costing runs; nothing blocks this one but re-testing before
> writing it down.

## Where the measurement stands, honestly

Bash counts across fourteen runs: **14, 10, 9, 8, 18, 18, 15, 27, 19, —, 16, —,
15, 21.** The environment improved monotonically; the counts did not. They never
approached the `max: 3` budget and the spread is wider than any change made.

**One run of one case is not evidence** — the reference says so, and this series
is the demonstration. Quoting a number from a single run is what produced every
wrong conclusion recorded above. A case needs several runs before its cost means
anything, and that is a cost decision rather than a technical one: at ~$1.20 a
run, five runs per case across six cases is ~$36.

## What the suite has actually bought

Four product defects, none reachable by a unit test, all found by watching an
agent or by a $0 environment probe:

* `skt` refused `--base <sha>` with a remedy the repository could not carry out
* `skt ticket --help` led with options rather than the shape of a call
* `git-epic-workflow` named the condition for using skt without saying how to
  test it
* `home clone` refuses a shim whose target its own message places inside the
  home — #330, and it blocks cloning a clone, which is what `skt ticket new`
  does for every ticket worktree

The environment work is done and reusable. The measurement work has not started.

---

# Run 15 (2026-09-07, after #330 and the skt help fix): the case has never been measurable

Score 0.25, 20 Bash calls, $1.23, 25 units loaded. **Do not read that score.**
The run established, from tool results rather than inference, that the
environment cannot support the task it asks for.

## What the trace says

```
19  Bash  (mkdir -p .git/refs/index-bases/probe …) ; (touch ./probe-write …)
    OUT   mkdir: .git/refs/index-bases/probe: Operation not permitted
          touch: ./probe-write: Operation not permitted
```

**The workspace is read-only inside the sandbox.** The case asks the agent to
create a worktree there. It cannot. No configuration of the skill, the PATH or
the prompt changes that.

Second, independently:

```
 4  Bash  ls -l $BUILD/shims/ ; command -v git ; $BUILD/shims/git --version
    OUT   ls: /private/tmp/skill-evals/…/shims/: Operation not permitted
          /usr/bin/git
          bash: /private/tmp/skill-evals/…/shims/git: Operation not permitted
```

**`$BUILD` is denied to the sandbox** — both the shims directory and, by the
same token, `$BUILD/home`. Proof that the home was denied too, not assumed:
`$BUILD/home/bin/cli` is PATH entry **2** and contains `skt`, and
`command -v skt` returned `/Users/hayde/.skill-manager/bin/cli/skt` — the
operator's live ROOT home, at entry ~20.

So the branched home has never been what the agent used. Every run in the
series above measured the operator's root home, reached through the PATH tail
that `run.sh` appends (`export PATH="$(eval_path …):$PATH"`), which defeats the
curation it is written to perform.

## What the agent actually did — attribution

**Not the skill's fault, and not the agent's.** It found the front door in
three calls (`command -v skt`, `skt --help`, `skt ticket --help`), ran the
right command with the right flags —

```
skt ticket new DEMO-1 --path ./wt-demo-1 --base HEAD
```

— and, when that failed on a ref it could not write, reported honestly that
nothing was created and nothing partial was left behind. The other ~15 Bash
calls were it diagnosing a broken `git` (PATH entry 1 denied, so `git` resolved
to `/usr/bin/git`, the xcode-select stub, exit 72) and then a read-only tree.

**Every one of those calls is a harness defect billed to the skill.** The
grader counted 20 Bash calls against `max: 3` and scored 0.25. A skill that did
its job scored a quarter because the environment could not let it finish.

## What this retro-invalidates

The fourteen-run series above — 14, 10, 9, 8, 18, 18, 15, 27, 19, —, 16, —, 15,
21 — was not measuring progressive disclosure. It was measuring agents
improvising against an impossible task in an environment they could not write
to, using a home nobody intended. That is why the counts never moved with the
changes: the changes were not in the causal path.

It also corrects a claim I filed in tla-spec-dev#326. "PATH is the only
variable that reaches the sandbox" is true. The accompanying belief that the
branched home on that PATH is therefore what the agent uses is **false** — the
path has to be one the sandbox permits, and `/private/tmp/skill-evals/…` is
not. The workspace home at `<cwd>/.skill-manager` IS reachable (its `bin/cli`
listed 12 shims), which is where a fix should point.

## The open question, which is a design decision and not a patch

Making this case measurable needs the agent to be able to WRITE its workspace.
That is a `claude plugin eval` sandbox question, not a harness variable, and
the options differ enough to be worth choosing deliberately rather than
guessing at:

1. find the sandbox setting that grants write to the case workspace;
2. move the fixture inside whatever tree the sandbox already permits for
   writes, and address the home from there;
3. change what the case measures — grade the COMMAND the agent chooses rather
   than the worktree it produces, which is measurable read-only and is closer
   to the actual question (does it find the front door).

Fixed meanwhile, because they were unambiguous:

* `rewrite-case.py` computed the regenerated case and **never wrote it** — a
  no-op since the day it was added, invisible because the committed unit list
  happened to match what the build produced. When the source home changed from
  the project home (10 units) to the root home (25), the case still named
  `../../units/eval-skill` and the whole run failed to load at $0.00. It now
  asserts its anchor and writes.
* The `/private/tmp` pin is no longer a workaround: skill-manager#330 is fixed
  and verified end to end on this shape.

**`run.sh`'s `:$PATH` is deliberately NOT fixed yet.** Removing the appended
operator PATH is correct in principle and would make things worse today: with
`$BUILD` denied, that tail is the only reason the agent reaches a working `skt`
at all. It comes out together with whichever option above is chosen.
