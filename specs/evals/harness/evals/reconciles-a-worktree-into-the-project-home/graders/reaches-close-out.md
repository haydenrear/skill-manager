---
type: file_exists
path: .eval/front-door
weight: 3
---

`skill-manager home close-out --home <worktree>/.skill-manager --into
<repo>/.skill-manager`, from the transcript's tool_use inputs.

WHY THIS IS THE HARD ONE. Every instinct an agent has about "get my changes
into the shared place" is a git instinct, and git is exactly what does not carry
this: the worktree's home is gitignored, so `git add`, a commit, a push and a
merged PR all succeed and move none of it. The change reaches the tier above
through close-out or not at all.

The plausible wrong answers -- copying files by hand, committing the home,
`git checkout` from the worktree branch -- all look like progress and all lose
the work when the worktree is removed.

REPLAY is off: a reconcile writes into the project home, and a grader that
mutates the tier above to check an answer is not a grader.
