# specs/evals — eval configs, the env that runs them, and what we ran

The instructions for *how* an eval is written and scored are **not here**. They
are `references/plugin_evals.md` in `spec-double-compiler` (tla-spec-dev), which
is the organizational standard. Read that first. This directory is the
repository-local half: the configs, the scripts that build the environment, and
the evidence.

## Layout

```
specs/evals/
  README.md              this file — how to run, and what belongs where
  harness/
    build-env.sh         builds the wrappers + EVALHOME from a Skill Manager home
    run.sh               the documented run command
    units-template/      the harness's OWN plugins, checked in
      toolchain/         SessionStart hook: reports the tool fallback chains
      fixture/           SessionStart hook: places the workspace a case describes
    evals/<case>/        case.yaml + graders/*.md — the configs
    build/               generated, gitignored: wrappers, EVALHOME, results
  results/               evidence: what was run, when, and what it cost
```

**Nothing machine-specific is checked in.** The unit wrappers are symlinks into
whichever home you point at, so they are built rather than committed:

```bash
cd specs/evals/harness
./build-env.sh "$SKILL_MANAGER_HOME"     # or a path to any home
./run.sh 'epic-*'
```

## Two rules that are not obvious

**Every case loads every unit.** The thing under test is retrieval *among* the
skills, not capability given the right one. Handing a case only the two units
its task needs does the retrieval for the agent and overfits progressive
disclosure; the score then says nothing about a real session. `build-env.sh`
regenerates each case's `plugins:` list from the home, so a newly installed unit
joins the runs instead of being silently missing.

**`--case` is not optional.** Case discovery recurses into the symlinked units,
and `spec-double-compiler` ships its own `examples/agent_integration/eval-plugin`
— whose cases otherwise run as part of this suite. `run.sh` requires the glob.

## What a cost number means, and what it does not

The graders see three things: a path in the workspace, a tool NAME with a count
range, and the final response. **Nothing sees which command ran** — a run whose
only Bash call was `echo hello` scores `Bash` 1x and `Bash(echo:*)` 0x.

So a green cost grader means *few calls*, never *the right call*. The question
"did the agent read the whole script instead of running one command" is answered
by `trace.jsonl` in the kept temp directory, not by a score. Read the trace.

## Calibration warning, from the first suite ever run here

Four runs of one case went 14 → 10 → 9 → 8 Bash calls, and **every reduction was
a defect in this harness, not in a skill**:

| run | Bash | the defect |
|---|---:|---|
| 1 | 14 | no toolchain hook — nine calls hunting for `git` |
| 2 | 10 | the hook reported presence (`command -v`, `-x`), not function |
| 3 | 9 | the hook executed candidates in ITS environment, not the agent's sandbox, where `/usr/bin/git` is an xcrun shim that fails |
| 4 | 8 | the hook stopped deciding and reported every candidate as a fallback chain |
| 5 | — | the workspace had no repo and no home to operate on; fixture hook added |

**A first red is more likely the harness than the skill.** Only the trace tells
them apart, and the run that proves a skill expensive should be the run whose
trace you have read.

## Adding a case

1. `evals/<name>/case.yaml` — `schema_version`, `name`, `plugins:` (regenerated),
   and `execution:` holding `prompt`, `allowed_tools`, `max_turns`.
2. `evals/<name>/graders/*.md` — one file per grader, YAML frontmatter with
   `type` and `weight`, body explaining what it can and cannot see.
3. Record the run under `results/`, with the trace excerpt that justifies the
   number.
