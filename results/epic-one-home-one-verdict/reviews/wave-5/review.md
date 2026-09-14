# Wave 5 review — `one-home-one-verdict`

- **Range:** `4578ce78` (plan revision 4 plus the placeholder fix) to `5ab5770b`.
- **Merged:** OHV-9 (#367), PR #368, as `5ab5770b`.
- **Policy:** `review_policy` requires one artifact per wave and no stop before finalization.
- **Diff:** `diff.stat` and `diff.patch`.
- **OHV-9's evidence:** `tickets/OHV-9/`.

**What the wave was for.** It closes GOAL-a-home-writes-only-itself, the goal added at revision 4 after the operator's root shims were overwritten through a test home's `bin/cli` links (DEF-OHV-011). An install in one home must never write another home's files.

**CI.** Run 34858642285 (`graph_set=full`, on `2a430bb6`) selected 26 graphs, executed 26, passed 26 and failed 0. The epic tip `5ab5770b` has the same code; it differs only under `results/` and in the plan's status line.

### Churn by surface

| surface | lines |
| --- | ---: |
| evidence | 484 |
| unit tests (`SkillScriptWriteThroughTest` 252, `BinCliWritersDoNotFollowLinksTest` 142) | 398 |
| production (`ForeignBinLinks.java` 277, plus small changes to `LauncherShims`, `SkillScriptBackend`, `BrewBackend`, `NpmBackend`) | 361 |
| test_graph (`ChildInstallWritesOnlyItself` 277) | 308 |
| plan | 2 |

**Shape.** One new helper, `ForeignBinLinks`, carries the guard. Every writer of `bin/cli` goes through it or is shown not to need it.

**Surprise.** A second leak that nobody had seen: `LauncherShims`, which writes `bin/cli/skill-manager` and `bin/launch/*`, also wrote through links. On the old code its test showed a parent home's CLI pin replaced by the child's.

## 1. Hot spots

| # | Location | Why it's hot | The question to ask |
| --- | --- | --- | --- |
| 1 | `ForeignBinLinks.java`: the detach / run / restore / stat-compare sequence | Every skill-script and pip install now moves the operator's links around a subprocess. A crash between detach and restore would leave links missing | Is restore in a `finally`, and does a JVM kill mid-install leave a recoverable state? `home repair` would report missing links as unresolved; is there a test? |
| 2 | "Outside the home" means any link target outside the store, not only another home | Brew cellar links, `/opt/homebrew/...`, are detached and restored on every skill-script install | Any cost or race for a long-running tool that execs through that link during an install? |
| 3 | `LauncherShims` now removes a link before writing | This changes `home shims` and pin generation in every home, including child homes whose `bin/cli/skill-manager` used to be a link to the parent's pin | Children now get their own pin file. Do the project graphs asserting "the child's CLI is a link" still hold, or do they only cover mirrored units? CI was green, so the assertions still hold today |
| 4 | A child home that actually runs a skill-script over a parent link now owns its own shim | A deliberate behaviour change to the child-home model's "linked at parent store" mode, for units whose installer runs | Does `ChildHomeMaterializer` later relink it on a sync (a flip-flop)? |

## 2. Implicit decisions and guardrail overrides

| Decision | Where | Alternative not taken | Cost to reverse |
| --- | --- | --- | --- |
| Detect a changed target by size + mtime + inode, never by reading content | `ForeignBinLinks` | Hash the target | Low |
| pip installs get the same guard because uv's behaviour can't be proven | OHV-9 (b) | Test uv hermetically | Low |
| npm, brew and tar were shown safe (they delete before linking) and moved into one shared helper | OHV-9 (b) | Leave each backend's code alone | Low |
| `ChildHomeMaterializer` and `HomeCloner`, which create parent links, were not changed | OHV-9 scope | Stop creating links into the parent for units that have an installer | Medium; see §4 |

| Override | Where | Judgement |
| --- | --- | --- |
| **Non-vacuity check with a temporarily reverted fix.** The worktree briefly held the pre-fix `SkillScriptBackend` so the new node could be shown red | OHV-9 evidence `graph/` | Restored before anything was committed or pushed; the red-then-green record is in the evidence |
| **The graph node points the gateway URL at an unreachable host** to avoid building a gateway venv during the install | `ChildInstallWritesOnlyItself` | A test-environment shortcut; the install's exit code is still asserted |
| **deploy-helm PR opened on a renamed repository** (`deploy-cdc` now redirects to `deploy-helm`) | haydenrear/deploy-helm#63 | Not merged, not published into any home |

No tests were skipped, no goal target changed, and close-out exited 0.

## 3. Where the bugs probably are

OHV-9 named the first three rows itself.

| Location | Why | Cheapest settling experiment |
| --- | --- | --- |
| A brand-new name a script links into another home | Only names that were detached are guarded, so a script that creates `bin/cli/new-tool -> /other/home/...` is not caught | A skill-script that runs `ln -s /tmp/parent/bin/cli/x "$SKILL_MANAGER_BIN_DIR/y"`; assert the install fails |
| A same-size, same-mtime, same-inode rewrite of a target | The stat check would miss it | *Suspicion.* Rewrite with `touch -r` to preserve mtime; see whether the guard notices |
| Two concurrent installs in one home | Detach/restore is not locked, so one install could restore links another had detached | Two parallel installs into a scratch home with a shared foreign link |
| Crash between detach and restore | Hot spot 1 | `kill -9` the CLI during a slow skill-script; check `home repair` on the home |

## 4. Architectural changes worth making

| Observed | Shape | Door | Cost |
| --- | --- | --- | --- |
| Test graphs in another repository parented a project home on the operator's real root | Every graph-built home gets a scratch parent; a tripwire watches `$HOME/.skill-manager` during graph runs | commit-diff-context-parent#262 (filed) | Small |
| Links into a parent home exist for units the child also installs | `ChildHomeMaterializer` should not link a unit whose installer the child will run, or should replace the link before the install. OHV-9 guards the write; the link shape stays | New issue on main at finalization | Medium |
| The write guard is at the installer level, while `WriteConfinement` already exists as a home-wide write guard | Route installer subprocess writes through a confinement check, for example by running scripts against a bind-free staging `bin/cli` | Future epic | Large |

## 5. Next steps

**Ready now: wave 6, OHV-8 (#358), the terminal evaluation.** It depends on all nine tickets, and all are merged. It owns seven goals. Its worktree `../wt-ohv-8` is fast-forwarded to `5ab5770b`.

**Deferred findings added this wave:** none.

**Goal trajectory (local signals):**

| Goal | Where it stands |
| --- | --- |
| GOAL-a-home-writes-only-itself | (1) the child install leaves the parent byte-identical: node green, red on the pre-fix code; (2) a write through a detached target fails the install: unit cases; (3) every bin/cli writer covered or documented: backend table. Local signals met; OHV-8 decides |
| GOAL-no-own-home-path | root 0, project 0 (`measure_goal_no_own_home_path.py`, re-read in this wave) |
| GOAL-one-marketplace-identity | root and project clean after wave 4; fleet context 0/13/8 |
| GOAL-one-verdict | verify and repair agree on every home checked |
| GOAL-one-record | rescoped at revision 3; OHV-8 measures it |
| GOAL-ci-green-fresh-runner | 26/26/0 held through every wave since wave 1 |
| GOAL-verdict-names-its-build | 4 of 5 built into skill-manager; `skt check` waits for skt#46 at finalization, owner's decision |

**Carried to finalization:**
- Issues on main for DEF-OHV-010 (unit-suite temp leak) and DEF-OHV-140 (blocking telemetry export).
- A child-home link issue (§4).
- Merging skt#46 and deploy-helm#63.
- Deleting the three backup directories under `/Users/hayde/IdeaProjects/.oh*-backup*`.

**Worktrees and headroom:** 11 epic worktrees, 89 GiB free, Docker healthy, CLI starts in 1 s.

**Recommendation (the default):** dispatch OHV-8.
