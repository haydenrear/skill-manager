# Wave 1 review — `one-unit-one-name`

Wave 1 is one ticket: **OUN-0**, PR #308, `feature/OUN-0`. Reviewed
2026-09-05 against `epic/one-unit-one-name` at `0583a11`.

Ticket scope was measurement and a written contract — no resolver change, no
installer change, no unit removed. It stayed inside that. Everything it
changed in the product is documentation; everything else is a harness, a
workflow config, or a repair of a regression it found.

## What landed

| | |
| --- | --- |
| four goal harnesses | one per epic goal, all four **red**, which is the expected effect |
| `scripts/unit_graph.py` | the shared reader: unit roots, both edge mechanisms, transitive + cycle-safe reverse walk |
| the rule, written once | `skill-manager-skill/references/skill-imports.md` § *How A Unit Name Is Addressed* |
| a Java baseline case | asserts the defect, with a control, so OUN-1 shows as an assertion flipping |
| `measure_goals.py` keyed by epic | the previous epic's ledger stays runnable instead of being rewritten away |
| two repairs of `main` | CI hardcoding `specs/current/tests`; the accepted manifest the close-out overwrote |

## Hot spots — where the bugs probably are

**1. The harness reads frontmatter with hand-written parsing.** It is not a
YAML parser and cannot be one without adding a dependency to a script that
must run under whatever `python3` a machine has. Review found exactly the
failure this invites: a top-level `skill-imports:` whose sequence items sit
at zero indent parsed as *no imports at all*. Fixed, and no unit on this
machine is written that way — which is why it would have gone unnoticed and
quietly undercounted every future measurement. **This is the most likely
place for the next defect too.** If a third case appears, the answer is to
stop hand-parsing and take the dependency.

**2. The `who-imports` probe decides "does a command exist" from help text.**
Matching `--who-imports` / `reverse` / `importers` in `--help` output is weak
evidence in general. It is defensible for a *baseline* whose claim is "no
such command", because a command that existed would document itself. It is
NOT good enough for OUN-8's terminal evaluation, which should run the
command and check its answer. Flagged for OUN-3 to replace, not inherit.

**3. `default_homes()` reads every sibling directory of the checkout.** The
totals therefore move between runs as other sessions create and delete
worktrees — measured 29 and 30 homes minutes apart. The per-home rows are the
durable part; a reviewer comparing bare totals across two runs will see a
difference that means nothing.

## Decisions taken implicitly, and guardrails touched

- **The addressability probe installs units.** That is a write, so it clones
  a home and installs into the clone. Review found the guard against
  installing into a real home was **one hardcoded absolute path** — correct
  on this machine, wrong everywhere else, in a script that ships in the
  repository. Replaced with the machine's actual home set.
- **The probe's subprocess now pins every agent-home axis**, not just
  `SKILL_MANAGER_HOME`: install registers with the agent CLIs, and that
  variable alone does not move where those writes land. `CLAUDE_CONFIG_DIR`
  is deliberately left alone — it relocates the keychain slot.
- **Temp homes are ~460 MB each.** The harness now sweeps its own leftovers,
  age-gated so concurrent runs do not delete each other's scratch. This
  repository has lost 1.4 GB to leaked per-run homes once already.
- **The reference doc was stating unimplemented behaviour in the present
  tense.** The refusal and the reverse-edge command do not exist; the doc
  said they did. It ships into homes, so agents would have acted on it. Now
  carries an explicit status marker separating what the product does from
  what this epic is establishing.

## The finding that changes wave 2

**Every home carrying `skt` already claims the name `skt` twice** — the
plugin at `plugins/skt` and a contained skill at `plugins/skt/skills/skt`.
Twenty-one homes here. Latent *only* because contained skills are
unaddressable, which is what OUN-1 fixes.

So **OUN-1 turns 21 latent collisions live**, and OUN-2's gate, written
without a rule for this, would refuse the `skt` plugin in every home it is
installed into — including during its own migration. The rule the doc now
states: a contained skill whose name equals its carrying plugin's is that
plugin's entry skill, one unit under one name. **OUN-1 must implement that
distinction, not merely descend into `plugins/*/skills/`.**

`DEF-OUN-002`. This is the single most important thing for wave 2 to read.

## Architectural signal

The largest defect class this wave is not the epic's subject: it is **epic
scaffolding that emits strings nobody executes**. Four of the eleven defects
came from `render_epic_assignment.py` — a `spec_unit` command naming a script
that exists nowhere, per-epic constants hardcoded so a new epic got the
previous one's base commit, a branch name that disagreed with the command the
skill tells agents to use, and a rationale naming a workflow that had been
closed.

They share one cause: **the assignment is validated for shape, never for
truth.** `validate_assignment.py` checks that `spec_unit` is a string; it
never checks that the string can run. *Recommendation: the validator should
resolve what the assignment declares — commands, branch, base commit, graph
names — before the URL is handed out.* Cheap, and it converts four
independent discoveries-by-accident into one gate.

## Recommended next steps

1. **Dispatch wave 2** — OUN-1 (#300) and OUN-3 (#301). They are independent
   and their conflict keys are disjoint. OUN-1 must read `DEF-OUN-002` first.
2. **`DEF-OUN-001` residual is owner-only** — the 72 promoted spec modules
   have no manifest entries, and the promotion should probably have merged
   the two manifests. Not a repair anyone downstream can make.
3. **Carry the two repairs to `main`** — PR #309. Until it merges, `main`'s
   CI stays red and only the epic branch is green.
4. **Teach `validate_assignment.py` to resolve what it validates**, per the
   signal above. Small, and it pays back for the rest of the epic.

## Ask

Merge #309 to `main` and #308 into the epic branch, then wave 2 goes out.
