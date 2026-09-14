# Plugin eval suite against the one-home-one-verdict build — 2026-09-14

Refs #337 (epic), before PR #371 merges. Standard followed:
`spec-double-compiler/references/plugin_evals.md`. Every number below was read against
its trace. Traces, verify logs and `.eval/` verdict listings are under `traces/`, and the
aggregate results are archived under `specs/evals/results/runs/<case>/2026-09-14T*.json`,
where `scripts/measure_goal_the_front_door_is_found.py` reads them.

## What was evaluated

| | |
| --- | --- |
| commit | `2b5f83ea` = `origin/epic/one-home-one-verdict` tip ("docs(epic): finalization …"), worktree `feature/OHV-evals` |
| CLI under test | release-shaped fatjar built as `.github/workflows/release.yml` does it: `jbang export fatjar --force -O lib/skill-manager.jar SkillManager.java`, then `bin/skill-manager` (the release launcher, `java -jar`) and `share/virtual-mcp-gateway` |
| its `--version` | `skill-manager 0.27.2` / `build: artifact fa6ad2b0263b built 2026-09-14T18:26:15.120Z (skill-manager.jar)`, jar sha256 `fa6ad2b0263b736409ab…` |
| why no commit in that string | `BuildIdentity` appends `+g<sha>` only when running out of a git checkout, and a jar has none (by design, #133). The same worktree's raw build answers `skill-manager 0.27.2+g2b5f83ea98d8` / `build: 2b5f83ea98d8 (refs/heads/feature/OHV-evals)`, and the jar was exported from that tree seconds earlier (`fatjar-build.log`) |
| Claude Code | 2.1.270 |
| sandbox JDK | `/opt/homebrew/opt/openjdk` (25.0.2) |

The pin did reach the sandbox. Every syncs run's trace shows
`skt sync: using this home's pinned CLI at …/.skill-manager/bin/cli/skill-manager`, and
that CLI ran and answered. The one exception is the bootstrap case's replay, which ran brew
0.27.2; see DEF-OHV-206.

## How the eval homes were made (never the real root or project home)

1. **Waited** for `wt-epic-one-home-one-verdict/…/2026-09-14-final-sync/run.log` to print
   `end Mon Sep 14 14:24:56 EDT 2026`, then took the "before" snapshot at 14:25:09. The
   snapshot is a sha256 of every file to depth 2, plus `installed/`, `bin/cli/`, `skills/`
   and `plugins/`, for `~/.skill-manager`, the project `.skill-manager`, `~/.claude` and `~/.codex`.
2. **One copy of the root home**:
   `<fatjar>/bin/skill-manager home clone --from ~/.skill-manager --to /private/tmp/ohv-evals/src-home`.
   The clone is read-only on its source: block-sharing, 21 records re-anchored, 38 bindings and
   6 registrations not inherited. `home verify --home src-home --against ~/.skill-manager`
   exited 0.
3. **Pinned the copy to the fatjar**: `SKILL_MANAGER_CLI=<dist>/bin/skill-manager <dist>/bin/skill-manager home shims --home src-home`,
   asserted by reading the shim (`cli="${SKILL_MANAGER_CLI:-/private/tmp/ohv-evals/dist/bin/skill-manager}"`).
4. **Per case**: `evals/<case>/setup.sh /private/tmp/ohv-evals/src-home` with
   `EVAL_BUILD_ROOT=/private/tmp/ohv-evals/builds EVAL_CLI=<dist>/bin/skill-manager EVAL_CLI_DIST=<dist>`.
   `branch_home` clones src-home into `$BUILD/home` and `$BUILD/fixture-workspace/.skill-manager`.
   It re-pins each clone to a copy of the dist in `$BUILD/units/toolchain/cli`, because a plugin
   dir is sandbox-readable and `$BUILD` is not. It then syncs skt, git-issue-workflow and
   git-epic-workflow to their tips, as before, and asserts the pin again. All six setups exited 0.
   `verify_env` ran `skt ticket new` for real in the epic case.
5. Then `evals/<case>/run.sh --keep`, one at a time. After each run its trace, verify.log and
   `.eval/` were copied into `traces/` and the ~5 GB sandbox was deleted (`/private/tmp/ohv-evals/run-one.sh`).

### Were the real homes written? Yes, but not by this run, and the evidence says whose

The "after" snapshot (15:44:41) differs from the "before" one (14:25:09) in 24 entries. Every
write to a skill-manager home carries a build string in its own log. None of them is this run's
build, which was either artifact `fa6ad2b0263b` or `+g2b5f83ea` on `feature/OHV-evals`.

| path | when (EDT) | written by | evidence |
| --- | --- | --- | --- |
| `~/.skill-manager/home.drift.json` | 14:26:10 | `skill-manager 0.27.2+g2b5f83ea98d8 (refs/heads/epic/one-home-one-verdict)`, `home drift` "acknowledged 4 changed unit(s) in /Users/hayde/.skill-manager" | `$TMPDIR/skill-manager/logs/home-drift-20260914-142610-f884.log`. That is a checkout on the **epic** branch, i.e. the epic agent's, not `feature/OHV-evals` |
| project `.skill-manager/installed/{git-issue,git-issue-workflow,git-epic-workflow,spec-double-compiler}*.json`, `units.lock.toml`, `cli-lock.toml`, `audit.log`, `skills/git-issue/**` | 14:30:58–14:31:32 | `skill-manager 0.27.2+g833ae0d71633 (refs/heads/epic/one-home-one-verdict)`: `home verify`, four `sync <unit>` (merged 4ca70df, fae9e9e, b6d269d, dd2d517), `home verify` | `home-verify-20260914-143058-d865.log`, `sync-20260914-1431{09,13,18,23}-*.log`, `home-verify-20260914-143132-e3f7.log`; audit.log rows 18:31:11–18:31:29Z. `833ae0d7` is a newer epic commit than this worktree's base |
| `~/.skill-manager/skills/{git-issue,git-issue-workflow,git-epic-workflow}/.git/index` | 14:42:50 | a `git status`-style index refresh by some process. No skill-manager log names the root at that time, and this run's setups at that moment read only `src-home` | mtimes; the snapshot shows no other change under those units |
| `~/.claude/backups/.claude.json.backup.*` (rotated, 10 entries) | throughout | Claude Code itself rotates these whenever `~/.claude.json` is written. `lib.sh eval_claude_home` symlinks `~/.claude.json` into every eval HOME for authentication, so eval runs do write it, **by the harness's design**, and so do other live sessions | nothing else under `~/.claude` or `~/.codex` changed (44 + 48 entries compared) |

So this run did not write the root home, the project home, `~/.claude` (beyond the
`.claude.json` auth link the harness has always used) or `~/.codex`. The epic agent was still
writing both skill-manager homes after its `end` line: 1 minute later for the root home, 6 minutes
later for the project home.

## Results

`prior` is the previous epic's archived score (`results/epic-one-unit-one-name/reviews/epic-close.md` §5).
A case was run 3× when its first run scored below prior or hit max_turns. The reconciles case
was also run 3×, see its row. Cost is per run, agent only.

| case | prior | runs | scores | cost | trace verdict | attribution |
| --- | ---: | ---: | --- | --- | --- | --- |
| epic-provisions-a-ticket-worktree | 1.00 | 1 | **1.00** | $0.41 | `Skill(git-epic-workflow)`, then `skt ticket new DEMO-1 --base <sha> --path ../wt-DEMO-1` (2 Bash). The replay through the epic-pinned home exited 0 with a worktree and its own home. In the sandbox the command was refused, "working tree is not clean", on `.eval-bin/` | skill ✓. Harness DEF-OHV-201 (did not cost a grader this time) |
| ticket-agent-opens-a-ticket | 0.94 | 3 | **0.82 / 0.82 / 0.82** | $0.48 / $0.58 / $0.52 | All three: `Skill(git-issue-workflow)`, then `skt ticket new TICKET-42` (run 3 added `--base main`). Replay exit 0, worktree and home created. The lost grader is the cost ceiling (4, 4, 5 Bash vs max 3). In every run one of those calls is the agent obeying the epic CLI's own refusal line `fix: git -C <cwd> status --short`, which shows only `?? .eval-bin/`. Run 1 also tried a $BUILD path; run 2 had a Read and a Grep of `references/provision.md` refused; run 3 was told the checkout is on `epic/demo-epic`, which is false | **harness**: DEF-OHV-201 (1 call per run), DEF-OHV-205, DEF-OHV-208. Skill and product ✓. Not an epic regression: the refusal message is the product being actionable |
| bootstraps-a-home-for-a-repo | 0.93 | 1 | **1.00** | $0.39 | `Skill(skt)`, then `bootstrap-home.sh .` (2 Bash). **The 1.00 is not a clean pass.** The Stop hook's replay exited **6**, "this home holds 18 skill(s) and an agent launched here can reach 0", and `home-exists` was written anyway. The replay also ran brew 0.27.2, not the build under test | skill ✓ (right door, one call). Harness DEF-OHV-202 (verdict ignores exit code) and DEF-OHV-206 (replay runs PATH's CLI). **Product DEF-OHV-203**: a sync where any unit's fetch fails projects no skills. Reproduced identically on brew 0.27.2 and on the epic jar, and it goes away with credentials, so it predates the epic |
| ticket-agent-closes-a-ticket | 0.90 | 1 | **1.00** | $0.31 | `Skill(skt)`, then `skt ticket close TICKET-7` (1 Bash). The replay (precondition `skt ticket new`, then close) exited 0 and the worktree was gone. In the sandbox the close found no worktree, because `../wt-TICKET-7` is never placed there | skill ✓. Harness DEF-OHV-204 (did not cost a grader) |
| syncs-a-stale-home-from-root | 0.71 | 3 | **0.14 / 0.71 / 0.71** | $1.43 / $0.68 / $1.42 | All three: `Skill(skt)`, then `skt status`, then `skt check`, which answered "new version available for git-issue-workflow — pull with: skt sync git-issue-workflow". Then `skt sync git-issue-workflow` moved the store to the tip `fae9e9e` through the **epic CLI pinned in the sandbox**, and exited 1 with TRANSITIVE_RESOLVE_FAILED: git-epic-workflow and git-integration-repo import the unit by its GitHub URL, but the fixture had rewritten its origin to a local mirror, so the resolver cloned from GitHub in the sandbox and got rc=128. The agent then investigated (38, 15, 32 Bash). Run 1 hit **max_turns (30)**, so the Stop hook never ran and the found front door scored as missed | **harness + sandbox**: DEF-OHV-209 (fixture manufactures the exit 1), #353 (max_turns loses graders, run 1). Skill and product ✓: the currency command is found and is right in 3/3. Same symptom recorded on 0.27.1/0.27.2 (`attribution/2026-09-13-migration-0.27.md`), so **not an epic regression** |
| reconciles-a-worktree-into-the-project-home | 0.62 | 3 | **0.71 / 0.14 / 0.14** | $0.46 / $0.62 / $0.45 | Run 1 scored above prior, but its trace was not archived (see UNDECIDED). The rerun was to read a trace, not to move the score. Runs 2 and 3: `Skill(skt)`, `ls ../wt-TICKET-9` (absent in the sandbox), `git worktree list` (which leaks the unreadable `$BUILD/wt-TICKET-9`), `home --help`, `home sync --help`, then **`skill-manager home sync --from <wt>/.skill-manager --to ./.skill-manager --merge`, the right operation**, written with backslash line-continuations. The extractor split it on `\n`, rejected the piece `… home sync \`, and wrote no front-door or source-undamaged verdict. The 0.14 is the instrument | **harness**: DEF-OHV-207 (front-door lost 2/3), DEF-OHV-204 (missing worktree plus leaked path), DEF-OHV-205 (Grep refused, run 2). Skill ✓ (right operation 3/3 by commands.txt/trace). Product ✓ |

**Total cost: $7.74** for 12 runs, plus $0.00 of setups, probes and repros.

### Regressions attributable to the epic

**None found.** Every sub-1.00 score reads, in its trace, as the harness, the sandbox, or a
pre-existing product defect reproduced on released 0.27.2. What the epic changed that an agent can
see:

- **`skt ticket new`'s dirty-tree refusal now names the cwd and a `fix:` command.** Agents
  obeyed it, which is the refusal working. What it surfaced is DEF-OHV-201.
- **`home verify` failing on repair findings / naming its build.** No agent ran `home verify`
  in any of the 12 traces, so stricter verify cost nothing here. `home verify` on the eval homes
  exited 0 at setup.
- **Skill-script installs detaching foreign `bin/cli` links.** Not exercised by any case.
- **The pinned epic CLI** executed inside the sandbox and answered correctly (`skt check`
  verdicts, `skt sync` moving the unit to tip), and every replay outside it succeeded where the
  fixture allowed.

### Front-door goal, as the harness reads it

`scripts/measure_goal_the_front_door_is_found.py` reads the newest archived run per case,
which is run 3 for the three re-run cases:

```
GOAL-the-front-door-is-found: 5 of 6 (bootstraps=found; epic-provisions=found;
reconciles=MISSED; syncs=found; closes=found; opens=found)  target 6 of 6, met false, exit 1
```

For reconciles, the newest run is a 0.14 whose front door the extractor mis-rejected
(DEF-OHV-207). The harness reports that as MISSED, which is the instrument's reading and not the
agent's behaviour. By trace, the front door was issued in 12 of 12 runs.

## UNDECIDED — runs and facts that are not measurements

- **reconciles run 1 trace lost.** `run-one.sh` copied `out/trace.jsonl` with errors silenced
  and then deleted the sandbox. The archived `commands.txt` holds 1 command, yet the grader counted
  9 Bash calls, and the hook's own log says `bash calls=1`. Runs 2 and 3 counted 8/8 and 6/6, so
  the undercount did not reproduce and is not filed. `run-one.sh` was fixed to keep the sandbox
  whenever the trace does not copy. Its 0.71 stands as a score with no trace read.
- **syncs run 1 (0.14)** ended `error_max_turns`: the currency-command and source-undamaged
  graders never ran (#353). Its trace shows the front door issued twice.
- **bootstraps run 1 (1.00)**: the replay's non-vacuity verdict is unsound for that run (exit 6),
  so "a real home appears" is unmeasured, not passed.
- **Why the JVM-spawned `git clone` gets rc=128 in the sandbox** while the agent's own
  `.eval-bin/git clone` of the same URL succeeds is not settled. DEF-OHV-209 removes the need for
  that clone rather than explaining it. A stray `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`
  appears in every in-sandbox JVM start. Its source was not traced, and this machine's IPv4 route
  is known to be broken.

## Harness changes made for this run (no product code changed)

1. `specs/evals/harness/lib.sh`: new `eval_build_of` and `eval_pin_cli`, called from `branch_home`
   after the clone and again after its sync. With `EVAL_CLI_DIST=<release-shaped stage dir>` set,
   each branched home is re-pinned to a copy of that stage inside `$BUILD/units/toolchain/cli`, a
   sandbox-readable plugin dir, and the pin is asserted by reading the shim. Unset, behaviour is
   unchanged. This is the workaround #353's third bullet asked for, in place of hand-patching pins.
2. Out of tree, not committed: `/private/tmp/ohv-evals/run-one.sh` (run, archive trace, delete
   sandbox), and the build and pin steps above.

## Deferred findings

`results/epic-one-home-one-verdict/tickets/OHV-evals/deferred.yaml`:

| id | kind | one line |
| --- | --- | --- |
| DEF-OHV-200 | harness docs | README documents `build-env.sh` / top-level `run.sh`, which do not exist |
| DEF-OHV-201 | harness | fixture workspace is dirty in-sandbox (`.eval-bin/`, `.eval/` not ignored); costs opens a Bash call every run |
| DEF-OHV-202 | harness | bootstrap `home-exists` verdict written after a replay that exited 6 |
| DEF-OHV-203 | **product (pre-existing)** | a sync with any failing unit projects no skills, so bootstrap-home.sh exits 6 |
| DEF-OHV-204 | harness | `../wt-TICKET-*` never placed in the sandbox; `git worktree list` leaks its unreadable path |
| DEF-OHV-205 | harness | skill `references/` unreadable in runs (Read/Grep refused in don't-ask mode) |
| DEF-OHV-206 | harness | bootstrap replay runs PATH's brew CLI, not the build under test |
| DEF-OHV-207 | harness | front-door extractor rejects backslash-continued commands; reconciles 0.14 ×2 |
| DEF-OHV-208 | harness | fixture hook tells every case it is on `epic/demo-epic` |
| DEF-OHV-209 | harness × sandbox | syncs fixture's local-mirror origin makes importers unmet, so `skt sync` exits 1 and the agent investigates |

Known and not re-filed: #353 (max_turns loses Stop-hook graders).

## Bootstrap repro excerpts (DEF-OHV-203)

`repro-bootstrap/bootstrap-{epic,brew}.log` (HOME = empty dir, so no git credentials): both show
`7 × git sync failed — git fetch/merge rc=1`, `synced 15 unit(s)`, then
`error: this home holds 18 skill(s) and an agent launched here can reach 0`, exit 6.
`repro-bootstrap/resync-{epic,brew}.log` (same homes, operator HOME for credentials, explicit
`CLAUDE_CONFIG_DIR`/`CODEX_HOME`/`GEMINI_HOME` into the scratch checkout): both
`synced 22 unit(s)`, exit 0, 18 skills in `.claude/skills`.
