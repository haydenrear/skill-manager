# HIS-6 — the defects the terminal evaluation found

**Build under test throughout:** `skill-manager 0.24.0+g17648705e67b`
(`17648705e67b`, `feature/218-his-6`, which is `epic/home-integrity-sync` @ `1764870`
plus this ticket). Where a measurement used a *different* build it says so on the line.

**Which build produced each home measured**, because a home built by an older CLI
measures an older product:

| home | its CLI pin | that build |
| --- | --- | --- |
| root `~/.skill-manager` | `/opt/homebrew/Cellar/skill-manager/0.24.0/libexec/bin/skill-manager` | **`0.24.0`, artifact `f61e97a46579`, built 2026-08-21T17:41:12Z** — the release. It predates HIS-21 and HIS-22 entirely. |
| project `<repo>/.skill-manager` | `/Users/hayde/IdeaProjects/skill-manager/skill-manager` | `0.24.0+g17648705e67b` — the epic tip |
| this worktree home | `<wt>/.skill-manager/bin/cli/skill-manager` | `0.24.0+g17648705e67b` |

---

## DEF-115 — `home verify` and `home repair` disagree about a SYMLINKED shim into another home. BLOCKING; escalated, not deferred.

Two throwaway homes, real CLI, one run each:

| A holds | `home verify --home A` | `home repair --home A` |
| --- | --- | --- |
| a **symlink** `bin/cli/linktool` -> a file in B | **exit 1** `FOREIGN_HOME bin/cli/linktool` | **exit 0** `✓ nothing … is damaged in a way this command knows about (0 entries examined)` |
| a **wrapper** `bin/cli/wrappertool` whose text execs the same path *(control)* | **exit 1** `FOREIGN_PATH_IN_SHIM bin/cli/wrappertool` | **exit 1**, same subject, with a repair line |
| **both, in one home** | exit 1, names **two** | exit 1, names **one** |

The control is what makes the arm readable: the two readers *can* agree, so the
disagreement is about the surface and not about the fixture. Row three is the sharpest
form — one home, one minute, two readers, **different subject sets**.

**Why it is structural.** `HomeRepair`'s foreign-shim detector consumes
`HomeCloner.scanShimDirs`, which extracts absolute paths out of a shim's **text**. A
symlink has no text, so `home repair` can never report this shape — while
`home repair --help` advertises, unqualified, *"shims pointing into another home"*, and
`home verify`'s own header tells the reader *"`home repair` reads the same two
directories"*. True of the directories, false of the conclusion. An agent that runs
`home repair`, reads `✓ … (0 entries examined)`, and concludes no shim reaches another
home has been misled by both help texts at once.

**This is DEF-104 with the mirror flipped, and that is the part worth arguing about.**
DEF-104 was: verify saw symlinks and was blind to content; repair saw content. HIS-21
taught verify to read content and declared *"one extraction rule … and one verdict …
for both readers, because two spellings of the question is what DEF-104 WAS."* The half
never done is repair learning to read **links**. The asymmetry was closed in one
direction and left open in the other, and **no graph and no unit case compared the two
readers on a symlink**, because every existing comparison plants a wrapper. The review
of PR #256 got one link closer (MAJOR-1, a symlinked `bin/cli` *directory*) and stopped.

`GOAL-one-home-one-answer` clause 1 fails on this. **Reported as measured, not fixed** —
fixing it would be repairing the product to make this ticket's own goal pass.

**Check left behind:** `DamagedHomeIsRepairableTest` — *"DEF-115: verify and repair
return the SAME verdict on a SYMLINKED shim into another home"*. **RED on the tree that
ships it, deliberately.** Both in-run controls are green, so the red is the claim:

```
[FAIL] DEF-115: … verify said [bin/cli/linktool, bin/cli/wrappertool];
                  repair said [bin/cli/wrappertool]
```

Caught by **B** (issue agent on #110); confirmed independently by **C** with a
two-hazards-in-one-home form the agent did not run.

---

## DEF-116 — `home sync` arms no drift gate, and a javadoc says it does. BLOCKING; escalated.

`DriftGate` exists so *"an agent cannot keep acting on a skill that moved underneath
it"*. `home sync` is how a skill moves underneath an agent in this epic. It records
nothing. Measured, with a positive control over the identical disk state:

```
ARM      home sync --from proj --to root --unit his6-probe    (bytes changed at root)
         home drift --home root            ->  ✓ no unread change          EXIT=0
CONTROL  home drift --home root --record   (same bytes, same minute)
                                           ->  ! 1 unit(s) changed …
                                                 modified skill:his6-probe EXIT=8
```

Same home, same bytes, two answers; the only difference is whether an operation
bothered to record. Exhaustive enumeration of every `DriftGate.<static>` use in
`src/main/java` (9 uses; control: `DriftGate.` matches 13 lines) gives **two**
production recorders: `ProjectSyncUseCase` (`project sync`) and `home drift --record`.
`HomeSync.java` touches neither `DriftGate` nor `HomeDigest`. Neither does `install`
or `upgrade`.

**The false claim is in the code, and it was written to justify a decision.**
`HomeCloner.rebaselineDrift`'s javadoc argues that dropping an inherited gate does not
weaken the gate because *"every later sync, pull, install or `home sync` into this copy
still diffs against this baseline and still records a pending change, and the tests
that assert that are untouched."* Three of those four verbs record nothing. That is the
DEF-021 family — javadoc drift — in the load-bearing position.

**What it costs `GOAL-gate-settles`:** clause 2 holds for the operation the tests drive
(`recordSince` called directly, or `project sync`), and not for `home sync`. The
clause's fixtures all call `DriftGate.recordSince` themselves — many honest cases, one
oracle, and the oracle sits downstream of the *recording* rather than of the *command*.
That is HIS-22's "six honest probes sharing one oracle", one layer out.

Caught by **C**. Missed by A and B.

---

## DEF-117 — `list`, `show` and `show --json` report a commit the store no longer holds

After a promotion moved new bytes into a home, the store working tree differs from the
recorded `gitHash`, and every inventory reader still prints that hash with no marker:

```
$ skill-manager list                              -> his6-probe  skill  -  3  e1f65e9  local_file
$ git -C <home>/skills/his6-probe log --oneline   -> e1f65e9 v1
$ git -C <home>/skills/his6-probe status --short  ->  M SKILL.md
$ grep EDIT <home>/skills/his6-probe/SKILL.md     -> WORKTREE EDIT v2 …
$ skill-manager show his6-probe --json            -> "sha": "e1f65e9372…", "shortSha": "e1f65e9"
```

**Control:** `his6-direct`, a clean checkout at the same moment, renders identically —
so nothing in the output separates "this home holds exactly the published bytes" from
"this home holds something else". `home sync` knows (`locally modified`); `skt status`
knows (`[edited]`); `list` and `show` do not, and `list` is the first thing an agent
runs. Two readers, two answers, about content rather than membership.

Caught by **C**. Missed by A and B.

---

## DEF-118 — `home describe --json`, the persisted `home.runtime.json`, and `gateway status` still claim `owned: true` for a home that declares nothing

The regression ledger left this open with a justification: *"Latent today: no in-repo
consumer reads that field to decide anything. HIS-6 owns the goal and should decide
it."* **The justification is false**, and that is the finding rather than the divergence.

| home | `home describe` (human) | `--json` | `gateway.properties` |
| --- | --- | --- | --- |
| root | `(owned — declared in gateway.properties)` | `true` | yes |
| project | `(no gateway.properties here — … claims no ownership of it)` | **`true`** | no |
| a fresh worktree home | same as project | **`true`** | no |

`home describe --write` prints *"claims no ownership of it"* and writes
`"owned": true` into `home.runtime.json` in the same invocation. `gateway status` says
`mode: owner` for the same home, and its own source comment calls `mode` *"the discovery
contract: another home reads `mode` to learn whether this endpoint is one it may
manage."*

**And the flag is load-bearing.** The guard is `if (!gw.owned() && !force)`. An
undeclared home has `owned() == true`, so it never fires for anything
`bootstrap-home.sh` produces:

```
$ skill-manager gateway down --dry-run       (from an undeclared worktree home)
  DRY RUN — no changes will be made
  effects (1):  → [1] stop gateway at http://127.0.0.1:51717         EXIT=0
```

No refusal, and a plan to stop the operator's shared gateway. In practice
`GatewayRuntime.stop()` keys off a local `gateway.pid` that is absent, so the stop is
skipped and the command exits 0 having reported a stop it did not perform.

**A positive control isolates it:** three scratch homes on a non-default port —
declared-owner, declared-attached, undeclared — show the human surface has **three
states for three facts** and JSON has **two states for three facts**. JSON cannot
express "undeclared" and collapses it into "owner".

**No test pairs `--json` with the gateway.** `HomeReportsMarkWhatTheyInventTest`, the
DEF-106 suite, asserts only against `r.out`; its own comment cites the JSON evidence —
*"The root home's descriptor declares `{"gateway": {"owned": true}}` … Two homes cannot
both own a port"* — and then never asserts on it. The bug the test's prose diagnoses is
the bug the test does not cover.

**Bonus, measured on this machine and read-only:** the process actually listening on
the shared endpoint is a **leaked test fixture** — an HIS-14 sandbox home under
`/var/folders`, alive since 2026-08-22, still growing a 17 MB `gateway.log`, serving
`tool_count: 0`. The root home's confident `(owned — declared in gateway.properties)`
names a PID dead since before that. Three homes report `status: up` over a broker with
no tools.

Caught by **B** (issue agent on #132). Missed by A and C.

---

## DEF-119 — `bootstrap-home.sh` picks the CLI from `PATH`, so the epic's own front door does not run the epic's build

`pick_cli()` prefers `command -v skill-manager` over any checkout-local build. On this
machine that is the ROOT home's pinned shim, which execs the main checkout's build.
Differential, same source home, same kind of destination:

| CLI | result |
| --- | --- |
| PATH shim (main build) | **exit 1**, 5 x `FOREIGN_HOME`, no `home.provenance.json`, no agent homes created |
| `<checkout>/skill-manager` (epic tip) | **exit 0**, provenance written with `parentStores`, the same five shims **sanctioned**, `home verify` exit 0 |

The epic's fix works; the epic's scaffolding cannot reach it, because the standard
ticket procedure exports `SKILL_MANAGER_CLI` *after* running bootstrap. **Three of the
four evaluation agents this ticket dispatched hit it**, in a repository where every
ticket starts with that script. It is exactly the class of fact
`GOAL-progressive-disclosure` exists about: something every agent needed and no tier
disclosed.

Caught by **B** (three agents independently). Missed by A and C.

---

## DEF-120 — a `home sync` that reconciled nothing exits 0, under a header saying it did

```
$ skill-manager home sync --from A --to B --unit tracing-observability
    unit: tracing-observability  (only this unit was reconciled; the rest of the home
                                  was not visited)
    held-back  skill:tracing-observability — locally modified; re-run with --merge …
    0 unchanged, 0 updated, 0 new, 0 merged, 1 held back, …                EXIT=0
```

Whole-home syncs do the same: `! 1 unit(s) were not reconciled and were left exactly as
they were`, exit 0. The counts line is correct; the exit code and the header are not.
Related, same command: `--unit` matches on **name across kinds**, so `--unit test-graph`
can reconcile `skill:test-graph` *and* `plugin:test-graph` under a header that says
"only this unit", and the `--json` `unit` field carries the bare name with no kind — so
issue #182's "record selected scope so automation can prove nothing else was eligible
to move" is not literally provable from the JSON.

Caught by **B** (issue agent on #182). Missed by A and C.

---

# Two findings about the epic's SCAFFOLDING that are worth more than most of the above

## The containment breach, and what caused it

At 19:15 during this evaluation a unit named `eval-skill` appeared in the **operator's
project home** — a home this ticket and every agent it dispatched were told in bold
never to write. Evidence is preserved read-only in
`evidence/containment-breach-project-home.txt`. Nothing was deleted: the bytes are the
evidence, and removing them is another write to the same forbidden home.

**The environment was pinned.** The agent's `SKILL_MANAGER_HOME` named its own worktree
home, set inline in the same shell. The destination did not come from the environment:

```python
# skill-publisher-skill/src/skt/publish.py — _parent_home(), worktree tier
main_tree = ctx_mod._git("worktree", "list", "--porcelain", cwd=root)
candidate = Path(main_tree.splitlines()[0].split(" ", 1)[1]) / ".skill-manager"
```

`skt publish` derives the destination from the **git worktree topology**, not from the
pinned home. That is deliberate and mirrors `bootstrap-home.sh`. The defect is what
surrounds it:

1. **The cross-home write happens before the arguments the second leg needs are
   validated.** `run()` performs `home sync … --to <parent>` and only then computes
   `ticket = ticket or _infer_ticket(start)`. The run failed at leg 2 with *"a ticket is
   required to publish a unit"* — a pure argument check — **after** an irreversible
   write into another home, and the tier-up write is not rolled back.
2. There is no `--dry-run` and no prompt on a command whose first act is to write a home
   the caller did not name.

**And both readers call the resulting home clean.** Read-only, at the epic tip:

```
$ skill-manager home verify --home <project home>   EXIT=0  ✓ every reference … resolves
$ skill-manager home repair --home <project home>   EXIT=0  ✓ nothing … is damaged
                                                              (16 entries examined)
```

The home holds a unit in `skills/` with a `.materialization/skill/eval-skill.json`
record and **no `installed/eval-skill.json`** — a shape neither reader looks for.
`GOAL-no-destructive-recovery` clause 2 asks that a damaged home be *"detectable by a
command rather than by an operator noticing"*. This one was detected by an operator
noticing, and the operator was this ticket, by accident, because a later `home clone`
inherited the foreign unit and an inventory came back one line longer than expected.

### The remedy — CORRECTED, and this time it was run before it was written

**The version first published here was wrong in three ways and had never been executed
against anything.** It read `skill-manager remove eval-skill --home <project home>`.
Measured against a synthetic home carrying this exact shape:

| what was published | what it does |
| --- | --- |
| `remove … --home <home>` | **`--home` is not a flag** of `remove` or of `uninstall`. `Unknown options: '--home'`, **exit 2**, nothing happens. |
| drop the flag, pin `SKILL_MANAGER_HOME` | removes the unit, but **leaves `.materialization/skill/<unit>.json` behind** — the exact half the prose claimed a raw `rm` would miss, so its own verification step would have failed |
| either form | **writes first**: `reconcile: onboarded=1` manufactures an `installed/<unit>.json` in the target home before removing anything |

A remedy aimed at the operator's home that had never been run is what issue #142 is
about, and this one had been published in three places. It was the single blocker at the
review of PR #257.

**Worse, and new: the safe way to check such an instruction is not safe.**
`remove --dry-run` — documented as *"Print the effects the program would run **without
mutating filesystem or gateway**"* — **writes `installed/<unit>.json` and leaves it
there**, on precisely this damage shape. Control: the same `--dry-run` on a
properly-installed unit in the same home changes **zero** paths, so the trigger is the
absent `installed/` record. Filed as **DEF-122**, blocking.

**The corrected remedy, run verbatim against a synthetic reproduction, with controls and
a negative control** — full transcript in `evidence/def121-remedy-transcript.txt`:

```
# 0. scope it. On the operator's real project home this returns EXACTLY two paths,
#    and units.lock.toml does not name the unit: 0 hits, control 4 unit rows,
#    and the control term `deploy-helm` hits 21,781 files, so the grep works.
grep -rl eval-skill <project home> --exclude-dir=.git
grep -c  eval-skill <project home>/units.lock.toml

# 1. remove the two paths. There is NO CLI verb for this shape: the unit was never
#    installed, so nothing in installed/ or units.lock.toml has to be kept in
#    agreement — and `remove`/`uninstall` would MANUFACTURE a record to delete it.
rm -rf <project home>/skills/eval-skill \
       <project home>/.materialization/skill/eval-skill.json

# 2. verify. Every line must be true.
#    unit directory gone · materialization record gone · installed/ record ABSENT ·
#    grep -rl returns nothing · CONTROL the four resident units still installed and
#    byte-unchanged · CONTROL units.lock.toml still has its 4 rows
```

**Do not over-read the absence of a CLI check in step 2.** `home verify` and
`home repair` exit 0 on the **damaged** home as well as the repaired one — that is
DEF-121 itself — so they are not a check on this remedy. The file-level verification,
with the negative control showing those same lines reading `NO` on an unrepaired home,
is.

**Still not performed on the operator's home.** That is the owner's act.

Filed as **DEF-121**.

## The evaluation's own instrument leaked between its agents

Two of the four evaluation agents wrote `env.sh` to the same session scratchpad path.
One overwrote the other between write and read, and an agent's first `home describe`
consequently ran **against a sibling agent's home**. It was caught only because the
command echoes its resolved paths; nothing in the transcript would otherwise have said
so. Any parallel evaluation using a common scratch filename is silently
cross-contaminated. This belongs in the vacuity ledger rather than the backlog: it is an
instrument that could not report its own invalidity.

---

# DEF-108 triaged — neither a defect nor a flake

Round 1 left `git-latest-source-tracking/gls.conflict` red and untriaged: *"either a
real defect or an undeclared flake."* It is **a stale assertion**, and the epic's own
fix is what reddened it.

Reproduced identically in round 3, at a tip two tickets later, in a third of the time
(42.9 s vs 133.2 s), with the same four assertion outcomes:

```
failureMessage: "rc=8 conflict=true markers=false UU=false"
  exited_with_rc_8               passed
  conflict_logged_with_filename  passed
  conflict_markers_in_skill_md   FAILED
  working_tree_shows_unmerged    FAILED
```

Deterministic, so not a flake. And the two failing assertions require the CLI to
**strand** the unit's repository in a merge conflict — the condition
`GOAL-symlink-merge-settles` exists to remove, whose baseline is *two units in the
operator's project home permanently stuck in `MERGE_CONFLICT`*. The CLI now rolls the
merge back and prints a remedy:

```
✗ git-latest-fixture: merge conflict in 1 file(s): SKILL.md
✗   nothing was changed — the merge was rolled back, so the store is exactly where it
    was … then `skill-manager sync git-latest-fixture --home …`
```

So a core graph was asserting the pre-goal behaviour and nothing noticed, because
`run.py --all` had never completed a sweep (DEF-011). **This is the second-best argument
in the epic for running the full sweep**, after DEF-107.

### Four corrections to that argument, from the review of PR #257

The conclusion survives all four and the accounting was exact, but the reasoning was
loose in four places and a terminal evaluation should not be:

1. **The goal was misquoted, twice.** `GOAL-symlink-merge-settles` is about *an in-unit
   symlink whose dereference leaves a merge conflict its own printed remedy cannot
   clear*. `gls.conflict` appends conflicting lines to `SKILL.md` — no symlink — and the
   node clears the state itself with `git merge --abort`. The node exercises the same
   **rollback-versus-strand** behaviour the goal is about; it is not an instance of the
   goal. "Evidence the behaviour changed" is right; "a second witness for that goal" was
   too strong.
2. **The commit was nameable and I wrote "whichever ticket changed it" twice.**
   `git log -S "rolled back"` gives **`f993ae7`**, *"fix(sync): a dereferenced store
   link is the materializer's work, not an author's"* — HIS-4's commit, for this very
   goal, and its body names the goal's own baseline (*"2 of 21 change-managed units
   stuck for nine days"*). Resolved with `git rev-parse --verify f993ae7^{commit}`
   before being written here, per the ledger's rule about SHAs in evidence. One command,
   and a vague claim becomes a checkable one.
3. **The control I named does not discriminate.** I said each inverted assertion is
   "paired with a control" and pointed at `precondition_both_sides_diverged` — which
   re-reads files the *node itself* wrote and is, by its own comment, *"blind to what
   the CLI did"*. It proves the fixture was built; it cannot tell a rollback from a
   strand. **The real protection is the two assertions I booked as "unchanged"** —
   `exited_with_rc_8` and `conflict_logged_with_filename`. A fixture that never
   conflicted fails both. That is what stops the inverted assertions passing vacuously,
   and I credited the wrong thing.
4. **And the asymmetry, which is the criticism worth taking.** For DEF-115 — the finding
   that would move a grade **FAIL → PASS** — I refused to touch the subject. For
   DEF-108 I **rewrote a core graph's assertions** and then cited the rewritten graph as
   a witness for that goal's **PASS**. Same session, opposite discipline, and *the
   relaxation happened on the goal that stayed green.*

   **The defence, and it is a real one:** the two acts differ in kind. DEF-108 changed a
   *test* that had gone out of date with the product; DEF-115 would have changed the
   *product* to move a verdict. The rule permits the first and forbids the second,
   deliberately. **The criticism lands anyway**, because I did not notice the asymmetry,
   did not price it, and then leaned on the rewritten node as *supporting evidence* for
   the goal it sits under — which is the step that should have been refused.
   **Correction:** the `gls.conflict` repair is reported as a harness fix only, and
   `GOAL-symlink-merge-settles` is decided by `sync-settles` and its TLC invariant
   **alone**. The repaired node is not cited for it.

### And an unfiled defect was sitting in this ticket's own evidence

The reviewer found it by reading the recheck log I had already published — nothing was
re-run:

```
✗ nothing was changed — the merge was rolled back, so the store is exactly where it was
✗ conflicted — … resolve + `git add` + `git commit` mid-merge, or `git reset` …
```

Two contradictory remedies in one output. The second instructs the operator into a
mid-merge state the first says was rolled back, and my own graph assertions confirm
there is no merge in progress. **A printed remedy that cannot clear the condition it
names is the literal wording of `GOAL-symlink-merge-settles`** — sitting in HIS-6's own
evidence for that goal. Filed as **DEF-123**.

**And the mechanism is narrower and more familiar than "two code paths".** Read at
`src/main/java/dev/skillmanager/app/ReportUseCase.java:249-251` (not
`…/reporting/…`, which does not exist): the contradictory sentence is the
**`storeDir == null` fallback**. The method already has correct branches — one for a
genuine mid-merge (`GitOps.isMidMerge`, line 253) and one for a failed stash pop (line
257). The bad line fires only when the reporter **does not know which directory to
name**. Yet the rollup line in the very same output names it:
*"Commit or drop the local work in `/var/…/skills/git-latest-fixture`"*.

So the information existed in the run and did not reach this reader. That is the
DEF-104 / DEF-115 family again — **two readers of one condition, one of them
better-informed, and no channel between them** — which makes it the third instance in
this ticket alone and the argument for treating it as a class rather than as three
defects.

It does not overturn the goal: clause 1 is *"0 units left in `MERGE_CONFLICT`"*, and 0
units are — the rollback works and the **first** remedy is correct and runnable. What
fails is the second line's accuracy.

**Harness fix, made.** `GlsConflict.java` now asserts the current contract. The two
assertions that were always right are unchanged; the two that encoded the stranding are
inverted, and each is paired with a control, because *"no markers"* is exactly what a
fixture that never conflicted also reports:

- `precondition_both_sides_diverged` — blind to what the CLI did; reads only the bytes
  the fixture wrote
- `DEF108_store_holds_no_conflict_markers`
- `DEF108_store_bytes_unchanged_by_the_refused_merge` — the pre-sync bytes are captured
  before the run, because a claim about survival needs the thing that must survive
  recorded first
- `DEF108_working_tree_has_no_unmerged_path`
- `DEF108_remedy_names_the_store_directory`

**This changes `graphs_passed`, and the scorecard reports both numbers separately.**

---

# A correction to my own instrument, recorded because it nearly cost a wrong headline

Reading the first `gls.conflict` envelope I printed each assertion with
`'PASS' if a.get('passed')` — but the field is `status`, not `passed`. Every assertion
printed FAIL, including the two that passed, and my first reading of DEF-108 was
"all four assertions fail". It was caught only because the number was *implausible*:
the captured node log plainly contained both the word `conflict` and the string
`SKILL.md`, which is exactly what `conflict_logged_with_filename` tests.

Vacuity-ledger mechanism C, in the medium the ledger added last: **a reader that cannot
report a pass**. It is the same shape as `grep -E "a\|b"` and `grep DEF-0` — an output
indistinguishable from the correct answer at a glance — and it happened to the agent
whose brief quoted those two rows.
