---
type: file_exists
path: probe-dir/.git/refs/x
weight: 2
---

SEPARATE FROM THE FILE WRITE, because that is how it actually failed. In run 15
`skt ticket new` got past every other obstacle and died on

    cannot lock ref 'refs/index-bases/…': unable to create directory
    for .git/refs/index-bases/…

A grant that permits `touch` in the workspace but not `mkdir -p` under a dot
directory leaves worktree provisioning broken while the simpler probe is green.
Asked as its own question so the two cannot be confused.
