Refs #453 (epic #414). Three housekeeping items; no production code and no `cheap-checks` node changed.

- **DEF-CC6-003:** `cheap-checks` is back in `EXCLUDED` as a local verdict graph (macOS `/usr/bin/time -l`, checkout project home; CI is ubuntu). `CLAUDE.md` counts updated. `run-graphs.py --only cheap-checks` still runs it.
- **DEF-CC6-001:** `doc-smoke` matches "project child homes" across a line wrap. Each gap must be one space or one wrap.
- **DEF-CC6-002:** `KNOWN_GOOD` is now `agent-home.sh`, `bootstrap-home.sh`, `close-change.sh`, `selftest.sh`. The assertion still requires all of them, and "four" in its name is now true (the list held three before).

## Validation

| Check | Result |
| --- | --- |
| `doc-smoke` | PASSED, sweep 20261109-172746 |
| `onboarding`, first run | **FAILED**, sweep 20261109-172746: `onboarding.global.home.untouched` only; `docs.and.scripts.static` passed |
| `onboarding`, rerun | PASSED, sweep 20261109-173544 (`graphs_selected=1 graphs_executed=1 graphs_passed=1 graphs_failed=0 graphs_not_run=0`) |
| `jbang RunTests.java` | ALL PASSED |
| spec unit | 11 passed |

**The assignment says once each; `onboarding` ran twice.** The first run was red because a root-home `sync git` from outside this ticket landed inside its window (`~/.skill-manager/audit.log`, 17:32:28Z). See DEF-CC8-001. The two law nodes after it were skipped in that run and ran in the rerun.

Negative check, using the method copied verbatim from the node (`negative-and-extractor-checks.txt`): the real wrapped SKILL.md matches; that file with the phrase removed, a reworded phrase, a blank-line split, a double space and an empty body all do not.

## Is the onboarding graph right for only one home?

No longer. The node reads the skill from the sandbox home if it has one, else `~/.skill-manager`, so it tracks whichever copy that is. `wt` was the home-dependent entry: the extractor finds it in the root home (plugin b9a00057, one comment in `provision.md:92`) and not in the project or worktree home (68922587). The extractor, run over all three copies, finds the four new entries in each. A future skill edit can still change what the docs reference, and this node would then go red on that edit.

## Goal contribution

`GOAL-checking-costs-what-changed`, contribution `guard`, expected effect: no change to any clause. Local signal `select-graph-set.py --scope full --print`: 29 registered, 27 selected, `hyper-experiments` and `cheap-checks` excluded. Classification: **no measurable movement**; no measured code path is touched. The harness leaving CI changes where the goal can be re-measured, not what it measures. Output is in `local-signal-full-set.txt`.

## Deferred findings

DEF-CC8-001 (`results/epic-cheap-checks/deferred/CC-8.yaml`): `onboarding.global.home.untouched` goes red when another session syncs the root home during the graph. Budget used: 1 of 5.

## Close-out verdict

`skill-manager home close-out`: clean, "holds nothing that removing it would destroy". No custody blockers.

## Skill changes proposed

none met

## Review input

**Hot spots.** `containsAcrossWrap` in `SkillManagerSkillDocsProjected.java`; `KNOWN_GOOD` and its javadoc in `OnboardingDocsAndScriptsStatic.java`.

**Decisions and overrides.** Took the local-only option the owner chose. Fixed the doc match in the node rather than the plugin text. Replaced `wt` rather than shrinking the list. Reran `onboarding` once, as above. No mismatch found against the plan\x27s schedule revision, wave, dependency or promotion order.

**Where I\x27d look for bugs.** The wrap regex does not allow a markdown blockquote prefix (`> `) on the continuation line, by design. The javadoc claim that the four appear in "every version checked" rests on two plugin versions across three homes.

**Machinery friction.** Units changed in the worktree home: none. The git-issue-workflow SKILL.md still titles its section "The one command: `wt`" while steering to `skt`; that is prose only and not part of this ticket.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
