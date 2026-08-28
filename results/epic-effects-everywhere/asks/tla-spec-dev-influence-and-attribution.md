# Ask for tla-spec-dev: guidance for influence and attribution

We want to run one chain end to end and have the workflow *carry* it:

    ticket  ->  test graph  ->  custom testing  ->  regression  ->  attribution

Today the first four links exist and the fifth is done by hand, in prose, after
the fact. This is a request for guidance on what tla-spec-dev should record so
the fifth link is a product of the workflow rather than an essay somebody writes
at close.

Below is one real defect that traversed the whole chain, and one that shows
where the chain breaks. Both are from `epic/home-integrity-sync`, delivered and
merged at `574e446`.

---

## The worked example: DEF-115

**Ticket.** `HIS-6`, the terminal evaluation, owned `GOAL-one-home-one-answer`,
whose clause 1 is: *no two readers disagree about the same path in the same home,
with no operator-supplied flag.* The model has an invariant for it.

**Test graph.** The graph layer already compared the two readers — `home verify`
and `home repair` — on the same home. It had done so for weeks and was green.

**Custom testing.** An issue-scoped agent, driving the real CLI against two
throwaway homes, planted a **symlinked** shim into a third home. `home verify`
exited 1 naming it. `home repair` exited 0: *"nothing is damaged in a way this
command knows about (0 entries examined)."*

Every existing graph comparison planted a **wrapper** — a regular file whose
text names a path. `home repair` reads shim TEXT, and a symlink has none. So the
graph was green because the shape it needed was structurally unreachable from
the fixture it used, not because the readers agreed.

**Regression.** The assertion landed **red on purpose**, with two in-run controls
(verify names the symlink; repair names the wrapper) so the red is the claim and
not the setup. It went green when `HomeCloner.collectShimFiles` stopped dropping
symlink entries.

**Attribution.** Caught by instrument **B** (issue-scoped agents). Missed by
**A** (26-graph sweep). Surface: `HomeCloner/verify <-> HomeRepair`. That edge
later turned out to carry the **highest blocking density of any edge** in the
whole backlog — 2 blocking in 9 shared, against `HomeRepair`'s 11 findings total.

## Where the chain breaks: DEF-124

The DEF-115 fix was scoped to `HomeCloner.SHIM_DIR_NAMES = [bin/cli, bin/mcp]`.

Eight days later the identical class reappeared **one directory over**: a
dangling or foreign symlink under `<home>/skills/<unit>/`, which `home verify`
reports and `home repair` structurally cannot, because none of its four
detectors walks the home's own `skills/` or `plugins/`. Three live homes on the
operator's machine were failing that way while `repair` called them clean.

**Nothing in the chain predicted it.** The invariant was satisfied *for the
surface the fix covered*, and no artifact anywhere records the sentence that
would have raised the alarm:

> this invariant is enforced on `bin/cli` and `bin/mcp`, and is unenforced on
> `skills/`, `plugins/`, and the agent directories.

The model knows the invariant. The fix knows its scope. Nothing joins them.

---

## What we would like guidance on

1. **Invariant coverage, as data.** For an invariant like
   `verify and repair agree`, how should a ticket declare the *surfaces* it is
   enforced over, so that "satisfied" carries its own scope? We want to ask
   "which surfaces does this invariant actually cover, and which are silently
   uncovered?" and get an answer from the workflow, not from reading Java.

2. **Fixture reachability.** DEF-115's graph node was green because its fixture
   could not express the failing shape. Is there a way to state, per case, the
   shapes it can and cannot produce — so a green result carries "and here is what
   this fixture could never have shown"? This is the vacuity question we have
   been answering by hand with mutation probes.

3. **Attribution at catch time, not close time.** We want each finding to record,
   *when it is caught*: the instrument that caught it, the instruments that
   missed it, and the architectural surface(s) it lives on. We have been doing
   this in an append-only YAML backlog and it works, but it is beside the
   workflow rather than part of it. Should this be a first-class field on the
   ticket close / retirement receipt?

4. **The influence graph.** We built a first cut by regex-attributing 122
   findings to 12 surfaces and ranking the **edges** — pairs of surfaces that
   appear in the same finding, because that is where two things had to agree and
   did not. It found by counting what we had found by reading, which is
   encouraging. But the attribution is our regexes over our own prose. Is there a
   principled source — the adapter map, the `@port` declarations, the
   spec-unit bindings — that tla-spec-dev could attribute against instead, so the
   graph is derived from the model rather than from vocabulary?

5. **Budgets as the feedback loop.** Our complexity ledger recorded
   `gate_passed: False` on **16 of 16** runs of an entire epic and nothing ever
   stopped. `max_component_variables` is 6; we measure 38; the state-space bound
   is UNKNOWN because no variable domain resolves from a `TypeInvariant`. We are
   about to decompose along an effect port with the explicit goal of getting
   under those budgets. What should the ledger record so that "the refactor
   bought decomposition" is a measurement and not a claim?

## The shape of the answer we are hoping for

Not a new subsystem. A small number of **declared fields** — surface, scope,
instrument, reachability — that the existing close and retirement receipts carry,
plus one report that joins them. If the workflow records those four things at the
moment a ticket closes, the influence graph is a query rather than an essay, and
DEF-124 becomes a thing the workflow can warn about instead of a thing we find
eight days later on a live machine.
