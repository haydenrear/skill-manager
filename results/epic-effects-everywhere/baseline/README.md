# epic/effects-everywhere — the baseline, before anything is changed

Three measurements taken on `574e446` (main, v0.25.0 released), so every later
claim has something to be compared against.

## 1. The influence graph — which surfaces actually produce bugs

The owner's ask, 2026-08-26: *"what parts of our architecture cause bugs the
most, so we want to capture that."*

Built from the 122 findings of the previous epic's backlog by attributing each
finding's **whole record** to one or more architectural surfaces. A finding may
touch several, so the counts sum past 122 — that is the point, not a defect of
the method: **the co-occurrences are the signal.**

| surface | findings | blocking | share |
| --- | ---: | ---: | ---: |
| test_graph / laws | 35 | 5 | 28.7% |
| HomeCloner / verify | 34 | 7 | 27.9% |
| sync / close-out | 31 | 3 | 25.4% |
| TLA+ / spec tooling | 29 | 2 | 23.8% |
| skt | 28 | 2 | 23.0% |
| project / manifest | 20 | 1 | 16.4% |
| confinement | 17 | 2 | 13.9% |
| docs / disclosure | 14 | 1 | 11.5% |
| AgentHomes / two axes | 14 | 2 | 11.5% |
| artifacts | 13 | 2 | 10.7% |
| HomeRepair | 11 | 2 | 9.0% |
| CLI execution strategy | 8 | 1 | 6.6% |

**Read the edges, not the nodes.** A count tells you where bugs were written; an
edge tells you where two things had to agree and did not.

| A | B | shared | blocking |
| --- | --- | ---: | ---: |
| TLA+ / spec tooling | test_graph / laws | 15 | 1 |
| docs / disclosure | skt | 11 | 1 |
| skt | sync / close-out | 11 | 1 |
| HomeCloner / verify | test_graph / laws | 11 | 2 |
| HomeCloner / verify | TLA+ / spec tooling | 10 | 1 |
| **HomeCloner / verify** | **HomeRepair** | **9** | **2** |
| HomeCloner / verify | sync / close-out | 7 | 2 |
| HomeRepair | test_graph / laws | 7 | 2 |

`HomeCloner/verify ↔ HomeRepair` carries the **highest blocking density of any
edge** — 2 blocking in 9 shared, against `HomeRepair`'s 11 findings total. That
is DEF-104, DEF-115 and DEF-124: one condition, two readers, different answers.
The graph found by counting what a human had already found by reading, which is
the first evidence that the method is measuring something real.

**Its limits, stated up front.** Regex attribution over a corpus one team wrote:
it inherits that corpus's vocabulary, it cannot see a surface nobody named, and
`test_graph/laws` ranks first partly because graph findings *describe* the code
they test, so they name two surfaces by construction. Treat the ranking as a
prior for where to look, never as a verdict. `influence-graph.json` carries the
per-finding attribution so any row can be checked by hand.

## 2. The budgets — EFF-0, answered

`EFF-0` proposed one change from the `modular_fuzzing.md` defaults:
`max_component_actions 8 -> 12`. **Rejected**, and the reason is what the ledger
says rather than a preference.

Measured across all 16 entries of `specs/results/complexity_ledger.json`:

```
gate_passed = False        16 of 16 runs, for the whole previous epic
max_component_actions   8   measured  9
max_component_variables 6   measured 38      <- 6.3x over
max_distinct_states 50000   measured  None   <- TLC never completed for this scope
state-space bound           UNKNOWN          <- no variable domain resolvable
```

**The gate has been red on every run and nothing stopped for it.** That is the
same shape as the receipt gap below and as `stale_stores`: a record produced by
one component and read by none.

The negotiated budgets, with the rationale each key is owed:

| key | value | rationale |
| --- | --- | --- |
| `max_component_actions` | **8** (default kept) | The 9th action is one of nine help/doc/agent-context actions in one component — `RenderProgressiveRootHelp`, `ExposeInstallLocalUnitWorkflowDocs`, `EmitSyncOneUnitAgentContext`. That is a *disclosure* concern wearing the effects model's clothes. Raising to 12 buys room for a component that should be split. Decompose, do not widen. |
| `max_component_variables` | **6** (default kept) | 38 today. The gap is the debt this epic exists to pay: a declared filesystem effect port collapses the `cli_*` variables into one effect log. Keeping 6 makes the ledger the instrument that decides whether effects actually bought decomposition. |
| `max_unresolved_bound_variables` | **0** (NEW) | The first gate violation is "state-space upper bound UNKNOWN: no variable domain could be resolved". A model whose bound cannot be computed was never really checked, and no other budget can bind until this one does. |
| `tlc_seconds` | 120 (default) | Unchanged; nothing measured argues against it. |
| `max_distinct_states` | 50000 (default) | Unchanged, and **currently unmeasurable** — TLC produced no reachable-state count for this scope. Reaching a state where this number exists is itself a deliverable. |
| `kill_rate_floor` | 0.8 (default) | Unchanged. |
| `max_component_variables` target | 38 → ≤6 | The epic's headline number. |

`budgets.source: negotiated`, 2026-08-26.

The owner's framing — *"we're doing it with the hope of lowering those budgets
from the benefits from changes in effects"* — is why the defaults were kept
rather than raised. **You cannot lower a budget you are 6.3× over.** First get
under it; then argue it down with the ledger as evidence.

## 3. The close receipts — recorded honestly, not fixed

`close_spec_workflow.py` refused to close `child-home-materialization-workflow`:
17 tickets marked `closed` have **zero** close receipts under
`specs/.history/child-home-materialization-workflow/`.

```
plan claims   17 closed + 22 delivered = 39
receipts        9   (ISSUE-166, ISSUE-168, HIS-5, HIS-6, HIS-8, HIS-19..22)
```

`CHM-9..23` were marked closed by a hand edit to the plan (`492a6b4`), never
through `close ticket`. Fifteen of the twenty-two delivered HIS tickets have no
receipt either; they pass only because the gate checks `closed` and not
`delivered`.

**Nothing was relabelled and no receipt was generated.** Both would have
fabricated evidence to satisfy a check, which is the failure this epic exists to
remove. The gap is recorded here and filed as a finding; the workflow stays open
until the owner chooses a disposition.

It is the fourth instance today of one shape: **a record written by one
component and read by none** — `stale_stores` (skt could not name the root
cause), `home.provenance.json` (status could not name the parent), the
`skills/` link scope (repair could not see what verify saw), and this.
