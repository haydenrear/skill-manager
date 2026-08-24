# Judged read — AFTER-1 (first draft of the contract page)

Verbatim final answer of a fresh agent given ONLY the four units' markdown, no source access.
Corpus: 52 md files. Q1-Q3 plus Q4 (discoverability) and Q5 (adversarial).
Its Q5 finding about git-issue-workflow/references/skill-homes.md was fixed before hand-off.

---
I'll start by exploring the allowed directory.
The front door already routes explicitly. Let me read the reference page.
Both answers are here. Now let me check the other three units for contradictions or restatements.
I have what I need. Both required documents were read, plus three cross-checks for the adversarial question.

---

## Q1 — What is a derived artifact, and what command names them?

**ANSWER:** A derived artifact is anything a home *produced* rather than authored — a CLI entry point/shim, a venv or other provisioned tree, a projection link, a unit store, a marketplace entry, a harness instance, an MCP registration, a doc import, a unit digest. Formally: "a thing in a home with a **producer** and **recorded inputs**", registered in the ledger `<home>/artifacts.lock.toml` (identity only — no hashes, timestamps, or absolute paths, which is what makes a home clone carry the file unchanged). There are nine kinds, and notably **only `cli-shim` is actually produced by `build`**; the other eight are rebuilt by `sync`, `rebind`, `harness instantiate`, or `home drift --record`.

The command that names them is **`skill-manager artifacts list`** (with `--kind` / `--owner` filters), plus `skill-manager artifacts show <id>` for one and `skill-manager artifacts stale` for the ones that no longer describe their inputs.

**SOURCE:** `/private/tmp/claude-501/-Users-hayde-IdeaProjects-skill-manager/dcb6a969-c6fd-484c-873c-6d6175b7d220/scratchpad/judged/after/skt/skills/skt/references/derived-artifacts.md`
- §0: "A **derived artifact** is anything a home *produced* rather than authored: a CLI entry point, a venv, a projection symlink, the unit store itself. `skill-manager artifacts list` names every one."
- §1: "An artifact is a thing in a home with a **producer** and **recorded inputs**." / "`<home>/artifacts.lock.toml` is the register of what a home is *entitled* to hold."
- §1: "**Only `cli-shim` has a per-artifact producer.** Everything else is *reported* by `build` with the command that does rebuild it, and is never claimed to have been built by `build`."
- Corroborating one-paragraph version at `.../skt/skills/skt/SKILL.md`: "A **derived artifact** is anything a home produced rather than authored — a CLI entry point, a venv, a projection, a unit store."

**CONFIDENCE:** high

---

## Q2 — Clone: inherits vs declares; healthy or broken?

**ANSWER:**
- **Inherits:** the artifacts the parent store *actually holds*. They stay usable and on `PATH`, reached through symlinks in the clone's **own** `bin/cli/<name>` (or `bin/mcp/<name>`). `home verify` names these out loud and exits 0 on them — "shared by right". Important qualifier the docs stress: this is **not** PATH inheritance. Launching through a home puts that home's `bin/` first and *strips every foreign home's bin directory* from the inherited PATH; you reach the parent's tool one entry at a time, via your own shim. The sanction requires all three of: exactly one segment deep under `bin/cli`/`bin/mcp`; resolving to the same real entry in the other home; and that home re-deriving **live** as an ancestor.
- **Declares (does not build):** everything else. The ledger row is written and nothing is materialized. Where an entry point would be, the clone writes a **cold shim** — a real executable whose only job is to refuse informatively, print the build id, and exit **86**. Cloning also skips `cache/`, `tmp/`, `logs/`, `venvs/`, `tools/`, `npm/` outright.
- **Healthy or broken: healthy.** Explicitly and repeatedly. `artifacts list` calls it `declared-only`; `home verify` reports it "DECLARED and not built" **but does not count it toward the exit code**; `home repair` does nothing because "there is nothing to repair"; `skt check` filters it out before a notification exists. The governing policy is `lazy_artifacts = true` in `<home>/home.policy.toml`, default on for project and worktree homes, off for the operator root.
- Also load-bearing: `home.provenance.json` "grants nothing" — it is a pointer for reporting/repair, and every reader re-derives the ancestry live. "Cloning is not a laundering step." And **the parent must outlive the clone**, or inherited links dangle.

**SOURCE:** same file, `.../skt/skills/skt/references/derived-artifacts.md`, §2:
- "A clone **inherits** the artifacts its parent actually holds — they stay usable, on `PATH`, through links in the clone's own `bin/`. What the parent does not hold, the clone **declares**: recorded in the ledger, not built."
- "5 shim(s) in <clone>/.skill-manager link at its parent store — a child home shares the parent's provisioned tools by design; the parent must outlive this home"
- "**Exit 86 means \"declared, not built\". It does not mean broken.** It is not 127: the command *was* found."
- "N entry point(s) in <home> are DECLARED and not built — normal in a home with `lazy_artifacts = true`, and not a failure."
- "**Any rule that treats \"declared and absent\" as evidence of damage fires on every healthy clone**, a different number of times on every machine."
- Corroborating, `.../git-issue-workflow/SKILL.md`: "Your worktree home *inherits* the derived artifacts its parent holds and *declares* the rest without building them … **On a home you just created, that is healthy, and rebuilding on arrival is waste.**"

**CONFIDENCE:** high

---

## Q3 — When to rebuild, and with what exact command?

**ANSWER:** Rebuild **after *you* change the unit that owns the artifact — not on arrival.** The rule is about authorship, not arrival: an artifact goes stale because its inputs moved, and on a fresh clone nobody moved anything.

Rebuild when: a tool refuses with exit 86 and you actually need that tool; you edited a `skill-script` CLI dep in a unit you're working on; you bumped a unit's declared tool version; or `skt check` names an artifact with a `rebuild with:` line. Do **not** rebuild when: you just created the worktree; `skt status` says N stale but `0 rebuildable`; or you're merely about to run the test suite.

Exact command: **`skill-manager build <artifact-id>`**, equivalently **`skt build <id>`** (skt also resolves short names). Whatever refused already printed the exact id. Variants:

```
skill-manager build                                # everything stale
skill-manager build --stale --dry-run --json       # free, changes nothing, names the producer
skill-manager build cli-shim:skill-script/skt      # one artifact + its STALE prerequisites
skill-manager build 'cli-shim:pip/jinja2-cli[yaml]' --force
skt build computeq
```

Quote ids containing `[` `]` (shell glob). Exit codes: 0 when it selected artifacts it cannot produce (a report, not a failure), 1 when a rebuild failed or a target is still stale — **but** the documented ARTI-06 wart is that `build` can exit 1 even when it built what you asked for, so re-run the entry point rather than trusting build's status. When triaging `artifacts stale` directly, only `its declared inputs moved: recorded <a>…, now <b>…` means act; the two "output is not there / is a link this home does not hold" reasons are lazy, not actionable.

**SOURCE:** same file, §0.3–4, §3, §4, §6:
- "**Rebuild after *you* change the unit that owns the artifact. Not on arrival.** A fresh clone that has built nothing is a healthy clone."
- "The command is `skill-manager build <artifact-id>` (`skt build <id>` is the same thing), and whatever refused already printed the exact id to pass it."
- §3 table rows, verbatim: "You just created the worktree and its home | **No.** Nothing has changed. Start working." … "`skt status` says N stale but `0 rebuildable` | **No.** Those are declared-not-built."
- §6: "**`build` can exit 1 even when it built what you asked for** (ARTI-06)."

**CONFIDENCE:** high

---

## Q4 — Discoverability

**Two documents**, and the routing was as obvious as it gets.

Path: `skt/skills/skt/SKILL.md` (the front door) → `skt/skills/skt/references/derived-artifacts.md`.

The front door does four things right: it lists `skt build [<id>]` in the opening command block; it has a top-level section titled "Derived artifacts — read this before deciding a home is broken", placed *second*, before the tier table; it gives a blockquoted one-paragraph precis so you can decide whether you need the page; and its reference table's first row is literally the three questions I was asked ("What is an artifact, what does a clone inherit versus declare, when do I rebuild?"). I did not have to guess, grep, or browse a directory listing.

Honest calibration: the front-door paragraph alone fully answers **Q2 and Q3**. It does **not** answer Q1's "what command names them" — `skill-manager artifacts list` appears only in the reference page. So one hop was genuinely required, not optional.

Single-sourcing is enforced across units: `skill-manager/SKILL.md`, `git-issue-workflow/SKILL.md`, and `git-epic-workflow/SKILL.md` each state the inherits/declares one-liner and then explicitly refuse to restate the contract, pointing at the same absolute path. `skill-manager/SKILL.md` even says the topic is "**not** documented here". Four entry points, one destination — I could have started from any of them and landed in the same place.

---

## Q5 — Adversarial

Yes. One real contradiction, and several softer problems.

### 1. Flat contradiction on `home verify` and dangling `venvs/` links — same example binary, opposite verdicts

`derived-artifacts.md` §2 and §4:

> "N entry point(s) in <home> are DECLARED and not built — normal in a home with `lazy_artifacts = true`, and not a failure."
> "Those lines **do not affect the exit code.**"
> `| its output … is a link whose target this home does not hold | inherited path, parent lacks it too. Lazy. | Only if you need that tool. |`
> "…and `home verify` exit 0. If that is what you are looking at, you are looking at a healthy home."

`git-issue-workflow/references/skill-homes.md:355-372`:

> "Any CLI shim whose target was under one of those arrives **dangling**, and `skill-manager home verify` refuses the home while it is."
> "**It is not a fixpoint for links into `venvs/`.** `home verify` rc=1 on `bin/cli/jinja2 -> ../../venvs/jinja2-cli/bin/jinja2` → run the remedy it prints → `home verify` rc=1 again, identical message, `<home>/venvs` still empty. … **That is a skill-manager gap**".

Both are describing a fresh clone. Both use `jinja2`/`venvs/jinja2-cli` as the example. One says exit 0 and healthy-by-design; the other says rc=1, unfixable, and a bug. An agent that lands on `skill-homes.md` — which is where `git-issue-workflow` sends you for home mechanics — gets exactly the wrong answer to Q2, from a document in the same corpus.

A reconciliation probably exists (with `lazy_artifacts = true` the clone writes a *cold shim*, a real executable, not a dangling symlink — so it isn't the case `skill-homes.md` measured). But **neither document says that**, `skill-homes.md` never uses the words "lazy", "declared", or "artifact" in that section, and it does not link to `derived-artifacts.md`. It reads as current measured behavior with a named remedy. This is the single most fixable flaw here: `skill-homes.md`'s "A clone is not a full copy" bullet is the one place in the corpus covering this ground that carries **no** pointer to the owning page, while three unit SKILL.mds do.

### 2. The refusal tells you to run a command the doc elsewhere says cannot fix it

The cold shim prints `build it: skill-manager build 'cli-shim:pip/jinja2-cli[yaml]'`, and the page's headline promise is "whatever refused already printed the exact id". But the stated *reason* is "it links to `../../venvs/jinja2-cli/bin/jinja2`, which this home does not have" — a missing `provisioned-tree`, which the nine-kinds table says is rebuilt by "`sync`, or the shim that fronts it", **not** by `build`. Combine that with "Only `cli-shim` has a per-artifact producer" and with ARTI-06 ("`build` can exit 1 even when it built what you asked for … re-run the entry point rather than trusting its status") and the guidance becomes: run this, it may not be the producer, and you can't read its exit code either. The page acknowledges each piece separately and never joins them. If "the shim that fronts it" is the mechanism that makes `build <cli-shim-id>` provision the venv transitively, that sentence deserves to be explicit at §0.4, not a parenthetical in a table cell.

### 3. The `PRUNED_INHERITED_ENTRY` anecdote conflicts with the inherit rule it's illustrating

> "eight artifacts were `declared-only` in that clone and exactly one fired, because the root store happened to hold that one binary."

Per §2, an artifact **the parent holds** is *inherited* — a usable symlink — not `declared-only`. So the anecdote's decisive fact ("the root store happened to hold it") is precisely the condition that should have made it inherited rather than declared. The resolution is presumably that the clone's parent is the *project* home while the binary sat in the *root* store further up the chain, but the page never distinguishes "the parent store" from "any store in the descent chain" when stating the inherit rule, even though `parentStores` is plural in the provenance JSON and `home verify` prints "1 of 1 recorded parent store(s)". The rule is stated single-parent and the bug story is multi-tier.

### 4. Smaller things

- **`pm/` is inconsistent.** The `provisioned-tree` row lists trees under "`cache/`, `venvs/`, `tools/`, `npm/`, `pm/`", but the clone skip-list is "`cache/`, `tmp/`, `logs/`, `venvs/`, `tools/` and `npm/`" — no `pm/`. `skill-homes.md` confirms `<home>/pm/<tool>` is a per-home install target. So is `pm/` copied into a clone or not? Unstated, and it's the interpreter directory.
- **`skt check: all current` on a home with 13 stale artifacts.** The page explains why (check reports units, not artifacts; artifact notifications come only from the *rebuildable* set), and the reasoning is sound. But "all current" as literal output, on the same home where `skt status` prints "13 stale of 39", is the exact string an agent will quote back when it later concludes something is wrong. The rationale is good; the word "all" is not.
- **`build`'s exit codes are stated twice, incompatibly.** §4: "exits **0** when it selected artifacts it cannot produce … and **1** when a rebuild failed or a target is still stale afterwards." §6: exits 1 "even when it built what you asked for". If it built it, it is not still stale — so §6 is not an instance of §4's rule, it is an exception to it. §4 flags "see the ARTI-06 wart" but doesn't say it contradicts the sentence it's attached to.

### What is genuinely good, for contrast

The page's §5 ("The two wrong answers, named") and the closing "what does a healthy clone look like?" checklist are the most useful paragraphs in the corpus — they pre-empt exactly the two failure modes an agent reasoning from source falls into, and give a positive signature of health rather than only negative rules. The "identity only, no fingerprint" ledger header and the "an id that changed when the bytes changed would make *this artifact is stale* unsayable" justification are the kind of design rationale that makes the rest inferable. None of my complaints above are about the contract itself, which is coherent; they are about one un-linked stale page and three places where the page states a rule and then quietly violates it in an example.
