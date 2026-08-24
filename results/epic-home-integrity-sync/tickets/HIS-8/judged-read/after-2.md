# Judged read — AFTER-2 (after AFTER-1's findings were fixed)

Verbatim final answer of a fresh agent given ONLY the four units' markdown, no source access.
A different agent from AFTER-1; it did not see AFTER-1's answers.
Corpus: 52 md files. Q1-Q3 plus Q4 and Q5; Q5 explicitly asked it to check
skill-homes.md against the skt page. All six of its Q5 findings were fixed before hand-off.

---
I'll start by exploring the allowed directory.
I read only within the permitted directory. Here are my answers.

---

## Q1 — What is a derived artifact, and what command names them?

**ANSWER.** A derived artifact is anything a skill-manager home *produced* rather than *authored* — a thing in a home with a **producer** and **recorded inputs**, throwable-away and re-derivable from the records it names. Examples given: a CLI entry point (`cli-shim`), a venv / provisioned tree, a projection link, the unit store itself. Nine kinds are enumerated: `cli-shim`, `unit-store`, `provisioned-tree`, `projection`, `marketplace-entry`, `harness-instance`, `mcp-registration`, `doc-import`, `unit-digest`. They are registered in `<home>/artifacts.lock.toml`, the "register of what a home is *entitled* to hold" — identity only, no hashes/timestamps/absolute paths, so a clone carries it unchanged.

The command that names them is **`skill-manager artifacts list`** (with `--kind` / `--owner` filters; `artifacts show <id>` for one, `artifacts stale` for the stale subset).

**SOURCE.**
- `…/after2/skt/skills/skt/references/derived-artifacts.md` §0: "A **derived artifact** is anything a home *produced* rather than authored: a CLI entry point, a venv, a projection symlink, the unit store itself. `skill-manager artifacts list` names every one."
- Same file §1: "An artifact is a thing in a home with a **producer** and **recorded inputs**." … "`<home>/artifacts.lock.toml` is the register of what a home is *entitled* to hold."
- `…/after2/skt/skills/skt/SKILL.md` L38–39: "A **derived artifact** is anything a home produced rather than authored — a CLI entry point, a venv, a projection, a unit store."

**CONFIDENCE.** High.

---

## Q2 — Clone: inherits vs. declares; is "declared but not built" healthy?

**ANSWER.**
- **Inherits:** the artifacts its *immediate source home's* `bin/` actually held. `bin/` is copied, so entries travel; where the source home's entry point was a link into a parent store, the clone's own `bin/cli/<name>` is a symlink at that store's copy — usable, on PATH, and `home verify` reports it as sanctioned and exits 0 ("a child home shares the parent's provisioned tools by design", "shared by right"). Also copied deliberately: `pm/` (pinned package managers), "because a home that cannot run `uv` cannot build anything."
- **Declares (records, does not build):** everything whose backing tree the clone does not carry. `cache/`, `tmp/`, `logs/`, `venvs/`, `tools/`, `npm/` are skipped outright; the ledger row is written and nothing is built. Where the source home had an entry point, the clone writes a **cold shim** — a real executable whose job is to refuse informatively, exit **86**, printing the exact `skill-manager build <id>` to run. If the source home never had an entry point, there is no file at all and you get plain `127`.
- **Two important qualifications the page makes explicitly:** inheritance is decided by the *immediate source home's* `bin/`, not the whole ancestry ("An entry point the source home never had is simply not there to inherit, no matter what a grandparent holds") — while what a copied link is *allowed to reach* is decided over the whole descent chain (`parentStores`). And this is **not PATH inheritance**: launching through a home strips every foreign home's bin dir; you reach the parent's tool one entry at a time through your own `bin/cli/<name>`.
- **Healthy or broken? Healthy — normal from birth.** `artifacts list` calls it `declared-only`; `home verify` calls it "DECLARED and not built", *reported, never counted* against the exit code; `home repair` does nothing ("There is nothing to repair"); `skt check` filters it out entirely. Governed by `lazy_artifacts = true` in `<home>/home.policy.toml` (default on for project/worktree homes, off for the operator root).
- Caveats worth carrying: the parent home **must outlive the clone** (deleting it dangles every inherited link), and `home.provenance.json` "grants nothing" — every reader re-derives the ancestry live; "Cloning is not a laundering step."

**SOURCE.** All `…/after2/skt/skills/skt/references/derived-artifacts.md` §0.2, §2:
- "A clone **inherits** the artifacts its parent actually holds — they stay usable, on `PATH`, through links in the clone's own `bin/`. What the parent does not hold, the clone **declares**: recorded in the ledger, not built."
- "It skips `cache/`, `tmp/`, `logs/`, `venvs/`, `tools/` and `npm/` outright — **but not `pm/`**, which holds the pinned package managers and is copied, deliberately".
- "**Two different words, and they are not the same set.** The clone inherits what its **immediate source home's `bin/` actually held**…"
- "**Exit 86 means 'declared, not built'. It does not mean broken.** It is not 127: the command *was* found."
- "N entry point(s) in <home> are DECLARED and not built — normal in a home with `lazy_artifacts = true`, and not a failure."
- "**A fresh clone that has built nothing is a healthy clone.**"
- Corroborating (pointer-only, no restatement): `…/after2/git-issue-workflow/SKILL.md` L228–235 ("On a home you just created, that is healthy, and rebuilding on arrival is waste"); `…/after2/git-issue-workflow/references/skill-homes.md` L354–383; `…/after2/skill-manager/SKILL.md` L130–136; `…/after2/git-epic-workflow/SKILL.md` L212–221.

**CONFIDENCE.** High.

---

## Q3 — When to rebuild, and with what exact command?

**ANSWER.** **Rebuild after *you* change the unit that owns the artifact — not on arrival.** "The rule is about **authorship, not arrival**." Concretely, rebuild when: a tool refuses with exit 86 *and you need that tool*; you edited a `skill-script` CLI dep in a unit you're working on; you bumped a unit's declared tool version; or `skt check` names an artifact with a `rebuild with:` line (that set is pre-filtered to the **rebuildable** ones). Do **not** rebuild because you just created a worktree home, because `skt status` says "N stale, 0 rebuildable", or pre-emptively before running the ticket's test suite.

**Exact command:**
```bash
skill-manager build <artifact-id>       # or: skt build <id>  (same thing)
skill-manager build --stale --dry-run --json    # free, names the producer per target
```
The id is printed by whatever refused. Quote ids containing `[` `]` (shell glob): `skill-manager build 'cli-shim:pip/jinja2-cli[yaml]'`. `skt build` also resolves short names (`skt build computeq`). Bare `skill-manager build` = everything stale.

Caveats stated: building a `cli-shim` also provisions the tree behind it (measured, stale 13→11 on one build); `build` exits 0 when it merely reports artifacts it cannot produce, and exits 1 when a producer failed *or* when a target is still stale after its producer ran — the latter happens when a `pip:` binary already on the ambient PATH defeats its own producer, and `--force` does not fix it.

**SOURCE.** `…/after2/skt/skills/skt/references/derived-artifacts.md` §0.3–0.4, §3, §4:
- "**Rebuild after *you* change the unit that owns the artifact. Not on arrival.**"
- "The command is `skill-manager build <artifact-id>` (`skt build <id>` is the same thing), and whatever refused already printed the exact id to pass it."
- §3 table rows quoted above.
- Also `…/after2/skt/skills/skt/SKILL.md` L21 `skt build [<id>]` and L42–43.

**CONFIDENCE.** High.

---

## Q4 — Routing from the front door

**Two documents**, and the routing was as obvious as it gets:

1. `…/after2/skt/skills/skt/SKILL.md` — second section, titled "Derived artifacts — read this before deciding a home is broken", with an explicit pointer and a one-paragraph summary that alone answers all three questions at low resolution.
2. `…/after2/skt/skills/skt/references/derived-artifacts.md` — the full contract, all three answers at high resolution, in sections numbered §1 (what), §2 (inherit vs declare), §3 (when to rebuild), §4 (the command).

I opened six more files (`git-issue-workflow/references/skill-homes.md`, `git-issue-workflow/SKILL.md`, `skill-manager/SKILL.md`, `git-epic-workflow/SKILL.md`, plus greps) **only for Q5's contradiction hunt**, not to answer Q1–Q3.

Notably good: the front door tells you *why* to read the page rather than the source ("the last two agents who read the source instead got the root cause wrong, in opposite directions"), and the single-source discipline holds — `skill-manager`, `git-issue-workflow` and `git-epic-workflow` all point at the same absolute path and explicitly say they do not restate it. That is unusually well done. The one thing that would have made the front door better: `skt status` is described as printing an `artifacts …` line, but the SKILL.md doesn't show a sample `skt status` output, so you learn the line's shape only on p.2 (§4).

---

## Q5 — Adversarial: contradictions, errors, and self-violations

### A. `skill-homes.md` vs. the skt reference page — no doctrinal conflict, but one stale present-tense claim inside `skill-homes.md` itself

On the overlapping ground, `skill-homes.md` is **consistent** with `derived-artifacts.md` and correctly refuses to restate the contract (L362–364: "**The whole contract — inherit versus declare, and when to rebuild — is stated once and is not repeated here**"). Its skip-list (`cache/`, `tmp/`, `logs/`, `venvs/`, `tools/`, `npm/`, L355) matches exactly, and it even carries a dated correction of its own former error (L375–383): *"This page used to say `home verify` refuses a clone until those links are re-provisioned… **That is no longer true and the gap is closed.**"*

**But the same page, ~100 lines later, still asserts the superseded behaviour in the present tense.** L479–484:

> "**Deleted rather than demoted:** the paragraph that told you to run `sync --force-scripts`… Its own next sentence said the command 'does NOT recreate `<home>/venvs`, so a link INTO `venvs/` stays dangling and `skill-manager home verify` **keeps refusing this home**' — measured: `home verify` rc=1 → run the remedy → rc=1, identical message, `venvs/` still empty. **What survives is the *fact*, one counted line:** `warning: N link(s) in this home do not resolve …`"

That names *the same artifact* (`bin/cli/jinja2 -> ../../venvs/jinja2-cli/bin/jinja2`) as the L375–383 correction, which says it is **now a cold shim exiting 86** and that `home verify` **exits 0** with *"every reference … resolves"*. A reader who lands on L479 first concludes their fresh clone has a counted warning and a red `home verify`; a reader who lands on L375 concludes the opposite. "One **counted** line" is the specific problem — `derived-artifacts.md` §2 is emphatic that these lines "**do not affect the exit code**". Either the surviving warning is the genuinely different *unresolved* category (in which case say so, because the example given is the declared-not-built one), or the paragraph is stale and should be re-dated like its neighbour was. It is undated while the passage it contradicts is dated 2026-08-23.

### B. Internal, `derived-artifacts.md` §4: the definition of `rebuildable` contradicts the page's own measured example

§4's vocabulary table:

> "**rebuildable** | stale, **present on disk**, and of a kind `build` produces | **Yes.** This is the real signal."

On the fresh clone measured in the very same section, `skt status` prints `13 stale of 39 — **0 rebuildable**, 13 declared-not-built`. Yet on that same clone (§2) `bin/cli/jinja2` **was** a cold shim — and a cold shim is defined two paragraphs earlier as "**a real, executable file** whose whole job is to refuse informatively." So `cli-shim:pip/jinja2-cli[yaml]` is stale, is a file present on disk, and is of kind `cli-shim` — the one kind `build` demonstrably produces (§4 builds exactly it, successfully). By the stated definition it is rebuildable; by the measured output the count is zero.

The reconciliation must be that "present on disk" secretly means "**materialized**" (`artifacts show` reports jinja2 as `declared-only`, i.e. "nothing **usable** at its path"), not "a file exists there". But the page never says that, and it uses a phrase that a cold shim literally satisfies. This is the single most likely place for a careful agent to derive a wrong answer, and it sits in the section the page says "the whole page exists for". Fix: replace "present on disk" with "**materialized** (not `declared-only`)".

### C. `derived-artifacts.md` §1 states an absolute and §4 overturns it

§1: "**Only `cli-shim` has a per-artifact producer.** Everything else is *reported* by `build`… and **is never claimed to have been built by `build`**."
§4: "**Building a `cli-shim` also provisions the tree behind it.**… The `stale` count went `13 → 11` on that one build: the shim **and** its `provisioned-tree:venvs/jinja2-cli` both became current."

The page notices and hedges ("that is true of naming one on the command line"), and the table row carries "or the shim that fronts it" — so it is survivable. But the §1 sentence is bolded as an absolute and is the one a skimmer keeps. It should be scoped where it is stated, not 250 lines later.

### D. A rule stated, then a case where the rule cannot be followed — unaddressed

§0.4: "whatever refused **already printed the exact id** to pass it." §3's only 86 row: "build the id **the refusal printed**."

§2 then establishes the case where nothing prints anything: "If the source home never had one, the ledger still declares the artifact and `bin/cli/<name>` is simply **absent** — so you get plain `127 command not found`, not 86. Measured: … `cli-shim:pip/pytest` was `declared-only` with no file at all (127)."

So the documented 127 path leaves the agent with no id and **no instruction anywhere on the page for what to do next**. §3's table has no `127` row. The obvious remedy (`skill-manager artifacts list --owner <unit>` to find the id) is never connected to this case. Given that the page's whole thesis is "don't conclude your home is broken", the `127` branch — which looks *exactly* like a broken PATH — is the branch most needing a remedy and is the only one without one.

### E. Smaller flaws

1. **The `home verify` sample output is malformed as printed** (§2): `bin/cli/computeq -> /Users/…/.skill-manager` — the arrow points at a *home directory*, not at an entry point inside it. All three shown links have identical targets. Presumably elided, but there is no ellipsis marking it, so it reads as a real symlink target and undercuts "it resolves to the *same real entry* in the other home" three paragraphs down.
2. **`computeq` is used as both examples.** It is the poster child for an *inherited, working* shim in §2, and the poster child for a *build target* in §4 (`skt build computeq`). Different example names would cost nothing.
3. **`skt build` with no argument is undocumented.** `SKILL.md` L21 advertises `skt build [<id>]` — id optional. `derived-artifacts.md` documents bare **`skill-manager build`** as "everything stale". If bare `skt build` means the same thing, then on the page's own healthy clone it would try to build 13 declared-not-built artifacts — precisely the "expensive clone" waste the page spends two sections forbidding. Either the bracket in the SKILL.md synopsis is wrong, or the page owes bare `skt build` a warning.
4. **ARTI-06 is documented as a warning that is wrong, and the wrong text is still quoted verbatim in the primary example.** §2's cold-shim output includes "that command exits 1 even when it built what you asked for (ARTI-06)"; §6 says it was re-measured and the build exits 0. Honest, but the reader meets the false claim 300 lines before the correction, in the block they are most likely to copy.
5. **Terminology collision across units.** `git-epic-workflow` uses "artifact" pervasively for the **wave review artifact** (`artifact_root: "results/epic-<slug>/review"`, `references/human-review.md` §3 "What the artifact carries"), an entirely unrelated sense. Its own routing table row — "Deciding whether a ticket home's `declared-only` artifacts need rebuilding" — is disambiguated only by `declared-only`. An agent in an epic reading "produce the artifact" is one bad association away from the wrong page. Neither skill flags the collision.
6. **`derived-artifacts.md`'s §5 "two wrong answers" is the best thing on the page** and I want to say so, since the brief asked for bluntness in both directions: naming the failure modes, attributing them to source-reading, and closing with "what does a healthy clone look like?" is exactly what stops the failure it targets. Flaws B and D are worth fixing precisely because the rest of the page is good enough that a reader will trust its phrasing literally.
