# OUN-10 — a shim resolves the home it is standing in

`GOAL-a-home-survives-being-copied-into-an-image`, property (b): **no → yes.**

DEF-283. Six `bin/cli` wrappers baked an absolute home path, `tla-spec-dev`
among them. `home repair` reported 0 findings, correctly by its own rule —
which asks "does this reach ANOTHER home", and an own-home absolute path does
not. It goes wrong only later, in a home that does not exist yet.

Delivered on `main` (PR #323).

## The measurement that framed it

`home clone` re-anchors these; `cp -R` — what an image build does — does not.
So every assertion in `ShimSurvivesACopyTest` is made on a **copied** home,
which is the ticket's constraint rather than a stylistic choice.

## What changed

`ShimHomeContract.selfDerivingRewrite` rewrites a frozen shim to derive its own
home from `BASH_SOURCE`, preserving the path INSIDE the home — it relocates the
shim, it does not repoint it. `SkillScriptBackend.reportFrozenShims` now
rewrites where it can and warns where it cannot, preserving mtime.

The original warning was right to exist and right not to fail the install: the
unit's own installer writes those bytes. But **skill-manager owns `bin/`**, so
it can fix the file afterwards.

## Narrow on purpose

Only text shell scripts are rewritten. A compiled launcher or a Python console
script is refused and still reported — a shebang is resolved literally by the
kernel and cannot be tokenized the way a stored path can.

## Two defects its own tests caught, both mine

- `shebang.contains("sh")` matched the temp directory `shim-home-1234`, so a
  Python console script was accepted as shell. It reads the interpreter's
  **basename** now.
- The `RunTests.java` registration silently did nothing — anchored on a string
  that exists on the epic branch and not on `main`. **ALL PASSED, with the new
  suite never running.** The registration asserts its anchor now, and that
  assertion has since fired for real on OUN-5.
