///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES ArtifactDagSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ARTI-06: {@code build <one>} repairs ONE artifact, and the home's other
 * artifacts are not collateral.
 *
 * <p>The repair {@code home verify} always printed was {@code sync
 * --force-scripts}: rerun every install in the home to fix the one thing that
 * moved. That is the eager front door in its purest form, and it is why a
 * ticket worktree paid seconds to minutes for a shim it could have rebuilt in
 * one. {@code build} is the per-artifact producer, and the property that makes
 * it worth having is not that the named artifact comes back — it is that
 * NOTHING ELSE MOVES.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li>The named artifact is rebuilt: {@code build --json} reports it
 *       {@code built} and {@code current} afterwards, and the tool's own output
 *       changes from the pre-edit marker to the post-edit one. The last clause
 *       is the one that cannot be faked by a lock rewrite — a repair that
 *       re-stamped the fingerprint without rerunning the installer would
 *       satisfy every other check here and leave the operator with the old
 *       binary, which is 6602eaf's "stop calling a no-op a rebuild".</li>
 *   <li>The sibling is untouched on all three axes it could move on: its
 *       bytes, its mtime, and the fingerprint recorded for it. Three, because
 *       each catches a different wrong implementation — a whole-home reinstall
 *       moves all three, a touch-only pass moves the mtime, and a lock rewrite
 *       moves only the record.</li>
 * </ul>
 */
public class BuildRepairsOneArtifact {

    static final NodeSpec SPEC = NodeSpec.of("build.repairs.one.artifact")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("artifact-dag", "build", "repair")
            .timeout("180s")
            .output("home", "string");

    public static void main(String[] a) {
        Node.run(a, SPEC, ctx -> {
            Path ws = ArtifactDagSupport.workspace(ctx, "build");
            Path store = ArtifactDagSupport.storeOf(ws);
            Path units = ws.resolve("units");
            Path alpha = ArtifactDagSupport.scaffoldUnit(units,
                    ArtifactDagSupport.UNIT_A, ArtifactDagSupport.TOOL_A);
            Path beta = ArtifactDagSupport.scaffoldUnit(units,
                    ArtifactDagSupport.UNIT_B, ArtifactDagSupport.TOOL_B);

            ProcessRecord installA = ArtifactDagSupport.sm(ctx, "install-alpha", store,
                    "install", alpha.toString(), "--yes");
            ProcessRecord installB = ArtifactDagSupport.sm(ctx, "install-beta", store,
                    "install", beta.toString(), "--yes");
            boolean both_fixtures_installed =
                    installA.exitCode() == 0 && installB.exitCode() == 0;

            Path siblingShim = store.resolve("bin/cli").resolve(ArtifactDagSupport.TOOL_B);
            Path siblingTree = store.resolve("cache")
                    .resolve(ArtifactDagSupport.treeDirName(
                            ArtifactDagSupport.UNIT_B, ArtifactDagSupport.TOOL_B));
            long siblingShimMtimeBefore = ArtifactDagSupport.mtime(siblingShim);
            long siblingTreeMtimeBefore =
                    ArtifactDagSupport.mtime(siblingTree.resolve("bin")
                            .resolve(ArtifactDagSupport.TOOL_B));
            String siblingBytesBefore = read(siblingShim)
                    + read(siblingTree.resolve("bin").resolve(ArtifactDagSupport.TOOL_B));
            String siblingFingerprintBefore = fingerprintOf(store, ArtifactDagSupport.TOOL_B);

            boolean the_units_skill_script_was_edited = ArtifactDagSupport
                    .editInstalledScript(store, ArtifactDagSupport.UNIT_A,
                            ArtifactDagSupport.TOOL_A);

            String target = ArtifactDagSupport.shimId(ArtifactDagSupport.TOOL_A);
            ProcessRecord build = ArtifactDagSupport.sm(ctx, "build-one", store,
                    "build", target, "--yes", "--json");
            String buildJson = ArtifactDagSupport.log(ctx, build);
            String targetStep = ArtifactDagSupport.sectionFor(buildJson, target);

            boolean build_succeeds = build.exitCode() == 0;
            boolean build_reports_the_named_artifact_built =
                    "built".equals(ArtifactDagSupport.jsonString(targetStep, "outcome"))
                            && "current".equals(
                                    ArtifactDagSupport.jsonString(targetStep, "freshness_after"));
            boolean build_leaves_nothing_it_selected_stale =
                    ArtifactDagSupport.jsonInt(buildJson, "still_stale") == 0
                            && ArtifactDagSupport.jsonInt(buildJson, "failed") == 0;

            // The tool itself, run through the sandbox: the repair reached the
            // bytes, not only the record.
            ProcessRecord invoke = ArtifactDagSupport.exec(ctx, "invoke-rebuilt", store,
                    List.of(store.resolve("bin/cli")
                            .resolve(ArtifactDagSupport.TOOL_A).toString()));
            String invoked = ArtifactDagSupport.log(ctx, invoke);
            boolean the_rebuilt_tool_runs_the_edited_installers_output =
                    invoke.exitCode() == 0
                            && invoked.contains(ArtifactDagSupport.TOOL_A + " "
                                    + ArtifactDagSupport.MARKER_AFTER);

            ProcessRecord stale = ArtifactDagSupport.sm(ctx, "stale-after-build", store,
                    "artifacts", "stale", "--json");
            String staleJson = ArtifactDagSupport.log(ctx, stale);
            boolean the_repaired_artifact_is_current_again =
                    stale.exitCode() == 0 && ArtifactDagSupport.jsonInt(staleJson, "stale") == 0;

            boolean a_sibling_artifacts_bytes_are_not_touched =
                    siblingBytesBefore.equals(read(siblingShim)
                            + read(siblingTree.resolve("bin")
                                    .resolve(ArtifactDagSupport.TOOL_B)));
            boolean a_sibling_artifacts_mtime_is_not_touched =
                    siblingShimMtimeBefore == ArtifactDagSupport.mtime(siblingShim)
                            && siblingTreeMtimeBefore == ArtifactDagSupport.mtime(
                                    siblingTree.resolve("bin")
                                            .resolve(ArtifactDagSupport.TOOL_B));
            boolean a_sibling_artifacts_recorded_fingerprint_is_not_touched =
                    !siblingFingerprintBefore.isBlank()
                            && siblingFingerprintBefore.equals(
                                    fingerprintOf(store, ArtifactDagSupport.TOOL_B));

            boolean pass = both_fixtures_installed
                    && the_units_skill_script_was_edited
                    && build_succeeds
                    && build_reports_the_named_artifact_built
                    && build_leaves_nothing_it_selected_stale
                    && the_rebuilt_tool_runs_the_edited_installers_output
                    && the_repaired_artifact_is_current_again
                    && a_sibling_artifacts_bytes_are_not_touched
                    && a_sibling_artifacts_mtime_is_not_touched
                    && a_sibling_artifacts_recorded_fingerprint_is_not_touched;

            NodeResult result = pass
                    ? NodeResult.pass("build.repairs.one.artifact")
                    : NodeResult.fail("build.repairs.one.artifact",
                            "both_fixtures_installed=" + both_fixtures_installed
                                    + " the_units_skill_script_was_edited="
                                    + the_units_skill_script_was_edited
                                    + " build_succeeds=" + build_succeeds
                                    + " build_reports_the_named_artifact_built="
                                    + build_reports_the_named_artifact_built
                                    + " build_leaves_nothing_it_selected_stale="
                                    + build_leaves_nothing_it_selected_stale
                                    + " the_rebuilt_tool_runs_the_edited_installers_output="
                                    + the_rebuilt_tool_runs_the_edited_installers_output
                                    + " the_repaired_artifact_is_current_again="
                                    + the_repaired_artifact_is_current_again
                                    + " a_sibling_artifacts_bytes_are_not_touched="
                                    + a_sibling_artifacts_bytes_are_not_touched
                                    + " a_sibling_artifacts_mtime_is_not_touched="
                                    + a_sibling_artifacts_mtime_is_not_touched
                                    + " a_sibling_artifacts_recorded_fingerprint_is_not_touched="
                                    + a_sibling_artifacts_recorded_fingerprint_is_not_touched
                                    + " | buildExit=" + build.exitCode()
                                    + " invokeExit=" + invoke.exitCode()
                                    + " staleExit=" + stale.exitCode()
                                    + " siblingMtime=" + siblingShimMtimeBefore + "/"
                                    + siblingTreeMtimeBefore
                                    + " -> " + ArtifactDagSupport.mtime(siblingShim) + "/"
                                    + ArtifactDagSupport.mtime(siblingTree.resolve("bin")
                                            .resolve(ArtifactDagSupport.TOOL_B)));
            return result
                    .process(installA).process(installB).process(build)
                    .process(invoke).process(stale)
                    .assertion("both_fixtures_installed", both_fixtures_installed)
                    .assertion("the_units_skill_script_was_edited",
                            the_units_skill_script_was_edited)
                    .assertion("build_succeeds", build_succeeds)
                    .assertion("build_reports_the_named_artifact_built",
                            build_reports_the_named_artifact_built)
                    .assertion("build_leaves_nothing_it_selected_stale",
                            build_leaves_nothing_it_selected_stale)
                    .assertion("the_rebuilt_tool_runs_the_edited_installers_output",
                            the_rebuilt_tool_runs_the_edited_installers_output)
                    .assertion("the_repaired_artifact_is_current_again",
                            the_repaired_artifact_is_current_again)
                    .assertion("a_sibling_artifacts_bytes_are_not_touched",
                            a_sibling_artifacts_bytes_are_not_touched)
                    .assertion("a_sibling_artifacts_mtime_is_not_touched",
                            a_sibling_artifacts_mtime_is_not_touched)
                    .assertion("a_sibling_artifacts_recorded_fingerprint_is_not_touched",
                            a_sibling_artifacts_recorded_fingerprint_is_not_touched)
                    .metric("skillScriptRuns", ArtifactDagSupport.skillScriptRuns(store))
                    .publish("home", store.toString());
        });
    }

    /** The {@code install_fingerprint} the lock records for one tool, or "". */
    private static String fingerprintOf(Path store, String tool) {
        for (String row : ArtifactDagSupport.cliLockRows(store)) {
            if (!ArtifactDagSupport.lockValue(row, "binary").equals(tool)) continue;
            return ArtifactDagSupport.lockValue(row, "install_fingerprint");
        }
        return "";
    }

    private static String read(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p) : "<absent:" + p + ">";
        } catch (Exception e) {
            return "<unreadable:" + p + ">";
        }
    }
}
