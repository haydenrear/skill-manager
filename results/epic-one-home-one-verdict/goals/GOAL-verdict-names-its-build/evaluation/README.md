# GOAL-verdict-names-its-build — OHV-8 evaluation

All runs used one scratch home, the one freshly onboarded in
`tickets/OHV-8/fresh-machine-onboard/run2/` with `HOME` and `user.home` redirected.

| verdict | epic `85d0d4511eb0` | OHV-9 build `71aad2fdb576` | released 0.27.2 | verdict |
| --- | --- | --- | --- | --- |
| `home verify` | `build: skill-manager 0.27.2+g85d0d4511eb0 @ 85d0d4511eb0 (refs/heads/feature/OHV-8)` (`09-epic-home-verify.out`) | `… 71aad2fdb576 (refs/heads/feature/OHV-9)` (`ohv9-build-verdicts/`) | no build named (`15-released-home-verify.out`) | met |
| `home repair` (text and `--json`) | `build:` line, `"build"` field | same, OHV-9 build | none | met |
| `home drift --json` | `"build"` | same | none | met |
| `artifacts list` (text and `--json`) | `build:` line, `"build"` field | same | none | met |
| `skt check` from **skt#46's branch** (`skt-check-pr46/`, head in `skt-branch-head.txt`) | `build: skt 0.8.2; skill-manager 0.27.2+g85d0d4511eb0 @ …` | `… 71aad2fdb576 …` | `… skill-manager 0.27.2 @ artifact 862d3a4c6017 built 2026-09-13T17:38:37Z` | met on the PR branch |

**5 of 5, distinguishable across two builds.**
- The two stamped builds (epic and OHV-9) each name themselves. Released 0.27.2 predates the stamp, so its
  outputs are distinguishable by the absence of a build line.
- skt#46 is intentionally not merged. This is **met on the PR branch**; the released-pair reading happens at
  finalization.
- With no `bin/cli/skill-manager` pin, which is a freshly onboarded home's state, `skt check` prints
  "no skill-manager build consulted (no-cli)" (`skt-check-pr46/01-no-pin.out`, DEF-OHV-184).
- For the runs with a pin, a wrapper pin was placed in the scratch home and removed afterwards.
