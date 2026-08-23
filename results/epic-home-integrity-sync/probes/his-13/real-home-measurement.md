# The measurements that are not fixtures

Run 2026-08-23 on `feature/159-his-13`, rebased onto epic tip `576fa0b`, with
the checkout's own CLI (`./skill-manager`, never the shim on PATH — DEF-006 /
DEF-068) and all four home variables pinned at this worktree.

**This file was rewritten after the review of PR #244. Its original headline was
a FALSE POSITIVE, and that is recorded below rather than deleted.**

---

## WITHDRAWN — "a real finding on this worktree's own home"

The first version led with:

> `home repair --home <wt>/.skill-manager`
> `✗ PRUNED_INHERITED_ENTRY bin/cli/tofu — is declared here, is gone, and the
> parent store /Users/hayde/.skill-manager this home records still holds it`
>
> …which answers §8's own "unsure #3" — the shape fires in the wild, on a home
> nobody constructed to make it.

**It was not a real finding.** The worktree home is a LAZY clone: it declares
`bin/cli/tofu` and deferred building it, exactly as every home this program
clones defers everything under the trees it skips. The artifact was never there
and was never pruned. The reviewer measured the same thing on a stock
`home clone` — a freshly created, untouched, healthy home reported as damaged —
and the verdict depended on which binaries happened to exist in the operator's
root store.

I presented it as proof of real-world value. It was proof of a false positive,
and it was 1 of the 5 findings in this file's own `cp -a` headline.

The check now also requires `lazy_artifacts = false` (production's own rule from
`HomeCloner.partitionDeclared`, read in the accusing direction). Both homes are
now reported clean:

```
home verify --home <fresh clone>   exit 0
home repair --home <fresh clone>   exit 0    (was: exit 1, PRUNED_INHERITED_ENTRY)
home repair --home <wt>/.skill-manager   exit 0
```

DEF-073 records what that costs: no record on disk distinguishes "was here and
was pruned" from "was declared and never built", so HIS-9's measured prune
residue is undetectable on a lazy home, and a later "improvement" that drops the
policy condition will reintroduce this exact false positive.

---

## STANDS — a `cp -a` copy of a real home

Literally the case `HomeCloner.sanctionedParentShim`'s javadoc defers to HIS-13:
*"a byte copy taken with `cp -a`, an rsync, a clone by an older skill-manager …
repairing such a home so it stands on its own record is HIS-13's."*

```
$ cp -a <wt>/.skill-manager  <probe>/h/.skill-manager
$ cp -a <wt>/.claude         <probe>/h/.claude

home verify --home <copy>      exit 0    "every reference resolves, and no path in it
                                          reaches any other Skill Manager home"
home repair --home <copy>      exit 1    4 findings, all MISANCHORED_AGENT_LINK —
                                          the copy's .claude/skills/* still resolve
                                          into the SOURCE home's store
home repair … --fix            exit 0    repaired 4 of 4
home repair … --fix (again)    exit 0    repaired 0 of 0, nothing changed
home repair --home <copy>      exit 0    a separate process, and it agrees
```

Re-measured after the blocker-1 fix: **four findings, not the five originally
reported.** The fifth was `tofu`.

Exit 0 and exit 1 about one home a minute apart is the half of DEF-070 that
survives review as separation rather than conflict: `home verify` walks the
STORE, and these four are on the home's other axis. The half that WAS a
conflict — three readers giving three answers about `bin/cli/tofu` — is gone.

---

## STANDS, and it replaces the withdrawn headline — real damage, found unprompted

The FIXED command, on the same worktree home, reported two findings I had not
planted and did not expect:

```
MISANCHORED_AGENT_LINK .codex/skills/one-forty-five
  -> resolves into the store at /private/var/.../sm-145-<n>/operator/.skill-manager
MISANCHORED_AGENT_LINK .gemini/skills/one-forty-five   -> same
```

The writer is **`AgentProjectionFollowsHomeTest`** — the #145 regression test,
whose subject is that an operation on one home must not write another home's
agent directories. Reproduced deterministically: with all five variables pinned
at a scratch directory, running that test alone leaves a dangling projection in
whatever `CODEX_HOME` / `GEMINI_HOME` name. The temp `operator` home it points
into is deleted when the run ends.

**Scope, measured rather than assumed.** My first draft of DEF-074 said this hits
the operator's real `~/.codex` in a normal environment. It does not: a full
`jbang RunTests.java` with all five variables UNSET wrote nothing into
`~/.codex`, `~/.gemini` or `~/.claude` — checked immediately, no
`one-forty-five` link and no link into any temp directory. The clause was
withdrawn before the entry shipped. What is true is narrower and more pointed:
the leak follows an **explicitly set** `CODEX_HOME` / `GEMINI_HOME`, which is
exactly what this epic's containment protocol tells every agent to set. Filed as
**DEF-074**.

This is what the withdrawn headline was reaching for, and unlike the withdrawn
headline it is real: a defect in a home, on the axis this epic exists for, that
no other instrument in the repository could see.

---

## Containment

Every probe directory (`<wt>/probe/`) was deleted after use, and the disk was
checked before and after. The operator's `~/.skill-manager`, `~/.claude`,
`~/.codex`, `~/.gemini` and the project home were checked for writes after every
run and after the clean-environment suite: no mtime inside the window, and every
link under the three agent directories still resolves into
`~/.skill-manager/skills`. The two leaked `one-forty-five` links in THIS
worktree's home were removed by hand, which is what the finding's own remedy
line says to do.
