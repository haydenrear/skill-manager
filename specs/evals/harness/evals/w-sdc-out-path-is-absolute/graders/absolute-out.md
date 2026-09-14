---
type: file_exists
path: .eval/require-absolute-out
weight: 3
---

THE BEHAVIOUR. Written when a Bash `generate cases` call passed `--out` as an
absolute path (`/...`, `$PWD/...`, `$(pwd)/...`). The target here is not inside
the .tla's directory, so a relative `--out cases/.../specs/generated/...` falls
back to `<spec dir>/<path>` -- a nested specs/ inside program_model (E-07, E-12).
