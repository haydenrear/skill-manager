# Desired Program Model — child-home-materialization-workflow

Planned destination for the active ticket workflow. It is both a formal model
target and a structured implementation plan. `ticket_plan.yaml` is the source
of truth for tickets.

Initial ticket: `CHM-1` — Child home units materialize by copy, not by symlink.

## What this epic changes

`skill-manager project resolve` scaffolds `<project>/.skill-manager` plus the
`.claude` / `.codex` / `.gemini` agent roots. Historically every unit in that
child home was a symlink into the parent store, so an agent editing a skill in
its project home wrote through to the global store. Child-home units are now
independent **copies**: staged, atomically moved into place, backed by a
per-unit materialization record, held back and reported when locally modified,
and with in-unit symlinks into the parent store dereferenced so the isolation
actually holds.

## Baseline

`../program_model/` is a **single 1897-line `SkillManager.tla`**. It has no
`Core.tla`, no `Internal.tla`, and no `External.tla` —
`tla-spec-dev scaffold workflow` warns about exactly that. The scaffold's README
template listed those three files; this file says what is actually here instead
of rewording the model to fit the template.

- Program model manifest: `../program_model/spec_manifest.yaml`
- Program model: `../program_model/SkillManager.tla`

## Layout here — transitional, deliberately

| File | Role |
| --- | --- |
| `Core.tla` | Shared constants and operators for the child-home slice. |
| `Internal.tla` / `Internal.cfg` | Fine-grained materializer state: staging, the materialization record, per-unit outcomes. Spec-unit case source. |
| `External.tla` / `External.cfg` | Publicly observable behavior of a child-home pass. Test Graph case source. |
| `External_regression_*.cfg`, `Internal_regression_*.cfg` | **Expected-violation** configs. Each must keep producing a TLC counterexample. |
| `SkillManager.tla` / `MC.cfg` / `MC_program_promotion.cfg` | The accepted monolith, carried forward unchanged: the not-yet-migrated remainder of the program. |

Two representations of one program is a hazard, not a feature. While both
exist, `Core`/`Internal`/`External` are authoritative for child-home
**materialization** (what a child unit contains, whether it is independent,
what happens to local edits) and `SkillManager.tla` stays authoritative for
child-home **membership** (which units, servers, shims, and agent configs a
child home has). Never state a rule in both. Phase `MIG` in `ticket_plan.yaml`
retires the split.

## The four desired properties

| Property | Invariant | View | Regression config |
| --- | --- | --- | --- |
| Independence | `ChildHomeWritesNeverReachTheParentStore` | External (+ Internal) | `External_regression_symlink.cfg` |
| No silent destruction | `AgentEditedChildUnitsAreNeverDestroyed` | External (`AgentEditsSurviveMaterialization` in Internal) | `External_regression_overwrite.cfg` |
| Convergence | `UnmodifiedChildUnitsConvergeOnTheirSource` | External | `External_regression_rawdigest.cfg` |
| Atomicity | `InFlightMaterializationLeavesTheChildUnitIntact` | Internal | `Internal_regression_nonatomic.cfg` |

The accepted model could not state any of them: `@port
SkillManagerCli.scaffold_project_child_home` (`SkillManager.tla:1315`) models
`child_home_units` as a bare, monotonically growing relation with no notion of
content, materialization, or independence. Under symlinks the parent unit and
the child unit were the same bytes, so "the agent's edit survived" and "the
parent store was untouched" were the same proposition and neither could fail.
`store_body` and `child_home` here are two distinct objects, which is what
makes the properties both statable and violable.

## Validation

```bash
# desired state — must be clean
bash specs/desired_program_model/run_tlc.sh specs/desired_program_model/External.tla specs/desired_program_model/External.cfg
bash specs/desired_program_model/run_tlc.sh specs/desired_program_model/Internal.tla specs/desired_program_model/Internal.cfg

# regressions — must each produce a counterexample
bash specs/desired_program_model/run_tlc.sh specs/desired_program_model/External.tla specs/desired_program_model/External_regression_overwrite.cfg
bash specs/desired_program_model/run_tlc.sh specs/desired_program_model/External.tla specs/desired_program_model/External_regression_symlink.cfg
bash specs/desired_program_model/run_tlc.sh specs/desired_program_model/External.tla specs/desired_program_model/External_regression_rawdigest.cfg
bash specs/desired_program_model/run_tlc.sh specs/desired_program_model/Internal.tla specs/desired_program_model/Internal_regression_overwrite.cfg
bash specs/desired_program_model/run_tlc.sh specs/desired_program_model/Internal.tla specs/desired_program_model/Internal_regression_nonatomic.cfg
```

Recorded results: `../results/chm-*.txt`, indexed in `spec_manifest.yaml`
under `validation:`.

## Not done here

- `case_adapters.toml` and `testgraph_bindings.yml` mappings for the new
  actions (ticket `CHM-8`).
- `test_graph` nodes for this epic — owned by a separate agent.
- Effect ports and providers — hard-blocked on the Internal/External migration
  (`ticket_plan.yaml` phase `EFF`).
- Nothing is closed and nothing is promoted. `specs/current` is unchanged from
  the accepted baseline.
