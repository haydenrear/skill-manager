# HIS-8 — goal contribution

Issue #224. Branch `feature/224-his-8`, base `epic/home-integrity-sync` at
`ef0107d`. Wave 10, promotion order 16, predecessor HIS-13. Schedule revision 9.

Goal: **GOAL-mechanism-documented** (`direct`).

Declared expected effect, verbatim from the plan:

> *"0 md files documenting the contract -> stated once and linked from the four
> units that instruct agents about homes; judged read answers all three
> questions"*

---

## 0. Headline, and the part a reviewer should attack first

**Delivered.** Clause 1 moved **0 of 4 → 4 of 4**; clause 2's judged read moved
**0 of 3 → 3 of 3**, measured twice with different agents.

**Read §6 before believing the numbers.** Three things are weaker than they look:

1. **Clause 1's instrument reads the operator's ROOT home**, which this ticket is
   forbidden to write. The `4 of 4` was measured over a sandbox `HOME` holding
   the four units as they were published. It becomes true of the real root home
   only when the epic owner merges four PRs and syncs. **At hand-off, the real
   root home still measures `0 of 4`.**
2. **The declared change-management path did not work** and was not used. Four PRs
   were opened by hand, mirroring `unit publish`'s conventions exactly (DEF-077).
3. **The spec-workflow close was taken under `--allow-open`**, i.e. the modeled
   state `CloseTicketWeakened`, for a reason unrelated to this ticket's evidence
   (DEF-080). HIS-6 should not read that flag as a comment on the evidence.

---

## 1. The rule this ticket is really about

Written first because it is the part most likely to be undone by someone tidying:

> **A second copy of a contract is covered by nothing, and it drifts silently.
> The failure is not that the copy is wrong on the day it is written — it is
> that nothing fails on the day it becomes wrong.**

This ticket did not have to argue that abstractly. It found it, twice, in its
own material:

- The BEFORE judged read was routed to
  `git-issue-workflow/references/skill-homes.md` and came back with the
  **opposite** answer to "is a fresh clone healthy": *"`home verify` refuses the
  home while it is"*, *"That is a skill-manager gap"* — present tense, with a
  measurement, naming the same example binary this contract uses. Re-measured
  2026-08-23: cold shim, exit 86, `home verify` **exit 0**.
- That bullet was corrected. The next judged read found the **twin passage 100
  lines below it**, undated, still asserting *"keeps refusing this home"* and
  *"one COUNTED line"*. **Correcting one copy and missing its twin, inside the
  fix for exactly that failure mode.**

That is the argument for stating a contract once, made by measurement rather
than assertion. It is also why the three linking units carry pointers and one
sentence of routing, and nothing more.

---

## 2. Baseline, re-measured at HEAD

`uv run .github/scripts/check-docs-coverage.py`, on `ef0107d`, against the real
root home — `probes/his-8/docs-coverage-BEFORE-realroot.out`:

```
TOTAL                          0            0            1            0
  (`skill-manager build`)  (cold shim)  (declared-only)  (artifacts list/show/stale)

Units that instruct agents about homes, and whether the contract reaches them:
  skt                    absent
  skill-manager          absent
  git-issue-workflow     absent
  git-epic-workflow      absent

0 of 4 instructing units reach the contract.
```

The one `declared-only` hit is a `spec-double-compiler` fixture, not
documentation. **The baseline in #224 reproduces exactly.**

Note for HIS-6: the baseline file `baseline/docs-coverage.txt` has a `home`
column the current script does not emit — the instrument's `TERMS` changed after
the baseline was captured. The four columns that both versions share agree.

---

## 3. What was built

### 3.1 The contract, stated once

`skills/skt/references/derived-artifacts.md` in the skt plugin — **the first
`references/` page that skill has ever shipped.** skt is the session-start front
door, and `skt check`/`skt status` are what announce artifacts to every session
in the first place, so the explanation now lives beside the announcement.

Structure follows the agent's order of need, as the slice required:

| § | answers |
| --- | --- |
| 0 | the four-point short version, so you can stop reading |
| 1 | what a derived artifact is; the ledger; the nine kinds and what rebuilds each; `artifacts list/show/stale` and every status word |
| 2 | inherit vs declare; cold shims and exit 86; why laziness; `lazy_artifacts`; **declared-not-built is not damage**; the descent record is a pointer, not a grant |
| 3 | **when to rebuild** — a seven-row table keyed on situation |
| 4 | `skill-manager build <id>`; how to read `artifacts stale` on a lazy home; exit codes; the failure the remedy cannot fix |
| 5 | **the two wrong answers, named and closed off** |
| 6 | known warts and boundaries, including that HIS-19 owns the CLI pin |

### 3.2 The two wrong answers

Both are named in §5 with the reasoning that produced them, why it is wrong, and
what the genuine invariant is instead:

- **"the clone/verify sanction rule is the bug."** It is not: the sanction *is*
  the rule. `home verify` reports parent-store shims as sanctioned and exits 0
  by design. The real invariant is narrower and already enforced — a
  parent-store link is sanctioned only when it is `bin/{cli,mcp}/<name>` one
  segment deep, resolves to the *same real entry*, and that home **re-derives
  live** as an ancestor.
- **"a clone should rebuild everything on arrival."** It rebuilds what nobody
  changed, and its second half — making *inherited* shims refuse — removes
  working tools from the agent's PATH to express an ownership preference the
  agent cannot act on.

§5 closes with the shape both share: *neither asks what a healthy clone looks
like*. The positive signature is then given, so the next agent has something to
match against rather than a list of prohibitions.

### 3.3 The facts from this epic the brief asked for

All present, all with their measured form:

- **`home.provenance.json` is a pointer, not a grant** (HIS-10). `parentStores`
  is "a snapshot kept for reporting and repair and never consulted to grant a
  sanction"; every reader re-derives live. Two consequences an agent can act on:
  the parent must outlive the clone, and hand-editing the record changes the
  pointer and not the answer.
- **Declared-and-not-built is a normal state** (HIS-13). The four-command table
  — `declared-only` / `DECLARED and not built`, *reported never counted* /
  nothing to repair / filtered before a notification exists — plus the shipped
  bug itself, stated plainly, and generalised: *any rule that treats "declared
  and absent" as evidence of damage fires on every healthy clone, a different
  number of times on every machine.*
- **The CLI pin** is named as a *separate* mechanism with a known defect that
  **HIS-19 owns**, and the page says not to infer the artifact contract from how
  the pin behaves today. The broken behaviour is not documented as contract.

### 3.4 The three linking units

Each gets a pointer and one sentence of routing. **None restates the contract.**

| unit | where | what it says |
| --- | --- | --- |
| `skill-manager` | References list | the topic is **not** documented here; absolute path to the page |
| `git-issue-workflow` | "Your Skill Manager home IS the worktree's" + Reference map | a copy is not a copy of everything the home can do; on a home you just created, `declared-only` is healthy and rebuilding on arrival is waste |
| `git-epic-workflow` | load-bearing rule 11 + Reference map | "current" is about unit **bytes**, not derived artifacts; do not paraphrase the contract into kickoff notes |

`git-issue-workflow` additionally carries **two corrections** to
`references/skill-homes.md` (§1).

`git-epic-workflow` also names a terminology collision the judged read caught:
in that skill "artifact" almost always means the **wave review artifact**, and
its new pointer uses the word in an unrelated sense.

---

## 4. Clause 1 — the counting half

`HOME=<sandbox> uv run .github/scripts/check-docs-coverage.py`, over the four
units as published — `probes/his-8/docs-coverage-AFTER-sandbox.out`:

```
unit                         md  skill-manager      cold shim  declared-only  artifacts lis
git-epic-workflow             8              1              0              1              1
git-issue-workflow           10              2              1              1              1
skill-manager                 7              1              1              1              1
skt                          27              2              1              1              1
TOTAL                                        6              3              4              4

  skt                    5 file(s)
  skill-manager          4 file(s)
  git-issue-workflow     5 file(s)
  git-epic-workflow      3 file(s)

4 of 4 instructing units reach the contract.
```

| | baseline | after |
| --- | --: | --: |
| instructing units reaching the contract | **0 of 4** | **4 of 4** |
| md files naming `skill-manager build` | 0 | 6 |
| md files naming a cold shim | 0 | 3 |
| md files naming `artifacts list/show/stale` | 0 | 4 |

**The TOTAL row is not the goal and should not be read as one.** It counts
*mentions*, and a pointer that names the command it is pointing at scores the
same as a page that explains it. The clause is "stated ONCE and the four link to
it", and the honest check for that is to read the four diffs: one page explains
the mechanism; the other three are a sentence and a path. A reviewer who thinks
one of the pointers has crossed into restatement should say so — the
`skill-manager` entry, which lists four surface terms in one sentence, is the
closest call.

**What this measurement is NOT.** The instrument's `ROOTS` are
`~/.skill-manager/{skills,plugins}`. This ticket must not write the root home, so
`HOME` was pointed at a sandbox holding the four published units and nothing
else. Consequently:

- the **instructing-units** half is exact — those four units are the whole
  question;
- the **TOTAL** row is over four units instead of twenty-three, so it is not
  comparable to the baseline's total for any *other* unit. No other unit's md
  changed, so the totals happen to coincide, but that is an argument, not a
  measurement.
- **the real root home still reads `0 of 4` at hand-off.** It becomes `4 of 4`
  when the epic owner merges the four PRs and syncs. HIS-6 must re-run the
  instrument after that, not cite this file's number.

---

## 5. Clause 2 — the judged read

> *"a fresh agent, given only the docs, answers what an artifact is, what a
> clone inherits versus declares, and when to rebuild."*

Three reads, three fresh agents, **no repository source access** — each given a
directory containing only the four units' markdown, and required to cite a file
path and a quoted sentence per answer or say `NOT ANSWERABLE FROM THE DOCS`. Per
the brief, an answer reachable only from source is a **failure**, so the sandbox
made source unreachable rather than relying on compliance, and the citation
requirement is how a lucky guess gets caught.

Full method, scoring rationale and verbatim transcripts: `judged-read/`.

| question | BEFORE | AFTER-1 | AFTER-2 |
| --- | --- | --- | --- |
| Q1 what an artifact is, what names them | **FAIL** — `NOT ANSWERABLE FROM THE DOCS` | PASS (high) | PASS (high) |
| Q2 inherits vs declares; healthy or broken | **FAIL** — verdict `INFERRED, not stated`, confidence low-medium, from a page that was **wrong** | PASS (high) | PASS (high) |
| Q3 when to rebuild, and the command | **FAIL** — "there is no general rebuild command" | PASS (high) | PASS (high) |
| | **0 of 3** | **3 of 3** | **3 of 3** |

BEFORE, verbatim: *"The phrase 'derived artifact' does not occur anywhere in the
four units, and no command is documented as enumerating or 'naming' such a
class."* And: *"this doc set does not carry a 'derived artifact' concept."*

Q2's BEFORE is scored **fail, not partial**. The agent reconstructed the
mechanics from `skill-homes.md` — but had to label the healthy-vs-broken verdict
`INFERRED, not stated`, reported low-medium confidence, and the page it leaned on
gave the wrong answer. An inference from a stale page is the failure this ticket
exists to close.

AFTER-2 on routing: *"Two documents, and the routing was as obvious as it gets"*
— front door → the page. And on single-sourcing: *"Four entry points, one
destination — I could have started from any of them and landed in the same
place."*

### 5.1 The reads were not a rubber stamp

Each AFTER read was asked an adversarial question, and each found real defects
that were fixed before hand-off:

| found by | defect | fix |
| --- | --- | --- |
| AFTER-1 | `skill-homes.md` flatly contradicts the contract on the same example binary | corrected in place, marked as a correction |
| AFTER-2 | **`rebuildable` defined as "stale, *present on disk*"** — a cold shim IS a file on disk, so the page's own "13 stale, 0 rebuildable" was unreachable from its own definition | predicate is `materialized`; the trap named |
| AFTER-2 | the **127 branch had no remedy** — a declared artifact with no entry point prints no id and looks exactly like a broken PATH | added the lookup that finds the id |
| AFTER-2 | "Only `cli-shim` has a per-artifact producer" stated as an absolute in §1, overturned in §4 | scoped where stated |
| AFTER-2 | bare `build` / `skt build` builds everything stale — the eager rebuild the page forbids, reached by typing less | warned |
| AFTER-2 | ARTI-06's false note quoted 300 lines before §6 corrects it | forward pointer |
| AFTER-2 | the corrected `skill-homes.md` bullet has a **stale twin 100 lines below** | corrected and re-dated |

No read was run after AFTER-2's fixes. **AFTER-2 therefore grades the corpus
minus those seven changes** — its three passing answers did not depend on them,
but a reviewer should know the last version was not itself judged.

### 5.2 Honest limits

- A doc-only sandbox is not a fresh agent in a real session. This measures
  whether the docs *can* answer, not whether an agent *will* read them.
- The corpus is four units. `CLAUDE.md`, the issue body and the assignment were
  excluded deliberately.
- n=1 per condition.

---

## 6. Measured, not read off the source — including two things source alone gets wrong

Every example on the page is from a real worktree clone on 2026-08-23. Two facts
came out the other way from what the source suggested:

**Building a `cli-shim` also provisions the tree behind it.** The kinds table
says `provisioned-tree` is not directly buildable, which reads as "you cannot
build a venv". Measured — `probes/his-8/build-one-artifact.out`:

```
$ ./.skill-manager/bin/cli/jinja2 --version        exit 86, cold shim, venvs/ empty
$ skill-manager build 'cli-shim:pip/jinja2-cli[yaml]' --yes
  built  cli-shim:pip/jinja2-cli[yaml]     1 selected: 1 built            exit 0
$ ./.skill-manager/bin/cli/jinja2 --version        jinja2-cli v0.8.2      exit 0
stale count: 13 -> 11    (the shim AND provisioned-tree:venvs/jinja2-cli)
```

**And that build exited 0**, so the ARTI-06 warning printed in *every* cold shim
— *"exits 1 even when it built what you asked for"* — is broader than what the
command now does. The page says so rather than repeating the warning as fact.

**A `pip:` dep already satisfied on the ambient PATH defeats its own producer.**
`probes/his-8/build-pytest-fail.out`:

```
$ skill-manager build 'cli-shim:pip/pytest' --yes                          exit 1
  ✗ the producer ran and this home still does not hold the artifact — it reported
    the dependency already satisfied from outside this home and wrote nothing.
    This is not a repair.
$ command -v pytest    /Library/Frameworks/Python.framework/Versions/3.10/bin/pytest
```

`--force` does not change it. **This is a home-isolation defect wearing a build
failure's clothes** — the operator's global Python decides whether a home can own
its own pytest — and it is a real limit of the remedy this page recommends.
Filed as **DEF-079** and documented on the page rather than papered over.

---

## 7. Change management — four repositories, four PRs

**The declared path did not work, and this is DEF-077.** `skt publish` and
`skill-manager unit publish` can only publish a unit the home installed. This
worktree home holds four skills and no plugins, so **none of the four units this
ticket must change is installed in it.** Measured —
`probes/his-8/publish-path-attempt.out`:

```
skt publish skt
  -> exit 12  "no unit named 'skt' in this home or its parent"
skill-manager unit publish skt --dry-run --ticket 224-his-8
  -> exit 1   "skt: not a git checkout at <wt-home>/skills/skt"
```

Sharper still: `skt publish` at the worktree tier syncs into the **project
home**, which this ticket's brief explicitly forbids a ticket agent from writing.
**The documented route and the epic's containment rule are in direct conflict**
for every ticket in this epic, and nothing warns about it.

**What was done instead**: the four leaf repos were cloned, edited, committed and
pushed, mirroring `unit publish`'s own conventions exactly — branch
`skill/<ticket>-<unit>`, PR title `skill(<unit>): <ticket>`, base `main`, `origin`
remote, **no force push, no `--direct`**. The results are indistinguishable from
what the tool would have produced. Sequence was leaf-to-parent: skt (which owns
the page) before the three that link to it.

### Published commits — the acceptance record

| unit | repository | branch | commits | PR |
| --- | --- | --- | --- | --- |
| `skt` (plugin) | `haydenrear/skill-publisher-skill` | `skill/224-his-8-skt` | `9d90fb02967d15374892b7c7eb791c7396291d76`<br>`f2136dca54bd79d1dce2fa86bfaa57201dcb29e7` | **#34** |
| `skill-manager` | `haydenrear/skill-manager-skill` | `skill/224-his-8-skill-manager` | `4efde0a7d911046171f8bd2fc6ab38349fa54451` | **#4** |
| `git-issue-workflow` | `haydenrear/git-issue-workflow-skill` | `skill/224-his-8-git-issue-workflow` | `fb9aabb7f01e60708cab2f229ac5c80d57c6662c`<br>`48cad18dbe477cfe61bb261ad7a687451dde4453` | **#23** |
| `git-epic-workflow` | `haydenrear/git-epic-skill` | `skill/224-his-8-git-epic-workflow` | `3c300fafc77c4c6d0fd6760458a163f75f1ab2fe`<br>`ef8da875b393bf824574ee9e1ccebdc2cff098c8` | **#16** |

**Nothing was pushed to any default branch.** Every push was to the feature
branch named above. Each repository's `main` is exactly where it was when this
ticket cloned it, and those tips are also what the operator's root home has
checked out:

| repository | `origin/main` at clone, unchanged |
| --- | --- |
| `skill-publisher-skill` | `6d49d7f` |
| `skill-manager-skill` | `7b02d14` |
| `git-issue-workflow-skill` | `9e1a913` |
| `git-epic-skill` | `726f133` |

The second commit on three of the four branches is the review response to the
AFTER-2 judged read (§5.1).

Version bumps: skt skill `0.3.1 → 0.4.0` (new documented surface),
`skill-manager` `0.1.1 → 0.1.2`, both workflow skills `0.4.0 → 0.4.1`.

**Not done, deliberately**: the project home and the root home were not synced —
the epic agent owns that reconciliation, and until it happens the clause-1
instrument still reads `0 of 4`.

**Also not done**: this repo vendors `skill-publisher-skill/` and
`skill-manager-skill/` as tracked directories, and both snapshots are stale
against their leaf repos — the skt one is missing `artifacts.py`, `build_cmd.py`
and `sweep.py` entirely, i.e. it predates the whole artifact surface this epic is
about. Refreshing it partially would make it newly misleading; refreshing it
wholesale would put a multi-feature diff in a documentation PR. **DEF-081.**

---

## 8. Validation

Run with the five home variables **unset**, per DEF-074, except where noted.

| declared | command | result |
| --- | --- | --- |
| spec unit | `uv run pytest specs/` | **38 passed** in 1.77s |
| repository unit | `jbang RunTests.java` | **ALL PASSED**, exit 0 — `probes/his-8/runtests.out` |
| graph | `run.py home-integrity` | **BUILD SUCCESSFUL** in 2m26s, 18/18 nodes, `status: passed`, run `20260824-004740` |
| goal local signal | `check-docs-coverage.py` | §4 |
| TLC | declared `N/A` in the plan | not run; no model change |
| `run.py --all` | declared **not required** | **not run** — belongs to HIS-6 |

CLI experiments in §6/§7 were run **pinned** (all four home axes plus
`SKILL_MANAGER_CLI`, and `./skill-manager` rather than the PATH shim, which is
the root home's released 0.24.0). Test suites were run **unpinned**. Every number
above says which.

### 8.1 The spec-workflow toolchain — it works now

**This has never worked in this epic (DEF-069). It worked here.**

```
tla-spec-dev --spec-root specs open ticket HIS-8
  -> scaffolded ticket-local workflow files: 83                    SUCCESS
```

The plan parses. HIS-8 is the first ticket in this epic to run either half, and
DEF-069's fix in `6d4c6a4` holds. Recorded as **SF-003** in
`specs/results/skill_feedback.md` so nobody re-files a defect that is resolved —
this epic's own backlog reconciliation note warns that unreconciled `open`
entries are how ledgers stop being read.

`close` took three attempts, and the two refusals were both correct:

```
1. close ticket HIS-8
   ERROR: ticket HIS-8 is not closed in ticket_plan.yaml: status=planned     exit 1
2. close ticket HIS-8 --allow-open           (ledger unfilled)
   VERDICT: REJECTED -- no recursive refinement record / no `narrative:`      exit 1
3. close ticket HIS-8 --allow-open           (ledger filled honestly)
   VERDICT: recorded
   promotion -> specs/current: removed 0 paths, preserved 1 current-only path
   recorded spec history entry: specs/.history/.../ticket-041-HIS-8           SUCCESS
```

Ticket `current` and `desired` are **byte-identical** (`diff -rq`, no output), so
promotion carried nothing. Complexity delta **zero** — `variables=38 actions=9`,
unchanged, because no model was touched.

**`--allow-open` was necessary and is a finding, not a shortcut.**
`TICKET_CLOSED_STATUSES = {accepted, closed, complete, completed, done}`; this
epic's word for a landed ticket is `delivered`; and a ticket agent stops at PR
open. **No status I could honestly set satisfies the gate.** The close is
therefore recorded as the modeled state `CloseTicketWeakened`. **HIS-6 must not
read `weakened: true` on HIS-8 as a comment on HIS-8's evidence** — it is a
vocabulary mismatch. **DEF-080**, filed upstream as
`haydenrear/tla-spec-dev#288` and recorded as SF-002.

The complexity-ledger refusal, by contrast, is a **good** gate and was satisfied
by filling `specs/tickets/HIS-8/results/complexity_ledger.yaml` with a real
refinement search ("none, and here is why structurally") and a real narrative.

---

## 9. Deferred — five entries, budget 5

Every one was grepped against the backlog first; none was already recorded.

| id | sev | one line |
| --- | --- | --- |
| **DEF-077** | major | `skt publish` / `unit publish` cannot publish a unit the home did not install — all four of this ticket's units — and the documented route syncs into a home the epic forbids the ticket to write |
| **DEF-078** | major | the **first** `skt publish --check` in a cold home reports a clean unit as `edited (unknown)`; the policy is right, the rendering makes an evidence gap indistinguishable from unpublished work, and that command is the close gate's input |
| **DEF-079** | major | `build` cannot provision a `pip:` dep whose binary is on the ambient PATH; the operator's machine decides whether a home can own its own tool |
| **DEF-080** | minor | `close ticket`'s accepted plan statuses exclude every status an unmerged epic ticket can honestly hold |
| **DEF-081** | major | this repo's vendored `skill-publisher-skill/` snapshot predates the entire artifact surface — `skt build` does not exist in the tracked copy |

**Not filed, and named here instead**: the cold shim's ARTI-06 note is broader
than current behaviour (§6). It is now documented on the page itself, which is
where an agent meets it, so a backlog entry would add a list item and no reader.

### 9.1 Backlog items routed to HIS-8 that this ticket did NOT do

Four entries carry `candidate_owner: HIS-8`. **Three are code changes outside
this ticket's documentation slice and remain open:**

| id | what it wants | why not here |
| --- | --- | --- |
| DEF-018 | `bootstrap-home.sh` should pass `--home` rather than export `SKILL_MANAGER_HOME` | a **code** change in `git-issue-workflow-skill`; #224's slice is the contract page |
| DEF-043 | `close-change.sh`'s UNPARSEABLE branch must stop advising `--force` | same repo, same reason; advising `--force` discards work the gate just refused to destroy, so it deserves its own ticket |
| DEF-042 | 19 unit assertions depend on ambient-unset variables | a sweep across four foreign suites |
| DEF-039 | the mutation-testing loop must be documented where it is performed | this is the one that *is* documentation, but it belongs in `CLAUDE.md` / the validation-loop page, not in the artifact contract; it was not in #224's slice and I did not widen to it |

They were **routed** to HIS-8 by earlier tickets because HIS-8 owns the publish
path, not because they are in its slice. #224's slice is authoritative and does
not include them. **Escalating rather than silently absorbing.**

---

## 10. What I am not confident about

Listed because a reviewer will find them anyway, and because five tickets in
this epic have had their headline claim overturned.

1. **Whether the pointers stay pointers.** Three units now name
   `artifacts list`, `declared-only` and `skill-manager build` in routing
   sentences. That is a link, not a restatement — but the boundary is a
   judgement, and `skill-manager`'s entry (four surface terms in one sentence) is
   the closest call. If a reviewer thinks it has crossed, it should be cut to the
   path and one clause.
2. **The absolute path in the pointers.**
   `$SKILL_MANAGER_HOME/plugins/skt/skills/skt/references/derived-artifacts.md`
   assumes skt is installed as a plugin at that location. **In a home without
   skt, the pointer dangles** — and the project home this epic works in is
   exactly such a home. Nothing checks this. A reviewer should decide whether the
   pointers need a fallback, and whether the docs-coverage instrument should
   assert that every path a unit names resolves.
3. **AFTER-2's fixes are unjudged.** The last seven changes were made in response
   to a read and not re-read.
4. **The `--force` half of DEF-079 is one observation.** I measured that
   `--force` did not change the pytest outcome. I did not establish *why* the pip
   backend treats an ambient binary as satisfying, so the diagnosis in DEF-079 is
   a strong inference from the tool's own message, not a source-level finding.
5. **I did not verify the four PRs' CI.** Each repo has a `ci.yml`; I pushed and
   opened PRs and did not wait for the checks.
6. **Nine kinds, four exercised.** The kinds table's "rebuilt by" column is from
   `ArtifactBuild.whyNotBuildable`'s own refusal strings. I measured `cli-shim`
   and `provisioned-tree` directly and confirmed `unit-store`/`projection`
   states by reading `artifacts list`. `marketplace-entry`, `harness-instance`,
   `mcp-registration`, `doc-import` and `unit-digest` are documented from the
   source's own text and **not measured**.
