///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeCloneSupport.java
//SOURCES ../lib/HomeIsolation.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code skill-manager home clone --to <project>/.skill-manager}, then an
 * INDEPENDENT scan of the result.
 *
 * <p>Asserts {@code NoOwnedSurfaceNamesAnotherHome} and
 * {@code AuthoredContentIsNeverRewritten} from {@code External.tla} — by walking
 * the copy, not by reading the clone's own report. The distinction is the point
 * of the node: {@code home clone} exiting 0 is a claim, and five tickets on this
 * epic have shipped green suites where the claim was true and the filesystem was
 * not. The report is checked too, but as a SECOND assertion, so a report that
 * disagrees with the scan fails rather than papering over it.
 *
 * <p>Also asserts the first half of
 * {@code SourceHomeIsByteIdenticalToItsCloneTimeSelf}: cloning is a read of the
 * source. The second half — that USING the clone is also a read of the source —
 * is {@code home.clone.edit.stays.in.clone}.
 */
public class HomeClonedIntoProject {
    static final NodeSpec SPEC = NodeSpec.of("home.cloned.into.project")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.clone.fixture.built")
            .tags("home-clone", "isolation")
            .timeout("300s")
            .output("cloneJson", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String fixture = ctx.get("home.clone.fixture.built", "fixtureHome").orElse(null);
            String cloneStoreRaw = ctx.get("home.clone.fixture.built", "cloneStore").orElse(null);
            String sourceDigest = ctx.get("home.clone.fixture.built", "sourceDigest").orElse(null);
            String authoredDigest = ctx.get("home.clone.fixture.built", "authoredDigest").orElse(null);
            if (fixture == null || cloneStoreRaw == null || sourceDigest == null
                    || authoredDigest == null) {
                return NodeResult.fail("home.cloned.into.project", "missing upstream context");
            }
            Path fixtureHome = Path.of(fixture);
            Path cloneStore = Path.of(cloneStoreRaw);

            ProcessRecord clone = HomeCloneSupport.sm(ctx, "home-clone", fixture,
                    "home", "clone", "--to", cloneStoreRaw, "--json");
            String cloneLog = HomeCloneSupport.log(ctx, "home-clone");
            String cloneJson = HomeCloneSupport.jsonLine(cloneLog, "{\"source\":");

            boolean cloneSucceeded = clone.exitCode() == 0;
            boolean cloneReportsClean = cloneJson.contains("\"clean\":true");

            // --- INDEPENDENT scan: nothing in the copy may name the source ---
            List<String> refs = HomeCloneSupport.referencesTo(cloneStore, fixture);
            String descentPrefix = HomeCloneSupport.DESCENT_SURFACE + " ";
            List<String> leaks = refs.stream()
                    .filter(r -> !r.startsWith("CONTENT ") && !r.startsWith(descentPrefix))
                    .toList();
            List<String> tolerated = refs.stream().filter(r -> r.startsWith("CONTENT ")).toList();
            List<String> descent = refs.stream().filter(r -> r.startsWith(descentPrefix)).toList();

            boolean noOwnedSurfaceNamesTheSource = leaks.isEmpty();

            // --- PRECONDITION: the record the scan exempts is actually there --
            //
            // Asserted, not hoped. Without it the exemption above is vacuous in
            // the vacuity ledger's mechanism-B sense: a clone that recorded no
            // descent at all also produces zero leaks, and "the exemption is
            // narrow" then reads exactly like "there was nothing to exempt".
            // Same argument the tolerated-content pair below already makes,
            // applied to the file HIS-10 added.
            //
            // Deliberately blind to production's exemption — it reads the
            // filesystem and this walk's own classification, so removing the
            // exemption from HomeCloner cannot move it. A precondition that
            // moves with the mutation under test is mechanism A, and this epic
            // has three of those on the ledger already.
            boolean theCloneRecordsItsDescentAndTheScanCountsIt =
                    HomeIsolation.recordsDescentNaming(cloneStore, fixture)
                            && descent.size() == 1;

            // --- the declared exception, both halves -----------------------
            // It must SURVIVE (byte-identical) and it must be COUNTED. Either
            // one alone is satisfiable by the wrong implementation: rewriting it
            // makes the count zero and consistent, leaving it silently makes
            // "0 leaks" read as "nothing survives".
            Path clonedAuthored = HomeCloneSupport.unitDir(cloneStore, HomeCloneSupport.UNIT_B)
                    .resolve(HomeCloneSupport.AUTHORED_HISTORY);
            boolean authoredSurvivedByteForByte = Files.isRegularFile(clonedAuthored)
                    && HomeCloneSupport.treeDigest(clonedAuthored).equals(authoredDigest);
            int reportedContentRefs = HomeCloneSupport.jsonInt(cloneJson, "contentReferences");
            boolean toleratedReferencesAreCounted =
                    reportedContentRefs == tolerated.size() && reportedContentRefs > 0;

            // --- the ledger went through the production serde, not bytes ----
            // Both repair paths leave a clone with no reference to the source, so
            // the leak scan above cannot tell them apart -- MEASURED: disabling
            // HomeCloner.reanchorLedgers entirely left every assertion in this
            // graph green, because reanchorRemainingState's byte substitution
            // covers the same files. The two outcomes are not equivalent:
            //   structured pass  -> "$SKILL_MANAGER_HOME/skills/<unit>"
            //                       relocatable AGAIN, indefinitely
            //   byte substitution -> "<clone>/skills/<unit>"
            //                       correct once, and the next `mv` breaks it
            // which is exactly the difference between External.cfg and
            // External_regression_absstate.cfg in the spec. Asserted here, in the
            // one node that runs before any CLI command rewrites the ledger.
            Path clonedLedger = cloneStore.resolve("installed")
                    .resolve(HomeCloneSupport.UNIT_A + ".projections.json");
            String ledger = HomeCloneSupport.read(clonedLedger);
            boolean ledgerIsTokenizedNotSubstituted =
                    ledger.contains("$SKILL_MANAGER_HOME/skills/" + HomeCloneSupport.UNIT_A)
                            && !ledger.contains(cloneStoreRaw + "/skills/"
                                    + HomeCloneSupport.UNIT_A);

            // --- in-home links came out RELATIVE ---------------------------
            Path inUnitLink = HomeCloneSupport.unitDir(cloneStore, HomeCloneSupport.UNIT_A)
                    .resolve(HomeCloneSupport.IN_UNIT_LINK);
            Path linkShim = HomeCloneSupport.shim(cloneStore, HomeCloneSupport.LINK_SHIM);
            boolean inHomeLinksAreRelative = HomeCloneSupport.isRelativeSymlink(inUnitLink)
                    && HomeCloneSupport.isRelativeSymlink(linkShim);
            // Relative is worthless if it resolves somewhere else. Both must land
            // inside the CLONE.
            boolean relativeLinksResolveInsideTheClone =
                    resolvesInside(inUnitLink, cloneStore) && resolvesInside(linkShim, cloneStore);

            // --- and PRODUCTION'S OWN VERDICT on the same question ---------
            //
            // The walk above is a SECOND SPELLING of a rule production already
            // owns in HomeCloner.verifyRoots, and that is this epic's signature
            // defect -- two readers of one rule -- with the epic's own
            // instruments as the subject. It bit here exactly as it bit
            // ticket-lifecycle: HIS-10 (#227) made a correct clone record its
            // DESCENT, which names the source on purpose, production exempted
            // that one file from its own isolation rule, and this walk did not
            // follow. It went red the day HIS-10 merged and took nine skipped
            // nodes with it, in TWO graphs, and nobody looked for a third copy
            // of the rule after the first one was fixed.
            //
            // Exempting the record above fixes today. Asking PRODUCTION the
            // same question is what stops the two drifting apart again.
            //
            // WHY NOT `verify` exit 0, the way ticket-lifecycle cross-checks:
            // measured on this graph's own clone, `home verify --home <clone>
            // --against <fixture>` exits 1 -- on bin/cli/hc-venv-tool, the
            // DANGLING_SHIM this fixture plants on purpose so that "a skipped
            // toolchain root is reported" has something to report. That is the
            // provisioning half of the command, not the isolation half. Binding
            // this cross-check to the exit code would make it red for a reason
            // the node is not about, and the repair for that would be widening
            // the fixture until it stopped saying anything. So the cross-check
            // reads the ISOLATION VERDICT, which is the half the walk duplicates.
            ProcessRecord verify = HomeCloneSupport.sm(ctx, "home-verify-clone", cloneStoreRaw,
                    "home", "verify", "--home", cloneStoreRaw, "--against", fixture);
            String verifyOut = HomeCloneSupport.log(ctx, "home-verify-clone");
            boolean productionAgreesNoPathNamesTheSource =
                    HomeIsolation.verdictIsClean(verifyOut, cloneStore);

            // --- the control that gives the cross-check its polarity --------
            //
            // "Production reported no isolation failure" is also satisfied by a
            // production that CANNOT report one: delete the leak collection
            // from verifyRoots and the assertion above goes green. That is the
            // vacuity ledger's mechanism C with the oracle itself as the
            // subject. a4a95cb measured this control by hand, once, in a probe
            // -- and measuring it by hand is exactly how the SECOND copy of the
            // rule survived the first fix. So it runs on every run.
            //
            // Removed in a finally, and its removal ASSERTED: eight downstream
            // nodes read this clone, and a decoy left behind would be a leak
            // the graph itself created. The source digest below is taken after
            // all of this, so both verify runs are also covered by "cloning
            // does not write to the source home".
            boolean productionRefusesAPlantedPathIntoTheSource = false;
            try {
                HomeIsolation.plantDecoy(cloneStore,
                        HomeCloneSupport.shim(fixtureHome, HomeCloneSupport.GOOD_SHIM));
                ProcessRecord verifyDecoy = HomeCloneSupport.sm(ctx, "home-verify-decoy",
                        cloneStoreRaw, "home", "verify", "--home", cloneStoreRaw,
                        "--against", fixture);
                productionRefusesAPlantedPathIntoTheSource = verifyDecoy.exitCode() != 0
                        && HomeIsolation.verdictRefusesNaming(
                                HomeCloneSupport.log(ctx, "home-verify-decoy"),
                                HomeIsolation.DECOY_LINK);
            } finally {
                HomeIsolation.removeDecoy(cloneStore);
            }
            boolean theDecoyIsGone = HomeIsolation.decoyIsGone(cloneStore);

            // --- the source home is untouched by the clone -----------------
            String afterDigest = HomeCloneSupport.treeDigest(fixtureHome);
            boolean sourceUnchangedByCloning = afterDigest.equals(sourceDigest);

            boolean pass = cloneSucceeded && cloneReportsClean && noOwnedSurfaceNamesTheSource
                    && theCloneRecordsItsDescentAndTheScanCountsIt
                    && productionAgreesNoPathNamesTheSource
                    && productionRefusesAPlantedPathIntoTheSource && theDecoyIsGone
                    && authoredSurvivedByteForByte && toleratedReferencesAreCounted
                    && ledgerIsTokenizedNotSubstituted && inHomeLinksAreRelative && relativeLinksResolveInsideTheClone
                    && sourceUnchangedByCloning;
            return (pass
                    ? NodeResult.pass("home.cloned.into.project")
                    : NodeResult.fail("home.cloned.into.project",
                            "exit=" + clone.exitCode()
                                    + " leaks=" + leaks
                                    + " tolerated=" + tolerated
                                    + " reportedContentRefs=" + reportedContentRefs
                                    + " ledgerIsTokenizedNotSubstituted="
                                    + ledgerIsTokenizedNotSubstituted
                                    + " inUnitLink=" + HomeCloneSupport.linkTarget(inUnitLink)
                                    + " linkShim=" + HomeCloneSupport.linkTarget(linkShim)
                                    + " digestBefore=" + sourceDigest
                                    + " digestAfter=" + afterDigest
                                    + " descent=" + descent
                                    + " verifyExit=" + verify.exitCode()
                                    + " productionAgreesNoPathNamesTheSource="
                                    + productionAgreesNoPathNamesTheSource
                                    + " productionRefusesAPlantedPathIntoTheSource="
                                    + productionRefusesAPlantedPathIntoTheSource
                                    + " theDecoyIsGone=" + theDecoyIsGone))
                    .process(clone).process(verify)
                    .assertion("home_clone_exits_zero", cloneSucceeded)
                    .assertion("home_clone_reports_clean", cloneReportsClean)
                    .assertion("independent_scan_finds_no_owned_surface_naming_the_source",
                            noOwnedSurfaceNamesTheSource)
                    .assertion("the_clone_records_its_descent_and_the_scan_counts_it",
                            theCloneRecordsItsDescentAndTheScanCountsIt)
                    .assertion("production_agrees_no_path_in_the_clone_names_the_source",
                            productionAgreesNoPathNamesTheSource)
                    .assertion("production_still_refuses_a_planted_path_into_the_source",
                            productionRefusesAPlantedPathIntoTheSource)
                    .assertion("the_planted_decoy_is_removed_from_the_clone", theDecoyIsGone)
                    .assertion("authored_content_survives_the_clone_byte_for_byte",
                            authoredSurvivedByteForByte)
                    .assertion("tolerated_content_references_are_counted_in_the_report",
                            toleratedReferencesAreCounted)
                    .assertion("the_cloned_ledger_is_tokenized_not_byte_substituted",
                            ledgerIsTokenizedNotSubstituted)
                    .assertion("in_home_symlinks_are_relative_in_the_clone", inHomeLinksAreRelative)
                    .assertion("relative_symlinks_resolve_inside_the_clone",
                            relativeLinksResolveInsideTheClone)
                    .assertion("cloning_does_not_write_to_the_source_home", sourceUnchangedByCloning)
                    .metric("cloneExitCode", clone.exitCode())
                    .metric("leakCount", leaks.size())
                    .metric("toleratedContentReferences", tolerated.size())
                    .metric("sanctionedDescentRecords", descent.size())
                    .metric("verifyExitCode", verify.exitCode())
                    .publish("cloneJson", cloneJson);
        });
    }

    /** True when the link's target resolves to a real path inside {@code root}. */
    private static boolean resolvesInside(Path link, Path root) {
        try {
            return link.toRealPath().startsWith(root.toRealPath());
        } catch (Exception e) {
            return false;
        }
    }
}
