# Judged read — AFTER-3 (the TRIMMED docs, after external review of PR #248)

Verbatim final answer of a fresh agent given ONLY the four units' markdown, no source access.
Run specifically to test whether M2's trim — three restatements cut to pure routing —
broke discoverability. Q4 asked directly, and explicitly invited the answer
"yes, the trim went too far". It did not.

Corpus: 52 md files. Result: **3 of 3, all high confidence, one hop, zero backtracking.**
Every Q5 finding it raised was fixed before hand-off except two, filed as DEF-083 and DEF-084.

---

I'll start by exploring the docs directory.
I read only files under the judged3 directory, starting from skt's SKILL.md.

---

## Q1. What is a derived artifact, and what command names them?

**ANSWER.**
A derived artifact is anything a home **produced** rather than authored — a thing in a home with a **producer** and **recorded inputs**, which can be thrown away and re-derived from the records it names. Examples given: a CLI entry point (`cli-shim`), a venv / provisioned tree, a projection symlink, the unit store itself. Fetching a unit's git repo produces one; installing a CLI dep produces one; projecting a skill into the agent's config dir produces one.

They are registered in `<home>/artifacts.lock.toml`, the "register of what a home is *entitled* to hold" — **identity only**, no fingerprint/hash/timestamp and no absolute path, which is what makes a home clone able to carry the file unchanged. The ledger is an optimisation, not a prerequisite; when ledger and disk disagree, "**the home wins on facts, the ledger wins on existence**."

Nine kinds: `cli-shim`, `unit-store`, `provisioned-tree`, `projection`, `marketplace-entry`, `harness-instance`, `mcp-registration`, `doc-import`, `unit-digest`.

The command that **names** them: **`skill-manager artifacts list`** (with `--kind` / `--owner` filters), plus `artifacts show <id>` for one and `artifacts stale` for the non-agreeing subset. Its output columns are materialization (`materialized` / `partial` / `declared-only`), agreement (`agrees` / `disagrees` / `unrecorded` / `unverifiable`), owner.

**SOURCE.** `/private/tmp/claude-501/-Users-hayde-IdeaProjects-skill-manager/dcb6a969-c6fd-484c-873c-6d6175b7d220/scratchpad/judged3/skt/skills/skt/references/derived-artifacts.md`
- "A **derived artifact** is anything a home *produced* rather than authored: a CLI entry point, a venv, a projection symlink, the unit store itself. `skill-manager artifacts list` names every one."
- "An artifact is a thing in a home with a **producer** and **recorded inputs**."
- "`<home>/artifacts.lock.toml` is the register of what a home is *entitled* to hold."
- "**Identity only, and home-relative only.** The ledger never says what is currently on disk; the two are compared at read time, every time."

**CONFIDENCE.** High.

---

## Q2. What a clone inherits vs. merely declares; is "declared but not built" healthy?

**ANSWER.**

*Inherits (built, usable, sanctioned).* A clone inherits **the entry points its immediate source home's `bin/` actually held** — `bin/` is copied, so its entries travel. Where the source home held a link into a parent store, the clone's own `bin/cli/<name>` is a symlink at that store's copy, and it keeps working. `home verify` names these and **passes them**: "a child home shares the parent's provisioned tools by design; the parent must outlive this home", and links into a re-deriving parent are "**shared by right**".

Two separate questions the page is explicit about: *what travels* is decided by the **immediate source home's `bin/`**; *what a copied link is allowed to reach* is decided over the **whole descent chain** (hence `parentStores` being a list). An entry point the source home never had is simply not there to inherit, "no matter what a grandparent holds."

Important qualifier: this is **not PATH inheritance**. Launching through a home puts *that home's* `bin/cli`, `bin/mcp`, `bin/launch` in front and **strips every foreign home's bin directory** from the inherited PATH; you reach the parent's tool through *your own* `bin/cli/<name>`, one entry at a time.

*Not copied at all.* Cloning skips `cache/`, `tmp/`, `logs/`, `venvs/`, `tools/`, `npm/` — **but not `pm/`**, which is copied deliberately "because a home that cannot run `uv` cannot build anything."

*Declares (recorded, not built).* For an artifact whose backing tree the clone does not carry, the clone writes the ledger row and stops. Where there was an entry point, it writes a **cold shim** — a real executable whose job is to refuse informatively with **exit 86** ("declared, not built", explicitly *not* 127 — "the command *was* found"), printing the exact id to build. Governed by `lazy_artifacts` in `<home>/home.policy.toml`, "Default: on for a project or worktree home, off for the operator root."

*The one branch that looks like breakage:* if the **source home never had the entry point**, there is no cold shim — `bin/cli/<name>` is simply absent, so you get plain **`127 command not found`** and nothing prints an id. Measured: `bin/cli/jinja2` was a cold shim (86) while `cli-shim:pip/pytest` was `declared-only` with no file (127). "Both are the same lazy state; only one of them can say so."

*Also:* `<home>/home.provenance.json` (`clonedFrom`, `parentStores`) "**grants nothing**" — every reader re-derives the chain live; "Cloning is not a laundering step." And **the parent must outlive the clone**.

*Healthy or broken?* **Healthy — a normal state from birth, not a fault.** `artifacts list` calls it `declared-only`; `home verify` "**says nothing about it, and exits 0**"; `home repair` does "**nothing. There is nothing to repair.**"; `skt check` filters it out before a notification exists. The page's portrait of a healthy clone: "links into a re-deriving parent, `declared-only` rows in the ledger, cold shims at unbuilt paths, a non-zero `stale` count with `0 rebuildable`, and `home verify` exit 0." Measured healthy clone: `13 stale, 4 unverifiable, 22 current, of 39 artifact(s)` with `0 rebuildable`. A shipped bug once flagged "a *fresh, untouched, healthy clone* as damaged (`PRUNED_INHERITED_ENTRY bin/cli/tofu`)", and the generalised lesson is: "**Any rule that treats 'declared and absent' as evidence of damage fires on every healthy clone**, a different number of times on every machine."

**SOURCE.** Same file (`skt/skills/skt/references/derived-artifacts.md`), §0, §2, §4, §5 — quotes above are verbatim. Corroborating pointers (no restatement): `git-issue-workflow/SKILL.md` — "your worktree home inherits some of its parent's derived artifacts and only declares the rest"; `git-epic-workflow/SKILL.md` — "A ticket home *inherits* the artifacts its parent holds and *declares* the rest, so `skill-manager artifacts stale` reporting a nonzero count in a fresh clone is not staleness this rule is about"; `git-issue-workflow/references/skill-homes.md` — "**A clone is not a full copy, and that is the design, not a shortfall.**"

**CONFIDENCE.** High.

---

## Q3. When to rebuild, and with what exact command?

**ANSWER.**
**When:** "**After *you* change the unit that owns the artifact. Not on arrival.**" The rule is "about **authorship, not arrival**" — an artifact goes stale because its inputs moved, and on a fresh clone nobody has moved anything. The page's table:

| situation | rebuild? |
| --- | --- |
| just created the worktree + home | **No.** Start working. |
| a tool refuses with exit 86 and you need that tool | Yes — build the id the refusal printed |
| you edited a `skill-script` CLI dep in a unit you are working on | Yes |
| you bumped a unit's declared tool version | Yes, for that artifact |
| `skt check` prints `rebuild with: skt build …` | Yes — that set is already filtered |
| `skt status` says N stale but `0 rebuildable` | **No** — those are declared-not-built |
| about to run the ticket's test suite | No — build what it refuses on, if it refuses |

Read `artifacts stale` by reason text: `its output … is not there` / `… is a link whose target this home does not hold` = lazy, only build if you need the tool; `its declared inputs moved: recorded <a>…, now <b>…` = **act**; `it is built from <id>, which is stale` = fix the upstream one.

**Command:** `skill-manager build <artifact-id>` — `skt build <id>` "is the same thing", and skt resolves short names to ids (`skt build jinja2`). Quote ids containing `[` `]`.

```bash
skill-manager build --stale --dry-run --json          # free, names the producer per target
skill-manager build cli-shim:skill-script/skt         # one artifact + its STALE prerequisites
skill-manager build 'cli-shim:pip/jinja2-cli[yaml]' --force
skt build jinja2
```

**Do not run bare `build` / bare `skt build` on a lazy home** — "With no argument it builds *everything stale*, and on a fresh clone that is every declared-not-built artifact — the eager rebuild this whole page argues against, reached by typing fewer characters."

Two caveats: (a) naming a `cli-shim` also provisions the tree behind it (measured: stale count went 13 → 11 on one build); only `cli-shim` is a name you can pass to `build` — every other kind is *reported* with the command that does rebuild it. (b) `build` exits **0** when it selected artifacts it cannot produce (a report, not a failure); it exits **1** when a producer failed *or* a target is still stale after its producer ran — notably when a `pip:` dep already on the ambient PATH "can defeat its own producer" and `--force` does not change it.

**SOURCE.** Same file, §0 item 4, §3, §4, §6.

**CONFIDENCE.** High.

---

## Q4. Discoverability — did the trim hurt?

**Honest answer: no, it did not hurt me at all on this task — and the premise as stated is not quite true of skt's SKILL.md.**

What actually happened: I opened `skt/skills/skt/SKILL.md` and had the destination inside ~30 lines. The section is titled "**Derived artifacts — read this before deciding a home is broken**", and instead of naming a *topic* it names *my four questions*, nearly verbatim:

- "what is a derived artifact, and what names them?"
- "what did my worktree home inherit from its parent, and what did it only declare?"
- "is this home broken, or is this what a healthy clone looks like?"
- "should I rebuild something, and with what command?"

That is the single most useful thing on the page. A pointer that names the *question* removes all the uncertainty a pointer that names the *subject* leaves behind. I never had to hedge about whether the page would answer me, and I never opened a second page to be sure. One hop, zero backtracking, zero source reading. The reference table row at the bottom repeats the same routing, which was redundant but harmless.

The "read it rather than the source; the last two agents who read the source got the root cause wrong, in opposite directions" line also worked exactly as intended — it pre-empted the "I'll just check the implementation" reflex, and §5 then paid it off by naming both wrong answers.

**Where the premise is wrong.** skt's SKILL.md is *not* routing-only. It still restates a lot of mechanism: the full three-tier home table, the `MERGE_CONFLICT` / "not stale" rule with its worked message, the `ticket sweep` gate criteria and exit codes, the startup-hook disclosure, the CoW/`du`-over-reports-30x note. So if a trim happened, it happened to the *artifact* topic specifically and not to the file. On the artifact topic the residual summary at the front door is actually well judged and I'd keep every word of it:

- the synopsis line `skt build [<id>]  # rebuild a derived artifact whose inputs you changed` — that comment alone is a correct one-line answer to Q3's *when*;
- "the derived artifacts it holds are inherited or declared rather than rebuilt";
- "`skt status` and `skt check` report artifact state into your opening context, so every session is told artifacts exist."

That is roughly the right dose: enough that an agent who never clicks still doesn't panic, not enough to drift.

**The two places I'd change something, concretely:**

1. **`exit 86` is not on the front door.** The realistic failure path is: an agent runs a tool, gets a weird `86`, and greps its loaded context for `86`. skt's SKILL.md has no hit — the string only exists on the reference page the agent hasn't opened. Adding four words to the routing block ("a tool refusing with exit 86") would make the front door match the symptom the agent actually has, rather than the vocabulary the docs use for it. Same for the 127 branch, which is even more likely to be misread as a broken PATH.
2. **The pointer from the other three units can dead-end, and says so without offering a fallback.** `skill-manager/SKILL.md`, `git-issue-workflow/SKILL.md` and `git-epic-workflow/SKILL.md` all point at `${SKILL_MANAGER_HOME:-$HOME/.skill-manager}/plugins/skt/skills/skt/references/derived-artifacts.md` with the caveat "(absent in a home that does not have the skt plugin installed)" — and git-epic-workflow adds "including this project's own home." So the epic skill knowingly routes to a file that is *not there* in the home it is running in, and then adds "do not paraphrase it." That is the one pointer that left me unsure it would answer me. It didn't bite here because the exercise handed me the tree, but an agent in a skt-less home follows that link, finds nothing, and has been explicitly told not to read the source — with no third option named. A one-line fallback (a git coordinate, or "install skt", or a two-sentence emergency summary) would close it.

Everything else: the trim did not go too far. "Yes, it went too far" would be a lie in this case.

---

## Q5. Adversarial — contradictions, errors, confusions

Blunt, in rough order of how much they'd cost a reader.

**1. `skt status`'s own example line is arithmetically incoherent, and it's the one line the page tells you to read.**
§4 shows:
```
$ skill-manager artifacts stale | tail -1
13 stale, 4 unverifiable, 22 current, of 39 artifact(s)
$ skt status | grep artifacts
artifacts  13 stale of 39 — 0 rebuildable, 13 declared-not-built, 4 unverifiable
```
The first line makes `stale` / `unverifiable` / `current` three disjoint buckets (13+4+22 = 39 ✓). The second line reads as a breakdown *of the 13 stale* and sums to 17. And the vocabulary table immediately below presents `rebuildable`, `declared-not-built`, `unverifiable` as three co-equal "skt's word" rows, which reinforces the wrong reading. Meanwhile §1 lists `unverifiable` as an **agreement** value, not a materialization one — so it is a category from a different axis being displayed in the same list as two materialization-derived ones. This is exactly the arithmetic an agent will try to do to decide "how many do I act on", and the page does not disambiguate it.

**2. The owner-less remedy contradicts its own diagnosis, one sentence apart.**
§1: "nothing in this home claims to have produced it: no record says which installer wrote it" — then, two lines later, "Such a tree comes back as a side effect of reinstalling **the unit that originally wrote it**, or not at all." You cannot reinstall the unit that wrote it if no record says which unit that is. Either name how to find the unit, or drop the remedy and keep "or not at all."

**3. The front door advertises the exact invocation the reference page forbids.**
`skt/skills/skt/SKILL.md` synopsis: `skt build [<id>]` — optional argument. §4 of the reference: "**Do not reach for bare `build` (or bare `skt build`) on a lazy home.**" And §4's own command block lists `skill-manager build   # everything stale — read the warning below` as the second entry, i.e. the discouraged form is given more prominence than the recommended `--stale --dry-run`. Mild, but this is a page whose entire thesis is "don't rebuild eagerly", and it lists the eager command twice before warning about it.

**4. Two different clones, two different artifact counts, presented as if one measurement.**
§4's healthy clone has 39 artifacts / 13 declared-not-built. §2's repair bug says "**eight** artifacts were `declared-only` in that clone." Both are "the clone measured for this page." Probably different runs, but nothing says so, and a reader reconciling the numbers wastes time.

**5. "Real copies, never symlinks" vs. "cloned copy-on-write".**
`skt/skills/skt/SKILL.md` says "Homes are real copies, never symlinks", then later "These homes are cloned copy-on-write, so `du` bills every shared block to every copy and over-reports by roughly 30x — a home `du` called 1.1 GB cost 33.7 MB of real space." Both are true, but stated 100 lines apart with no acknowledgement, and the first is the kind of sentence an agent quotes when reasoning about disk. **And it collides across documents:** `git-issue-workflow/references/skill-homes.md` justifies skipping `tools/` with "copying `tools/` costs **1.3 GB**" — a `du`-shaped number, from the same family of homes the skt page says `du` over-reports by ~30x. If that 1.3 GB is a `du` figure, the real cost is ~40 MB and the justification is inflated by the exact factor the sibling page documents. Nobody reconciles this.

**6. `git-issue-workflow/references/skill-homes.md` vs. the skt page: no contradiction, and unusually well-behaved — with one omission.**
This is the check you asked for, so: they do **not** contradict. skill-homes.md deliberately refuses to restate ("**is stated once and is not repeated here**", "this bullet deliberately does not reproduce its details", "this page does not restate the distinction"), and it carries two explicit, dated **retractions** of claims that the skt page now supersedes:
- "This page used to say `home verify` refuses a clone until those links are re-provisioned … **RETRACTED: re-measured 2026-08-23, a fresh ticket-worktree clone passes `skill-manager home verify` (exit 0) untouched.** The claim is left here as a correction so nobody restores it from memory."
- "**Re-dated 2026-08-23 … that remaining `keeps refusing` clause is stale too**, because a lazy clone no longer produces the link it is about."

That is the correct handling and it matches the skt page's §2 exactly. The **omission**: skill-homes.md's skip list is "`cache/`, `tmp/`, `logs/`, `venvs/`, `tools/` and `npm/`" with no mention of `pm/`; the skt page's list is the same six "**but not `pm/`**, which holds the pinned package managers and is copied, deliberately." An agent reading only skill-homes.md would conclude `pm/` is skipped too (it's the same shape of directory), and would then not understand why `uv` works in a clone. One clause would fix it.

**7. A generated message the docs must talk you out of believing.**
Every cold shim prints "*that command exits 1 even when it built what you asked for (ARTI-06); re-run this entry point rather than trusting its status*" — and §6 says that is now false ("Re-measured 2026-08-23: `build 'cli-shim:pip/jinja2-cli[yaml]'` succeeded and exited **0**"). The page handles this honestly (flags it at the example, explains it in §6), but the ground truth is that a **shipped, generated, user-facing string is wrong** and the fix has been to document around it. That is a product bug being carried by prose. It also puts the reader in the position of having to remember which of two authoritative-looking texts to disbelieve.

**8. Minor: the provenance example is timestamped into the future.**
`"clonedAt" : "2026-08-24T00:27:15.950595Z"` sits in a page whose measurements are dated 2026-08-23. Cosmetic, but it's the kind of thing that makes a reader wonder whether the "measured" blocks are transcripts or reconstructions.

**9. Rule-then-violate check — one near-miss, self-defused.**
§2's `home verify` transcript shows three shims all rendering as `-> /Users/…/.skill-manager`, which looks like three links to the same place. The page pre-empts it: "That is verbatim. The `->` names **the home each link reaches**, not the link's full target — which is why all three read the same." Good catch by the author; I'd still consider changing the glyph, since `->` in `ls -l` output means the opposite thing and the transcript is otherwise formatted to look like tool output.

**No contradiction found** between the four units on the substance of inherit-vs-declare, on "declared-only is healthy", or on the rebuild command. `git-epic-workflow/SKILL.md` additionally does the right thing by disambiguating its own overloaded vocabulary — "**One word, two meanings**… a **derived artifact** is a thing a Skill Manager home produced … 'rebuild the artifact' is never an instruction this skill gives" — which is the single best piece of cross-unit hygiene in the set.
