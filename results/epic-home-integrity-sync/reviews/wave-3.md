# Wave 3 review — epic/home-integrity-sync

**Range:** `23e35c7..22b947c` · **Merged:** HIS-10 (#228 → #227, closing #206)
**Schedule revision:** 4 · **Review policy:** `cadence: wave`, `gate: true`

---

## What the wave was for

One ticket, and it was the keystone: make the readers of *"which home is this path
in, and does this home own it"* give one answer instead of three. Everything else
in the epic waits behind it — HIS-4 needs a usable worktree, HIS-9 needs to know
what a sanctioned link is before it can refuse an unsanctioned write, HIS-13 needs
a canonical reader to detect damage against.

## Goal movement

Measured on a real clone of this repository's project home, not a fixture:

| goal | clause | baseline | measured | verdict |
| --- | --- | --- | --- | --- |
| `GOAL-one-home-one-answer` | 1 — one verdict per scenario, no flag | **3** distinct verdicts | **1** | on track |
| | 2 — spelling-invariant | blind through a symlink | same verdict both spellings; vacuity-proved | on track |
| | 3 — lazy contract holds | 5 pruned, 6 re-provisioned, ~90 s | **0 pruned**, 2 installed (the two declared cold) | on track |
| `GOAL-home-invariants` | via HIS-10 | — | the sanction is a function of recorded descent, re-derived live | on track |

`on track`, not `met` — **#218 (HIS-6) decides these** on the integrated tip.

**Clause 3 has a caveat that belongs next to the number, not in a footnote.** See
*The benefit is gated* below.

## Integrated validation

Run on the merged tip `22b947c`:

| lane | result |
| --- | --- |
| `jbang RunTests.java` | ALL PASSED |
| `run.py home-integrity` | BUILD SUCCESSFUL (4m34s), 14 nodes |
| `run.py project-child-home` | BUILD SUCCESSFUL (3m51s) |
| `run.py harness-smoke` | BUILD SUCCESSFUL (4m39s) |
| `uv run pytest specs/` | 38 passed |
| `run.py --all` | **not run — and not owed.** See *Validation policy*, below |

Note the ticket agent **re-ran** `project-child-home` and `harness-smoke` after the
re-derivation change rather than carrying the earlier green forward. That was the
right call unprompted: re-derivation *narrows* the sanction predicate, and both
graphs build child homes with CLI deps, so a stale green there would have been the
wrong kind of evidence.

---

## The thing this wave should be remembered for

**The first version of the fix reproduced the defect it was fixing, one level up.**

The provenance record was **self-certifying**. Measured, by hand, on two homes:

```
child is NOT a registered child of parent; child/bin/cli/tool -> parent/bin/cli/tool
  home verify --home child   -> exit 1, 1x FOREIGN_HOME            [correct]
  echo '{"schemaVersion":1,"clonedFrom":"/nowhere",
         "parentStores":["<parent>"]}' > child/home.provenance.json
  home verify --home child   -> exit 0, 0x FOREIGN_HOME            [gate off]
  prints: "descent: this home was cloned from /nowhere; 1 recorded parent store(s)"
```

Anything that could write one file into a home switched that home's isolation gate
off permanently — and `clonedFrom: /nowhere`, a path that does not exist, was
accepted and reported as authoritative. Worse in context: the new `verify` output
**advertised the filename**, so an agent trying to clear a `FOREIGN_HOME` refusal
would have reached for exactly this.

And a second case needing **no hand-editing at all** — product operations only:
clone `proj`→`wt1`, then remove the parent's claim (what `ChildHomeRegistry.delete`
does on teardown). Now `verify proj` = 1 and `verify wt1` = 0 **on the same shim and
the same parent**; the pruner deletes in one tier and keeps in the other; cloning
`wt1`→`wt2` **re-mints** the claim. That is `GOAL-one-home-one-answer`'s own failure
mode, recreated on the tier axis by the fix for it.

**Both were caught by the adversarial reviewer, and the first was reproduced
independently before being acted on.**

## What landed instead

**The record is a pointer to evidence, not the evidence.** `sanctions` walks
`clonedFrom` hop by hop and asks `ChildHomeLink.isChildOf` **live** at each hop,
with a cycle guard and `MAX_HOPS = 16`:

```java
private static boolean rederive(Path home, Path foreign, Set<Path> seen, int hops) {
    if (hops >= MAX_HOPS) return false;
    Descent descent = read(home);
    if (descent == null) return false;
    Path from = recordedSource(descent);
    if (from == null) return false;
    if (!seen.add(realized(from))) return false;
    if (ChildHomeLink.isChildOf(from, foreign)) return true;
    return rederive(from, foreign, seen, hops + 1);
}
```

`parentStoresOf` derives at **write** time rather than copying the source's set
forward — a better answer than the re-check I proposed, because it removes the
carry-between-clones instead of validating it later. `parentStores` survives only
as a repair snapshot nothing consults. A relative `clonedFrom` is refused outright
rather than resolved against the process CWD.

**The cost is stated where the old javadoc promised the opposite.** That javadoc
chose this evidence because it *"needs no second home to be present, readable, or
even to still exist when the question is asked."* Re-derivation gives that up, and
**fails closed**: evidence that cannot be re-derived does not sanction. Worst case
is a worktree whose project home was deleted reverting to pre-HIS-10 behaviour — a
bad day, not a breach.

**Not claimed: forgery-proof.** A writer can still name some *other* home that
genuinely is a claimed child, and the chain re-derives. What is gone is the
*arbitrary* grant. This adds no new class of in-home forgery, because
`ChildHomeLink` already accepts a materialization record inside the judged home —
pre-existing trust, named rather than widened. That honesty is worth more than the
stronger claim would have been.

## Vacuity — seven disables, and they discriminate

This epic's failure mode has been *a branch nobody asserted on*: HIS-1's first
regression test, HIS-7's first graph node, the `/var` fixture, and then the
self-certifying record, which got through a full review-and-vacuity pass because
the only laundering assertion covered a source carrying **no** record.

| row | disable | result |
| --- | --- | --- |
| A–C | the three original fixes | green **against the trusting version** — i.e. the original suite could not have caught this |
| **D** | trusting `sanctions` restored | reddens exactly the two new cases |
| **E** | byte accounting removed | reddens exemption cases 2 and 4 |
| **F** | path test removed | reddens exemption case 3 |
| **G** | exemption removed entirely | reddens 1 and 3, and turns `home clone` itself red |

They fire on **different** cases rather than all together, which is what makes them
evidence rather than a checklist. Rows E–G exist because the agent flagged the leak
exemption as its own uncovered branch and offered to defer it; the answer was no,
and it then ran three disables where one was asked for.

The new graph node's metric was also changed from `allAgree ? 4 : 0` to
`readers.agreeing` / `distinctVerdicts` / `sanctioning`, so the goal can show
partial progress — the vacuity run reports **2/2/2**, which is the epic-tip
baseline the old boolean literally could not express.

---

## The benefit is gated, and the number should not be read without this

`skt sync`, `skt publish`, `skt check` and `bootstrap-home.sh` all resolve their CLI
from the **root home's pin**, which DEF-006 measured as Homebrew **0.23.0** — a
release predating this epic *and* the artifact-DAG epic before it. So a worktree
created through the documented front door is still cloned by a build that writes no
provenance record, is still refused exactly as before, and its first sync still
prunes five shims.

**"0 pruned, 2 installed" is true of the raw build only.**

That makes the failed **v0.24.0 release re-run** load-bearing rather than cosmetic:
it is what turns this fix from *correct* into *reachable*. It publishes a release,
so it is the owner's call. HIS-6 must also be told **which build produced the homes
it measures**, or its terminal numbers will be measured through the old code.

## Hot spots — where I would look first

- **`CliShimPruner` now deletes in fewer cases, which is the point — and keeps
  links alive longer, which is DEF-007's problem.** A sanctioned inherited link now
  survives *every* sync **because of this ticket**, and therefore lives until a
  `build --force` reaches `InstallerRegistry.takeOwnershipOfShim` — the same
  follow-the-link delete in a different method. Whoever fixes DEF-007 must cover
  **both** call sites, and the causal point is recorded there.
- **`HomeProvenance.read` still follows a symlink** (MED-5, DEF-009), so a record
  can be pointed outside the home. Its severity dropped materially with
  re-derivation — you would still need a real claimed-child relationship — but it
  is a trust-path read that follows links, in a ticket about home boundaries.
- **The exemption's encoding contract.** The accounting counts raw text and covers
  it with parsed values, so an escaped path makes the counts differ and the branch
  fails **closed** (finding stands) — safe. The unsafe mirror image, two different
  strings whose escaped forms coincide, **was not measured** and is named as
  DEF-009's.

## Decisions made implicitly

- **Re-derivation over annotation.** The reviewer offered a fallback — mark entries
  record-derived vs verified and let the destructive reader require a fact. The
  stronger option was taken. It is the right default, and it is a real behaviour
  change for any home whose ancestor is unreachable.
- **`--against`'s `srcRoot` arm was demoted, not deleted.** Deleting it turns every
  pre-HIS-10 copy red on a command that used to pass. The acceptance line
  "`--against` no longer decides" is therefore true **of a home this build cloned**,
  and is stated that narrowly. Removing the arm is a migration, paired with HIS-13.

## Deferred findings

- **DEF-007** — *widened.* MED-9's second half added, with the causal point above.
- **DEF-009** — *new.* MED-3 (silent fail-open in front of a delete path; `write()`
  is not temp + `ATOMIC_MOVE`), MED-4 (encoding contract), MED-5 (`read` follows a
  symlink), MED-8 (`rootSpellings` / `insideHomeText` have no named assertions),
  LOW-12, LOW-13. The agent's note that the real fix is *a shared durable-record
  writer, not six patches* is worth keeping.
- **DEF-010** — *new.* LOW-10 (per-file `rootSpellings`, 240 ms vs 168 ms over 4000
  wrappers), LOW-11, LOW-14.
- **DEF-006** — *open, unassigned, and now load-bearing.* See above.
- **DEF-005** — open. Every clone's first sync still exits 11 on `spec-double-compiler`'s
  `skill-imports`.

## Recommended next steps

**Wave 4 is HIS-4 (#216), HIS-9 (#226) and HIS-12 (#161), in parallel.** Conflict
keys are disjoint: `ChildHomeMaterializer`/`Rederivable` against the effect-boundary
write guard against `HomeDescriptor`/`ProjectCommand`. All three depend only on
work now merged.

**HIS-9 changed shape this wave** and should be dispatched with that understood: it
absorbed DEF-007 as instance (0), so its guard must cover **deletes**, not only
writes. `CliShimPruner.prune` opens `cliBinDir()` with `Files.isDirectory` +
`Files.list` — both follow a symlink — and deletes through it; a prune in `homeB`
emptied `homeA/bin/cli`, 2 entries → 0, reproduced twice independently.

**HIS-4 should finally use its declared worktree.** It is the ticket about homes,
the machinery now works, and wave 3 proved it end to end — bootstrap exit 0, five
inherited shims kept, bare `home verify` exit 0. It must be bootstrapped with
`SKILL_MANAGER_CLI` pointed at the epic build until DEF-006 is closed.

One decision, unchanged from wave 2 and now more consequential: **the v0.24.0
release re-run.**

---

## Validation policy — set by the owner at this gate

> *"--all takes forever. We'll do it once at the end with the goal/evals/scorecard."*

Recorded as the root `validation_policy` block in the canonical plan, and as
`owns_the_terminal_sweep` on **HIS-6**. The sweep is a **terminal** instrument;
no wave before HIS-6 owes a full run. Waves 1–3 each validated against a
hand-picked subset and reported it honestly as a subset — **that is now the
policy, not a shortfall against it.** What each wave still owes is that the
targeting be *argued* in its review rather than asserted: which graphs ran, which
did not, and why the subset covers what the wave changed.

**The decision raises DEF-011's second half rather than retiring it.** Running the
sweep once, at the end, is the right economy — and it is exactly when a chain that
stops at its first failing task costs the most. There is no earlier complete run
to fall back on, and **a terminal scorecard that dies on graph 1 is no scorecard**,
at the moment the epic most needs one. `--all` has never completed in this epic;
one lost teardown race in the *first* graph meant 1 of 24 executed and 23 never
ran, and the output for that is *"stopped at 1"*, which reads identically to *"only
1 graph exists"*.

So DEF-011(2) moved from *worth doing* to a **prerequisite for HIS-6**, owner
changed from unassigned to HIS-6. The options are in the backlog entry and the
choice is the owner's: `--continue` plus a tally, independent per-graph
invocations, or keep the chain and require `graphs_executed` to be read locally
the way CI already produces it.

## What wave 3 found in the instrument, and what it did about it

**Diagnosed, not guessed.** `smoke` failed twice at `servers.down / registry_down`
— same node, same assertion, so not a flake. An early theory that macOS
ControlCenter's port 5000 was colliding is **wrong** and is recorded as wrong:
`registry.up` binds a random port (52348 on the failing run). The real cause is in
`ServersDown.killByPidFile`:

```java
h.destroy();                                  // SIGTERM
try { h.onExit().get(5, SECONDS); }           // wait
catch (Exception e) { h.destroyForcibly(); }  // SIGKILL
return !h.isAlive();                          // sampled IMMEDIATELY
```

`destroyForcibly` is a request, not a reaping, and nothing waits after it. The
registry JVM (pid 70183) was **confirmed dead afterwards** — `ps -p 70183` empty,
no `SkillRegistryApp` surviving. The node reported a failure to stop a process
that had stopped.

**Not a wave-3 regression:** the failing path reads a pid file and signals a
process, and touches no home code. That is a code-level argument, which is
stronger evidence about causation than a bisect would have been.

**Fixed by the epic agent**, because rule 13 puts the epic's own machinery in
scope and this is the instrument rather than the product: wait for the reap after
SIGKILL, a graceful budget sized for a JVM shutdown rather than guessed, and the
node timeout raised so the wait cannot merely relocate the failure. The old shape
and the measurement live in the method's javadoc so it is not restored.

**Verified by the only control available for a harness fix:** `smoke` went
**red, red, green** — two reproducible failures, then `BUILD SUCCESSFUL in
16m 47s` — same machine, same graph, the fix as the only variable.
