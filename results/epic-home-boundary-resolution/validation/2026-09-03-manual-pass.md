# Manual testing pass — epic tip, 2026-09-03

End-to-end, through the real front doors, with every change applied. Scratch
paths only; **0 recently-modified files** in `~/.skill-manager`, `~/.claude`,
`~/.codex`, `~/.gemini` afterwards.

| # | what | result |
| --- | --- | --- |
| M1 | `skt ticket new` from the project home | worktree + own home + pinned base + retention ref |
| M2 | `home repair` on the new worktree home | clean, 38 entries — **the clone-time fix, live** |
| M3 | real agent via the home's own `bin/launch/claude` | authenticated, used `Bash`, answered `worktree` / `feature/MANUAL-1` |
| M4 | `skt check` on the fresh home | 0 migration notices — silent when there is nothing to say |
| M5 | `wt close` | clean |
| M6 | protected-home audit | 0 writes |

M2 is the one worth naming: before this epic that home would have been created
holding a copy of `spec-double-compiler` it did not run. It is now created
correct, and nothing had to be repaired afterwards.

The negative half of M4 was measured separately, on a home that still has the
shape: it names the three entries, the command, and why the command is safe.
A notice that cannot be quiet is noise; one that cannot fire is decoration.
