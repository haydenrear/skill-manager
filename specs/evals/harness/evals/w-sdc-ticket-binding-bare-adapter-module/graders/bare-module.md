---
type: file_exists
path: .eval/require-bare-module
weight: 3
---

THE BEHAVIOUR. Written when an edit's input binds `adapters:RefundInternalAdapter`
with nothing dotted in front of it -- the form that resolves against the
selected ticket view (c80e67e). Copying the neighbouring
`specs.program_model.adapters:` lines is the defect: green, and the ticket's
adapter never runs.
