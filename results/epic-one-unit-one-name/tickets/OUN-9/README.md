# OUN-9 — a copy of a home is not a copy of its login

`GOAL-a-home-survives-being-copied-into-an-image`, property (a): **no → yes.**

DEF-282, filed externally and **verified here**: `auth.token` was byte-identical
in all three tiers, mode 0600, keys `access_token` / `refresh_token` /
`expires_at`. Issue #281 had carried it as an *unverified inference* since it
was filed, because the reasoning ran from the absence of an exclusion rather
than from cloning a home and looking.

Delivered on `main` (PR #320), not into the epic branch — a home is about to be
baked into a container image and an epic branch reaches main only at
finalization.

## What changed

`HomeCloner.CREDENTIAL_ROOT_FILES` — one file, `auth.token`, skipped from every
copy. `credentialsNotCopied(source)` reads the **source**, so the clone can say
what it dropped rather than the copy having to notice something is absent.

## Why the disclosure is half the ticket

A credential that silently fails to arrive is a confusing "not logged in"
later. Omission and disclosure are different fixes and only one of them was
asked for, so `home clone` now prints the file, the reason, and
`skill-manager login` as the way to get it back.

## Controls

Three, in the graph node `home.clone.carries.no.credential`:

- **the source keeps its own** — this drops a copy, it does not log the operator
  out. A node checking only the copy would pass against an implementation that
  deleted the original.
- **an ordinary root file still travels** — otherwise "no `auth.token` in the
  copy" is also what a clone that stopped copying root files would produce.
- **the clone SAYS so** — the operator-visible half, asserted on the real
  command's output.
