# GOAL-one-verdict — OHV-8 evaluation

Build: `skill-manager 0.27.2+g85d0d4511eb0` (raw, `wt-ohv-8`). Full table: `tickets/OHV-8/README.md`.

| clause | baseline | measured | target | verdict |
| --- | --- | --- | --- | --- |
| (1) planted shapes reported by both | no graph | 11 of 11 (`home-verdicts-local-20260914-153435/`, 18/18 nodes); CI run in `tickets/OHV-8/README.md` | every planted shape | met. `/var` not planted (DEF-OHV-185) |
| (2) disk facts damaged while verify or repair exits 0 | root 4, project 4 | root 0, project 0 in `real-homes/` (15:38Z) and `real-homes-run2/` (15:57Z) | 0 | met; both homes still damaged, see DEF-OHV-180/181 |
| (3) fleet: verify 0 while repair reports | 50 of 61 | 0 of 57 (`fleet-epic-build.summary.json`; 11 epic worktrees excluded) | 0 | met |

- `observe_real_homes.py <dir>` reads the disk (bin/cli and bin/mcp shims, checkout agent links, orphaned
  projection records, pm stamps, marketplace registrations in the home's own agent files). It matches each fact
  to the `home repair --json` subject and to the `home verify` text saved beside it.
- `bin/cli/tlc2` in the project home is a link into the root. `home verify` names it as the sanctioned
  parent-store shim, and it is not counted.
- Released 0.27.2 exits 0 on the project home in both passes, and on root in the second. That disagreement is
  between builds, not between verify and repair within one build.
