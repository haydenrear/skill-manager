# Confirmed tonight: #261–#264, one falsified claim, one new hypothesis

Worked against scratch fixtures only, on the owner's instruction, after two
live-home incidents earlier the same evening. Nothing here changed a real home.

## The single sentence

**Three of these four issues are the same defect: a unit or a shim bakes in an
absolute path at install time, and skill-manager has no say in it.** That is why
they cannot be fixed one repository at a time, and why they belong in the
effects epic rather than in a patch.

---

## #262 — confirmed, live, on this machine

The shim body is written by **the unit's own install script**, not by
skill-manager:

```bash
# spec-double-compiler/skill-scripts/install-tla-spec-dev.sh
ENTRYPOINT="$SKILL_DIR/scripts/tla_spec_dev.py"
cat > "$WRAPPER" <<SH
exec python3 "$ENTRYPOINT" "\$@"        # $SKILL_DIR frozen at install time
SH
```

Measured on the project home, unchanged by anything tonight:

```
<project>/.skill-manager/bin/cli/tla-spec-dev
  -> SYMLINK -> /Users/hayde/.skill-manager/bin/cli/tla-spec-dev
  -> body:  exec python3 "/Users/hayde/.skill-manager/skills/spec-double-compiler/scripts/tla_spec_dev.py"
```

So an edit to `spec-double-compiler` **in the project home** is not what
`tla-spec-dev` runs. The issue's own framing is the important part: *the
verification step is the thing that is broken, so more careful verification
produces more confident wrong conclusions.*

**Two candidate fixes, and they are not equivalent.**

- *In the unit* — each skill-script author rewrites their wrapper to derive the
  home from `BASH_SOURCE`. One unit at a time, forever, and only for units we
  control.
- *In the installer* — `SkillScriptBackend` rewrites or wraps what a
  skill-script emits, so every unit gets home-relative resolution whether its
  author considered it or not. One change, all units, third-party included.

The second is right and it is an **installer contract change**, not a patch.

**It must not become "always this home".** A child home with no copy of the unit
*should* run the parent's — that is the sanctioned parent-store sharing
`home verify` already reports as by-design. The rule is *prefer this home's
copy, fall back to the pinned one*.

## #261 — same class, opposite direction

A unit cannot say "this tracked path is not part of the installed artifact", so
`git clone` brings the whole archive. Verified exactly: **12.3 GB across 174
`specs/.history` directories, 18,759 tracked files in the installed copy**, and
install is a bare `git clone --quiet` — no `--depth`, no `--filter`, no
sparse-checkout. The only `exclude` machinery in the tree is merge-algebra.

`#262` is *the unit declaring a path skill-manager should have overridden*;
`#261` is *the unit unable to declare a path skill-manager should have
skipped*. Same missing contract, both directions.

**The upstream archive fix (`902cfd7`) does not touch this**, and says so
itself: *"Stop archiving build scaffolding, and measure why that is all this can
fix."* Synced to both tiers; re-measured after: **12.3 GB, unchanged.**

## #263 — confirmed independently, with two measurements to add

Hit at the start of this session while building the disclosure eval, before the
issue was read.

- **Auth is per-`CLAUDE_CONFIG_DIR`.** A fresh config dir reports
  `{"loggedIn": false, "authMethod": "none"}` while the default reports
  `true` / `claude.ai`. Seeding it with the account stanza from `.claude.json`
  is **not** sufficient — still "Not logged in".
- **Overriding `HOME` breaks it a second way.** macOS resolves the login
  keychain through `$HOME`, so a sandbox that redirects `HOME` cuts the child
  off from the OAuth credential even when the config dir is right. Symlinking
  `Library/Keychains` alone restores it — narrowly, never all of `~/Library`.
- **`--bare` is not a workaround.** It states plainly that auth is *strictly*
  `ANTHROPIC_API_KEY` or `apiKeyHelper`; OAuth and keychain are never read.

What worked here is what the issue landed on independently: one interactive
`claude auth login` per config dir. Its warning deserves repeating — **never
change the credential path of a session that is currently running**; undoing it
requires the API call it just broke.

## #264 — confirmed independently

Hit in this session's first bootstrap: five `FOREIGN_HOME` refusals, resolved
only by `SKILL_MANAGER_CLI=<checkout>/skill-manager`. The operator's own memory
note already records the rule — *drive scratch homes with the raw build, not a
home's `bin/cli` pin*. The issue's diagnosis is right: **a non-zero exit from a
shim is not evidence about its version**, and `bootstrap-home.sh` reads a
cross-home refusal as "too old".

---

## A claim of mine that is WRONG, withdrawn here

I reported that **`skt sync` moves the store without updating the installed
record**, from an observation on the project home (record `436c78c5`, store
`902cfd7f`, both tiers).

**It does not reproduce.** Built a local unit repo with two commits and a
scratch home, and tested both shapes:

```
store at v1, remote at v2, sync --git-latest   ->  record v2, store v2   AGREE
store already at v2, record forced to v1       ->  record v2, store v2   AGREE
```

`skt sync` is a thin wrapper over `skill-manager sync <unit> --git-latest`, and
that path reconciles the record in both shapes. **The attribution was wrong and
the fix is withdrawn before it was written.**

## What the observation might actually have been — hypothesis, untested

Between the `skt sync` and the check, the child-home pass ran — the same pass
that silently deleted the skt plugin. `writeChildRecord` writes the **parent's**
record into the child for any unit not held back. The root home still held
`436c78c5` at that moment.

So the record may have been **overwritten backwards from the parent**, not
failed to advance. That is a different defect in the same family as the deletion
already fixed, and it is recorded as a hypothesis with no test behind it.
**Whoever picks it up should reproduce it before believing it** — that is the
second hypothesis of mine this evening that a fixture refuted.
