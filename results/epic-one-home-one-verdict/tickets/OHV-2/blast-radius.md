# OHV-2 blast radius — who gates on `home verify`'s exit code

Measured 2026-09-13 before landing (a), at `feature/OHV-2` off `3d6d4cd4`.

**Verdict: not bad.** No caller outside this repository runs `home verify` and
gates on its exit code. The scripts that mention it (`bootstrap-home.sh`,
`selftest.sh`, skt) only quote it in prose or comments. Every gating caller is
inside this repo: a graph node, the fixpoint law, or a unit test. The change
therefore landed as specified, with no `--strict`/severity split.

## Method

- `rg "home.{0,12}verify|['\"]verify['\"]"` over `src`, `test_graph/sources`,
  `test_graph/build.gradle.kts`, `skills`, `scripts`, `.github`, `CLAUDE.md`.
- The same pattern over the worktree home's shipped scripts:
  `.skill-manager/skills/*/scripts`, `.skill-manager/plugins/skt/src`, and
  `~/.skill-manager/skills/*/scripts`, `~/.skill-manager/plugins/*/src|bin`.
  Comment-only lines and `git rev-parse --verify` / `show-ref --verify` hits
  were dropped.
- `*.md` under the installed skills, for instructions that tell an agent to
  treat verify's exit as a gate.

## Callers outside this repo

| caller | invokes `home verify`? | gates on exit? | effect on a home with today's common findings |
| --- | --- | --- | --- |
| git-issue-workflow `scripts/bootstrap-home.sh` | **no**: it runs `home clone` (in-process `HomeCloner.verify`, unchanged) and its own `find` for dangling links; `home verify` appears only in a warning sentence (l.1951) and comments | no | none. Its warning "home verify REFUSES this home until they do" stays true. |
| git-issue-workflow `scripts/selftest.sh` | no; it asserts that bootstrap's *sentence* mentions `home verify` (l.377) | no | none |
| git-issue-workflow `scripts/wt`, `new-change.sh`, `close-change.sh`, `agent-home.sh` | no | — | none |
| skt plugin (`src/skt/*.py`: `ticket`, `sweep`, `check`, `homes`, `publish`) | no; the only hit is a docstring in `check.py:636` | — | none |
| git-epic-workflow `SKILL.md:212`, git-issue-workflow `references/skill-homes.md` | prose describing what verify answers | no | the description is now incomplete: verify also fails on repair findings. Doc drift only; this is unit content in another repo, not fixed here. |
| skt `references/derived-artifacts.md` (l.292–330) | prose: "`home verify` exits 0 on a healthy lazy clone" | no | still true for a clean lazy clone (`PRUNED_INHERITED_ENTRY` is policy-gated off on lazy homes) |
| `home clone` acceptance (production) | calls `HomeCloner.verify` in-process, not `VerifyCmd` | its own exit | unchanged: (a) composes in `VerifyCmd` only |

## Callers inside this repo (in scope)

| caller | gates on exit | expected on its fixture home | status |
| --- | --- | --- | --- |
| `common/HomeFixpointLaw` (every graph) | yes: exit 0, or its printed remedy must clear it | graph fixture homes are written by current code, so no frozen or unstamped findings are expected. If one appears, verify now prints `complete it with: … home repair --home <h> --fix`, which the law runs. | CI (full set) |
| `lib/IntentionalDamage` (#344 declarations) | treats every ✗ line outside the "do not resolve" section as unexplained | only `HomeCloneFixtureBuilt` declares (a dangling shim, not a repair kind). A repair finding on that home would now be judged as usual. | not changed; CI decides |
| `home-verdicts/*` (OHV-0 nodes) | pinned `TODAY_home_verify_exits_0` on 3 shapes | flipped to 1 + names-it in this change | local + CI |
| `home-integrity/DamagedHomeIsRepairable` | `verifyBefore == 1` names FOREIGN_PATH_IN_SHIM; `verifyOther` must NOT name it | the undamaged copy has no repair findings | CI |
| `home-integrity/ReadersAgreeAboutOneClone` | bare verify on a clone leaf == 0 | leaf is a sanctioned clone; repair is expected clean | CI |
| `home-clone/HomeClonedIntoProject`, `artifact-dag/LazyCloneDeclaresWithoutBuilding`, `onboarding/OnboardingRemediesAreRunnable`, `ticket-lifecycle/TicketLifecycleProvisioned` | yes | fresh clones and bootstraps, expected repair-clean | CI |
| unit: `DurableCliPinTest`, `HomeBindsBothAxesTest`, `LazyHomeScaffoldTest`, `RemediesAreRunnableTest`, `HomeVerifyDiagnosticTextTest` | yes | — | green locally (focused run) |

## What does change for an operator

`home verify` now exits 1 on most real homes. At kickoff, repair reported
damage on 50 of 61 homes where verify exited 0, mostly
`FROZEN_HOME_PATH_IN_SHIM` and `UNSTAMPED_PM_TREE`, and 69 orphaned projection
records sit across the fleet. Nobody's automation refuses on that. A person or
agent running `home verify` by habit now sees every finding named, with
`home repair --home <h> --fix` as the printed remedy. That remedy is GOAL-one-verdict
clause (3) moving toward 0.
