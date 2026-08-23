# The sweep: every private "does anything here name another home" in `test_graph/sources/`

**HIS-17 / #238, slice item 3.** The issue asked for the fifth copy of a rule
production already owns. This is the whole list, **including the entries where
nothing changed** — the list is the deliverable, and "we looked and there were
none" is a different statement from "we did not look", which is what let the
second, third and fourth copies survive `a4a95cb`.

**CORRECTED after review of #242, which falsified the enumeration.** The first
version listed eleven and missed at least four — including a fourth scan *in a
file three of whose scans it did list*. The **conclusion survives** (the only two
byte-reading cross-home scans are the two that were fixed), but a document whose
opening claim is the "looked / did not look" distinction has to have the list
right, so the misses are listed below **as misses**, not folded in silently.

Method: `Files.walk` / `walkFileTree` / `readSymbolicLink` / `contains(needle` /
`FOREIGN` / `leak` / `referencesTo` / `filesNaming` / `CONTENT|PROVISIONED|STATE`
across every `.java`, `.py` and `.sh` under `test_graph/sources/`, then each hit
read for what it actually asks. 29 graphs, 24 of which carry
`common/HomeFixpointLaw.java`.

**Production owns the question** in `HomeCloner.verifyRoots` (`src/main/java/
dev/skillmanager/store/HomeCloner.java`), surfaced as
`skill-manager home verify [--home X] [--against Y]`, with `classify` at line
2332 and the HIS-10 descent exemption at lines 1813–1814.

## The tripwire

Every `home clone` writes `<dst>/home.provenance.json` unconditionally
(`HomeCloner.java:587` → `HomeProvenance.recordDescent(src, dst)`), and that
record names the source home by absolute path. **Any private scan that (a)
walks a tree that is a clone and (b) searches for the source home's path will
hit it.** That is the trip condition, and it is the only one that matters for
this class.

## The eleven live instances, plus the two already known

| # | where | what it asks | graphs | cross-checks production? | trips on the descent record? | disposition |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `home-clone/HomeCloneSupport.referencesTo` + `surfaceOf`, via `HomeClonedIntoProject.java:62` | full-tree byte + symlink walk of a CLONE for the SOURCE home's path, with a hand-rolled `HomeCloner.classify` | **home-clone, checkout-home** | **was: no** | **YES — was red** | **FIXED.** Descent record reclassified `DESCENT`, and the node now requires production's isolation verdict in both directions |
| 2 | the same helper, via `HomeCloneFixtureBuilt.java:242` | the FIXTURE home naming its own path — inverted polarity, it *requires* hits | home-clone, checkout-home | no | no — the fixture is built by `install`, never cloned, so it holds no record | **no change.** Reads "at least one of each surface"; an extra hit cannot fail it |
| 3 | `child-home/ChildHomeSupport.storeLinksBelow` (221–252) | symlinks under a child unit resolving into the PARENT store | project-child-home | not in the node | no — symlink targets only | **no change**, see "why 3–5 stay" below |
| 4 | `project/ProjectDependenciesResolved` — private copy of the same, `storeLinksBelow` (533–580) | ditto, over four parent unit roots | project-resolve, project-smoke | not in the node | no | **no change** |
| 5 | `smoke/harness/HarnessChildHomeMaterialized` — third copy, `storeLinksBelow` (337) | ditto, over a harness child home | harness-smoke | not in the node | no | **no change** |
| 6 | `home-integrity/HomeIntegrity.projectionSourceIsDecidable` (743–793) | every recorded projection target lands in this home or a REGISTERED child | home-integrity | no | no — record-driven, never enumerates the file | **no change** |
| 7 | `onboarding/OnboardingSupport.ledgerTargetsOutside` / `childHomesOutside` / `foreignBindings` | ledger targets and `child-home.json` naming another checkout | onboarding | same graph, other nodes (`OnboardingRemediesAreRunnable:144,174` run bare and `--against`) | no — scoped to two record families, though the home IS a clone | **no change** |
| 8 | `home-sync/HomeSyncRootToProject.java:90–98` | the ROOT home's path inside three named `.materialization` files | home-sync | not in the node | no — scoped to three files, though the home IS a clone | **no change** |
| 9 | `home-clone/HomeCloneDescriptorResolves.java:64` | both home paths, inside one file (`home.runtime.json`) | home-clone | no (runs `home describe`) | no | **no change** |
| 10 | `checkout-home/CheckoutHomeProvisioned.java:119` | this home's own store path inside one file (`bin/cli/skill-manager`) | checkout-home | no | no | **no change** |
| 11 | `ticket-lifecycle/TicketLifecycleSupport.filesNaming` (477–506) | full-tree walk of two worktree homes for the project and root paths | ticket-lifecycle | **yes** — `a4a95cb` added `home verify` exit 0 | exempted at depth 1 since `a4a95cb` | already fixed; the model this ticket generalised |

### Why 3–5 stay, and what that costs

They are one walk written three times, which is the same smell — but they are
**not** this defect, and the honest disposition is to say so rather than to
touch three graphs on a hunch:

* They read **symlink targets**, never file bytes, so no record naming a home
  can trip them.
* The boundary they test is *"resolves into the parent store"*, which is
  precisely what production's bare `home verify` answers (`FOREIGN_HOME` /
  `sanctionedParentShim`) — and **all three graphs carry `HomeFixpointLaw`**,
  which runs `home verify --home <h>` over every home the graph produced and
  re-runs the printed remedy. So the cross-check exists at graph level.

**The residual risk, named:** `HomeFixpointLaw` passes no `--against`, so the
SOURCE-REFERENCE half of production's check is never run by it — production
prints `NOT CHECKED` for exactly that half. A clone-specific contract change
therefore still cannot be caught by the law alone. That is why the two nodes
this ticket touched cross-check with `--against` explicitly, and why
`artifact-dag`'s node, which clones a home on every run and was asking the
weaker question, now passes `--against` too. Folding `--against` into
`HomeFixpointLaw` would need a source home per candidate, which the law does
not have — recorded as **DEF-064**.

### The four the first sweep MISSED, found by review of #242

Listed apart from the table so the miss is legible rather than absorbed. None of
them changes the conclusion — every one is target- or containment-based, none
reads file bytes for a home path — but three of them are *containment walks*,
which is the same family as instances 3–5, and one of them lives in the file
instance 7 already cites.

| # | where | what it asks | graph | trips on the descent record? | disposition |
| --- | --- | --- | --- | --- | --- |
| 12 | **`OnboardingSupport.escapingLinks` (`:461-485`)**, live at `OnboardingProjectionsMaterialized.java:233` | `Files.walk(dir, 4)` over the three agent dirs, reporting every symlink whose target escapes the agent root | onboarding | no — targets only | no change. **The worst miss: three of the four scans in this file were enumerated as instance 7 and this one was not.** A `Files.walk` in a file already being read |
| 13 | `OnboardingSupport.servable` (`:427-435`) | `toRealPath().startsWith(agentRoot.toRealPath())` per projected unit | onboarding | no | no change — one link at a time, containment not enumeration |
| 14 | `ProjectProfilesResolved.realPathInside` / `realPathInsideAny` (`:271-296`), used at `:135-136` | is this path inside any of the parent store's four unit roots | project-resolve, project-smoke | no | no change — the same `toRealPath` prefix compare as 3–5 |
| 15 | `HomeCloneEditStaysInClone.realPathStartsWith` (`:191-196`), used at `:136-138` | does the clone's agent link resolve inside the clone and **not** inside the source | home-clone | no | no change — two links, and it already asserts the negative direction |

**And one adjacent gap the sweep should have named and did not.** Nothing in
this document says *which assertion fires the day a verb starts writing a new
root-level record.* `HomeSyncPermutations.java:141-143` is the closest thing:

```java
List<String> dryWrote = HomeSyncSupport.wroteNothingBut(destBeforeDry,
        HomeSyncSupport.entryDigests(dest), Set.of());
```

An **empty allow-list over a whole home root** — "`--dry-run` writes nothing",
the strictest root-digest assertion in the tree. It is not a cross-home scan, so
it is not an instance; but it *is* the thing that would redden first if a new
root-level record appeared, and HIS-10's `home.provenance.json` is exactly that
kind of record. A reader looking for "who notices a new root file" would not
have found it from the first version of this page.

### Near-misses, checked and excluded

Listed so the next reader does not re-derive them: tree digests and inventories
(`treeDigest`, `inventory`, `homeDigest`, `snapshot`, `entryDigests`) compare
bytes with no needle; `tripwire/TripwireSupport` deliberately never filters on
target content; `HomeCloneWorksWithSourceRenamed:116–130`,
`OnboardingCloneIsHonest:135–175` and `HomeCloneToolchainsReprovisionable:109`
scan COMMAND OUTPUT rather than a tree; `sandbox/SandboxEnvContract` greps this
repository's own sources; `CheckoutHomeLaunchIsolated:119` and
`OnboardingLaunchEnv` ask the same question of `PATH` entries, not of a
filesystem tree; `home-integrity/ReadersAgreeAboutOneClone` and
`onboarding/OnboardingRemediesAreRunnable` are pure production consumers already.
`lib/extentprobe.py` and `ticket-lifecycle/lockprobe.py` are the only non-Java
node sources and neither asks this question.

### A second divergence found while sweeping, not fixed

`HomeCloneSupport.surfaceOf` mirrors `HomeCloner.classify` but omits its
`PROVISIONED_SEGMENTS` (`.venv`, `node_modules`, `site-packages`,
`__pycache__`, …). A provisioned subtree under `skills/` therefore classifies
`CONTENT` in the graph and `PROVISIONED` in production. Today the fixture has no
such path, so nothing is wrong and nothing is asserted; it is a live way for the
two to disagree. **DEF-063.**
