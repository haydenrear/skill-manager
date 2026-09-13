# Starter prompt — epic `one-home-one-verdict`

Paste the block below into a fresh agent session opened in
`/Users/hayde/IdeaProjects/skill-manager`, on `main` at or after `v0.27.2`.

---

You are the epic agent for **`one-home-one-verdict`** (GitHub #337) in
`haydenrear/skill-manager`. The epic is filed but **not scaffolded**. There is no
`epic/*` branch, no spec workflow and no `ticket_plan.yaml` yet, so your first job
is planning, not implementation.

**Before anything else, confirm the ground you stand on:**

- `git status` on `main` is clean and matches `origin/main`, and the tip is at or
  after the `v0.27.2` release commit.
- `skill-manager --version` reports `0.27.2`, and `skt --version` reports `skt 0.8.2`.
- `skt check` is all current in the root home and in this repo's project home.
- `git worktree list` should show only this checkout. If `wt-oun-*`, `wt-ci-otel`,
  `wt-fix-311` or a `.claude/worktrees/agent-*` worktree is still listed, the
  previous epic's sweep has not run. Tell me. Don't create worktrees on top of it,
  and don't remove them yourself without my go-ahead. Every one of them is merged.
  Their only home content is an `eval-skill` test residue, so the sweep is safe,
  but it is still an owner decision.

**Read these, in this order:**

1. `results/epic-one-home-one-verdict/STARTER.md`. It is the handoff: the state of
   the world, what has been tried, and the traps that each cost a day.
2. Issue **#337** (the epic) and its tickets, in dependency order:
   - **#338 OHV-1**: every verdict names the build that produced it. This comes first.
   - **#339 OHV-2**: one reader for home state, with `verify` and `repair` as views.
     Read its 2026-09-13 comment: agent links outside the home, and orphaned
     projection records, are invisible to `verify`.
   - **#340 OHV-3**: one record of what a home holds. It carries **#292**, which has a
     failed attempt on record.
   - **#341 OHV-4**: a home never spells its own path.
   - **#346 OHV-5**: the full graph set runs green on a fresh CI runner. It carries
     **#343**, **#344** and **#345**, and depends on #343.
3. `results/epic-one-unit-one-name/reviews/epic-close.md`, the previous close review.
4. The three deferred backlogs under `results/*/deferred/backlog.yaml` (154
   findings), and `results/epic-one-home-one-verdict/attribution/2026-09-13-migration-0.27.md`.

**What the epic is for, in one line:** 41% of 154 recorded defects are a guard
whose rule is narrower than its name. Five commands answer five different
questions about one home, and all five exit 0 on a home that is broken.

**Then use the `git-epic-workflow` skill, plan-and-schedule role:**

- Agree the measurable goals with me before scaffolding anything. The goals in #337
  are a *proposal*.
- Agree the deferment policy and the review cadence, and record both in the plan.
- Only then create `epic/one-home-one-verdict`, scaffold the spec workflow, and
  write `ticket_plan.yaml`, including a terminal evaluation ticket against the
  goals as we actually agree them.
- Out of scope, and don't absorb them: #351 (Gemini plugins), #352 (marketplace
  name drift), #353 (eval `max_turns` graders), #327 (`remove` on unpushed work)
  and #269 (the HBR epic's shim boundary). Name them in the plan as adjacent.

**Hold to these, because earlier work learned each of them the hard way:**

- **Run the real thing on real homes.** 0.27.0 passed on a clone and failed on the
  root home. 0.27.1 passed on the root home and failed in every project home. For
  any change to what a sync installs, retires or reports, run this repo's
  `./skill-manager` against real project homes (not `commit-diff-context-parent`
  unless I say so) after backing up `installed/`.
- **A local test-graph sweep's count is not evidence.** Gradle stops at the first
  failing graph. For coverage, use CI's per-graph jobs:
  `gh workflow run ci.yml -R haydenrear/skill-manager --ref <branch> -f graph_set=full`.
  On `main` today, `home-clone`, `checkout-home`, `ticket-lifecycle`,
  `artifact-dag`, `home-tripwire` and `onboarding` already fail. That is #346's
  baseline, not a regression.
- **macOS local runs can disagree with Linux CI** on anything that scans for a
  home path (#343).
- **#292 has a failed attempt on record.** The ledger re-record is a fixpoint. Fix
  that first, and land each change on its own, verified against `plugin-smoke`
  *and* `artifact-dag`.
- **An eval only measures a released build**, and a `max_turns` run scores the cap
  (#353). Read the trace before you trust the number.
- **Record every defect in bug attribution** and in this epic's deferred backlog.
  Before filing a new issue, search the backlogs and existing issues for duplicates.
- **Stop at every wave boundary** with a committed review artifact, and wait for me.

Start by running the ground checks above and reading `STARTER.md` and #337. Then
tell me the goals you would propose, and the one measurement you would take first
to re-establish the baseline on `v0.27.2`.
