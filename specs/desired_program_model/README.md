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
| `Core.tla` | Shared constants and operators for both slices. |
| `Internal.tla` / `Internal.cfg` | Fine-grained materializer state: staging, the materialization record, per-unit outcomes. Spec-unit case source. |
| `Internal.tla` / `Internal_clone.cfg` | `home clone` phase by phase: copy, per-surface fixups, complete or roll back, hand over. |
| `External.tla` / `External.cfg` | Publicly observable behavior of a child-home pass. Test Graph case source. |
| `External.tla` / `External_home.cfg` | Publicly observable behavior of a **home**: clone it, write through it, move it, re-provision it. |
| `External.tla` / `External_sync.cfg`, `External_sync_merge.cfg` | Publicly observable behavior of **three home tiers**: `home sync` and `home close-out`. Two configs, one per decomposition axis — see below. |
| `External_regression_*.cfg`, `Internal_regression_*.cfg` | **Expected-violation** configs. Each must keep producing a TLC counterexample. |
| `External_regression_rewritecontent_reportonly.cfg`, `External_sync_guard_*.cfg` | **Guard** configs. Each must keep returning **"No error has been found"** *while modelling a defect*. See "The negative result" below. |
| `External_sync_roundtrip.cfg` | **Reachability** config: its counterexample is the round trip. Must keep producing one. |
| `SkillManager.tla` / `MC.cfg` / `MC_program_promotion.cfg` | The accepted monolith, carried forward unchanged: the not-yet-migrated remainder of the program. |

Two representations of one program is a hazard, not a feature. While both
exist, `Core`/`Internal`/`External` are authoritative for child-home
**materialization** (what a child unit contains, whether it is independent,
what happens to local edits) and `SkillManager.tla` stays authoritative for
child-home **membership** (which units, servers, shims, and agent configs a
child home has). Never state a rule in both. Phase `MIG` in `ticket_plan.yaml`
retires the split.

## Two levels, and why they are separate specs

`Core`/`Internal`/`External` carry **two** slices. The unit-level slice is about
the units inside one home; the home-level slice is about whole homes and what
happens when one is copied. Each module defines two specs over disjoint variable
sets — `Spec`/`HomeSpec` in External, `Spec`/`CloneSpec` in Internal — and each
`.cfg` names the one it checks.

They are separate because they share no invariant, so a single `Next` over both
would explore the *product* of two reachable state spaces (250 × 706 for
External) and check nothing new. What the split does not cover is recorded in
`spec_manifest.yaml` under `two_specs_per_module.cost_of_the_split`; do not treat
it as an oversight, and do not fold it without reading that entry.

## The four unit-level properties

| Property | Invariant | View | Regression config |
| --- | --- | --- | --- |
| Independence | `ChildHomeWritesNeverReachTheParentStore` | External (+ Internal) | `External_regression_symlink.cfg` |
| No silent destruction | `AgentEditedChildUnitsAreNeverDestroyed` | External (`AgentEditsSurviveMaterialization` in Internal) | `External_regression_overwrite.cfg` |
| Convergence | `UnmodifiedChildUnitsConvergeOnTheirSource` | External | `External_regression_rawdigest.cfg` |
| Atomicity | `InFlightMaterializationLeavesTheChildUnitIntact` | Internal | `Internal_regression_nonatomic.cfg` |

## The home-level properties

A home is a **pure function of `SKILL_MANAGER_HOME`**: copy it, point the env var
at the copy, forget the original. Every invariant below is one consequence of
that, and each is the declared target of exactly one regression config.

| Property | Invariant | View | Regression config |
| --- | --- | --- | --- |
| Relocatability | `AHomeIsAPureFunctionOfItsRoot` | External `HomeSpec` | `External_regression_absstate.cfg` |
| No leak on any owned surface | `NoOwnedSurfaceNamesAnotherHome` | External `HomeSpec` | `External_regression_sharedtoolchain.cfg` |
| Cross-home independence | `WritesThroughOneHomeReachNoOtherHome` | External `HomeSpec` | `External_regression_abslink.cfg` |
| The source is byte-identical | `SourceHomeIsByteIdenticalToItsCloneTimeSelf` | External `HomeSpec` | `External_regression_verbatimstate.cfg` |
| Authored content is untouched | `AuthoredContentIsNeverRewritten` | External `HomeSpec` | `External_regression_rewritecontent.cfg` |
| Toolchains absent, never shared | `ToolchainRootsAreNeverShared` | External `HomeSpec` | `External_regression_sharedtoolchain.cfg` |
| `pm/` survives the skip | `AHomeMissingItsToolchainsStillHasItsPackageManagers` | External `HomeSpec` | `External_regression_nopm.cfg` |
| Missing shims are reported | `EveryHomeMissingItsToolchainsSaysSo` | External `HomeSpec` | `External_regression_silentshims.cfg` |
| Tolerated references are counted | `EveryToleratedContentReferenceIsReported` | External `HomeSpec` | (see negative result) |
| Fixups precede hand-over | `EveryOwnedSurfaceIsReanchoredBeforeAHomeIsHandedOver` | Internal `CloneSpec` | `Internal_regression_unreanchored.cfg` |
| A failed clone leaves nothing | `NoUnfinishedDestinationSurvivesAFailedClone` | Internal `CloneSpec` | `Internal_regression_partialclone.cfg` |

## The three home tiers: `home sync` and `home close-out`

Content used to flow only **down**, at materialization time. A ticket agent
improved a skill inside a worktree home, the worktree was removed, and the
improvement was gone with no symptom at all — `rm -rf` succeeds identically
whether the directory held work or not. Every guarantee above stops at the
boundary of one home, and the boundary is where the work was being lost.

| Property | Invariant | Witness | Regression config | Guard config |
| --- | --- | --- | --- | --- |
| No edit loss | `AReconciliationOnlyWritesPathsTheDestinationDidNotMove` | `sync_unsound` | `External_regression_syncoverwrite.cfg`, `External_regression_prefersource.cfg` | `External_sync_guard_overwrite.cfg` |
| Merge soundness | `AReconciliationOnlyWritesAgainstABaselineBothHomesPassedThrough` | `sync_history` | `External_regression_nomergekind.cfg`, `External_regression_ffprovenance.cfg`, `External_regression_mergebase.cfg` | `External_sync_guard_ffprovenance.cfg` |
| Conflict atomicity | `AConflictedUnitIsNeverPartiallyWritten` | `sync_unsound` | `External_regression_partialconflict.cfg` | `External_sync_guard_conflict.cfg` |
| Close-out gate | `NoHomeIsTornDownWhileItHoldsUniqueWork` | `sync_gone` | `External_regression_ungatedcloseout.cfg` | `External_sync_guard_teardown.cfg` |
| Round trip | `NoWorktreeEditEverReachesTheRootHome` | — | `External_sync_roundtrip.cfg` *(must fail)* | — |

These **extend** `AgentEditedChildUnitsAreNeverDestroyed`; they do not restate
it. That invariant is about one home's units and a materializer refreshing them
from a store, and its destroyer is a refresh policy. These are about two homes,
per path, and their destroyers are a merge base, a provenance rule and a
teardown — none of which the unit-level slice has an action for.

**Two of the regression configs model code that is in the tree today.**
`External_regression_ffprovenance.cfg` is `MaterializationRecord.reconcileKind`
protecting merge results but not a fast-forward from a home that is then torn
down (CHM-9); `External_regression_mergebase.cfg` is
`ChildHomeMaterializer.mergeBase` preferring the destination's record, which
after a merge is *newer* than the true common ancestor (CHM-10). Both were
reproduced end to end against the real classes and neither is fixed. So
`External_sync.cfg` describes the desired state, not the current one — which is
what `desired_program_model` is for.

### Why two configs and not one

The slice was first authored at full width — three tiers, two paths, a
per-write log carrying the destination home. It reached **574,209 distinct
states** at the 120s `tlc_seconds` budget and was still climbing. The budget
was not raised. The write log was compressed to `<<path, reason>>` pairs
(456,874 — not enough), and then the slice was split along its two independent
axes, expressed as the constants `SyncTiers` and `SyncPaths`:

```
External_sync.cfg        SyncTiers = {root, project, worktree}   SyncPaths = {p1}         180 distinct
External_sync_merge.cfg  SyncTiers = {project, worktree}         SyncPaths = {p1, p2}     283 distinct
```

Three tiers is what the provenance, merge-base and close-out properties need:
each turns on a destination whose content came from a home that is **not** the
one now pushing, which two homes cannot express. Two paths is what the merge
algebra needs. No invariant relates a tier question to a path question, so
checking them together was multiplying two state spaces to check nothing new.
The cost — a property needing three tiers *and* two files at once cannot be
stated — is recorded in `spec_manifest.yaml validation.decomposition`.

### Two surfaces are deliberately NOT held to relocatability

`RelocatableSurfaces` is `{state, symlink}` only.

- **`provisioned`** — a venv console script's shebang is an absolute path and the
  kernel resolves it literally, so it can be neither tokenized nor made
  relative. It is re-anchored per clone. `mv` on a home breaks it and only
  another clone repairs it; that asymmetry is why `home clone` exists.
- **`content`** — files under `skills/`, `plugins/`, `docs/`, `harnesses/` may
  legitimately record the absolute path a past run used (append-only spec
  history, effect-provider evidence). 175 do in the real home. They are left
  exactly as written, and a *tolerated content reference* is a distinct model
  concept from a *leak*: `NamesAForeignHome` is true of both, `HomeLeaks` covers
  only the owned surfaces.

### The negative result — a reporting invariant is not an invariant

An earlier ticket on this epic established that `EveryPassReportsExactlyTheHeldBackUnits`
alone cannot catch silent destruction: after an overwrite the unit is no longer
modified, so both sides of the equivalence go false and TLC returns clean. Only
the `agent_edits` witness catches it.

Every home-level invariant was held to the same standard, and the pair below is
the proof. Same policy (`CloneContentPolicy = "REWRITE"`, which corrupts
append-only records), different invariant lists:

```
External_regression_rewritecontent_reportonly.cfg   TypeOK + EveryToleratedContentReferenceIsReported
  -> No error has been found. 2966 states generated, 706 distinct, depth 12.
     The ENTIRE state space. After the rewrite nothing survives to report, so
     the report is perfectly self-consistent about a destroyed record.

External_regression_rewritecontent.cfg              ... + AuthoredContentIsNeverRewritten
  -> Error: Invariant AuthoredContentIsNeverRewritten is violated.
     2 states, depth 2, trace: CloneHomeIntoProject.
```

The only difference is an invariant that reads the `home_authored` witness.
Keep both: if the `reportonly` config ever starts failing, the reporting
invariant has been changed into something else and this record is stale.

The sync slice restates it in a **stronger** form. There the guard invariant is
not a report at all — it is a filesystem-consistency check, `every divergence
from a record is an edit made in that home` — and it *still* finds nothing:

```
External_sync_guard_overwrite.cfg    HomeSyncPolicy = "OVERWRITE_DESTINATION"
  -> No error has been found. 9545 states generated, 2357 distinct, depth 14.
     A reconciliation that overwrites a destination rewrites that destination's
     RECORD in the same step, so afterwards the home agrees with itself exactly,
     at every path, over content nobody chose.

External_regression_syncoverwrite.cfg  ... + AReconciliationOnlyWritesPathsTheDestinationDidNotMove
  -> Error: Invariant ... is violated. 14 states, depth 3.
```

A coherent home is not an intact one. And a witness can be correct and still
blind: under today's provenance rule (`External_sync_guard_ffprovenance.cfg`)
the write log's own "the destination had not moved this path" test is **true**
for every write the CHM-9 defect makes — the project home really had not moved
anything. Catching that needed a witness of a *different question*
(`sync_history`: did the source ever pass through this state), which is a fact
about the other home entirely.

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

# three home tiers — desired state clean, regressions must each fail,
# guards must each stay clean, and the round trip must produce its trace
bash specs/desired_program_model/run_tlc.sh specs/desired_program_model/External.tla specs/desired_program_model/External_sync.cfg
bash specs/desired_program_model/run_tlc.sh specs/desired_program_model/External.tla specs/desired_program_model/External_sync_merge.cfg
for cfg in syncoverwrite prefersource partialconflict ungatedcloseout ffprovenance nomergekind mergebase; do
  bash specs/desired_program_model/run_tlc.sh specs/desired_program_model/External.tla \
       specs/desired_program_model/External_regression_$cfg.cfg
done
for cfg in overwrite teardown conflict ffprovenance; do
  bash specs/desired_program_model/run_tlc.sh specs/desired_program_model/External.tla \
       specs/desired_program_model/External_sync_guard_$cfg.cfg
done
bash specs/desired_program_model/run_tlc.sh specs/desired_program_model/External.tla specs/desired_program_model/External_sync_roundtrip.cfg
```

`run_tlc.sh` takes paths relative to the **current** directory, so from inside
`specs/desired_program_model` the short form works:

```bash
cd specs/desired_program_model

# home level, desired — must be clean
bash run_tlc.sh External.tla External_home.cfg
bash run_tlc.sh Internal.tla Internal_clone.cfg

# home level, regressions — must each produce a counterexample
for cfg in External_regression_absstate.cfg \
           External_regression_verbatimstate.cfg \
           External_regression_abslink.cfg \
           External_regression_sharedtoolchain.cfg \
           External_regression_silentshims.cfg \
           External_regression_nopm.cfg \
           External_regression_rewritecontent.cfg; do
  bash run_tlc.sh External.tla "$cfg"
done
bash run_tlc.sh Internal.tla Internal_regression_partialclone.cfg
bash run_tlc.sh Internal.tla Internal_regression_unreanchored.cfg

# the negative result — must stay CLEAN
bash run_tlc.sh External.tla External_regression_rewritecontent_reportonly.cfg
```

Do **not** run `MC_program_promotion.cfg` as a gate: it does not complete
(>3000 s, recorded in `spec_manifest.yaml` as
`baseline_has_no_completed_tlc_run`).

Recorded results: `../results/chm-*.txt`, indexed in `spec_manifest.yaml`
under `validation:`. The home-level runs are in
`../results/chm-home-isolation-tlc.txt`.

## Not done here

- `case_adapters.toml` and `testgraph_bindings.yml` mappings for the new
  actions (ticket `CHM-8`).
- `test_graph` nodes for this epic — owned by a separate agent.
- Effect ports and providers — hard-blocked on the Internal/External migration
  (`ticket_plan.yaml` phase `EFF`).
- Nothing is closed and nothing is promoted. `specs/current` is unchanged from
  the accepted baseline.
