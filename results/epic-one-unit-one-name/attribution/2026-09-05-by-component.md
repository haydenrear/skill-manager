# Bug attribution — epic/one-unit-one-name

Every defect this epic has found or fixed so far, attributed to the
architectural component that PRODUCED it — not the file the patch landed in.
The question is which part of the design keeps generating work.

Counted once each. My own instrument errors are counted too: a measurement
that reports the wrong number costs more than most product bugs, and leaving
them out flatters the ledger.

Through wave 2 (OUN-0, OUN-1, OUN-3).

## The table

| component | count | defects |
| --- | ---: | --- |
| **Epic scaffolding — assignments that emit unexecuted strings** | **4** | nonexistent `spec_unit` script; hardcoded per-epic maps + stale `BASE`; `feature/oun-0` vs `feature/OUN-0`; a rationale naming a closed workflow |
| **Measurement instruments (mine)** | 4 | zero-indent `skill-imports` parsed as none; hardcoded operator path as the protected-home guard; temp homes leaked on kill; agent-home axes unpinned in the probe |
| **Spec promotion / close-out** | 2 | accepted `spec_manifest.yaml` replaced by the workflow's; CI hardcoded `specs/current/tests` |
| **Measurement instruments (mine)** — wave 2 | 2 | the harness measured the PREVIOUS build; the harness DELETED the home it was measuring |
| **Product — agent-home boundary** | 1 | `install` removes `<home>/plugins/` when `CODEX_HOME`/`GEMINI_HOME` names a Skill Manager home (#311) |
| **Dev loop — build caching** | 1 | jbang keys its cache on the entry script, so `./skill-manager` runs a build older than its own sources |
| **Documentation** | 1 | unimplemented behaviour written in the present tense in a doc that ships into homes |

Fifteen. Through wave 1 not one was in the product's resolver, installer or
store — correct for a measurement-only ticket. Wave 2 found the first product
defect (#311), and it is **not** in the surfaces this epic changes either: it
is in the agent-home boundary, reached by a harness, not by the resolver work.

## The largest class is the machinery, not the product

**Epic scaffolding produced four, and all four are one shape:** the
assignment declared something that was never resolved against reality.

- `spec_unit` named `.skill-manager/skills/spec-double-compiler/scripts/run_spec_units.py`
  — present in no installed version of that skill and in no checkout. Every
  assignment this renderer has ever produced carried it, so the declared spec
  validation was never run by anyone it was handed to.
- Three per-epic constants were hardcoded maps keyed by slug. A new epic died
  with a `KeyError` on its own name, and the near-miss was worse than the
  crash: adding a map entry would have stamped the *previous* epic's base
  commit onto every assignment.
- The declared branch was `feature/oun-0`; `skt ticket new OUN-0` — the front
  door the skill itself names — creates `feature/OUN-0`. On a case-insensitive
  filesystem those are one ref, so the disagreement would have surfaced as
  something stranger than a missing branch.
- `validation.tlc` explained itself by naming a workflow that had been closed
  and removed in the very commit the epic is based on.

The common cause is structural: **`validate_assignment.py` checks shape, never
truth.** It confirms `spec_unit` is a string. Nothing confirms the string can
run, that the branch matches what the tooling creates, or that the named
graphs exist.

*Recommendation:* the validator should RESOLVE what the assignment declares —
commands, branch, base commit, graph names — before the issue URL is handed
out. Four accidental discoveries collapse into one gate.

## My instruments produced as many defects as the machinery

Four, and the instructive one is the frontmatter parser: a top-level
`skill-imports:` whose sequence items sit at zero indent parsed as *no
imports*. No unit on this machine is written that way, so every measured
number was correct — and would have stayed correct right up until the day it
silently was not.

The other three are all the same admission: **a harness that writes is a
harness that needs the same guardrails as the product.** It installs units,
so it needed a real protected-home set instead of one absolute path; it
clones homes, so it needed to take its litter with it; it spawns the CLI, so
it needed every home axis pinned, not the one that is easy to remember.

## The close-out is a two-defect component with one cause

Both came from `e07e577`: a promotion performed by copying files rather than
through the promoter, and a CI step that hardcoded a directory which only
exists while a workflow is open. Closing the workflow removed the directory
and turned every CI run on every branch red before collecting a test.

*Recommendation:* neither defect is about specs. Both are about **a step that
assumes a transient tree is permanent.** Worth checking whether anything else
in CI or the skills names `specs/current` unconditionally.

## Wave 2: the instruments produced more defects than the feature did

OUN-1's product change is one branch in one method and four test cases. It
worked the first time. What did not work was everything used to *observe* it,
and both failures had the same signature — **the harness reported a defect
the product did not have.**

**It was measuring the previous build.** jbang keys its cache on the hash of
the ENTRY script. `SkillManager.java` barely changes, so editing anything
under `src/main/java` leaves the cached jar in place and `./skill-manager`
keeps running the old one. `RunTests.java` is a different entry script, so the
unit suite rebuilt and went green while the harness, driving the wrapper,
stayed red. Two instruments disagreeing about the same change, and the one
that was wrong was the one closer to the user.

**It was deleting the home it measured.** Wave 1's review added
`CLAUDE_HOME`/`CODEX_HOME`/`GEMINI_HOME` pinning to the probe on the theory
that more pinning is safer. `CODEX_HOME` and `GEMINI_HOME` are the config
*directory*, not its parent, so pointing either at a Skill Manager home makes
`install` remove `<home>/plugins/<plugin>` — exit 0, silent. The probe emptied
the plugins directory and then reported that a plugin-contained skill was not
addressable. Both statements true, unrelated, the second caused by the first.

*Recommendation, and it is the same one both times:* **a measurement must
assert the conditions it depends on.** The harness now checks that the CLI it
ran is newer than the sources, and that the unit it is asking about is still
on disk after the probe. Neither check is clever; the absence of both is what
turned a working change into an hour of chasing.

## And a defect worth having found

#311 is the wave's only product defect and it came from misusing the product,
which is the least respectable way to find one and does not make it less real:
**silent deletion of a store directory, reported as success.** It joins #262
and #289 in the "which home does this write?" class, with the distinguishing
property that this one's answer is destructive rather than merely wrong.

