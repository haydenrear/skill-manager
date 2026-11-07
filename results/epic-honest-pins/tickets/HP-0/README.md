# HP-0 evidence (#408)

Worktree `/Volumes/dev/skill-manager-hp-0`, branch `feature/HP-0`, base 1b52427487a0.

## (1) #408 — MigrationSatisfiesTheGateTest

- Root cause, reproduced: with a Homebrew git 2.56.0 bottle, `git push <url> HEAD:refs/heads/main`
  from a clone whose origin is that URL UPDATES `refs/remotes/origin/main`; with git 2.54 it does
  not. The fixture relied on the 2.54 behaviour, so on the runner's git 2.56 the precondition
  "the local refs alone say unpublished" was false.
- Fix: the fixture pushes a first commit through origin, a second by URL, and rewinds
  `refs/remotes/origin/main` to the first with `update-ref`; a setup assertion checks the remote
  holds the second. Precondition and asserted behaviour unchanged.
- `jbang RunTests.java` with /usr/bin/git 2.54: **ALL PASSED** (exit 0).
- `jbang RunTests.java` with git 2.56.0 on PATH: the #408 case **PASSES**; 2 OTHER cases fail on a
  transient `.git/objects/maintenance.lock` (DEF-HP0-001, not seen on CI) —
  `unit-git-2.56-failures.txt`.
- CI dispatch on the pushed branch: see the PR body.

## (2) honest-pins graph and (3) baseline

See `../../baseline/README.md`. Local signal (`honest-pins-local-signal.json`):
pin nodes (b)-(e) red, record nodes (a)-(e) red, each on its shape_* assertion only;
every setup_* and control_* green.

## Validation matrix

| entry | result |
| --- | --- |
| spec_unit `uv run --with pytest pytest specs/program_model/tests -q` | 11 passed |
| repository_unit `jbang RunTests.java` | ALL PASSED (git 2.54) |
| graph honest-pins | executed; red per unfixed shape, as expected (sweep 20261107-172944) |
| graph cli-pin | PASSED (sweep 20261107-173940) |
| graph smoke | NOT VERIFIED: `postgres.up` failed, the Docker daemon is not running on this host and `open -a Docker` fails (-600); no smoke node runs anything HP-0 changed (`sweep-cli-pin-smoke.json`) |
