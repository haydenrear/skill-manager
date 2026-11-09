# cheap-checks baseline (CC-0, #441)

Measured on 2026-11-08 at `origin/epic/cheap-checks` dbd6c783 (0.29.0) with the CC-0 harness on top.
Evidence: `2026-11-08-dbd6c783/` (`cheap-checks.json` per-node status and assertions; `stale-time.json` and
`close-out.json` the timing evidence; `sweep.json` the sweep ledger).

```
GRADLE_OPTS=-Dorg.gradle.daemon=false JAVA_TOOL_OPTIONS=-Djava.net.preferIPv6Addresses=true \
  TESTGRAPH_CONTINUE_AFTER_FAILURE=1 python3 .skill-manager/plugins/tla-spec-dev/skills/test-graph/scripts/run.py cheap-checks --continue
```

Sweep `20261109-022337`, run `20261109-022350`: graphs_executed=1, graphs_failed=1. All 6 clause nodes executed. **Every setup_* and
control_* assertion passed; every node is red only on shape_* assertions.**

Fixtures. Timing nodes use `home clone` of this checkout's project home (tla-spec-dev: 26,588 files, 429 MB), not #401's
fixture (another repository's 31,756 files / 428 MB). Installers, projection, edit detection and exclusion use synthetic
units so a counter is exact. Agent CLIs are fakes on PATH (real ones are never run), so projection is counted in CLI
calls, not seconds.

## GOAL-checking-costs-what-changed

| clause | node | target | measured | verdict |
| --- | --- | --- | --- | --- |
| (a) stale time | `cheap.stale-time` | < 6 s | median **8.02 s** (8.05, 8.02, 5.03), 617 MB RSS | red |
| (a) no bytes read, clean checkout | `cheap.stale-time` | 0 files | **26,170** plugin files read by the JVM (JFR `jdk.FileRead`; plus 69 manifests) | red |
| (a) planted edits detected | `cheap.edit-detection` | 4 of 4 | **3 of 4**: tracked, untracked, re-pointed link yes; **gitignored no** | red |
| (b) close-out time | `cheap.close-out` | < 5 s | median **45.69 s** (44.83, 46.27, 45.69) | red |
| (b) close-out RSS | `cheap.close-out` | < 300 MB | median **1,277 MB** | red |
| (c) foreign installers re-run | `cheap.foreign-installers` | 0 | **2 of 2** (also 2 of 2 under `--force-scripts`, metric only) | red |
| (d) foreign plugins re-projected | `cheap.foreign-projection` | 0 | **3 agent-CLI calls** naming the other plugin (claude uninstall, claude install, codex add), of 11 CLI calls in the sync | red |

## GOAL-excluded-paths-keep-git

| clause | node | target | measured | verdict |
| --- | --- | --- | --- | --- |
| (a) files at excluded path | `cheap.install-exclusion` | 0 | **2** | red |
| (b) git ops (log, blame, diff across history, status, commit, fetch, push) | same | 7 of 7 | **7 of 7** | green |
| (c) commit keeps excluded paths in tree | same | yes | **yes** | green |

Exclusion is measured with a placeholder manifest key (`install_exclude`); no mechanism exists, so the unknown key is
ignored at install. CC-5 replaces the key in `InstallExclusion.java`.

## Read this before quoting a number

- **`artifacts stale` time is bimodal on this machine** and straddles the 6 s target: three runs of the node over this
  session gave [9.21, 9.57, 9.08], [5.17, 5.14, 5.12] and [8.05, 8.02, 5.03] (the last is the one recorded above). The
  second run dropped the close-out node for a dev iteration and is not the baseline. The cause of the two modes was not
  determined. A single sub-6 s reading is therefore weak evidence for CC-1; the no-bytes assertion is the sharper instrument.
- **Close-out measured 45-50 s and 1.27-1.45 GB here, against #401's 19 s / 1.2 GB** on a different repository's fixture.
- **The gitignored arm is not a regression.** The plan says the current digest sees gitignored files; it does not
  (`HomeDigest.withoutIgnored`). Asserted as the target states; CC-1 owns the policy. See `deferred/CC-0.yaml` DEF-CC0-001.
- JFR counts reads by the CLI's JVM only, not by git subprocesses (intended: the target is "ask git"). Its control:
  a dirty checkout must read files, else 0 means nothing.
- BSD `/usr/bin/time -l` only; off macOS the timing nodes fail `setup_time_l_parsed` rather than estimate.
