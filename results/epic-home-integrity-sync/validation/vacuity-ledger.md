# Vacuity ledger — every assertion this epic caught passing against broken code

**Tracked for HIS-6.** The owner's instruction at the wave-6 status review:

> *"Each of them should have updated assertions so that the test graph catches. It's
> an opportunity to fix as well as a hole in the process. The great thing about
> updating assertions is it doesn't take a whole other graph. And they're quick."*

So this file is two things: the count HIS-6 reports, and a **work list**. Every row
names the assertion that would have caught it, where that assertion goes, and
whether it exists yet.

---

## The eleven instances

| # | ticket | what happened | caught by |
| --- | --- | --- | --- |
| 1 | HIS-1 | first regression test passed without the fix — `describesSource` rescued it because both trees were left pristine | author |
| 2 | HIS-7 | first graph node passed with the guard disabled — a two-tier fixture has no inherited symlink for `cat >` to follow | author |
| 3 | epic | the obvious `/var` fixture would have passed **both** ways: `/private/var/X` contains the substring `/var/X` | epic agent, before it was written |
| 4 | HIS-9 | row M: a test-only backend made `installOne` return `SKIPPED` before any check ran | author |
| 5 | HIS-4 | probe 7a reddened a **precondition** ("the escrow took something"), not the claim | author |
| 6 | HIS-12 | V10 confessed vacuous — the fixture carried no line the pin extractor keys on | author |
| 7 | HIS-12 | V19 found a gap in the guard it was checking — the pattern needed a `+`, so the assignment form walked past | author |
| 8 | HIS-14 | V8: **neither "frozen" fixture was frozen** — both wrote `policy.toml`; the file is `home.policy.toml` | author |
| 9 | HIS-15 | V1 reddened a precondition — **inside the guard written to stop exactly that** | author |
| 10 | HIS-11 | the escrow probe reddened "something was taken", not "it came back" | author |
| 11 | HIS-17 | the walk was widened on the **regular-file** branch and the only control added was a **symlink** decoy — a branch production decides earlier and by different rules. The claim reddened under every probe, and the widening was tested by none of them | **reviewer** |

**Eight of eleven were caught by the ticket's own author**, three by a reviewer or
the epic agent. That ratio is the process working. The rate not falling is the
finding — and row 11 is the first one no existing mechanism describes.

---

## Four mechanisms, not eleven accidents

Grouping them is what turns anecdotes into assertions. **D was added by HIS-17**,
because row 11 fits none of A, B or C — see below for why that matters more than
the count does.

### A — the probe reddens a *precondition*, not the *claim*
Rows **5, 9, 10**. The mutation makes the test fail, so it looks like a passing
vacuity check — but it failed on the setup, and the claim was never evaluated.

**The assertion:** a vacuity record names **which assertion reddened**, and a probe
is only counted when the reddened assertion is the claim. Preconditions are
declared as preconditions and are **stream-blind / mutation-blind** where possible,
so that reverting the fix cannot move them.

### B — the fixture cannot express the defect
Rows **1, 2, 3, 8**. The setup is degenerate in a way that makes broken and fixed
indistinguishable: two tiers where three are needed, two pristine trees, two
substring-compatible spellings, a filename that never took effect.

**The assertion:** a fixture **asserts its own preconditions**. HIS-14's V8 is the
model — it found that neither frozen fixture was frozen only because something
finally checked. A precondition that is not asserted is a hope.

### C — the mutation never reached the code
Rows **4, 6, 7**. The disable was applied to a path the run does not take, or the
pattern matched nothing, so a **green** result reads exactly like a passing check.

**The assertion:** the harness asserts `s.count(old) == 1` before mutating (DEF-035),
and the probe asserts the mutated path was **reached** — not merely that the suite
stayed green. HIS-15's exit-13 case is the model: it proves the code ran before it
proves what the code did.

### D — the probe exercises a *different branch* than the change
Row **11**. **The newest, and the one that hides best**, because it passes every
test the first three impose: the mutation compiles and runs, the fixture is rich
enough to express the defect, the mutated path is demonstrably reached, and the
claim genuinely reddens. All four readings are true and the change is still
untested — because the assertion that reddened is downstream of a *different*
branch than the one that moved.

HIS-17's blocker is the worked example. The walk was widened on the
**regular-file** branch of `HomeCloner.verifyRoots` — "this filename at this depth
is not a leak". The control added for it was a **symlink** decoy, and
`verifyRoots` decides symlinks *earlier in the same walk* and *without consulting
descent records or byte accounting at all*. So:

| probe | mutation | reddened | and yet |
| --- | --- | --- | --- |
| V1 | production's exemption removed | the cross-check | never touched the accounting |
| V2b | the isolation gate emptied | the decoy control | the symlink branch |
| V3 | a real state leak planted | the independent walk | the un-widened path |

Three probes, three genuine reds, and **the widened branch tested by none of
them**. It took a reviewer reading `plantDecoy`'s own javadoc — which *says* it
targets the branch decided "without consulting descent records… or byte
accounting" — to notice the control named its own disjointness out loud.

**V5 is what the mechanism looks like when you finally aim at the right branch.**
Production's accounting was loosened to a filename check, and:

```
production_refuses_a_descent_record_carrying_an_unaccounted_path   RED   <- the claim
production_still_refuses_a_planted_path_into_the_source            pass  <- the decoy sees nothing
production_agrees_no_path_in_the_clone_names_the_source            pass
home_clone_reports_clean / home_clone_exits_zero                   pass
independent_scan_finds_no_owned_surface_naming_the_source          pass
the_walk_and_production_agree_about_this_clone                     pass
```

**Every assertion the pre-review version shipped is in the pass column.** Four
readers, one wrong answer, one table.

**The assertion:** a probe names **which branch** it exercises, and a change names
**which branch** it moved; a probe is only counted against a change when the two
are the same. Where a rule has branches that are decided separately — different
inputs, different order, different evidence — each branch needs its own control,
and a control whose javadoc explains that it deliberately avoids the evidence
under test is naming a gap, not a strength.

**Why this is not just B with extra words.** B is a fixture too poor to express
the defect. Here the fixture expressed the defect fine — the descent record was
present, correct, and read on every run. The probe simply pointed somewhere else.
A richer fixture would not have helped; a differently-aimed probe was the only fix.

---

## Work list — assertions to add, and where

Cheap by design: these are assertions on **existing** nodes and existing suites, not
new graphs.

| for | assertion | goes in | state |
| --- | --- | --- | --- |
| A | a vacuity record names the reddened assertion, and it is the claim | the ticket template + `RunHis*` harnesses | **convention only** — not mechanised |
| A | preconditions are asserted separately and are blind to the mutation under test | each ticket's probe harness | HIS-15 did it; nothing enforces it |
| B | a fixture asserts its own preconditions before exercising the claim | unit suites | HIS-14, HIS-15, HIS-11 did it; **not general** |
| B | a spelling-pair fixture asserts the two spellings are **not** substring-compatible | `home-clone`, `sync-settles` | **HIS-17** |
| B | a tier fixture asserts the tier count it needs actually exists | `home-integrity` | HIS-7's node does; **not general** |
| C | the mutation harness asserts its pattern matched exactly once | every `RunHis*` | **DEF-035**, routed to HIS-8 |
| C | a probe asserts the mutated path was reached | unit suites | HIS-15 did it; **not general** |
| D | a probe names which BRANCH it exercises, and it is the branch the change moved | every `RunHis*` + probe record | **HIS-17** wrote it down; **convention only** |
| D | a rule with separately-decided branches has a control per branch | `home-clone` (done: symlink decoy + descent tamper) | **HIS-17**; not general |
| — | a home's **unit membership** is what the graph intended | new law beside `HomeFixpointLaw` | **HIS-16** |
| — | every private "does anything name another home" scan cross-checks production | `home-clone`, `checkout-home`, `artifact-dag` | **HIS-17** |

### What is already mechanised, and what is not

**Mechanised:** `HomeFixpointLaw` (24 of 30 graphs), HIS-12's remedy source-scan,
HIS-15's `--json` convention guard. All three caught something no enumerated list
would have — the remedy scan caught a **sibling ticket's** drift during a merge, and
the `--json` guard caught a failure path **created one promotion slot earlier**.

**Not mechanised:** every row above marked *convention only*. They are real practice
and they are held by discipline rather than by a check, which is precisely the
condition that produced eleven instances.

**The honest read for HIS-6:** the discipline is working — eight of eleven
self-caught — and the count is not falling, because eleven of the twelve
countermeasures are still enforced by care rather than by code.

**And the taxonomy was incomplete for ten instances before anyone noticed.** D was
not a new failure; it was a failure mode that had no name, so nothing looked for
it. That is worth more to HIS-6 than the count: a ledger of mechanisms is itself
an enumerated list, and enumerated lists are what this epic keeps catching out.

---

## Related findings

`DEF-035` (mutation testing with `git checkout` eats uncommitted work, **recurred
after being filed**, plus the `count == 1` clause), `DEF-021` (javadoc drift, eight
instances, six of which the proposed check would miss), `DEF-011` (`--all` has never
completed), `DEF-046`/`DEF-047` (a probe narrower than the condition it decides).
