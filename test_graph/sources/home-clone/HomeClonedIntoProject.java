///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeCloneSupport.java

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
            List<String> leaks = refs.stream().filter(r -> !r.startsWith("CONTENT ")).toList();
            List<String> tolerated = refs.stream().filter(r -> r.startsWith("CONTENT ")).toList();

            boolean noOwnedSurfaceNamesTheSource = leaks.isEmpty();

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

            // --- the source home is untouched by the clone -----------------
            String afterDigest = HomeCloneSupport.treeDigest(fixtureHome);
            boolean sourceUnchangedByCloning = afterDigest.equals(sourceDigest);

            boolean pass = cloneSucceeded && cloneReportsClean && noOwnedSurfaceNamesTheSource
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
                                    + " digestAfter=" + afterDigest))
                    .process(clone)
                    .assertion("home_clone_exits_zero", cloneSucceeded)
                    .assertion("home_clone_reports_clean", cloneReportsClean)
                    .assertion("independent_scan_finds_no_owned_surface_naming_the_source",
                            noOwnedSurfaceNamesTheSource)
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
