# Progressive disclosure, priced

Owner's instruction, 2026-08-25: *"prove that in order for agents to do what they
need they only need to use, say, 2000 tokens or so. And we should be stopping them
and fixing it if they go longer. The eval should be CHEAP because of how good we've
set up progressive disclosure. Make sure that skt, artifacts, all of that builds and
is good on real agents that don't inherit."*

They went longer. This is where they were stopped, what it cost, and the fix —
measured, not proposed.

## The agents genuinely do not inherit

Every prior disclosure measurement in this epic used Task subagents, and
`judged-reads-contaminated-by-memory` records why that invalidated them: auto-memory,
the operator's skill listing and git status are injected into a subagent's prompt and
independently answer at least two of the tested questions.

These runs are `claude -p` **subprocesses**. They share no context with the session
that launched them. `CLAUDE_CONFIG_DIR` points at a purpose-built config that
symlinks settings for auth but carries `projects: {}` and **zero plugins**, so there
is no auto-memory, no repo `CLAUDE.md`, and no operator skill listing. The only
skills an agent sees are the ones the tier under test projects — which *is* the first
rung of disclosure, and is therefore the variable, not a leak.

Containment is structural. Every run pins all four home axes **and `HOME` itself** at
a scratch path, so `$HOME/.skill-manager` — skill-manager's fallback when nothing is
set — cannot resolve to the operator's real root home. The real homes were copied
copy-on-write (87 MB actual) and their credentials scrubbed from the copies before any
agent ran.

## What is measured, and why not "total tokens"

A fresh agent pays ~40k tokens for the system prompt and tool schemas before reading
one byte of this project. That floor is the harness's, and no amount of good
disclosure moves it. What disclosure governs is the second number:

**`corpus_tokens`** — bytes of tool-result content returned by Read/Grep/Glob/Bash
whose target is inside the home under test, ÷ 4. Budget: **2,000**.

The root home holds **8.5 million tokens** of markdown. The budget is 2,000. That
ratio, 4,250:1, is the entire claim being tested.

## Results

| run | tier | front door available | corpus tokens | vs budget | $ |
| --- | --- | --- | --- | --- | --- |
| `orient-worktree` | worktree | the tier's own 5 consumer skills | **10,879** | 5.4× over | 0.62 |
| `orient-root` | root | 24 skills, skt included | **21,186** | **10.6× over** | 0.90 |
| `orient-worktree-with-skt` | worktree | + the skt front door | **823** | ✅ **within** | 0.53 |
| `execute-project` | project | none needed | **~0** | ✅ within | 0.24 |

### The good news first, because it is the larger half

**`execute-project` passed cleanly and cheaply.** A fresh agent asked to report
installed units, stale artifacts and a rebuild command found
`skill-manager list --json` (5 units, 0 stale, exit 0) and
`skill-manager artifacts stale` (18 stale, exit 0), produced a valid
`skill-manager build cli-shim:brew/docker`, and reported **zero failures** — in 8
turns, 72 seconds, and **without reading a single byte of documentation**. It worked
entirely from `--help`.

So: **skt, artifacts and build are good on real agents that do not inherit.** The
CLI discloses itself well.

### The bad news is narrow and specific

The *home system* does not disclose itself at all. Orientation — which tier am I,
what do I inherit, how does my edit reach the tier above and its own repo, what must
I not touch — cost 10,879 tokens at the worktree tier and **21,186 at root**, where
it should have been cheapest.

What the root agent actually read, in order of size:

    56,690 B  skills/git-issue-workflow/references/skill-homes.md   (13,320 tokens)
    13,494 B  plugins/skt/src/skt/publish.py                        ← Python source
    10,164 B  plugins/skt/src/skt/status.py                         ← Python source
     2,478 B  plugins/skt/src/skt/context.py                        ← Python source

Two things in that list are damning. **The one document that answers the questions is
13,320 tokens — 6.7× the entire budget in a single Read, with no summary to read
instead.** And three of the four answers were reconstructed from **skt's own Python
source**, which HIS-20's rule scores as a failure of the clause, not a pass.

### The fix already exists and costs 251 tokens

`skt status` prints tier, home, checkout, spec workflow, units, artifact staleness
and the next command in **1,003 bytes — 251 tokens, 8× under budget.**

It is installed at exactly one tier:

    tier-root        plugins: andrej-karpathy-skills cdc-agent-substrate-plugin skt
    tier-project     plugins: (none)
    tier-worktree    plugins: (none)

The two tiers where ticket agents actually work do not have it. `orient-worktree-with-skt`
is the same tier, same prompt, same model, with the skt front door projected:
**10,879 → 823 tokens, a 13.2× reduction**, and the answers got *better* — all four
correct, sourced to `home.provenance.json` (212 B), `home.policy.toml`, and skt's
documentation rather than its source. The agent even noticed on its own that the home
had no `skt` binary and adapted its answer accordingly.

## The quick fix was tried end to end, and it did NOT work

`[plugins.skt]` was **already declared** in this repository's `skill-project.toml`.
The project home had simply never realized its own manifest — DEF-096 — so the
10,879-token orientation cost is DEF-096's price, measured.

So the fix was applied for real: `skt` and its imported `skill-manager` unit were
installed into the project home (all four axes pinned at the checkout), a worktree
home was cloned from it, and the same prompt re-run.

| run | corpus tokens |
| --- | --- |
| `orient-worktree` — before | 10,879 |
| `orient-worktree-with-skt` — controlled, skt projected onto the tier's own units | **823** |
| `orient-worktree-fixed` — **real clone of the fixed project home** | **9,460** |

**13% better, not 13×.** The controlled run is not reproducible by installing the
unit, and the reason is visible in one line of the read log:

    25,706 B  skills/skill-manager/SKILL.md      ← 6,426 tokens, 3.2x the budget, one Read
       611 B  cache/skt-check.json
       ...
    skt invocations: 0

**The agent never ran `skt status`. Not once.** It read skt's reference *pages* and
`skill-manager`'s 25 kB SKILL.md instead. Installing the front door and satisfying
skt's `skill-imports` also installed a document that outweighs the command by
26:1 — and the agent read the document.

The controlled run scored 823 because the skill set it saw happened to be lean, not
because skt was present. That is a difference the first version of this page did not
distinguish, and the end-to-end run is what caught it.

**What the fix actually is, then:** not "install the front door" but "make the cheap
command the first thing an agent is told to do, and stop shipping SKILL.md files that
outweigh it." That is design work, and it is scheduled as a goal in the next epic
rather than patched here. The install stays — it is the manifest being realized, it
is correct on its own terms, and it buys 13% — but it is not the fix and this page
does not claim it is.

## Honest limits

- **n = 1 per cell, one model (Sonnet).** The 13× direction is far larger than
  plausible run-to-run variance and the mechanism is visible in the read logs, but
  these are single samples, not a distribution.
- **`corpus_tokens` under-counts execution tasks.** `execute-project` reads 0 because
  the agent invoked `$SKILL_MANAGER_CLI` without naming the home path, so the
  attribution rule misses it. Its ~0 means "read no documentation", which is the
  finding; it does not mean "consumed nothing".
- **The front door is necessary and demonstrably not sufficient**, twice over. The
  root run had skt projected and still cost 21,186, because skt's Python source sits
  inside the home and the agent grepped it. The end-to-end worktree run had skt
  installed and cost 9,460, because a 25 kB SKILL.md arrived with it and the agent
  read that instead of running the command. Availability is not use.
- **Tier is classified from path shape**, so a home copied to a scratch path reports
  the tier its *path* implies, not the tier its provenance records. Both fixtures were
  therefore answered as `project`, and the agents were right to say so. Cost is
  unaffected; per-tier *correctness* grading needs fixtures that are real git
  worktrees, which these are not.

## Reproducing

    python3 drive.py --task <id> --prompt-file orient.md \
        --home <tier fixture> --cwd <scratch> --model sonnet --max-turns 30 \
        --cli <checkout>/skill-manager

`project-skills.sh <config-dir> <tier>` sets which units the agent can see. Raw
transcripts are not committed — they carry absolute scratch paths and are large; the
per-run `*.result.json` under `runs/` carries the measurement, the read list, and the
answer.
