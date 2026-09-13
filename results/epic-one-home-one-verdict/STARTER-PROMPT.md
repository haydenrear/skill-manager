# Starter prompt — epic `one-home-one-verdict`

Paste the block below into a fresh agent session opened in
`/Users/hayde/IdeaProjects/skill-manager`, on `main` at or after `v0.27.1`.

---

You are the epic agent for **`one-home-one-verdict`** (GitHub #337) in
`haydenrear/skill-manager`. The epic is filed but **not scaffolded**: there is no
`epic/*` branch, no spec workflow, and no `ticket_plan.yaml` yet. Your first job is
planning, not implementation.

**Read these, in this order, before doing anything else:**

1. `results/epic-one-home-one-verdict/STARTER.md`. It is the handoff. It records
   what has already been tried and the traps that each cost a day. The issues
   don't record any of that.
2. Issue **#337** (the epic) and its tickets **#338, #339, #340, #341, #346**.
   #338 comes first. #339, #340 and #341 depend on it. #346 depends on #343.
3. `results/epic-one-unit-one-name/reviews/epic-close.md`, the previous epic's
   close review.
4. The three deferred backlogs under `results/*/deferred/backlog.yaml`
   (154 findings). They are the evidence this epic stands on.

**What the epic is for, in one line:** 41% of 154 recorded defects are a guard
whose rule is narrower than its name. Five commands answer five different
questions about one home, and all five exit 0 on a home that is broken.

**Then use the `git-epic-workflow` skill, plan-and-schedule role:**

- Agree the measurable goals with me before scaffolding anything. The four
  goals in #337 are a *proposal*, not a decision.
- Agree the deferment policy and the review cadence, and record both in the plan.
- Only then create the `epic/one-home-one-verdict` branch, scaffold the spec
  workflow, and write `ticket_plan.yaml`, including a terminal evaluation
  ticket against the goals as we actually agree them.

**Hold to these, because the previous epic learned each of them the hard way:**

- **Measuring a mechanism is not running it.** Validate against a real home
  (clone `~/.skill-manager` and run the real command), not only a synthetic
  fixture. A migration that looked correct in every test relinked the unit it
  had just retired. That was only found by running it on a clone of a real home.
- **A local test-graph sweep's count is not evidence.** Gradle stops at the
  first failing graph, so later graphs silently don't run, and
  `validation-reports/` still holds passing reports from earlier runs. For
  coverage, use CI's per-graph jobs:
  `gh workflow run ci.yml -R haydenrear/skill-manager --ref <branch> -f graph_set=full`.
- **macOS local runs can disagree with Linux CI** on anything that scans for a
  home path. See #343.
- **#292 (in #340) has a failed attempt on record.** The ledger re-record is a
  fixpoint: every row a prune drops comes straight back. Fix that first, and
  land each change on its own, verified against `plugin-smoke` *and*
  `artifact-dag`.
- **Migrations must be validated on the REAL home, not a clone.** 0.27.0 shipped a
  migration that could not finish on the root home (#175). Projects there declared
  `[skills.skill-manager]`, retiring the standalone threw on the project claim, and
  the whole sync rolled back. A clone drops its source's project and child-home
  claims, so the clone test passed. 0.27.1 (#347) fixed it, and the root home is
  now at `(0, 0, 1)`. For any migration change, run this repo's `./skill-manager`
  against `~/.skill-manager` itself, after backing up `projects/` and `installed/`.
- **Record every defect you find in bug attribution** and in this epic's
  deferred backlog. Before filing a new issue, search all three backlogs and
  existing issues for duplicates.
- **Stop at every wave boundary** with a committed review artifact, and wait
  for me.

Start by reading `STARTER.md` and #337, then tell me the goals you would propose
and the one measurement you would take first to confirm the baseline still
holds on `v0.27.0`.
