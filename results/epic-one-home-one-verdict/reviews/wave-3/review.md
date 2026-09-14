# Wave 3 review — `one-home-one-verdict`

**Range:** `c65a99d2` (the wave-2 review commit) → `42486f86`.

**What landed:** plan revision 3 (`bbe29bd4`), OHV-4 (#341, PR #365, merged as `42486f86`), and the owner-approved `home repair --fix` on the root home. Under `review_policy` each wave gets an artifact and there is no stop until finalization. The diff is in `diff.stat` / `diff.patch`; the root-fix evidence is under `goals/GOAL-no-own-home-path/2026-09-14-root-fix/`.

**What the wave was for:** GOAL-no-own-home-path and GOAL-one-verdict. A shim that spells its own home is now reported however it got that way, and `--fix` fully re-anchors it.

**CI:** run 34814017783, `graph_set=full` on `af8f0aac`: **26 selected / 26 executed / 26 passed / 0 failed**. The epic tip `42486f86` matches `af8f0aac`'s code; the only differences are under `results/` and in the revision-3 plan text.

### Churn by surface

| surface | lines |
| --- | ---: |
| evidence | 466 |
| production (`ShimHomeContract.java` 243) | 298 |
| unit tests | 276 |
| test_graph | 226 |
| goal harnesses | 52 |
| plan (revision 3 + OHV-4 status) | 42 |

- **Shape:** the change is concentrated in `ShimHomeContract`, where the detector and the rewrite now answer separate questions.
- **Surprise:** the home-verdicts slowdown turned out not to be code. See §3.

## 1. Hot spots

| # | Location | Why it's hot | The question to ask |
| --- | --- | --- | --- |
| 1 | `ShimHomeContract.frozenHomeLines` vs `selfDerivingRewrite` | Both parse shell lines with different rules. The detector skips the shebang and `#` comments. The rewrite touches every line after the shebang, comments included | Do heredocs containing `#`, or `#` inside quotes, get classified the same by both? |
| 2 | Whole-prefix spelling match with `PathSpellings` aliases | It decides "this home" versus a sibling home that shares a prefix | Is one unit case (the sibling prefix) enough for `/Users/x/.skill-manager` vs `/Users/x/.skill-manager-2`? |
| 3 | `IntentionalDamage` declarations in home-clone (third ticket in a row) | Every widening of a detector forces declaration edits | `home verify --json` would let declarations use finding objects instead of prose (already recommended in the wave-2 review) |
| 4 | The root home, `~/.skill-manager/bin/cli/{computeq,helm-deploy,monitoring}` | The first real-home write of this epic's repair logic on the operator's own root | Did anything outside `bin/cli` change? Evidence shows `repair --fix` returned exactly the 3 findings. The before/after listings are in the goal evidence |

## 2. Implicit decisions and guardrail overrides

| Decision | Where | Alternative not taken | Cost to reverse |
| --- | --- | --- | --- |
| A spelling only in a `#` comment is not reported. `--fix` leaves the file byte-identical, but a fresh install still re-anchors it | OHV-4 (a), at the epic agent's instruction per #341 | Report comments too | Low |
| A venv-internal shebang is not reported | OHV-4 (a) | Report it as unrepairable | Low |
| A non-shell file's exec line is reported but not rewritten (`repairable=false`) | OHV-4 (a) | Rewrite it anyway | Low |
| GOAL-one-record clause (1) now counts only rows a prune can prove. DEF-OHV-130/131 rows are reported separately | plan revision 3, **owner decision** | Keep the target (a certain miss), or add a ticket | Recorded; the kickoff reading is kept verbatim |

| Override | Where | Judgement |
| --- | --- | --- |
| **Real home written:** `home repair --fix` on `~/.skill-manager` with the OHV-4 branch build (`0.27.2+gaf8f0aac18cb`), not a released build | goal evidence `build.txt`, `fix.json` | **Owner-approved.** Pre-fix shims are backed up with sha256 and copied into the evidence. A Python guard ran `--fix` only if the findings were exactly the three expected shims. Afterwards each shim passed `bash -n`, its exec target resolved, and no line still spelled the home |
| **A guard false positive:** my first root-fix run aborted on its own shell quoting before writing anything | epic agent's run | Nothing was written; rerun with the guard in Python |
| **PR opened by the epic agent:** the ticket agent's session ended on the machine restart after it pushed, before it opened the PR | PR #365 body notes it | The evidence is the agent's committed README; no content was changed |
| **Machine restarted by the owner** to clear the hung Docker backend | — | Background ticket agents in progress were lost. None had unpushed work: OHV-4 had pushed `af8f0aac` |

- No skips added.
- The only goal target or baseline change in range is revision 3's GOAL-one-record clause (1), an owner decision recorded in the plan header.
- OHV-4's close-out: exit 0.

## 3. Where the bugs probably are

| Location | Why | Cheapest settling experiment |
| --- | --- | --- |
| `CliObservability` default OTLP endpoint (DEF-OHV-140, **root cause confirmed**) | Every CLI process spent about 14 s on a hung `localhost:4318` held by Docker's backend. After the restart, `skill-manager --version` takes 1 s (it was 14.15 s). Any operator whose port 4318 accepts connections without answering pays this on every command | Point `OTEL_EXPORTER_OTLP_ENDPOINT` at a listening-but-silent socket (`nc -l 4318`) and time `--version` |
| Comment classification on heredocs | *Suspicion.* `#` inside a heredoc body is not a comment | Plant a shim whose heredoc line contains `# /Users/x/.skill-manager/...`, then run repair |
| Root home after `--fix`, when copied into an image | The copied-home probe reads 3 of 3 met now. But the root pins the released 0.27.2 CLI, whose rewrite still exempts half-rewritten shims | If a future install of deploy-helm into root runs through 0.27.2's writer, does it reintroduce the shape? After the epic releases, reinstall deploy-helm in a scratch root and re-probe |

## 4. Architectural changes worth making

| Observed | Shape | Door | Cost |
| --- | --- | --- | --- |
| A telemetry exporter can block a command for its whole flush deadline (DEF-OHV-140) | A short connect timeout, plus a background export that never blocks exit; the graph sandbox disables OTel unless opted in | New issue on main | Small |
| A background ticket session died on a restart. The only unfinished step was opening the PR | The epic agent could open ticket PRs from committed evidence whenever an agent stops after its push (done here) | `git-epic-workflow` epic-ticket.md: "a pushed branch plus a committed README is recoverable" | None |
| The scratchpad is wiped on a macOS reboot, and OHV-4's root backups lived there | Owner-home backups belong in the committed evidence tree (done for the root fix) | Practice + the skill's validation standards | None |

## 5. Next steps

**Ready now: wave 4, OHV-6 (#352).** Its dependencies OHV-0 and OHV-2 are merged. It serves GOAL-one-marketplace-identity (direct) and GOAL-one-verdict (direct).

**Deferred findings added:** DEF-OHV-140 (major, adjacent). The cause is confirmed by the restart. **Recommend:** promote it to an issue on main at finalization, together with DEF-OHV-010 (the unit suite's temp leak), since both hit every local run.

**Goal trajectory:**

| Goal | After wave 3 |
| --- | --- |
| GOAL-no-own-home-path | **Local signal met on real homes:** clause (1) root 0, project 0 (`measure_goal_no_own_home_path.py` met=true). Clause (2): copied-home probe 3 of 3 (was 2 of 3). Clause (3) fleet context: 38 of 61 homes before the fix, not re-measured after. OHV-8 decides |
| GOAL-one-verdict | Clause (2): root's 3 unreported shims are now reported, then fixed, and root verify/repair both exit 0. Clause (1): 8 shapes pinned in home-verdicts; pending are #352's four (OHV-6) |
| GOAL-one-record | Rescoped at revision 3; OHV-8 measures under the new definition |
| GOAL-verdict-names-its-build | skt#46 merges at finalization (owner) |
| GOAL-ci-green-fresh-runner | 26/26/0 held |

**Owner decisions:** all four earlier ones are answered (Docker, root fix, skt#46, GOAL-one-record). Still open: an issue on main for DEF-OHV-010 and DEF-OHV-140; recommended at finalization.

**Worktrees and headroom:** 9 epic worktrees, 112 GiB free. Docker is healthy after the restart, so wave-4 agents can run Docker-backed graphs locally again.

**Recommendation (the default):** dispatch OHV-6.
