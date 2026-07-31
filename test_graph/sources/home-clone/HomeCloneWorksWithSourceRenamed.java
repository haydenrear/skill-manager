///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeCloneSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * {@code AHomeIsAPureFunctionOfItsRoot}, empirically: the clone keeps working
 * with the source <b>renamed away</b>.
 *
 * <p>This is the only assertion in the graph that can distinguish "no leak the
 * scan knows how to look for" from "no dependence on the source at all". A
 * reference the scan misses — a path assembled at runtime, a link the walk
 * skipped, a cached absolute root — survives every static check and fails here,
 * because the path it names has ceased to exist.
 *
 * <p>The source is renamed rather than deleted, and restored afterwards, for two
 * reasons. The standing constraint aside, later assertions still need it: this
 * node re-asserts byte-identity after the restore, so a rename that silently
 * changed the tree would be caught rather than hidden by the teardown.
 *
 * <p>Each command's output is also required to name NEITHER the source home nor
 * its moved-away name. Exit 0 alone is too weak: a ledger that still recorded the
 * source would print that path happily, and the command would succeed because it
 * never dereferenced it. Naming it at all is the leak.
 *
 * <p>Four surfaces are exercised, one per way the clone could still reach back:
 * {@code list} (the installed-unit index), {@code show} (a single unit's record
 * plus its store path), {@code bindings list} (the projection ledger, the
 * surface that held the reproduced defect on #20), and the {@code bin/cli} shim
 * (a generated script whose exec target was re-anchored by byte substitution).
 */
public class HomeCloneWorksWithSourceRenamed {
    static final NodeSpec SPEC = NodeSpec.of("home.clone.works.with.source.renamed")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.clone.edit.stays.in.clone")
            .tags("home-clone", "relocatability")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String fixture = ctx.get("home.clone.fixture.built", "fixtureHome").orElse(null);
            String cloneStoreRaw = ctx.get("home.clone.fixture.built", "cloneStore").orElse(null);
            String sourceDigest = ctx.get("home.clone.fixture.built", "sourceDigest").orElse(null);
            String agentBytes = ctx.get("home.clone.edit.stays.in.clone", "agentBytes").orElse(null);
            if (fixture == null || cloneStoreRaw == null || sourceDigest == null
                    || agentBytes == null) {
                return NodeResult.fail("home.clone.works.with.source.renamed",
                        "missing upstream context");
            }
            Path fixtureHome = Path.of(fixture);
            Path movedAway = fixtureHome.resolveSibling(
                    fixtureHome.getFileName() + "-moved-away");
            Path cloneStore = Path.of(cloneStoreRaw);

            Files.move(fixtureHome, movedAway, StandardCopyOption.ATOMIC_MOVE);
            boolean sourceIsGone = !Files.exists(fixtureHome)
                    && Files.isDirectory(movedAway);

            ProcessRecord list;
            ProcessRecord show;
            ProcessRecord bindings;
            ProcessRecord shim;
            ProcessRecord projections;
            String projectionsOut;
            String listOut;
            String showOut;
            String bindingsOut;
            String shimOut;
            try {
                list = HomeCloneSupport.sm(ctx, "list-renamed", cloneStoreRaw, "list", "--json");
                listOut = HomeCloneSupport.log(ctx, "list-renamed");

                show = HomeCloneSupport.sm(ctx, "show-renamed", cloneStoreRaw,
                        "show", HomeCloneSupport.UNIT_B, "--json");
                showOut = HomeCloneSupport.log(ctx, "show-renamed");

                bindings = HomeCloneSupport.sm(ctx, "bindings-renamed", cloneStoreRaw,
                        "bindings", "list", "--json");
                bindingsOut = HomeCloneSupport.log(ctx, "bindings-renamed");

                // `bindings list` prints only the target root, never the
                // ledger's sourcePath — MEASURED: injecting a ledger whose
                // sourcePath named the source home left every assertion in this
                // node green, because no command in the set printed it. That is
                // the exact surface the reproduced defect on #20 lived on, so it
                // has to be read explicitly.
                String bid = HomeCloneSupport.jsonString(bindingsOut, "id");
                projections = bid.isBlank()
                        ? null
                        : HomeCloneSupport.sm(ctx, "binding-show-renamed", cloneStoreRaw,
                                "bindings", "show", bid, "--json");
                projectionsOut = HomeCloneSupport.log(ctx, "binding-show-renamed");

                shim = HomeCloneSupport.exec(ctx, "shim-renamed", cloneStoreRaw,
                        List.of(HomeCloneSupport.shim(cloneStore, HomeCloneSupport.GOOD_SHIM)
                                .toString()));
                shimOut = HomeCloneSupport.log(ctx, "shim-renamed");
            } finally {
                // Restore before asserting, so a failure above still leaves the
                // fixture where the next node expects it.
                if (!Files.exists(fixtureHome) && Files.isDirectory(movedAway)) {
                    Files.move(movedAway, fixtureHome, StandardCopyOption.ATOMIC_MOVE);
                }
            }

            boolean listWorks = list.exitCode() == 0
                    && listOut.contains(HomeCloneSupport.UNIT_A)
                    && listOut.contains(HomeCloneSupport.UNIT_B)
                    && !listOut.contains(fixture) && !listOut.contains(movedAway.toString());
            boolean showWorks = show.exitCode() == 0
                    && showOut.contains(HomeCloneSupport.UNIT_B)
                    && !showOut.contains(fixture) && !showOut.contains(movedAway.toString());
            boolean bindingsWork = bindings.exitCode() == 0
                    && bindingsOut.contains(HomeCloneSupport.UNIT_A)
                    && !bindingsOut.contains(fixture) && !bindingsOut.contains(movedAway.toString());
            // The ledger's own recorded paths, read back with the source gone.
            boolean projectionsWork = projections != null && projections.exitCode() == 0
                    && projectionsOut.contains(HomeCloneSupport.UNIT_A)
                    && !projectionsOut.contains(fixture)
                    && !projectionsOut.contains(movedAway.toString());

            // The shim must not merely exit 0 — it must produce the CLONE's
            // bytes, which is the agent's edit from the previous node. Exit 0
            // with the source's bytes would mean it read the wrong home; exit 0
            // with nothing would mean it read no home at all.
            boolean shimWorks = shim.exitCode() == 0 && shimOut.contains(agentBytes);

            boolean sourceRestored = Files.isDirectory(fixtureHome) && !Files.exists(movedAway);
            String afterDigest = sourceRestored
                    ? HomeCloneSupport.treeDigest(fixtureHome)
                    : "(not restored)";
            boolean sourceHomeIsStillByteIdentical = afterDigest.equals(sourceDigest);

            boolean pass = sourceIsGone && listWorks && showWorks && bindingsWork
                    && projectionsWork && shimWorks
                    && sourceRestored && sourceHomeIsStillByteIdentical;
            return (pass
                    ? NodeResult.pass("home.clone.works.with.source.renamed")
                    : NodeResult.fail("home.clone.works.with.source.renamed",
                            "sourceIsGone=" + sourceIsGone
                                    + " list=" + list.exitCode() + "/" + listWorks
                                    + " show=" + show.exitCode() + "/" + showWorks
                                    + " bindings=" + bindings.exitCode() + "/" + bindingsWork
                                    + " projections=" + (projections == null ? "-"
                                            : projections.exitCode()) + "/" + projectionsWork
                                    + " shim=" + shim.exitCode() + "/" + shimWorks
                                    + " sourceRestored=" + sourceRestored
                                    + " digestBefore=" + sourceDigest
                                    + " digestAfter=" + afterDigest))
                    .process(list).process(show).process(bindings).process(shim)
                    .assertion("the_source_home_really_was_renamed_away", sourceIsGone)
                    .assertion("list_works_with_the_source_renamed_away", listWorks)
                    .assertion("show_works_with_the_source_renamed_away", showWorks)
                    .assertion("bindings_list_works_with_the_source_renamed_away", bindingsWork)
                    .assertion("the_ledgers_recorded_source_paths_name_no_missing_home",
                            projectionsWork)
                    .assertion("a_bin_cli_shim_execs_the_clones_own_bytes_with_the_source_gone",
                            shimWorks)
                    .assertion("the_source_home_was_restored", sourceRestored)
                    .assertion("the_source_home_is_still_byte_identical_after_the_round_trip",
                            sourceHomeIsStillByteIdentical);
        });
    }
}
