///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES CheapChecksKit.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GOAL-checking-costs-what-changed clause (b), #401: {@code home close-out} under 5 s
 * and under 300 MB RSS on a home holding {@code tla-spec-dev}.
 *
 * <p>Fixture: two {@code home clone}s of this checkout's project home. Closing one into
 * the other is the worktree-to-project shape. This is NOT #401's fixture (another
 * repository's 31,756-file, 428 MB home); the plugin here is this repository's own
 * copy, and the file count is in the evidence.
 *
 * <p>CONTROL: an unpublished edit to the plugin still blocks by name (exit non-zero),
 * so a speedup that stops looking turns this red.
 *
 * <p>CONTROL: an edit to a small unit in the same large home blocks by name, and is
 * caught at about the speed of a clean close-out: a fast path that made the clean
 * case fast by making the dirty case skip the home would pass a clock on the first.
 */
public class CloseOutTime {
    static final String ID = "cheap.close-out";
    static final double TARGET_SECONDS = 5.0;
    static final double TARGET_RSS_MB = 300.0;
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("cheap-checks", "close-out", "401", "timing")
            .timeout("900s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path a;
            Path b;
            ProcessRecord cloneA;
            ProcessRecord cloneB;
            try {
                root = CheapChecksKit.scratch(ctx, "close-out");
                a = root.resolve("worktree-home");
                b = root.resolve("project-home");
                cloneA = CheapChecksKit.cloneHome(ctx, "clone_a", root, CheapChecksKit.projectHome(), a);
                cloneB = CheapChecksKit.cloneHome(ctx, "clone_b", root, CheapChecksKit.projectHome(), b);
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }
            Path plugin = a.resolve("plugins/tla-spec-dev");
            boolean setupCloned = cloneA.exitCode() == 0 && cloneB.exitCode() == 0 && Files.isDirectory(plugin);
            long pluginFiles;
            try {
                pluginFiles = CheapChecksKit.fileCount(plugin);
            } catch (Exception e) {
                pluginFiles = -1;
            }
            CheapChecksKit.sm(ctx, "warmup", root, a, "home", "close-out", "--home", a.toString(), "--into", b.toString());

            double[] seconds = new double[3];
            double[] rss = new double[3];
            boolean measured = true;
            int exit = -1;
            String out = "";
            for (int i = 0; i < 3; i++) {
                CheapChecksKit.Timing t = CheapChecksKit.timed(ctx, "closeout_" + i, root, a,
                        "home", "close-out", "--home", a.toString(), "--into", b.toString(), "--json");
                seconds[i] = t.seconds();
                rss[i] = t.rssMb();
                measured &= t.measured();
                exit = t.process().exitCode();
                out = t.output();
            }
            double median = CheapChecksKit.median(seconds);
            double medianRss = CheapChecksKit.median(rss);
            boolean controlCleanSafe = exit == 0 && out.contains("\"safe\"") && !out.contains("\"safe\" : false")
                    && !out.contains("\"safe\":false");

            boolean controlEditBlocks = false;
            boolean controlSmallEditBlocks = false;
            double smallEditSeconds = -1;
            double editedPluginSeconds = -1;
            ProcessRecord blocked = null;
            ProcessRecord blockedSmall = null;
            try {
                // First, while the plugin is clean: the other units stay on the
                // fast path, so this reading is the cost of finding one real edit.
                Path smallUnit = a.resolve("skills/tracing-observability/SKILL.md");
                String original = Files.readString(smallUnit);
                Files.writeString(smallUnit, original + "\ncheap-checks unpublished edit\n");
                CheapChecksKit.Timing small = CheapChecksKit.timed(ctx, "closeout_small_edit", root, a,
                        "home", "close-out", "--home", a.toString(), "--into", b.toString(), "--json");
                Files.writeString(smallUnit, original);
                blockedSmall = small.process();
                smallEditSeconds = small.seconds();
                controlSmallEditBlocks = blockedSmall.exitCode() != 0
                        && small.output().contains("tracing-observability");

                Path readme = plugin.resolve("README.md");
                Files.writeString(readme, Files.readString(readme) + "\ncheap-checks unpublished edit\n");
                CheapChecksKit.Timing edited = CheapChecksKit.timed(ctx, "closeout_edited", root, a,
                        "home", "close-out", "--home", a.toString(), "--into", b.toString(), "--json");
                blocked = edited.process();
                editedPluginSeconds = edited.seconds();
                controlEditBlocks = blocked.exitCode() != 0
                        && edited.output().contains("tla-spec-dev");
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed planting the edit: " + e).assertion("setup_ok", false);
            }

            // Against the clean close-out just measured, not the absolute target: this
            // reads one small unit's bytes more than the clean run, so it should
            // cost about what the clean run costs.
            boolean smallEditFast = smallEditSeconds >= 0 && smallEditSeconds <= median * 1.5 + 1.0;
            boolean timeOk = median < TARGET_SECONDS;
            boolean rssOk = medianRss < TARGET_RSS_MB;
            String json = "{\"fixture\":\"two home clones of the project home holding tla-spec-dev\","
                    + "\"plugin_files\":" + pluginFiles + ",\"seconds\":" + java.util.Arrays.toString(seconds)
                    + ",\"median_seconds\":" + median + ",\"rss_mb\":" + java.util.Arrays.toString(rss)
                    + ",\"median_rss_mb\":" + medianRss + ",\"target_seconds\":" + TARGET_SECONDS
                    + ",\"target_rss_mb\":" + TARGET_RSS_MB + ",\"clean_exit\":" + exit
                    + ",\"small_unit_edit_seconds\":" + smallEditSeconds
                    + ",\"plugin_edit_seconds\":" + editedPluginSeconds + "}";
            String detail;
            try {
                detail = "evidence=" + CheapChecksKit.evidence(ctx, "close-out", json) + " " + json;
            } catch (Exception e) {
                detail = json;
            }
            boolean setup = setupCloned && measured;
            NodeResult result = setup && controlCleanSafe && controlEditBlocks && controlSmallEditBlocks
                    && smallEditFast && timeOk && rssOk
                    ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(cloneA).process(cloneB).process(blocked).process(blockedSmall)
                    .metric("median_seconds", median)
                    .metric("median_rss_mb", medianRss)
                    .metric("plugin_files", pluginFiles)
                    .metric("small_unit_edit_seconds", smallEditSeconds)
                    .metric("plugin_edit_seconds", editedPluginSeconds)
                    .assertion("setup_two_clones_hold_plugin", setupCloned)
                    .assertion("setup_time_l_parsed", measured)
                    .assertion("control_clean_home_closes_safe", controlCleanSafe)
                    .assertion("control_unpublished_edit_blocks_by_name", controlEditBlocks)
                    .assertion("control_small_unit_edit_blocks_by_name", controlSmallEditBlocks)
                    .assertion("shape_small_unit_edit_caught_at_clean_closeout_speed", smallEditFast)
                    .assertion("shape_close_out_under_5s", timeOk)
                    .assertion("shape_close_out_under_300mb_rss", rssOk)
                    .log(detail);
        });
    }
}
