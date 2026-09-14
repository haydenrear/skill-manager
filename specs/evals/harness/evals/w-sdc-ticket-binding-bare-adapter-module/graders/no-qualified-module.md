---
type: file_exists
path: .eval/forbid-no-qualified-module
weight: 2
---

Written when no edit bound the NEW adapter under any `specs.<tree>.adapters:`
prefix. Matched only against RefundInternalAdapter, so an Edit whose
old_string quotes the existing qualified lines does not trip it.
