---
type: file_exists
path: .eval/forbid-no-root-ledger
weight: 3
---

THE BEHAVIOUR. Written when no Write, Edit or Bash call touched
`specs/deferred_findings.yaml` -- the path that collides with the sealed rows
under specs/results/deferred_findings_*.yaml on merge (E-10). Reading the
fixture's sealed file is fine; this matches the tool INPUT, so a Read is not a
Write/Edit/Bash call and never fails it.

Caveat: a Bash `ls specs/deferred_findings.yaml` probe also matches and fails
this. Paired with the require grader so an idle or probing run is legible.
