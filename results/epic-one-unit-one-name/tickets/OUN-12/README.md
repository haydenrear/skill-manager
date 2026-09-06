# OUN-12 — a home copy that crosses a platform boundary

`GOAL-a-home-survives-being-copied-into-an-image`, property (c) — the copy is
usable on Linux: **no → yes.**

DEF-285, the last of the four findings blocking the meta-harness container.
Delivered on `main` (PR #324).

## Two halves, failing in opposite ways

### Cost — asserted on laptops and nowhere else

The three-tier model was adopted on a measured 3.8%: a 189 MB clone consuming
7.22 MB. That is an APFS side effect of `clonefile(2)`, which the JDK takes for
`Files.copy(…, COPY_ATTRIBUTES)` and does not request on Linux. **Nothing in
the product said so**, so `home.clone.costs.far.less.than.a.copy` had nothing
to compare a measurement against where blocks are not shared, and SKIPPED.

`HomeCopyEconomics` declares what a copy onto a given filesystem costs, read
from `FileStore.type()`. The node no longer skips: it measures, asks what the
product says it should have measured, and fails when they disagree **in either
direction**.

| filesystem | declared | what the node asserts |
| --- | --- | --- |
| macOS + apfs | `SHARES_BLOCKS` | the `COPY_ATTRIBUTES` defence verbatim |
| Linux + ext4, overlay | `FULL_COPY` | the copy cost its full size — and publishes the number |
| Linux + btrfs, XFS, ZFS | `MAY_SHARE_BLOCKS` | correctness only; whether the JDK asks the kernel to reflink is version-dependent |

**Mutation, run rather than asserted:** deleting `COPY_ATTRIBUTES` from
`Fs.copyRecursive` fails the node —
`declared=SHARES_BLOCKS … cloneShared=0.00% … costOfThisCopy=67.11MB` — while
`the_cloned_tree_is_byte_identical_to_its_source` stays **green**. The digest
assertion would have ridden along through the regression; the cost assertion is
the one carrying weight.

If a future JDK starts reflinking on Linux the node fails. That failure is
worth having: it would mean the home model just got cheaper somewhere it was
not before.

### Platform-specific bytes — the copy that cannot run

`pm/` is 203 MB of Mach-O arm64 here and is deliberately **not** in
`SKIPPED_DIRS`. On Linux those bytes are executable by permission bits and
answer `ENOEXEC` in fact, so the home reported `node` as installed and
something else died later with `Exec format error`, far from the copy that
caused it.

Two answers, because they cover different copies:

- **`PmPlatform` stamps** each version directory with the platform that
  provisioned it, and the resolver treats a foreign one as **not installed** —
  so it is re-provisioned rather than executed, which needs neither `node` nor
  `uv` to do. This is the one that matters for an image build, because an image
  build does not call `home clone`.
- **Unstamped trees** — every home that exists today — are judged by the
  binary's magic number. Protecting only homes created after the fix protects
  nobody.
- **`home clone --portable`** leaves `pm/` behind entirely.

`home clone` now prints what the copy cost and what it carries that belongs to
this machine. The finding's sharpest line was that 203 MB travelled with **no
build-time signal at all**.

## Deferred, and said out loud

Restoring block-sharing economics *on* Linux needs `FICLONE`, a native call the
JDK does not expose. On ext4 there is no reflink to ask for at all. So the
honest deliverable here is a **known** cost, not a cheap one — and the
declaration is what makes it checkable rather than folklore.

## Validation

- `jbang RunTests.java` — ALL PASSED, 9 new cases in `CopyCrossesAPlatformTest`.
- `test_graph home-clone` — 16/16, including the new
  `home.copy.carries.no.foreign.binary` with its two controls (an ordinary
  clone still carries `pm/`; a native toolchain in the same home still
  resolves).
- The mutation above, run twice — red with the flag deleted, green with it
  restored.
