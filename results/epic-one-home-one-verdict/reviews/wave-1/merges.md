# Wave 1 — merge record

Epic branch `epic/one-home-one-verdict`. Wave base (the plan-revision-2 commit): `de4231386a9b`.
Merges are `gh pr merge --merge` (merge commit, never squash), in promotion order.

| order | ticket | PR | planned predecessor | merged predecessor | merge commit | checks at merge | ticket CI evidence (graph_set=full) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | OHV-1 (#338) | #360 | — | — | `8bd883f3` | unit tests ✓, title lint ✓ (after retitle), DCO action-required (non-gating) | run 34790309867: 25 executed / 19 passed / 6 failed — the main baseline six |
| 2 | OHV-7 (#357) | #363 | OHV-1 | OHV-1 | `b218322c` | unit tests ✓, title lint ✓, DCO non-gating | run 34790187503: 25/19/6, baseline six |
| 3 | OHV-0 (#356) | #359 | OHV-7 | OHV-7 | `c8426cb5` | unit tests ✓ (on reconciled head e2a47b2f), title lint ✓ (after retitle), DCO non-gating | run 34791432559: 26/20/6, home-verdicts 9/9 on Linux, baseline six |
| 4 | OHV-5 (#346) | #362 | OHV-0 | OHV-0 | `f38a9298` | unit tests ✓, title lint ✓ (on reconciled head d132fa2b), DCO non-gating | run 34793308847: 25/24/1, artifact-dag only (OHV-3's) |
| 5 | OHV-3 (#340) | #361 | OHV-5 | OHV-5 | `3d6d4cd4` | unit tests ✓, title lint ✓ (head f1335127), DCO non-gating | run 34798708463 on merged head 53d3e7e1: **26/26/0**; f1335127 differs only in OHV-3's README |

## Reconciliation notes

- **OHV-7** was rebased by its agent; the force-push was refused by the permission classifier. The epic agent rebuilt the branch as a merge of the epic tip plus the ticket's own close commit. Tree identical to the rebased version; 52 runner tests OK; pushed without force. Rule from then on: reconcile by merge, never rebase or force-push.
- **OHV-0** merged the epic tip `b218322c` (`e70e491a`), no conflicts; home-verdicts re-run locally 9/9 (12m32s against ~1m before, cause unknown); OHV-1's `build` field did not disturb the JSON parser.
- **OHV-5** agent had finished; the epic agent merged the epic tip `c8426cb5` into feature/OHV-5 (`d132fa2b`), no conflicts, pushed without force. Pre-merge review of its CI changes: `seed-agent-home.sh` refuses when any of ~/.skill-manager, ~/.claude, ~/.codex, ~/.gemini exists and writes ~/.claude.json only if absent; git-issue-workflow fetched at pinned SHA fae9e9ef; Claude/Codex CLI install extended to onboarding; no trigger or EXCLUDED change. Guardrail note for the review: the tripwire's >100-entry vacuity floor is met by 16 synthetic units, not a real install.
- **Out-of-declared-path files, all judged in scope:** OHV-1 `RunTests.java` (test registration); OHV-7 `CLAUDE.md` (the runner text the slice names); OHV-0 `scripts/home_observations.py` (shared harness helper).
- **OHV-3** merged the epic tip `f38a9298` (`53d3e7e1`), with no conflicts. It cancelled pre-merge run 34798086104. The PR head moved to `f1335127` (README only) after CI; the epic agent verified that only `results/.../tickets/OHV-3/README.md` changed before merging.
