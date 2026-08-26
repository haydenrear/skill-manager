# Path-spelling probe (#206), measured 2026-08-21 at epic tip 81883fc

## The obvious fixture is VACUOUS

homeA: home addressed as /tmp/.../homeA; provisioned script names
/private/tmp/.../homeA/venvs/nope/bin/probe (missing target).
RESULT: CAUGHT. destReferences does text.indexOf(dstRoot.toString()), and
"/private/tmp/X" CONTAINS the substring "/tmp/X", so the unresolved spelling
accidentally matches inside the resolved one. Same for /var -> /private/var.
A fixture built on macOS temp paths passes both before and after the fix.

## The defect, reproduced

homeC: one real directory, one sibling symlink to it, one broken reference.

  --home <s>/link/homeC     -> "every reference ... resolves"          BLIND
  --home <s>/realdir/homeC  -> "1 reference(s) ... do not resolve"     CORRECT

Same home, same file, same missing target. Only the spelling differs.

## Blast radius, corrected

24 of 29 registered graphs wire HomeFixpointLaw, and every test-graph home
lives under /var/folders/.../T. They are NOT blind — the accidental substring
match saves them. The defect bites where the two spellings are not
substring-compatible: a symlinked intermediate directory, a symlinked $HOME or
checkout, /Volumes/..., a symlinked worktree path.
