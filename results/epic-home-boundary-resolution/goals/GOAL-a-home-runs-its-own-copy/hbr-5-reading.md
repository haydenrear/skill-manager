# GOAL-a-home-runs-its-own-copy — HBR-5 reading, 2026-09-02

**The instrument was wrong. The corrected value is 2, not 55.**

| | old test | corrected |
|---|---|---|
| unsanctioned pairs | 55 | **2** |
| sanctioned fallbacks | 0 | **53** |
| homes scanned | 41 | 41 |

## What the walker asked, and what it should have asked

It asked *"does this home hold the unit DIRECTORY the shim reaches past?"*:

```python
mine = home / kind / unit
(pairs if mine.exists() else fallback).append(row)
```

A shim does not run a directory. `deploy-helm`'s runs a per-unit **cache venv
binary**:

```
exec <home>/cache/skill-script-deploy-helm-computeq/venv/bin/computeq
```

and `home clone` **deliberately does not copy** `cache/`, `venvs/`, `tools/` or
`npm/` — bootstrap-home.sh says so in its own output. So a freshly cloned home
holds `skills/deploy-helm` and *not* the venv that shim execs. The old test read
that as "has its own copy, runs the parent's anyway" — an unsanctioned crossing.
It is the exact opposite: the mirror is the **only way the tool runs at all**,
which is the sanctioned fallback the metric says it excludes.

The corrected test maps every foreign path a shim names into this home and
charges the crossing only when the home holds **all** of them.

## The baseline was wrong the same way

`19 unsanctioned / 0 sanctioned fallbacks` (2026-08-29) came from the same code.
"Every single crossing is the unsanctioned kind, with a local copy sitting
unused" was an artefact, not a finding. The growth 19 → 55 was real but
measured the wrong population: it tracked how many worktrees other projects
created, because each new clone adds three unprovisioned `deploy-helm` mirrors.

## What is actually left

Two pairs, both `spec-double-compiler` / `tla-spec-dev` — a plain Python script
with no venv, so the home holds everything the shim needs and reaches past it
anyway:

| home | reaches into |
|---|---|
| `IdeaProjects/skill-manager/.skill-manager` | `~/.skill-manager` |
| `IdeaProjects/wt-epic-mh-testing/.skill-manager` | `IdeaProjects/skill-manager/.skill-manager` |

These are genuine, they are the shape `home repair` now names
(`PARENT_SHIM_SHADOWS_LOCAL_COPY`, #289), and one of the two is this
repository's own project home.

**Target 0 is now within reach** — it is two shims, not a machine-wide
remediation across 19 homes owned by other projects.
