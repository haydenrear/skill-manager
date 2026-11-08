# HP-8 evidence (#394)

- `honest-pins-local-signal.json` — local signal: `honest.record-e` failed at baseline (both `shape_*` clauses red, control green) → **passed** at f4187e64, control still green. Classification: moved as expected.
- `honest-pins-record-e-envelope.json` — the node's envelope from run 20261107-210240.
- `sweep-project-smoke-project-child-home-home-integrity-honest-pins.json` — one sweep of the four matrix graphs: project-smoke, project-child-home, home-integrity PASSED; honest-pins FAILED only on `honest.pin-e` and `honest.record-b`, both red at baseline and outside this slice.
- `unit-suite-summary.txt` — `jbang RunTests.java`: ALL PASSED, 0 `[FAIL]`.
- `home-close-out.json` — `home close-out --home <worktree>/.skill-manager --into <project home> --json`: safe=true, exit 0, no blockers.

Not run: a root-home sync on a real home. A root sync reconciles every registered child home, so on the operator's root home (or a copy whose registrations still point back) it would write real project homes (DEF-HP2-002). The graph node drives the real CLI (`project resolve`, then root `sync <unit>`) on scratch homes instead.
