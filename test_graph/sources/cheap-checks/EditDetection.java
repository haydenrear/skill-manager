///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES CheapChecksKit.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GOAL-checking-costs-what-changed clause (a), the detection half: every planted edit
 * under a marketplace plugin still marks its {@code marketplace-entry:} row stale, so a
 * faster check cannot be a blinder one. Tracked, untracked and link are green at the
 * CC-0 baseline and must stay green through CC-1.
 *
 * <p><b>The gitignored arm is RED at baseline, and that is measured rather than designed.</b>
 * CC-1's slice says git status cannot see a gitignored file "and the current digest
 * does". It does not: {@code HomeDigest.withoutIgnored} drops the unit's gitignored
 * paths before digesting, on purpose (schema 2), so a gitignored file has never moved
 * this row. The arm asserts the epic target as written ("every planted edit"); CC-1
 * states the policy and, if the policy is that an ignored file is not content, says so
 * in this file rather than leaving it red. Baseline note: results/epic-cheap-checks/baseline/.
 *
 * <p>Plants, one at a time, restoring between them: a tracked edit, an untracked file,
 * a gitignored file (git status cannot see this one; CC-1 has to state the policy) and
 * a re-pointed marketplace link (to a byte-identical copy, so only the link target
 * differs). Each plant has a CONTROL: after restoring, the row is clean again, which
 * proves the detection was the plant and not a sticky row.
 */
public class EditDetection {
    static final String ID = "cheap.edit-detection";
    static final String PLUGIN = "cc-edit-plugin";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("cheap-checks", "artifacts-stale", "410", "edit-detection")
            .timeout("600s");

    interface Plant { void apply() throws Exception; }

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path home;
            ProcessRecord install;
            Path copy;
            try {
                root = CheapChecksKit.scratch(ctx, "edit-detection");
                home = root.resolve("home");
                CheapChecksKit.writePolicy(home);
                Path upstream = root.resolve("upstream");
                CheapChecksKit.writePlugin(upstream, PLUGIN, "marker");
                Files.writeString(upstream.resolve(".gitignore"), "ignored.txt\n");
                CheapChecksKit.git(upstream, "init", "-b", "main", "--quiet");
                CheapChecksKit.commitAll(upstream, "A");
                // Agent projection is what writes the marketplace link, so this install binds
                // (no --no-bind-default) through fake agent CLIs; real ones are never run.
                Path bin = root.resolve("fake-bin");
                CheapChecksKit.writeFakeAgents(bin, root.resolve("agent-cli.log"), root.resolve("claude-marketplaces"));
                install = CheapChecksKit.sm(ctx, "install", root, home, bin,
                        "install", "git+" + upstream.toUri(), "--yes");
                copy = root.resolve("identical-copy");
                CheapChecksKit.git(root, "clone", "--quiet", upstream.toString(), copy.toString());
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }
            Path plugin = home.resolve("plugins").resolve(PLUGIN);
            Path link = home.resolve("plugin-marketplace/plugins").resolve(PLUGIN);
            boolean setupInstalled = install.exitCode() == 0 && Files.isDirectory(plugin)
                    && Files.isSymbolicLink(link);
            boolean setupClean = !staleRow(ctx, "before", root, home);
            final Path pluginF = plugin;
            final Path linkF = link;
            final Path copyF = copy;
            Path original;
            try {
                original = Files.readSymbolicLink(link);
            } catch (Exception e) {
                return NodeResult.fail(ID, "no marketplace link to re-point: " + e)
                        .process(install).assertion("setup_installed_with_marketplace_link", false);
            }

            Arm tracked = arm(ctx, "tracked", root, home,
                    () -> Files.writeString(pluginF.resolve(".claude-plugin/plugin.json"),
                            Files.readString(pluginF.resolve(".claude-plugin/plugin.json")) + " "),
                    () -> CheapChecksKit.git(pluginF, "checkout", "--", "."));
            Arm untracked = arm(ctx, "untracked", root, home,
                    () -> Files.writeString(pluginF.resolve("untracked.txt"), "x"),
                    () -> Files.delete(pluginF.resolve("untracked.txt")));
            Arm gitignored = arm(ctx, "gitignored", root, home,
                    () -> Files.writeString(pluginF.resolve("ignored.txt"), "x"),
                    () -> Files.delete(pluginF.resolve("ignored.txt")));
            Arm relinked = arm(ctx, "repointed_link", root, home,
                    () -> { Files.delete(linkF); Files.createSymbolicLink(linkF, copyF); },
                    () -> { Files.delete(linkF); Files.createSymbolicLink(linkF, original); });

            boolean setup = setupInstalled && setupClean;
            boolean controls = tracked.restored && untracked.restored && gitignored.restored && relinked.restored;
            boolean shape = tracked.detected && untracked.detected && gitignored.detected && relinked.detected;
            String detail = "tracked=" + tracked + " untracked=" + untracked + " gitignored=" + gitignored
                    + " repointed_link=" + relinked + " setupInstalled=" + setupInstalled + " setupClean=" + setupClean;
            NodeResult result = setup && controls && shape ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(install)
                    .metric("planted_edits_detected", (tracked.detected ? 1 : 0) + (untracked.detected ? 1 : 0)
                            + (gitignored.detected ? 1 : 0) + (relinked.detected ? 1 : 0))
                    .assertion("setup_installed_with_marketplace_link", setupInstalled)
                    .assertion("setup_clean_checkout_row_is_not_stale", setupClean)
                    .assertion("control_row_clean_after_restoring_tracked_edit", tracked.restored)
                    .assertion("control_row_clean_after_removing_untracked_file", untracked.restored)
                    .assertion("control_row_clean_after_removing_gitignored_file", gitignored.restored)
                    .assertion("control_row_clean_after_restoring_link", relinked.restored)
                    .assertion("shape_tracked_edit_detected", tracked.detected)
                    .assertion("shape_untracked_file_detected", untracked.detected)
                    .assertion("shape_gitignored_file_detected", gitignored.detected)
                    .assertion("shape_repointed_link_detected", relinked.detected)
                    .log(detail);
        });
    }

    record Arm(boolean detected, boolean restored) {}

    static Arm arm(NodeContext ctx, String name, Path root, Path home, Plant plant, Plant restore) {
        try {
            plant.apply();
            boolean detected = staleRow(ctx, name + "_planted", root, home);
            restore.apply();
            boolean clean = !staleRow(ctx, name + "_restored", root, home);
            return new Arm(detected, clean);
        } catch (Exception e) {
            return new Arm(false, false);
        }
    }

    static boolean staleRow(NodeContext ctx, String label, Path root, Path home) {
        ProcessRecord r = CheapChecksKit.sm(ctx, label, root, home, "artifacts", "stale", "--json");
        return CheapChecksKit.output(ctx, r).contains("marketplace-entry:" + PLUGIN);
    }
}
