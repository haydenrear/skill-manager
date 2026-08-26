# Why the agent read everything, and what actually fixed it

The owner's question, 2026-08-26: *"So the agent reads the entire skt? Why does it
do this? Did you check to see why?"*

I had not. I had established *that* it read `skill-manager/SKILL.md` and never ran
`skt status`. Checking *why* produced two causes, and **the larger one was mine.**

## Cause 1 — my own prompt forbade the cheap path

The orientation prompt said:

> For every answer, cite a file path you actually read and quote one sentence from
> it verbatim.

`skt status`'s output is not a file path with a quoted sentence. **The instruction
that was supposed to catch lucky guesses also made the expensive behaviour
mandatory.** Every "reads too much" number in the first round is partly an artifact
of that sentence.

Rewritten to accept either evidence — *"either a file path you read, or the exact
command you ran and the line of its output that says it. Both count equally"* — the
same tier, same corpus, same model went **9,460 → 794 tokens**.

## Cause 2 — the run-to-run variance is larger than the effect I was reporting

Two runs, identical fixture, identical prompt, identical model: **794** and
**10,294**. Nothing differed but the sampling.

That invalidates every single-run comparison on this page's first version,
including the 823 I had presented as evidence the front door worked. **n=1 cannot
measure a 13× claim when n=1's own spread is 13×.** Everything below is medians of
three.

## Cause 3, the real one — `skt status` answered one question in four

With the prompt fixed and repeats in place, the product defect is visible on its
own. A session starts with four questions. `skt status` answered the first:

| question | old `skt status` |
| --- | --- |
| which tier am I? | **yes** — `tier: project` |
| what is this home a copy of? | no |
| how does my edit reach the tier above, and its own repo? | no |
| what must I never write? | no |

`skt publish` already knew all three missing answers. It just never said them
anywhere an agent would look *before* it had a reason to run publish. So status now
asks `publish._parent_home` — **the same resolver, not a second spelling of it** —
and prints three lines:

    parent     <parent> — this home was cloned from it and syncs back to it
    publish    edited a unit here? `skt publish <unit>` — syncs it to the parent
               above, then publishes to the unit's own git repo. Nothing else
               carries an edit out of this home; git does not.
    writes     this session writes <home>. Never write <parent>, or any other
               home, by hand.

241 → 362 tokens. Three states, and the difference between them is load-bearing: a
parent exists; the root tier, where nothing is above **by design**; or unresolvable,
which must *also* say publish will refuse — an agent told only "unknown parent"
helpfully hand-copies the unit into another home, which is the damage shape the line
exists to prevent.

The eval then found a gap in that fix within the hour. `_parent_home` derives the
parent from **path shape**, so in a sandbox with a redirected HOME it printed
`parent UNRESOLVED` directly above a `home.provenance.json` that named the parent.
`home clone` wrote that record; nothing read it — the same write-once-read-never
shape the change exists to remove. Status now falls back to the descent record and
says where the answer came from.

## The A/B, three runs per arm

One variable: `src/skt/status.py` and `skills/skt/SKILL.md`. Same home, same prompt,
same model, same projected skills.

| arm | corpus tokens | median | within 2,000 | median $ | median wall |
| --- | --- | --- | --- | --- | --- |
| old skt | 332 · 8,351 · 15,470 | **8,351** | **1 of 3** | $0.48 | 137 s |
| new skt | 0 · 514 · 1,142 | **514** | **3 of 3** | $0.15 | 31 s |

**16.2× on the median, and the number that matters more is 1-of-3 → 3-of-3.** The
old arm's spread is the point: sometimes an agent stumbles onto the answer cheaply
and sometimes it reads for fifteen thousand tokens, and which one you get is luck.
The new arm removes the luck.

The best run cost **zero corpus tokens, one turn, 15 seconds, $0.07** — and answered
all four questions correctly, citing the SessionStart hook's own `skt status` output
for every one. The hook already ran it; the answers were in the session preamble
before the agent did anything.

## What is still not claimed

- **n=3 per arm.** The medians are 16× apart and the budget outcome is 1/3 vs 3/3,
  but three samples of a distribution this wide is enough to act on, not enough to
  quote a precise multiple.
- **One model** (Sonnet), one task, one tier.
- **The root tier was not re-measured** after the fix. Its 21,186 was taken under
  the old prompt, and its distinct problem — skt's Python source sitting inside the
  home, inviting a grep — is untouched by this change.
- **The fixture caught me twice.** The first A/B was invalid because a copied home's
  `bin/cli/skt` shim embeds an absolute path, so both arms ran the old skt; that is
  a property of `cp`, not of the product — `home clone` re-anchors shims, and it was
  verified doing so. Recorded because the wrong conclusion was one unchecked
  assumption away, twice.
