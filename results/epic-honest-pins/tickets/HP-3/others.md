# HP-3: the other graphs in validation.graphs

One sweep, `20261107-194746` (graph-run.sh, `--continue`), feature/HP-3 at 46f3d13b:

| graph | verdict | run |
| --- | --- | --- |
| checkout-home | PASSED | 20261107-194800 |
| home-sync | PASSED | 20261107-195121 |
| home-clone | PASSED | 20261107-200045 |
| ticket-lifecycle | FAILED (not attributable) | 20261107-200641 |

ticket-lifecycle: the ONLY red node is `ticket.lifecycle.global.home.untouched`
(`the_operators_real_agent_homes_did_not_move`), and every difference it lists is
an mtime-only change (same size) to `.claude/skills/synced/<session>/manifest.json`
and `.last-complete-round` under the operator's real `~/.claude`: the Claude
desktop session running this agent re-syncs its skill listing on a timer. Nothing
under any skill-manager home differs, and the change touches no ~/.claude path
HomeSync writes. Not attributable to HP-3; known operator-host noise.
Every other ticket-lifecycle node passed, including the close-out / publish nodes
that run `home sync`.
