# Wave 4: merge record

Epic branch `epic/one-home-one-verdict`. Wave base: `10df4440`, the wave-3 review commit.

| Order | Ticket | PR | Planned predecessor | Merged predecessor | Merge commit | Checks at merge | Ticket CI evidence (graph_set=full) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 8 | OHV-6 (#352) | #366 | OHV-4 | OHV-4 | `7357b8ab` | Unit tests ✓, title lint ✓ on head `982f64e8`; DCO non-gating | Run 34846521245 on `dc4f6356`: **26/26/0** |

## Before the merge

- **CI evidence still holds.** The PR head `982f64e8` differs from the CI-tested `dc4f6356` only in:
  - a merge of the epic tip `10df4440`, which touches `results/` and plan revision 3;
  - OHV-6's evidence commits.
- **Plan diff** against the tip: OHV-6's status line only.
- **Secret scan** of the committed OHV-6 evidence: three hits, all false positives. One is a graph name, `password-reset`; the other two are the env-var name `[RUNPOD_API_KEY]` with no value. The epic agent inspected them before merging.
- **Backups kept out of the PR.** The `tickets/OHV-6-backups/` directory (global agent files) was never committed, and was moved out of the repository.
- **Superseded run.** Run 34846096713 was cancelled by the ticket agent after it found a bug (fixed in `dc4f6356`). It is not evidence.
