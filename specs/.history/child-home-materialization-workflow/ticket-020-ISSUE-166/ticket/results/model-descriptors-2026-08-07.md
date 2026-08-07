# ISSUE-166 model descriptor comparison — 2026-08-07

The close-time model identity remains the accepted `SkillManager` monolith.
ISSUE-166 does not modify that model: its ticket copies of `SkillManager.tla`
and `MC.cfg` are byte-identical to `specs/program_model`, and
`specs/program_model` is byte-identical to baseline
`b5a0d800e28cf984887ab441b5c25746d7067d23`.

| Descriptor | Accepted before | Accepted after | Claimant internal view | Claimant external view |
| --- | ---: | ---: | ---: | ---: |
| variables | 38 | 38 | 6 | 9 |
| actions | 9 | 9 | 1 | 1 |
| largest connected component | 2 variables | 2 variables | 3 variables | 3 variables |
| static state-space bound | unknown | unknown | 64 | 768 |
| TLC generated / distinct / depth | 10 / 1 / 1 | 10 / 1 / 1 | 2 / 2 / 2 | 2 / 2 / 2 |

Machine-readable descriptors are in `complexity-accepted.json`,
`complexity-internal.json`, and `complexity-external.json` beside this file.

The accepted monolith retains two pre-existing advisory findings: its variable
domains cannot be statically resolved by the descriptor, and its two-variable
CLI disclosure component is touched by nine actions against the advisory
eight-action threshold. ISSUE-166 neither creates nor changes either finding.
Its bounded views have no descriptor warnings: each largest connected
component is three variables, below the six-variable component budget; each
has one action, below the eight-action budget; and both known state-space
bounds are below one million.

The external view intentionally exposes nine total variables because exact
public coherence requires separate selected, checkout, installed, units-lock,
registration, child, trunk, fetch, and receipt observations. They do not form
one oversized component: the descriptor finds a maximum connected component
of three. Removing any of those distinct observations would stop the model
from expressing one of the ticket's required revision/provenance or
no-independent-source-selection assertions.

No representation decrease or generated-state drop occurred in the accepted
model, so there is no transition-level deletion to inspect. A refinement
search considered collapsing the five revision surfaces into one variable;
that was rejected because it would make cross-surface disagreement
inexpressible. No smaller faithful claimant slice was found.
