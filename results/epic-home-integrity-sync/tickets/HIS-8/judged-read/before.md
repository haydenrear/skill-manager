# Judged read — BEFORE (the four units at origin/main)

Verbatim final answer of a fresh agent given ONLY the four units' markdown, no source access.
Corpus: 51 md files. Questions Q1-Q3 as stated in this directory's README.

---
I'll start by exploring the allowed directory.
I read only the four unit doc trees under the permitted directory. Here is what I found.

---

## Q1. What is a *derived artifact* in a skill-manager home, and what command names them?

**ANSWER:** `NOT ANSWERABLE FROM THE DOCS`

The phrase "derived artifact" does not occur anywhere in the four units, and no command is documented as enumerating or "naming" such a class. `grep -ri "derived"` across the whole tree returns six hits, none of which define a term, and `grep -ri "artifact"` finds the word only in unrelated senses (epic *review artifacts*, `cli-lock` binary artifacts, spec-double generated artifacts).

What the docs *do* have, which is adjacent but not the same thing and which I am deliberately not upgrading into an answer:

- One sentence about generated project-env state being "derived": `/private/tmp/claude-501/-Users-hayde-IdeaProjects-skill-manager/dcb6a969-c6fd-484c-873c-6d6175b7d220/scratchpad/judged/before/skill-manager/references/projects.md:314` — "Generated `.skill-manager/env.md`, env `pyproject.toml`, vendor checkouts, and tool shims are derived from the manifest and lock."
- One use of "re-derivable" for a fixed list of home directories: `.../git-issue-workflow/references/skill-homes.md:354` — "**A clone is not a full copy.** `cache/`, `tmp/`, `logs/`, `venvs/`, `tools/` and `npm/` are skipped (they are re-derivable, and copying `tools/` costs 1.3 GB)."

Neither passage generalizes to a named concept, and neither names a command that lists them. `skt check`'s one-line synopsis mentions "stale artifacts" (`.../skt/skills/skt/SKILL.md:14`) but the body of that page explains it only in terms of unit staleness and recorded unit errors — never artifacts of a home.

**SOURCE:** absence, established by exhaustive grep over the permitted tree; nearest passages quoted above from `.../skill-manager/references/projects.md:314-316` and `.../git-issue-workflow/references/skill-homes.md:354-356`.

**CONFIDENCE:** high (that the term and the command are absent).

---

## Q2. What a clone inherits vs. merely declares; is "declared but not built" healthy or broken?

**ANSWER:** Partly answerable. The docs describe exactly this shape without using the words "inherit", "declare" or "build", so the substance is there and the framing is mine.

**What a clone inherits (real bytes copied):**
- The **unit store**. `.../git-issue-workflow/references/skill-homes.md:402` — "`home clone` copies the **store**." The store is `<home>/skills/` (line 396: "The store is `<home>/skills/`.").
- Copying is copy-on-write, so inheriting the store is nearly free: line 127 — "a `home clone` of a 189 MB home consumes **7.22 MB — 3.8%**."
- Cloning copies units but never installs any: line 376 — "Cloning copies units; it never *installs* any." So an empty parent yields an empty clone.

**What it does not copy (and so at most declares):**
- Six directories are skipped outright: line 354 — "`cache/`, `tmp/`, `logs/`, `venvs/`, `tools/` and `npm/` are skipped (they are re-derivable…)". Consequence, same line 356: "Any CLI shim whose target was under one of those arrives **dangling**, and `skill-manager home verify` refuses the home while it is."
- The **projection ledger is copied and re-anchored, but the symlinks it describes are not created** — this is the clearest "declared, not built" case in the docs. Lines 413-416: "`home clone` copies `installed/<unit>.projections.json` *and re-anchors it*, so a fresh worktree home already declares the right destination under the right root. **The records are correct; only the symlinks are missing.** Materializing them is instant, offline, and touches no unit content."
- The agent homes themselves are outside the copy: line 402-405 — "The agent homes live *beside* it, so they are not in the copy — and a freshly cloned worktree home therefore has a full store and three empty agent directories. Measured: `wt new`, exit 0, full contract, `verified: 20 skill(s) servable`, and `ls -a <wt>/.claude` answering `.` and `..`. Every agent launched in that worktree saw **zero** skills."

**Healthy or broken?** The docs give *two different verdicts for two different cases*, and never a general one:

- Unbuilt **skill projections** are treated as **not acceptable**: bootstrap materializes them from the ledger, and if it cannot, line 424 — "A home an agent cannot read its skills from exits `6` and names every missing link plus the `sync --skip-mcp` that would create it; `--allow-unprojected` accepts one deliberately (**still never as *verified***)."
- Unbuilt **`venvs/`-backed CLI shims** are treated as **expected and tolerated**: `home verify` calls them a failure but cannot be satisfied. Lines 366-373 — "**It is not a fixpoint for links into `venvs/`.** `home verify` rc=1 … → run the remedy it prints → `home verify` rc=1 again, identical message, `<home>/venvs` still empty. Nothing in `sync` recreates a venv the clone deliberately skipped, so such a home cannot pass `home verify` by following `home verify`'s own instruction. **That is a skill-manager gap**; only the tools those links name are affected, so `bootstrap-home.sh` reports it and moves on rather than refusing."
- Separately, a fresh worktree home is expected to trip the drift gate, and the docs explicitly say not to read that as breakage: line 519 — "'Content moved since the last recorded digest' is the ordinary state of a home anyone is working in, so **expect the gate on a new worktree and do not read it as a broken bootstrap**."

**SOURCE:** all from `/private/tmp/claude-501/-Users-hayde-IdeaProjects-skill-manager/dcb6a969-c6fd-484c-873c-6d6175b7d220/scratchpad/judged/before/git-issue-workflow/references/skill-homes.md` (lines 123-133, 354-373, 396-425, 519), plus `.../skill-manager/references/projects.md:325` — "`clone --to` | Make a new home from an existing one, **skipping re-derivable `cache/`**, and verify nothing in the copy still points at the original." The words "inherit / declare / build" and any single healthy-vs-broken verdict are `INFERRED, not stated` — inferred from the ledger-vs-symlink split and from the two different exit behaviors (exit 6 refusal for projections; report-and-continue for venv links).

**CONFIDENCE:** medium-high on the mechanics (directly quoted); low-medium that the docs would endorse "declared but not built" as one named, healthy state — they do not frame it as one thing.

---

## Q3. When should you rebuild a derived artifact, and with what exact command?

**ANSWER:** `NOT ANSWERABLE FROM THE DOCS` as asked — there is no general "derived artifact" class and therefore no single rebuild command. The docs give per-thing rebuild rules instead, and one of them is an explicit warning that the obvious rebuild command does *not* work:

- **`skill-script:` CLI binaries** — rebuild is automatic on fingerprint change; the explicit replay is `sync <skill> --force-scripts` / `sync --force-scripts`. `.../skt/references/skill-scripts.md:140` — "Use `--force-scripts` when you need an explicit replay without changing the script bytes or deleting the binary. On named sync, the replay scope is the named unit or units."
- **Project envs and generated env files** — `.../skill-manager/references/projects.md:308-316`: `skill-manager env sync <env> --project-dir <project>`, and "Update the manifest, then re-run the CLI instead of editing generated env files by hand."
- **The CLI pin at `<home>/bin/cli/skill-manager`** — one writer only: `.../git-issue-workflow/references/skill-homes.md:245` — "**`skill-manager home shims` writes it. `bootstrap-home.sh` does not.**" Repair is re-running `home shims`; a pin whose target is gone "is a refusal, not a repair" (line 279).
- **Missing skill projections** — from the ledger first, then `sync --skip-mcp` "only for what the ledger cannot answer" (lines 413-419).
- **Stale units** — `skt sync <unit>` / `skill-manager sync <unit>`, run in root *and* project home before scheduling tickets (`.../git-epic-workflow/SKILL.md:196-203`).
- **The explicit negative:** the instruction to rebuild clone-dangling links with `sync --force-scripts` was **deleted as wrong**. `.../git-issue-workflow/references/skill-homes.md:467-472` — "**Deleted rather than demoted:** the paragraph that told you to run `sync --force-scripts` to re-provision the links a clone left dangling. Its own next sentence said the command 'does NOT recreate `<home>/venvs`…' — measured: `home verify` rc=1 → run the remedy → rc=1, identical message, `venvs/` still empty."

**SOURCE:** paths and quotes as given above. The claim that there is *no* general rebuild command is from absence (exhaustive grep for `rebuild|re-build|regenerat|re-derive|materializ` over the permitted tree returned only the per-thing cases listed).

**CONFIDENCE:** high that no general answer exists in these docs; high on each per-artifact command, which is quoted.

---

**Bottom line:** this doc set does not carry a "derived artifact" concept. Q1 and Q3 fail on the term itself; Q2's mechanics are documented in detail in `git-issue-workflow/references/skill-homes.md`, but the healthy-vs-broken judgement is split across two cases and is never stated as a general rule.
