# Vacuity ledger — every assertion this epic caught passing against broken code

**Tracked for HIS-6.** Fourteen instances, four mechanisms. The owner's
instruction at the wave-6 status review:

> *"Each of them should have updated assertions so that the test graph catches. It's
> an opportunity to fix as well as a hole in the process. The great thing about
> updating assertions is it doesn't take a whole other graph. And they're quick."*

So this file is two things: the count HIS-6 reports, and a **work list**. Every row
names the assertion that would have caught it, where that assertion goes, and
whether it exists yet.

---

## The fourteen instances

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
| 12 | HIS-13 | probe V6 removed `rewrite`'s no-op guard and **reddened nothing**: the branch only runs for a finding, and on the second repair the report is clean, so the idempotence claim is not downstream of it. Mechanism **D**, in the harness of the ticket that had just read row 11 | author |
| 13 | HIS-13 | `makeStale()` planted `bin/cli/never-built` — a name **no parent store holds** — as the oracle for clause 3 of the ticket's own goal. That is the one flavour of staleness the check under test could never report, so the assertion could not fail. A stock `home clone` was meanwhile being called damaged | **reviewer** |
| 14 | HIS-8 | the **widened sweep for a stale claim across four units** used `\|` inside `grep -E`, where it is a literal pipe — so every multi-term pattern searched for a string that cannot occur and returned a confident **all-zeros**. It reported three units clean of a claim it had never looked for. Mechanism **C** in a new medium: not a mutation that never reached the code, but a **search that never reached the corpus** | author |

**Nine of thirteen were caught by the ticket's own author**, four by a reviewer
or the epic agent. That ratio is the process working. The rate not falling is
the finding — and row 11 was the first one no existing mechanism described.

**Rows 12 and 13 are the more useful pair, and they should be read together.**
Both are HIS-13's, the ticket that shipped the epic's only *repairer*, written
by an agent who had read this file before writing its first assertion.

- **Row 12 is mechanism D recurring one ticket after it was named.** Naming a
  mechanism does not make it visible; the author looked for it, wrote a probe,
  and the probe still pointed at a branch the claim was not downstream of. It
  was self-caught only because the probe came back **all green**, which is the
  one signal a vacuous probe cannot hide. That is an argument for running every
  probe and reading its colour, not for trusting the taxonomy.
- **Row 13 is what row 12 costs when nothing comes back green.** The fixture was
  degenerate (mechanism **B**) in the single respect that mattered, so the
  assertion for the clause the ticket was *graded on* could not fail — and the
  defect it was meant to catch was live in the shipped command: a fresh,
  untouched `home clone` was reported as damaged, with the verdict depending on
  which binaries happened to exist in the operator's root store. Six probes ran
  against that method. All six reddened. None of them could see it, because they
  all reddened *the same degenerate oracle*.

**The composite lesson, which neither row states alone:** a probe suite can be
large, honest, and entirely blind, when every probe is read against one oracle
and that oracle cannot express the defect. HIS-13 ran twelve mutations and
observed twelve reds on the arm this bug lived in. The count of probes is not a
measure of coverage; the count of *distinct oracles* is closer.

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
Rows **4, 6, 7**, and now **14**. The disable was applied to a path the run does
not take, or the pattern matched nothing, so a **green** result reads exactly like
a passing check.

**The assertion:** the harness asserts `s.count(old) == 1` before mutating (DEF-035),
and the probe asserts the mutated path was **reached** — not merely that the suite
stayed green. HIS-15's exit-13 case is the model: it proves the code ran before it
proves what the code did.

**Row 14 widens this mechanism from probes to INSTRUMENTS, and the ledger had no
row for it.** Every C instance before it was a *mutation* that never reached the
code. Row 14 is a *search* that never reached the corpus: `grep -E "a\|b"` treats
`\|` as a literal pipe, so the pattern is unmatchable and the sweep returns zeros
that read exactly like a clean bill of health. Nothing distinguishes *"this claim
is not in these three units"* from *"my pattern cannot match anything"*.

The generalisation is one sentence: **an instrument that cannot fail is the same
defect as an assertion that cannot fail**, and a zero is only evidence if the
instrument has been shown capable of producing a non-zero. Same shape as
DEF-035's `count == 1` clause, applied to search rather than mutation.

It is worth noting how row 14 was caught: the author was, at that moment,
*auditing someone else's sweep for exactly this class of defect* and nearly
shipped one. That is the same recurrence property as row 12 — reading the entry
does not confer immunity — which is why this needs a check and not a note.

**And it recurred twice more in the same ticket, both times one level further
out.** After row 14 was written, HIS-8 (a) proposed a clause-1 metric that keyed
on a bare token and so could not distinguish an index term from an explanation —
caught by a falsification control, not by reading it — and (b) posted a 40-hex
SHA it had expanded by hand, which resolved to nothing. Three instances, one
ticket, one shape: **an output that carries no way to detect its own
invalidity.** A sweep's zero, a metric's 1, and a well-formed SHA are all
indistinguishable from the correct answer at a glance, and all three are cheap to
make self-checking. That is the argument for these being checks rather than
conventions.

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

**AND IT RECURRED IMMEDIATELY (row 12).** HIS-13 read this section before
writing an assertion, wrote a probe against its repair path, and the probe
moved a no-op guard that the idempotence claim is not downstream of. It came
back 12-pass/0-fail and was kept as `probes/his-13/V6-VACUOUS.out` rather than
quietly re-aimed. So the honest status of D is: **written down, and not yet
prevented by writing it down.** The check that caught it was procedural — every
probe is run, and an all-green probe is treated as a failed probe rather than as
a passing test.

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
| C | **a sweep is validated against a string it is KNOWN to contain before its zeros are believed** — a positive control per pattern, so an unmatchable pattern fails loudly instead of reporting clean | every ticket's evidence sweep; the `RunHis*` harnesses that grep | **HIS-8 (row 14)** — not mechanised. Cheap: one known-present string per pattern, asserted non-zero before the real corpus is scanned |
| C | a multi-term grep declares its dialect — `-E` with `\|`, or BRE with `\\|`, never mixed | same | **HIS-8 (row 14)** — the specific spelling that produced it |
| C | **every SHA written into evidence is resolved before it is written** — `git rev-parse --verify <sha>^{commit}` — because a 40-hex string looks equally authoritative whether or not the object exists | every ticket's `goal-contribution.md` and PR body | **HIS-8** — not mechanised. HIS-8 fabricated one in a PR comment by expanding a short form by hand; it self-caught and corrected, and then verified all 15 SHAs in its own evidence. This is the artefact HIS-6 re-reads, so an unresolvable SHA silently voids the "record what was published" acceptance assertion |
| D | a probe names which BRANCH it exercises, and it is the branch the change moved | every `RunHis*` + probe record | **HIS-17** wrote it down; **convention only** |
| D | a rule with separately-decided branches has a control per branch | `home-clone` (done: symlink decoy + descent tamper) | **HIS-17**; not general |
| — | a home's **unit membership** is what the graph intended | new law beside `HomeFixpointLaw` | **HIS-16** |
| — | every private "does anything name another home" scan cross-checks production | `home-clone`, `checkout-home`, `artifact-dag` | **HIS-17** |
| A | a probe records WHICH assertion reddened, and whether it was the claim or a precondition | probe harnesses | **HIS-13** — mechanised in `probes/his-13/probes.py`, which classifies every red and prints the two counts. The review of #244 found five undeclared precondition-reds in the hand-written table it replaced |
| B | a clause-3 / non-detection oracle plants the variant that CAN trip the check | `home-integrity`, unit suites | **HIS-13** (row 13) — the discriminating pair is now one disk state judged under two policies |
| D | an all-green probe is reported as a FAILED probe, never omitted | every `RunHis*` | **HIS-13** — how row 12 was caught at all |

### What is already mechanised, and what is not

**Mechanised:** `HomeFixpointLaw` (24 of 30 graphs), HIS-12's remedy source-scan,
HIS-15's `--json` convention guard. All three caught something no enumerated list
would have — the remedy scan caught a **sibling ticket's** drift during a merge, and
the `--json` guard caught a failure path **created one promotion slot earlier**.

**Not mechanised:** every row above marked *convention only*. They are real practice
and they are held by discipline rather than by a check, which is precisely the
condition that produced eleven instances.

**The honest read for HIS-6:** the discipline is working — ten of fourteen
self-caught — and the count is not falling. It has now recurred **inside the
ticket that read the entry about it** (row 12) and, in row 14, **inside a sweep
being run to audit another unit for precisely this class of defect**. That is the
strongest evidence in this file that writing a mechanism down does not prevent
it. Three of the seventeen countermeasures are mechanised; the rest are still
enforced by care.

**Row 14 also moves the boundary of what this ledger covers.** Every earlier row
is an *assertion* that could not fail. Row 14 is an *instrument* that could not
fail — the same defect one level out, in the tooling a ticket uses to produce
evidence rather than in the evidence itself. HIS-6 should count it, because a
sweep whose zeros are believed is exactly as load-bearing as a green suite.

**One thing did change, and it is worth more than the count.** HIS-13's harness
now classifies each red as landing on the CLAIM or on a PRECONDITION and prints
both counts into the probe record. That is mechanism A moved from convention to
code, and it immediately found five reds that had been credited as claims in a
hand-written table.

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
