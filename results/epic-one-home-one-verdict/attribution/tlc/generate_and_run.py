#!/usr/bin/env python3.12
"""Generate HomeVerdictsInternal cfgs, run TLC on a scratch copy, record results."""
import json, re, shutil, subprocess, sys, time
from pathlib import Path

WT = Path("/Users/hayde/IdeaProjects/wt-ohv-tla")
MODEL = WT / "specs/program_model"
SCRATCH = Path("/private/tmp/claude-501/-Users-hayde-IdeaProjects-skill-manager/9972f0f0-9e78-40bb-8155-34bfc5133ca5/scratchpad/hv-tlc")
OUT = WT / "results/epic-one-home-one-verdict/attribution/tlc"
TLC2 = "/Users/hayde/.skill-manager/bin/cli/tlc2"

HEALTHY = {
    "VerifyComposition": "COMPOSES_REPAIR",
    "DetectorReach": "AGENT_LINKS_AND_RECORDS",
    "DetectorSpellings": "ALL_ALIASES",
    "VerdictStamp": "STAMPED",
    "ShimDetection": "CONTENT",
    "ShimRewriteScope": "EVERY_LINE",
    "PruneRerecord": "EXCLUDE_PROVEN_GONE",
    "TeardownReaping": "REAP_PROVEN_ABSENT",
    "RecordVersionPolicy": "RESTATE_AT_HEAD",
    "MarketplaceMatch": "IDENTITY_AND_PATH",
    "ManifestIdentity": "DERIVED",
    "MarketplaceRepair": "REPORT_AND_FIX",
    "BinWritePolicy": "DETACH_FOREIGN_LINKS",
    "DetachRestore": "RESTORE",
    "ForeignWriteCheck": "STAT_AND_REFUSE",
    "LauncherWrite": "UNLINK_THEN_WRITE",
}

ALL_INV = [
    "TypeOK",
    "EveryPlantedFactIsFoundByRepair",
    "VerifyFailsOnEveryCountedRepairFinding",
    "VerifyPassesWhenRepairCountsNothing",
    "AVerdictNamesTheBuildThatProducedIt",
    "AShimSpellingItsOwnHomeIsReported",
    "ACommentOnlySpellingIsNotAFinding",
    "AFreshlyWrittenShimNamesNoHome",
    "ARepairedShimNamesNoHome",
    "ACopyOfASettledHomeRunsItsOwnShims",
    "PruneStaysPruned",
    "RowsProvenAbsentAtRemovalAreReaped",
    "ALinkStillOnDiskKeepsItsRow",
    "ASyncedRecordAgreesWithItsCheckout",
    "ASyncRegistersUnderItsOwnIdentity",
    "AfterRepairAndSyncNothingForeignIsEnabled",
    "AnOwnEntryWriteStaysInItsHome",
    "TheChildOwnsTheShimItInstalled",
    "AWriteThroughADetachedLinkIsRefused",
    "ALinkTheInstallerDidNotReplaceIsPutBack",
    "AGeneratedLauncherIsWrittenIntoItsOwnHome",
]

# name -> (header, overrides, target(s) expected, excluded invariants, kind)
CFGS = {
    "HomeVerdictsInternal": dict(
        header="""The delivered behaviour of one-home-one-verdict (#337). Every invariant holds.
Expected outcome: "No error has been found".""",
        over={}, expect=None, exclude=[]),

    "HomeVerdictsInternal_regression_verifyownwalk": dict(
        header="""DEF-OHV-002 / #339, the headline defect. `home verify` runs only its own
isolation walk and never HomeRepair.detect: exit 0 on a home `home repair`
reports damaged. Kickoff: 50 of 61 homes. Origin: 9a547b20 (#130, verify as
HomeCloner.verify) and f95a0f98 (#244, HomeRepair added as a second walk and
never composed). Fixed by OHV-2, #364 merged 48d23c51.
Expected outcome: VerifyFailsOnEveryCountedRepairFinding FAILS.""",
        over={"VerifyComposition": "OWN_WALK_ONLY"},
        expect="VerifyFailsOnEveryCountedRepairFinding", exclude=[]),

    "HomeVerdictsInternal_regression_blinddetectors": dict(
        header="""DEF-OHV-002, the detector half. No DANGLING_AGENT_LINK and no
ORPHANED_PROJECTION_RECORD kind: this repository's project home held 3 dangling
agent links and 1 orphaned projection record while verify AND repair both said
0 -- the two readers agreeing, blind. Never built before OHV-2 (git log -S
returns nothing on main); the repair walk dates from f95a0f98 (#244).
Fixed by OHV-2, #364 merged 48d23c51.
Expected outcome: EveryPlantedFactIsFoundByRepair FAILS.""",
        over={"DetectorReach": "STORE_ONLY"},
        expect="EveryPlantedFactIsFoundByRepair", exclude=[]),

    "HomeVerdictsInternal_regression_aliasspelling": dict(
        header="""#343. Given a home as /private/var/..., HomeCloner.rootSpellings and
ShimHomeContract.rootSpellings returned {given, real} -- the same string twice --
so a /var/... spelling was never scanned. Origin: c0aae68e (#228) and 43e5fb99
(#323). Fixed by OHV-5 (PathSpellings), #362 merged f38a9298. macOS only; no
graph node plants it (DEF-OHV-185).
Expected outcome: EveryPlantedFactIsFoundByRepair FAILS.""",
        over={"DetectorSpellings": "GIVEN_AND_REAL"},
        expect="EveryPlantedFactIsFoundByRepair", exclude=[]),

    "HomeVerdictsInternal_regression_noexemption": dict(
        header="""NOT A SHIPPED DEFECT: the fix gone too far, caught inside OHV-2 by
ChildHomeShimIsolationTest ("a COPY of a sanctioned child inherits the
sanction") going red. Composing repair without the parent-store-shim
exemption fails verify on every ticket-worktree clone. Kept so the exemption
cannot be removed as "an inconsistency" without this going red.
Expected outcome: VerifyPassesWhenRepairCountsNothing FAILS.""",
        over={"VerifyComposition": "COMPOSES_WITHOUT_EXEMPTION"},
        expect="VerifyPassesWhenRepairCountsNothing", exclude=[]),

    "HomeVerdictsInternal_regression_unstamped": dict(
        header="""#338. Verdicts printed no build: released 0.27.2 and the pinned repo build
0.27.2+g71c5146 printed identical verdicts on one home. No single origin commit:
BuildIdentity existed for --version since 7ae6eccc and no verdict command used
it. Fixed by OHV-1, #360 merged 8bd883f3 (skt check: skt#46, unmerged at
833ae0d7).
Expected outcome: AVerdictNamesTheBuildThatProducedIt FAILS.""",
        over={"VerdictStamp": "UNSTAMPED"},
        expect="AVerdictNamesTheBuildThatProducedIt", exclude=[]),

    "HomeVerdictsInternal_regression_tokenmeansdone": dict(
        header="""DEF-OHV-001, the writer half. selfDerivingRewrite returned null for any body
containing SKILL_MANAGER_SHIM_HOME ("already rewritten"), so an installer that
half-adopted the recipe (token export, literal exec) kept its literal exec line.
Origin: 43e5fb99 (#323). Fixed by OHV-4 (b)+(c), #365 merged 42486f86.
Expected outcome: AFreshlyWrittenShimNamesNoHome FAILS (depth 1).""",
        over={"ShimRewriteScope": "TOKEN_MEANS_DONE"},
        expect="AFreshlyWrittenShimNamesNoHome", exclude=[]),

    "HomeVerdictsInternal_regression_foreignfixhalfrewrite": dict(
        header="""DEF-OHV-180, second write (11:50:45 EDT 2026-09-14): another session ran the
RELEASED 0.27.2 `home repair --fix` on the root. FOREIGN_PATH_IN_SHIM's fix
mapped the other home's path onto the root's and called the rewrite, which saw
the token already in the export line and did nothing: the exec line was left
spelling the root -- DEF-OHV-001's shape, back. Same origin as the writer half
(43e5fb99). Fixed by OHV-4 (b), #365 merged 42486f86; on the root, by the
owner-approved epic-build --fix at 12:26 EDT.
WHICH PATH TLC PRINTS: the shortest counterexample (depth 3) is the FROZEN
finding's --fix over an installer-written half shim. The FOREIGN path DEF-OHV-180
took (SomethingElseWritesTheShim with a foreign exec line, then RepairFixShim)
is also depth 3 and calls the same rewrite; TLC reports whichever it reaches
first, and both are this defect.
NARROWED LIST: AFreshlyWrittenShimNamesNoHome and ACopyOfASettledHomeRunsItsOwnShims
fail at the same or a shallower depth under this constant (the same rewrite
serves every writer), and would hide the --fix path this config exists to pin.
Expected outcome: ARepairedShimNamesNoHome FAILS.""",
        over={"ShimRewriteScope": "TOKEN_MEANS_DONE"},
        expect="ARepairedShimNamesNoHome",
        exclude=["AFreshlyWrittenShimNamesNoHome", "ACopyOfASettledHomeRunsItsOwnShims"]),

    "HomeVerdictsInternal_regression_rewritegateddetector": dict(
        header="""DEF-OHV-001 as shipped in 0.27.2: BOTH pre-fix rules. HomeRepair.scanFrozenShims
reported FROZEN_HOME_PATH_IN_SHIM only when selfDerivingRewrite returned a
rewrite (ffa2108b, #335), and the rewrite gave up on any shim holding the token
(43e5fb99, #323). The half-rewritten root shims were exempt from both verdicts:
`home repair` 0 findings of 78, copied-home probe 2 of 3. Fixed by OHV-4 (a),
#365 merged 42486f86.
NARROWED LIST: the three writer/copy invariants fail at depth 1-2 under
TOKEN_MEANS_DONE (see _regression_tokenmeansdone) and would mask the detector.
Expected outcome: AShimSpellingItsOwnHomeIsReported FAILS.""",
        over={"ShimDetection": "REWRITE_AVAILABLE", "ShimRewriteScope": "TOKEN_MEANS_DONE"},
        expect="AShimSpellingItsOwnHomeIsReported",
        exclude=["AFreshlyWrittenShimNamesNoHome", "ARepairedShimNamesNoHome",
                 "ACopyOfASettledHomeRunsItsOwnShims", "ACommentOnlySpellingIsNotAFinding"]),

    "HomeVerdictsInternal_regression_rebuildfromindex": dict(
        header="""#292 (DEF-HBR-003's fixpoint half). ArtifactPrune.apply re-recorded the ledger
as ArtifactLedger.of(ArtifactIndex.of(store).artifacts()); the index merges the
disk WITH the ledger, so every pruned row came back (59 before, 59 after).
Origin: 468daf8f (#207). Fixed by OHV-3 (a), #361 merged 3d6d4cd4.
WHICH PATH TLC PRINTS: the removal's own reap (depth 2), because uninstall's
PruneOrphanArtifacts runs the same apply and the same re-record. The
whole-home `artifacts prune` after a pre-OHV-3 removal -- #292's literal 59 -> 59
-- is depth 3 and fails the same way.
Expected outcome: PruneStaysPruned FAILS.""",
        over={"PruneRerecord": "REBUILD_FROM_INDEX"},
        expect="PruneStaysPruned", exclude=[]),

    "HomeVerdictsInternal_regression_leaverows": dict(
        header="""DEF-OHV-003 / DEF-HBR-003. Uninstall and retirement removed a unit's files and
kept its ledger rows: a row with no outputs REFUSED, a row whose outputs were
absent returned CLAIMED. Root at kickoff: 14 ledger-only rows for retired units.
Origin: 468daf8f (#207). Fixed by OHV-3 (b), #361 merged 3d6d4cd4, going
forward only (pre-OHV-3 rows remain: DEF-OHV-131, #374).
Expected outcome: RowsProvenAbsentAtRemovalAreReaped FAILS.""",
        over={"TeardownReaping": "LEAVE_ROWS"},
        expect="RowsProvenAbsentAtRemovalAreReaped", exclude=[]),

    "HomeVerdictsInternal_regression_reapunconditionally": dict(
        header="""#292's FIRST fix, 252c6c48 (2026-09-12), reverted the same day by c4f7dff8: a
row with no outputs became a row-only PRUNE without proof its agent link was
gone. plugin-smoke's home.fixpoint.law went red on three misanchored agent
symlinks "that no later cleanup can reach, because the ledger is what teardown
reads and the row naming it had just been dropped". Never released. OHV-3 (b)
is the evidence-capturing replacement.
Expected outcome: ALinkStillOnDiskKeepsItsRow FAILS.""",
        over={"TeardownReaping": "REAP_UNCONDITIONALLY"},
        expect="ALinkStillOnDiskKeepsItsRow", exclude=[]),

    "HomeVerdictsInternal_regression_hashonly": dict(
        header="""DEF-OHV-004. A sync moved installed/<unit>.json's gitHash
(InstalledUnit.withGitMoved) and never its version, and the up-to-date path
wrote nothing: root 5, project 1 records whose version disagreed with the
checkout at the very hash they named. Origin: 39e42838 (#45). Fixed by OHV-3
(c), #361 merged 3d6d4cd4 (RecordVersionRefresh); real homes at finalization
(DEF-OHV-182).
Expected outcome: ASyncedRecordAgreesWithItsCheckout FAILS.""",
        over={"RecordVersionPolicy": "HASH_ONLY"},
        expect="ASyncedRecordAgreesWithItsCheckout", exclude=[]),

    "HomeVerdictsInternal_regression_substring": dict(
        header="""#352 shapes 1 and 2. ensureMarketplaceAdded judged registration by
`list.stdout().contains(name)` and trusted `marketplace add`'s exit code. Claude
keeps the OLD name when a path is re-added, so `plugin install p@<identity>`
failed: "Marketplace '<identity>' not found" (AGENT_SYNC_FAILED). Origin:
61e9553b (#165, per-home identities, left the substring match from f14c2117).
Fixed by OHV-6 (a), #366 merged 7357b8ab.
Expected outcome: ASyncRegistersUnderItsOwnIdentity FAILS.""",
        over={"MarketplaceMatch": "SUBSTRING"},
        expect="ASyncRegistersUnderItsOwnIdentity", exclude=[]),

    "HomeVerdictsInternal_regression_trustedmanifest": dict(
        header="""#352 shape 3. A copied or cloned home's plugin-marketplace manifest carried
its source's identity, and every agent registered this home under it
(wt-229-library-attestation: skill-manager-919db26e). Origin: the manifest name
is written by 61e9553b (#165); the reader trusting it was not traced to one
commit. Fixed by OHV-6 (b), #366 merged 7357b8ab.
Expected outcome: ASyncRegistersUnderItsOwnIdentity FAILS.""",
        over={"ManifestIdentity": "TRUSTED_FROM_DISK"},
        expect="ASyncRegistersUnderItsOwnIdentity", exclude=[]),

    "HomeVerdictsInternal_regression_nomarketplacerepair": dict(
        header="""DEF-OHV-005 / #352 shape 4. Another home's marketplace (commit-diff-context-
parent's skill-manager-919db26e) registered and enabled in the root's Claude and
Codex configs, and no verdict named it: no repair kind existed. Fixed by OHV-6
(c), #366 merged 7357b8ab; root cleared 2026-09-14.
Expected outcome: AfterRepairAndSyncNothingForeignIsEnabled FAILS.""",
        over={"MarketplaceRepair": "NONE"},
        expect="AfterRepairAndSyncNothingForeignIsEnabled", exclude=[]),

    "HomeVerdictsInternal_regression_writethrough": dict(
        header="""DEF-OHV-011 (08:41 EDT 2026-09-14) and its recurrence DEF-OHV-180 (11:31 EDT).
A child home's bin/cli/<tool> was a symlink to the operator's real root shim;
deploy-helm's skill-script wrote `cat >"$launcher"`, which follows it, and the
root's computeq/helm-deploy/monitoring were rewritten to exec into a test
report. Origin: 9c8480fb (#97, the unguarded Shell.runToLog) and 3cbcba85
(#287, binStamps NOFOLLOW_LINKS + reportFrozenShims skipping links, so nothing
saw it). Fixed by OHV-9 (a), #368 merged 5ab5770b; deploy-helm#63 open.
Expected outcome: AnOwnEntryWriteStaysInItsHome FAILS.""",
        over={"BinWritePolicy": "WRITE_THROUGH"},
        expect="AnOwnEntryWriteStaysInItsHome", exclude=[]),

    "HomeVerdictsInternal_regression_launcherfollow": dict(
        header="""Found by OHV-9 (b)'s writer audit, red on the original code: LauncherShims
wrote bin/cli/skill-manager (and bin/launch/*) with Files.writeString, which
follows a link, so a child home's entrypoint link wrote the child's pin into
the PARENT. Origin: c94e791b / e65962ef (#130). Fixed by OHV-9, #368 merged
5ab5770b (writeOwnFile).
Expected outcome: AGeneratedLauncherIsWrittenIntoItsOwnHome FAILS.""",
        over={"LauncherWrite": "FOLLOW_LINK"},
        expect="AGeneratedLauncherIsWrittenIntoItsOwnHome", exclude=[]),

    "HomeVerdictsInternal_regression_nostatcheck": dict(
        header="""NOT A SHIPPED DEFECT: a design pin on OHV-9. Detaching the links alone does
not catch a script that writes the detached target by its own path; the
before/after stat (size, mtime, file key) in ForeignBinLinks.restore is what
fails that install naming the other home. Kept so the stat check cannot be
dropped as redundant with the detach.
Expected outcome: AWriteThroughADetachedLinkIsRefused FAILS.""",
        over={"ForeignWriteCheck": "NONE"},
        expect="AWriteThroughADetachedLinkIsRefused", exclude=[]),

    "HomeVerdictsInternal_regression_norestore": dict(
        header="""NOT A SHIPPED DEFECT: a design pin on OHV-9. A detach with no restore would
leave the child home without every mirrored tool the script did not replace.
Expected outcome: ALinkTheInstallerDidNotReplaceIsPutBack FAILS.""",
        over={"DetachRestore": "DETACH_ONLY"},
        expect="ALinkTheInstallerDidNotReplaceIsPutBack", exclude=[]),

    "HomeVerdictsInternal_finding_prunedresidue": dict(
        header="""A FINDING, made executable. Runs the HEALTHY configuration against the
stronger reading of GOAL-one-record clause (1), NoProjectionRowOutlivesItsLink.
It must FAIL, and its counterexample is the delivered product: a post-OHV-3
removal leaves an agent link dangling (so the row is correctly kept), `home
repair --fix` removes the link (DANGLING_AGENT_LINK, OHV-2), and no later
`artifacts prune` can prove it gone -- the evidence rode only in the removal's
own PruneOrphanArtifacts effect. So DEF-OHV-131's shape is still PRODUCIBLE
after OHV-3. Modelled, not reproduced on a real home.
Expected outcome: NoProjectionRowOutlivesItsLink FAILS.""",
        over={}, expect="NoProjectionRowOutlivesItsLink",
        exclude=None, only=["TypeOK", "NoProjectionRowOutlivesItsLink"]),

    "HomeVerdictsInternal_finding_outrightpath": dict(
        header="""A LIMIT, made executable. Runs the HEALTHY configuration against the unscoped
reading of GOAL-a-home-writes-only-itself clause (2). It must FAIL: a
skill-script in a child with NO link to detach that writes the parent's file by
its absolute path succeeds, because the stat check covers only detached
targets. ForeignBinLinks' javadoc states this limit; nothing else pins it.
Expected outcome: AnyWriteIntoAnotherHomeIsRefused FAILS.""",
        over={}, expect="AnyWriteIntoAnotherHomeIsRefused",
        exclude=None, only=["TypeOK", "AnyWriteIntoAnotherHomeIsRefused"]),

    "HomeVerdictsInternal_probe_reach": dict(
        header="""REACHABILITY PROBES -- EVERY ONE MUST FAIL, against the HEALTHY configuration.
Run WITH -continue, or TLC stops at the first:
  tlc2 -continue -config HomeVerdictsInternal_probe_reach.cfg HomeVerdictsInternal.tla
(run_tlc.sh passes no -continue.) Expected: 6 violations, one per probe. Fewer
means a group's antecedent is no longer reached and its invariants are passing
without being evaluated.""",
        over={}, expect="PROBES", exclude=None,
        only=["TypeOK", "ProbeVerdicts", "ProbeShims", "ProbePrune", "ProbeRecords",
              "ProbeMarketplace", "ProbeWrites"]),
}


def render(name, c):
    consts = dict(HEALTHY)
    consts.update(c["over"])
    if c.get("only"):
        invs = c["only"]
    else:
        invs = list(ALL_INV)
        if c["expect"] and c["expect"] in invs:
            invs.remove(c["expect"])
            invs.insert(1, c["expect"])
        for x in c.get("exclude") or []:
            invs.remove(x)
    lines = ["\\* " + l if l else "\\*" for l in c["header"].splitlines()]
    if c["over"]:
        lines.append("\\*")
        lines.append("\\* Flipped from the healthy configuration: " +
                     ", ".join(f"{k} = \"{v}\"" for k, v in c["over"].items()) + ".")
    out = "\n".join(lines) + "\nSPECIFICATION Spec\n\nCHECK_DEADLOCK FALSE\n\nCONSTANTS\n"
    for k in HEALTHY:
        out += f"  {k} = \"{consts[k]}\"\n"
    out += "\nINVARIANTS\n" + "".join(f"  {i}\n" for i in invs)
    return out


def run(name, c):
    d = SCRATCH / name
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    shutil.copy(MODEL / "HomeVerdictsInternal.tla", d)
    shutil.copy(MODEL / f"{name}.cfg", d)
    args = [TLC2, "-workers", "1"]
    if c["expect"] == "PROBES":
        args.append("-continue")
    args += ["-config", f"{name}.cfg", "HomeVerdictsInternal.tla"]
    t = time.time()
    p = subprocess.run(args, cwd=d, capture_output=True, text=True)
    secs = round(time.time() - t, 1)
    text = p.stdout + p.stderr
    (OUT / f"{name}.out").write_text(text)
    violated = re.findall(r"Invariant (\w+) is violated", text)
    distinct = re.findall(r"(\d+) distinct states found", text)
    generated = re.findall(r"(\d+) states generated", text)
    depth = re.findall(r"The depth of the complete state graph search is (\d+)", text)
    trace_states = len(re.findall(r"^State \d+:", text, re.M))
    ok_clean = "No error has been found" in text
    if c["expect"] is None:
        passed = ok_clean and not violated
        expected = "no error"
    elif c["expect"] == "PROBES":
        want = {"ProbeVerdicts", "ProbeShims", "ProbePrune", "ProbeRecords", "ProbeMarketplace", "ProbeWrites"}
        passed = set(violated) == want
        expected = "6 probe violations"
    else:
        passed = violated[:1] == [c["expect"]]
        expected = f"{c['expect']} violated"
    return dict(cfg=f"{name}.cfg", expected=expected,
                actual=("no error" if ok_clean and not violated else ", ".join(violated) + " violated"),
                matches=passed, distinct=distinct[-1] if distinct else None,
                generated=generated[-1] if generated else None,
                depth=depth[-1] if depth else None,
                trace_len=trace_states or None, rc=p.returncode, seconds=secs,
                command=" ".join(["tlc2"] + args[1:]))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    only = sys.argv[1:]
    results = []
    for name, c in CFGS.items():
        (MODEL / f"{name}.cfg").write_text(render(name, c))
    for name, c in CFGS.items():
        if only and name not in only:
            continue
        r = run(name, c)
        results.append(r)
        print(json.dumps(r))
    if not only:
        (OUT / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    bad = [r for r in results if not r["matches"]]
    print(f"{len(results) - len(bad)} of {len(results)} as expected")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
