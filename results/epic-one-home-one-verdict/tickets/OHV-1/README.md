# OHV-1 evidence — every verdict names the build that produced it

Issue: haydenrear/skill-manager#338. Branch `feature/OHV-1` from `origin/epic/one-home-one-verdict` @ `de423138`.

## What changed

| surface | text | --json |
| --- | --- | --- |
| `home verify` | first line `build: <stamp>` | n/a (the command has no `--json`) |
| `home repair` | `build: <stamp>` before the verdict (with and without `--fix`) | `"build"` field; also in the `frozen` error document |
| `home drift` | `build: <stamp>` on every branch (`--record`, `--ack`, pending, clean) | `"build"` field in both shapes |
| `artifacts list` | `build: <stamp>` under the header | `"build"` in `ArtifactReport` (so `show`/`record --json` carry it too), schema stays 1 |
| `skt check` (skt repo, PR haydenrear/skt#46) | last line `  build: skt <v>; skill-manager <stamp>` | schema 6: `skt_version`, `skill_manager_build`, `cli.build` |

`<stamp>` is `BuildIdentity.stamp()`, which joins the two lines `--version` already prints: `<release line> @ <build>`. No verdict logic changed, and no JSON field was renamed.

## Unit tests

- `jbang RunTests.java` → `ALL PASSED`, exit 0. New suite `VerdictsNameTheirBuildTest`: 8 cases.
  It judges one home as two checkouts through `BuildIdentity.judgedFrom`. Each output must name its own commit and not the other's, the two outputs must differ, and with the build removed they must be identical (for JSON: every other field equal).
- `uv run --with pytest pytest specs/program_model/tests -q` → 11 passed.
- skt: `uv run --with pytest pytest -q` → 292 passed, 3 skipped (includes `tests/test_check_build_stamp.py`: one home, two real pins).

## Local signal (`local-signal/`)

`home repair --home <this worktree's home>`, run through two builds:

- `brew-build.txt`: `/opt/homebrew/bin/skill-manager` (0.27.2, `artifact 862d3a4c6017`) prints `✓ nothing … is damaged … (41 entries examined)` with **no build line**. Its JSON has no `build` field.
- `repo-build.txt`: `./skill-manager` (0.27.2+gde4231386a9b) prints `build: skill-manager 0.27.2+gde4231386a9b @ de4231386a9b (refs/heads/feature/OHV-1)` above the same verdict. Its JSON carries the same string in `"build"`.
- `diff.txt`: the two outputs differ only in `--version` and the build line/field. Exit codes and `examined`/`clean`/`findings` are identical.

`other-verdicts-{repo,brew}.txt` cover `home drift` (text/json), `home verify`, and `artifacts list` (text/json). The repo build names itself in every one; brew names nothing.
`skt-check-new.txt` / `skt-check-new-json.txt`: the new skt against this home ends with `build: skt 0.8.2; skill-manager 0.27.2+gea638f5560e0 @ …` and reports schema 6. `skt-check-installed.txt` (the installed skt) has no build line.

Against `expected_effect` "0 of 5 -> 5 of 5": **5 of 5 locally**, and a reader can tell the two builds apart. The skt half counts only once haydenrear/skt#46 merges and is released to homes.

## Home close-out (`close-out.txt`)

`./skill-manager home close-out --home /Users/hayde/IdeaProjects/wt-ohv-1/.skill-manager --into /Users/hayde/IdeaProjects/skill-manager/.skill-manager`
→ `✓ … holds nothing that removing it would destroy`, exit 0. It first waited on the project home's lock, which another wave-1 agent held.

## Deferred findings

None.

## CI

Run https://github.com/haydenrear/skill-manager/actions/runs/34790309867 (`graph_set=full`, workflow_dispatch on feature/OHV-1): **executed 25 / passed 19 / failed 6**. Failing: artifact-dag, checkout-home, home-clone, home-tripwire, onboarding, ticket-lifecycle. That is the same set as the main baseline (25/19/6), so this ticket adds **no new failure**. The plan's graphs for this ticket, **plugin-smoke** and **home-integrity**, both pass. Unit tests and virtual-mcp-gateway pytest are green. Artifact: `results/epic-one-home-one-verdict/tickets/OHV-1/ci-graphs-executed.json`.
