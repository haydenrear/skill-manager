# Wave 5 merge record

Epic branch: `epic/one-home-one-verdict`. Wave base: `4578ce78` (plan revision 4 plus the placeholder fix).

## Commits pushed straight to the epic branch before the merge

| commit | what | why no PR |
| --- | --- | --- |
| `926cd655` | plan schedule_revision 4, the wave-4 review, and the root-fix evidence | plan and evidence only; no ticket |
| `4578ce78` | GOAL-a-home-writes-only-itself's metric changed from `bin/cli/<tool>` to a literal tool name | `validate_assignment.py` read `<tool>` as an unrendered placeholder |

## Merge

| order | ticket | PR | planned predecessor | merged predecessor | merge commit | checks at merge | ticket CI evidence (graph_set=full) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 9 | OHV-9 (#367) | #368 | OHV-6 | OHV-6 | `5ab5770b` | unit tests ✓, title lint ✓ on head `71aad2fd`; DCO not gating | run 34858642285 on `2a430bb6`: **26/26/0** |

## Checks before the merge

- `71aad2fd` differs from the CI-tested `2a430bb6` only under `results/` and in the plan's status line.
- The plan diff against the tip touched only OHV-9's status line.
- The secret scan of the added evidence came back clean.

## Notes

- OHV-9 was created from `926cd655` and merged forward to `4578ce78` by its agent. Nothing was rebased or force-pushed.
- OHV-8's worktree was fast-forwarded to `5ab5770b`. It had no commits of its own.
