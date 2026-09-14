# GOAL-one-marketplace-identity — OHV-8 evaluation

Build: `skill-manager 0.27.2+g85d0d4511eb0`. Full table: `tickets/OHV-8/README.md`.

| clause | baseline | measured | target | verdict |
| --- | --- | --- | --- | --- |
| (1a) four planted shapes reported and cleared | no graph | 4 of 4 (the `marketplace.under.another.name`, `marketplace.identity.unregistered`, `copied.marketplace.identity` and `foreign.marketplace.registration` nodes in `../../GOAL-one-verdict/evaluation/home-verdicts-local-20260914-153435/`) | 4 of 4 | met |
| (1b) 0 AGENT_SYNC_FAILED on the next sync | — | 0 in every node log, but no node runs a sync (local-file units cannot sync). Supporting evidence: OHV-6's four real-home `sync skt` runs, 0 each | 0 | unmeasured |
| (2) root and project | root shape 4, project none | root 0, project 0 (`home repair` MARKETPLACE_* kinds, both passes); old harness: root none (shape 2 exposed), project none | 0 | met |
| (3) fleet | 0/15/11, any 24 of 61 | corrected, from repair kinds over 57 homes: 1/2/3/4 = 1/0/15/6, any 22. Old harness, 68 homes: 0/17/7, any 22 | reported | met (reported) |

**DEF-OHV-160 correction:**
- `scripts/measure_fleet_one_home_one_verdict.py` counts shapes from `home repair --json` kinds:
  `MARKETPLACE_REGISTERED_UNDER_ANOTHER_NAME`=1, `MARKETPLACE_IDENTITY_UNREGISTERED`=2,
  `MARKETPLACE_IDENTITY_COPIED`=3, `FOREIGN_MARKETPLACE_REGISTRATION`=4. Those kinds read each home's own
  `.claude`/`.codex` files.
- The old harness reads shape 1 only from the global file (0 against 1), and counts shapes 3 and 4 more
  loosely (17 against 15, 7 against 6).
- The old harness's output is `measure_goal_one_marketplace_identity-old-harness.json`.
