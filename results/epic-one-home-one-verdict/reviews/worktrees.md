# Worktree ledger — `one-home-one-verdict`

Epic-wide. Every worktree this epic created, what it holds, and why it still stands. **Worktrees stand until the epic's default-branch merge is verified, then all of them go in one sweep.** Removal is never by `skt ticket sweep`, per the owner on 2026-09-13; it is `git worktree remove` with no `--force`, gated on a clean tree, no unpushed commits, and a `home close-out` exit of 0.

Updated at wave-1 close, 2026-09-13. Free disk: 88 GiB.

| worktree | branch | ticket | created from | merged | home close-out | state | standing because |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `../wt-epic-one-home-one-verdict` | `epic/one-home-one-verdict` | epic | `71c51464` (main) | n/a | n/a (epic) | tracks the epic tip | the integration worktree, until finalization |
| `../wt-ohv-1` | `feature/OHV-1` | OHV-1 #338 | `de423138` | #360 `8bd883f3` | exit 0, nothing held | clean, pushed | end-of-epic sweep |
| `../wt-ohv-7` | `feature/OHV-7` | OHV-7 #357 | `de423138` | #363 `b218322c` | exit 0, nothing held | clean, pushed; local ref `ohv7-rebased-backup` keeps the refused rebase | end-of-epic sweep |
| `../wt-ohv-0` | `feature/OHV-0` | OHV-0 #356 | `de423138` | #359 `c8426cb5` | exit 0, nothing held | clean, pushed | end-of-epic sweep |
| `../wt-ohv-5` | `feature/OHV-5` | OHV-5 #346 | `de423138` | #362 `f38a9298` | exit 0, nothing held | clean, pushed | end-of-epic sweep |
| `../wt-ohv-3` | `feature/OHV-3` | OHV-3 #340 | `de423138` | #361 `3d6d4cd4` | exit 0, nothing held | pushed; 1 uncommitted file at close (agent evidence draft), to check before the sweep | end-of-epic sweep |
| `../wt-ohv-2` | `feature/OHV-2` | OHV-2 #339 | `3d6d4cd4` | #364 `48d23c51` | exit 0, nothing held | pushed | end-of-epic sweep |
| `../wt-ohv-4` | `feature/OHV-4` | OHV-4 #341 | `48d23c51` | #365 `42486f86` | exit 0, nothing held | pushed | end-of-epic sweep |
| `../wt-ohv-6` | `feature/OHV-6` | OHV-6 #352 | `42486f86` | #366 `7357b8ab` | exit 0, nothing held | pushed | end-of-epic sweep |
| `../wt-ohv-8` | `feature/OHV-8` | OHV-8 #358 | `7357b8ab` | — | — | created at wave-4 close; evaluation now waits for OHV-9 | wave 6 |

**Backups of real-home and global agent files** (can hold credentials; never committed): `/Users/hayde/IdeaProjects/.ohv6-realhome-backups-2026-09-14` (OHV-6 real homes), `/Users/hayde/IdeaProjects/.ohv6-root-marketplace-backup-2026-09-14` (root, first attempt, unused), `/Users/hayde/IdeaProjects/.ohv-root-fix13-backup-2026-09-14` (root 13-finding fix). All `chmod 700`. Delete after the epic's finalization is verified.

No ticket home held a unit edit, so nothing was reconciled into the project home and nothing needs `unit publish`.

**Not the epic's:** `../skill-manager-wide-evals` (`feature/wide-small-evals`) was created by another session and is outside this ledger.

**Removed before the epic started** (the previous epic's 14 worktrees, owner request 2026-09-13): `wt-ci-otel`, `wt-fix-311`, `wt-oun-{0,1,2,2b,3,4,5,6,9,10,12}` and `.claude/worktrees/agent-ad3590e3db975befa`, with `git worktree remove`, no force. `home close-out` refused on 13 of them, each time only for `skill:eval-skill` fixture residue, and the removal did not gate on that (see kickoff attribution).
