# OUN-4 — skill-dev-skill, deleted everywhere

Owner decision, 2026-09-05: *"delete everything to do with skill-dev-skill …
All Java code should be updated."* So the ticket took the full cost that
`DEF-OUN-005` had priced, rather than the slice the plan wrote.

## The premise, proved with the tool rather than grep

```
$ skill-manager deps --who-imports skill-dev-skill
what imports skill-dev-skill in /Users/hayde/.skill-manager
  nothing imports it directly
  reaches it only directly — 0 unit(s) in total
```

That is OUN-3 justifying OUN-4, which is why the plan sequenced them that way.
Reviewing the unit itself agrees: its CLI is `open / status / sync / git /
close` over an installed unit's worktree — every one of which is now `skt
publish`, `skt ticket`, or `sync --from <dir> --merge`.

## What came out

| | |
| --- | --- |
| the unit | `skill-dev-skill/` — 11 files |
| **two** bundled lists | `BundledSkills.GITHUB_COORDS` **and** `OnboardCommand.BUNDLED_SKILLS` |
| a registered graph | `skill-dev-smoke` — 9 sources, 7 nodes |
| the accepted model | `SkillDocSurfaces`, `SkillDevSkillWorkflows`, `ExpectedSkillDocCoverage` |
| CLI metadata | 4 `docs(…)` surfaces in `CliMetadata` |
| tests | the doc-coverage surface in `SkillManagerSkillDocsTest`, the graph names in `test_program_model_contract` |
| prose | `skill-project.toml`, three graph javadocs |

**The second bundled list is the finding.** `BundledSkills.GITHUB_COORDS` and
`OnboardCommand.BUNDLED_SKILLS` hold the same three facts for two different
readers, and the compiler cannot relate them. A retired unit left in either one
is still onboarded by whichever path reads that copy. Both are now commented to
say the other exists.

## The onboard assertions were inverted, not deleted

`onboard.skills.installed` asserted `skill-dev-skill` was present; it now
asserts it is **absent**, along with its `installed/` record.
`onboard.seeded.by.server` asserted the registry seeded it; it now asserts it
does not.

Deleting those assertions would have left the absence untested — and a seed
list that quietly grows the unit back is exactly the regression nobody would
notice, because the unit installs perfectly well. It simply isn't wanted.

## What was left alone, deliberately

Six javadoc mentions in `src/main/java/dev/skillmanager/artifacts/` and
`SyncUseCase` cite `skill-dev` in accounts of **measured incidents** — "the
live `provisioned-tree:cache/uv-tools owner=skill-dev-skill` while the install
wrote only `cache/uv-tools/skill-dev`". Renaming the unit in a record of what
happened would falsify the record, for the same reason `specs/.history` is
append-only. They stay.

## The model edit was cheap, and that is worth separating

`DEF-OUN-006` says the accepted model needs plugin containment before it can
describe OUN-2's refusal, and that is expensive. This edit was the opposite
kind: **removing a name from a set**. Different cost entirely, so it did not
have to wait for the containment decision — and the two should not be confused
when that decision is made.

## Validation

| | result |
| --- | --- |
| `jbang RunTests.java` | ALL PASSED |
| `uv run pytest specs/program_model/tests` | 11 passed |
| `onboard`, `smoke`, `doc-smoke` | all green |
| `select-graph-set.py --scope full` | 29 graphs registered, selector accepts the removal |
