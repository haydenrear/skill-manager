# Bug attribution — epic/one-unit-one-name

Every defect this epic has found or fixed so far, attributed to the
architectural component that PRODUCED it — not the file the patch landed in.
The question is which part of the design keeps generating work.

Counted once each. My own instrument errors are counted too: a measurement
that reports the wrong number costs more than most product bugs, and leaving
them out flatters the ledger.

Through wave 1 (OUN-0).

## The table

| component | count | defects |
| --- | ---: | --- |
| **Epic scaffolding — assignments that emit unexecuted strings** | **4** | nonexistent `spec_unit` script; hardcoded per-epic maps + stale `BASE`; `feature/oun-0` vs `feature/OUN-0`; a rationale naming a closed workflow |
| **Measurement instruments (mine)** | 4 | zero-indent `skill-imports` parsed as none; hardcoded operator path as the protected-home guard; temp homes leaked on kill; agent-home axes unpinned in the probe |
| **Spec promotion / close-out** | 2 | accepted `spec_manifest.yaml` replaced by the workflow's; CI hardcoded `specs/current/tests` |
| **Documentation** | 1 | unimplemented behaviour written in the present tense in a doc that ships into homes |

Eleven, and **not one of them is in the product's resolver, installer or
store** — the surfaces this epic exists to change. That is what a
measurement-only ticket should produce, and it means the count says nothing
yet about the epic's actual subject.

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

## The #311 fix, and what its own test caught

The fix was written against one write path and was **incomplete**. Both faces
come from the same expression — `CodexAgent.pluginsDir()` is
`$CODEX_HOME/plugins` and `CodexAgent.skillsDir()` is `$CODEX_HOME/skills`, and
those two variables name the config directory itself rather than its parent —
but they are reached through different code:

| face | path | effect |
| --- | --- | --- |
| **a** | `PluginMarketplace.cleanupLegacyAgentPluginEntries` | deletes every installed plugin |
| **b** | `LiveInterpreter.syncAgents` → projector | replaces each installed unit with a link to its own path |

I fixed **a**, wrote the graph node, and the node's *control* found **b**. The
control exists for an unrelated reason — "the plugins survived" is satisfied
by an install that failed before reaching the cleanup, so the node also asserts
the install it asked for really happened. That assertion came back
`exit=0 unitInstalled=false`, which is what a self-referential symlink looks
like to `Files.isDirectory`.

*The generalisable part:* **a control written against vacuity found a second
defect, not a vacuous pass.** The two are the same shape — both are "this
assertion is true for a reason other than the one you think" — which is why
controls keep earning their cost in this repository.

The guard is now one question on the store (`ownsUnitDirectory`) asked from
both paths, rather than two guards that could drift apart. That is the same
correction the wave-1 attribution recommended for a different component: one
place to ask, not one per caller.

## An instrument note, counted separately because it cost time

`checkout-home` went red during this fix's graph sweep. It was **not** a
regression: I invoked the runner with `SKILL_MANAGER_HOME` exported, and that
graph is precisely the one that asserts a pinned shim binds the home it lives
in — so an inherited home variable naming a different home is a conflict it is
right to refuse (`exit=79`). Green with `env -u SKILL_MANAGER_HOME`, same
commit.

Every other graph tolerated the variable, which is what made it look like a
finding. Recorded so the next sweep does not re-diagnose it.

