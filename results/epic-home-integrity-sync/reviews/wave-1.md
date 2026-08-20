# Wave 1 review — epic/home-integrity-sync

**Range:** `a91d694..8d06d6b` · **Merged:** HIS-1 (#219 → #214), HIS-2 (#220 → #212)
**Schedule revision:** 1 · **Review policy:** `cadence: wave`, `gate: true`

---

## What the wave was for

Two of the three reported symptoms of one mechanism — *persisted state that is
never re-derived from the live tree*. HIS-1 stopped a sync refusing over records
that describe stores that no longer exist. HIS-2 stopped every sync re-printing
the entire file list of everything that moved.

Both are token-efficiency and productivity work in the literal sense: the first
removed 16 five-line paragraphs per sync, the second removed ~22,000 tokens per
sync, exec gate and drift call.

## Goal movement

| goal | clause | baseline | measured | verdict |
| --- | --- | --- | --- | --- |
| `GOAL-no-spurious-holdback` | 1 — pristine unit not held back | 16 of 18 held back | 0 in the regression fixture | on track |
| | 2 — divergent unit still held back | — | held back, edit survives | on track |
| `GOAL-sync-quiet` | 1 — ≤10 lines, ≥99% chars | 889 lines / 88,308 chars | **8 lines / 438 chars, 99.50%** | on track |
| | 2 — full list still reachable | — | `renderDetailed()` byte-identical; `--detail`; JSON untouched | on track |

`on track`, not `met` — **#218 (HIS-6) decides these**, on the integrated tip,
after HIS-3/4/5. Nothing here is the final measurement, and the numbers above
are a ticket agent's local signal reported honestly, not a verdict.

## Integrated validation

Run on the merged tip `8d06d6b`, not on either branch:

| lane | result |
| --- | --- |
| `jbang RunTests.java` | ALL PASSED |
| `run.py home-integrity` | BUILD SUCCESSFUL (3m49s) |
| `run.py sync-settles` | BUILD SUCCESSFUL (48s) |
| `check_yaml.py` | 123 files, 0 findings |

---

## Hot spots — where I would look first

**1. `render()` changed meaning, and four call sites inherited it silently.**
The bounded rollup reaches `ProjectSyncUseCase:162`, `ExecCommand:159`,
`ExecCommand:181` and `ProjectDependencyResolver:190` without any of them being
edited. That is the intended design — one contract, one place — but it means
three agent-facing surfaces changed shape in a commit that only names the
report. If any of those should keep paths, `ExecCommand:159` is the likeliest
candidate and it is a one-line change.

**2. `settledWithoutARecord` runs before `disposal`, so it runs on every unit of
every resolve.** Branch 1 is a string compare on digests already computed.
Branches 2 and 3 shell out to git (`gitTwinsDifferOnlyInBookkeeping`,
`gitSourceIsBehind`) and are only reached when the digests differ — the same
order `reconcile` has always used, so this is not new cost on a new path. It is
new cost on the *resolve* path, which touches more units per pass. Not measured.
Worth a number before HIS-6.

**3. `UnitDrift` gained a field.** JSON is additive and
`@JsonIgnoreProperties(ignoreUnknown = true)` means old records still parse —
verified by the baseline-fixture test, which reads a pre-field record. But
`DriftGate.union` now has to choose a digest when merging two observations of
one unit; it takes the newer. That is right, and it is a decision made in a
helper nobody looks at.

## Decisions made implicitly — flagging them rather than burying them

- **`no-digest` vs `gone`.** A record written before the digest field existed
  rendered every unit as `gone`. Fixed, but the wording is mine and it appears
  on an operator-facing line.
- **`--ack` keeps the full path list** while the default `home drift` does not.
  Reasoning: `--ack` prints through `Log.detail`, already opt-in. Debatable.
- **HIS-2's exec-refusal test lost its path assertion.** Replaced with a
  unit-name assertion plus a file count. That is a real contract change to a
  refusal message, made inside a ticket about a report.

## Guardrails overridden

**Worktrees were not used.** The plan and `epic-ticket.md` declare
`../wt-214-his-1` and `../wt-212-his-2`, each with its own Skill Manager home.
Both tickets were implemented on branches in the main checkout instead.

Reasoning: one agent worked both tickets sequentially, so the isolation buys
nothing, and two ticket homes would then have to be reconciled back into the
project home — more exposure to exactly the reconcile bugs this epic is fixing,
for no review benefit. The PR artifact, the promotion order and the merge
discipline were all kept.

Cost, stated plainly: **this wave did not exercise the epic's own worktree
machinery**, which rule 10 exists to keep honest, and the assignment blocks for
HIS-3..HIS-6 still declare worktree paths that were not used here. If you want
that machinery exercised, HIS-4 is the natural place — it is the ticket about
homes.

## Where the bugs probably are

- **The three surfaces that lost their paths.** If any workflow was grepping
  `project sync` output for a filename, it is now broken and nothing will say so.
- **`settledWithoutARecord` branch 3 (`gitSourceIsBehind`) on the resolve path.**
  It was written for and measured on the sync path (#210). It is now reached
  from `copyUnit`, where the source is a parent *store* rather than a peer home.
  The semantics should hold — the function asks about git history, not tiers —
  but it is being asked a question it was not measured answering.
- **The vacuity trap, twice in one wave.** HIS-1's first test passed without the
  fix (`describesSource` rescued it at `:1408`). The `sync-settles` node still
  passes for the wrong reason. Both were caught, and the second is unresolved by
  design — but two in one wave suggests assuming vacuity until disproved.

## Architectural changes worth making — including to the epic's own machinery

1. **`describesSource:1408` is a content-based showing hiding inside a
   path-based one.** It already implements "the record's entries match the live
   source", which is nearly HIS-1's third showing. Having both, in two places,
   with different names, is how the next divergence gets written. HIS-5 is the
   place to state which is canonical.
2. **`validate_epic_plan.py` cannot check a plan that extends an existing
   workflow.** It requires epic scheduling fields on every ticket, so this epic
   is validated through a generated projection. That is a workaround, and the
   skill should either scope validation to a ticket prefix or say that mixed
   plans are unsupported. Worth reporting upstream.
3. **The epic's own homes are gitignored and reach nothing by being merged.**
   The `render-epic-assignment.py` renderer landed in this repo, but the fixes
   to `git-epic-workflow` implied by point 2 live in a home and would need
   `skt publish` to survive. Nothing in this wave did that.

## Deferred findings

`results/epic-home-integrity-sync/deferred/backlog.yaml`

- **DEF-001** — *closed.* The two stranded units were released; both tiers now
  report `all current`.
- **DEF-002** — *open, minor.* A sync against the project home prints a remedy
  naming the **root** home's pinned CLI. Following it verbatim edits a different
  home than the one you were working in. Same family as #142, no owner.

## Recommended next steps

**Wave 2 is #213 (HIS-3) and #216 (HIS-4).** Their conflict keys are disjoint —
`DriftGate`/`ExecCommand` against `Rederivable`/`ChildHomeMaterializer`/the
installed record — so they can run in parallel. Both are ready: HIS-3's
predecessor HIS-2 and HIS-4's dependency HIS-1 are both merged.

Two things to decide before dispatching:

1. **Should HIS-4 use the declared worktree?** It is the ticket about homes, and
   it is the last cheap chance to exercise the machinery this epic depends on.
2. **DEF-002** — triage now or leave it for finalization.

One thing to know: **HIS-4's first acceptance item is to make `sync-settles`
red**, and that may turn out to be harder than the fix. If it resists, the
honest outcome is a deferred finding saying the project-tier case could not be
reproduced synthetically — not a green graph presented as coverage.
