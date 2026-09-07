# skill-harness — evals that load every unit

Built to `references/plugin_evals.md` in spec-double-compiler. Read that first.

## Why every case loads every unit

The thing under test is **retrieval among all the skills**, not capability given
the right one. Loading only the two units a task needs does the retrieval for
the agent and overfits progressive disclosure; the score then says nothing about
a real session, where all of them are present.

One thin wrapper per unit, each with the skill **symlinked inside** (containment
stops at the entry, so this reads the live units out of the project home with no
copies and no drift).

## Running

```bash
EVALHOME=...           # see plugin_evals.md "The home" — needs Library/Keychains
HOME=$EVALHOME CLAUDE_CODE_WALNUT_SPIRE=1 \
  claude plugin eval . --case 'epic-*' --ablation none --runs 1 \
      --keep-temp --max-cost-usd 2 --allow-tools Bash Read Write Edit Skill
```

`--case` is **not optional**: case discovery recurses into the symlinked units,
and spec-double-compiler ships its own `examples/agent_integration/eval-plugin`,
whose cases otherwise run as part of this suite.

## What four runs of one case cost, and what each drop was

| run | Bash calls | the fix |
|---|---:|---|
| 1 | 14 | no toolchain hook — 9 calls hunting for `git` |
| 2 | 10 | hook reported presence (`command -v`, `-x`), not function |
| 3 | 9 | hook executed candidates — but in ITS environment, not the agent's sandbox, where `/usr/bin/git` is an xcrun shim that fails |
| 4 | 8 | hook stops deciding: reports every candidate as a fallback chain |

**Every reduction so far was a harness defect, not a skill defect.** That is the
calibration warning for anyone reading a first red here: it is more likely mine
than the skill's, and only `trace.jsonl` tells them apart.

## The finding that is not the harness

Run 1 had no `skt` on PATH. The agent read `bootstrap-home.sh` and replayed its
steps by hand. Run 2 had it, and that collapsed to one `skt ticket new`.

**A front door on PATH or it does not exist** — measured, not argued.

## Still missing

The workspace is a bare repo with no Skill Manager home, so runs 3-8 of the
remaining calls are the agent looking for something to operate on. The case
needs a FIXTURE placed by a hook (plugin_evals.md §3): an epic checkout on an
epic branch with a project home. Until then this measures improvisation in an
under-specified workspace as much as it measures the skill.
