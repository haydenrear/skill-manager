///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Dirty-state guard for {@code skill-manager sync --from <dir>}: when
 * the installed copy is git-tracked AND has uncommitted edits or
 * commits ahead of the recorded baseline, sync must refuse to
 * overwrite, exit 7, and print actionable {@code git fetch}/{@code
 * git merge} instructions plus the equivalent {@code skill-manager
 * sync … --merge} invocation.
 *
 * <p>This is the {@code rc=7} contract — automation reading the
 * {@code rc=7} banner needs the merge metadata to act on. The store's
 * dirty state is intentionally left in place; the next node
 * ({@code source.sync.merges.clean}) reuses it to exercise the
 * {@code --merge} happy path.
 */
public class SourceSyncRefusesOnDirty {
    static final NodeSpec SPEC = NodeSpec.of("source.sync.refuses_on_dirty")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("source.fixture.installed")
            .tags("source-tracking", "sync", "abort")
            .timeout("30s")
            .output("dirtyStoreDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String fixtureDir = ctx.get("source.fixture.published", "skillDir").orElse(null);
            String skillName = ctx.get("source.fixture.published", "skillName").orElse(null);
            String storeDir = ctx.get("source.fixture.installed", "storeDir").orElse(null);
            if (home == null || claudeHome == null || codexHome == null
                    || fixtureDir == null || skillName == null || storeDir == null) {
                return NodeResult.fail("source.sync.refuses_on_dirty", "missing upstream context");
            }

            // Drift the install — append a line so working tree is dirty.
            Path skillMd = Path.of(storeDir).resolve("SKILL.md");
            Files.writeString(skillMd,
                    "\n\nlocal-edit-from-test-graph\n",
                    StandardOpenOption.APPEND);

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", skillName, "--from", fixtureDir)
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);

            StringBuilder out = new StringBuilder();
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println(line);
                    out.append(line).append('\n');
                }
            }
            int rc = p.waitFor();
            String body = out.toString();

            boolean exitedSeven = rc == 7;
            // The banner is structured so harnesses can match on it.
            boolean mentionsLocalChanges = body.contains("has local changes");
            boolean mentionsFetch = body.contains("git fetch") && body.contains("HEAD");
            boolean mentionsMergeFlag = body.contains("--merge");
            // Local edit must still be on disk — sync mustn't have clobbered it.
            String afterMd = Files.readString(skillMd);
            boolean editPreserved = afterMd.contains("local-edit-from-test-graph");

            boolean pass = exitedSeven && mentionsLocalChanges && mentionsFetch
                    && mentionsMergeFlag && editPreserved;
            return (pass
                    ? NodeResult.pass("source.sync.refuses_on_dirty")
                    : NodeResult.fail("source.sync.refuses_on_dirty",
                            "rc=" + rc + " local=" + mentionsLocalChanges
                                    + " fetch=" + mentionsFetch
                                    + " mergeFlag=" + mentionsMergeFlag
                                    + " editPreserved=" + editPreserved))
                    .assertion("exited_with_rc_7", exitedSeven)
                    .assertion("banner_mentions_local_changes", mentionsLocalChanges)
                    .assertion("banner_includes_git_fetch_recipe", mentionsFetch)
                    .assertion("banner_includes_merge_flag_recipe", mentionsMergeFlag)
                    .assertion("local_edit_preserved", editPreserved)
                    .publish("dirtyStoreDir", storeDir);
        });
    }
}
