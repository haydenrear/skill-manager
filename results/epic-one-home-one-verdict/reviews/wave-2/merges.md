# Wave 2: merge record

Epic branch `epic/one-home-one-verdict`. Wave base: `8cfa0692` (the wave-1 review commit).

| Order | Ticket | PR | Planned predecessor | Merged predecessor | Merge commit | Checks at merge | Ticket CI evidence (graph_set=full) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 6 | OHV-2 (#339) | #364 | OHV-3 | OHV-3 | `48d23c51` | Unit tests ✓ and title lint ✓ on head `dec65837`; DCO non-gating | Run 34805817917 on `6eb77ce4`: **26/26/0**. `dec65837` differs only under `results/` (README, blast-radius.md, CI JSON), which the epic agent verified before merging |

- **Branch.** Created from `3d6d4cd4` before the wave-1 review commit `8cfa0692` landed. That commit only touched `results/`, and the merge was clean.
- **Superseded CI runs, not evidence:**
  - Run 34802282392 (24/26): home-clone and checkout-home were red on fixture damage that `verify` now reports.
  - Run 34804201355: cancelled on re-dispatch.
