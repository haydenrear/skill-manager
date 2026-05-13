///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * After {@link DocSyncLocalEditPreserved} left the dest holding a
 * local edit, re-run {@code sync --force} and verify the dest now
 * matches the upstream source bytes (the local edit is lost; the
 * user accepted that by passing {@code --force}).
 */
public class DocSyncForceClobbers {
    static final NodeSpec SPEC = NodeSpec.of("doc.sync.force.clobbers")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("doc.sync.local.edit.preserved")
            .tags("sync", "doc-repo", "ticket-48")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String project = ctx.get("doc.bound.to.project", "projectRoot").orElse(null);
            if (home == null || project == null) {
                return NodeResult.fail("doc.sync.force.clobbers", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path storeSrc = Path.of(home, "docs/hello-doc-repo/claude-md/review-stance.md");
            Path tracked = Path.of(project, "docs/agents/review-stance.md");

            String sourceBytes;
            try {
                sourceBytes = Files.readString(storeSrc);
            } catch (Exception e) {
                return NodeResult.fail("doc.sync.force.clobbers",
                        "could not read source: " + e.getMessage());
            }

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", "hello-doc-repo", "--force");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "sync-force", pb);

            String after = "";
            boolean readOk = false;
            try {
                after = Files.readString(tracked);
                readOk = true;
            } catch (Exception ignored) {}
            boolean matchesSource = after.equals(sourceBytes);

            boolean pass = readOk && matchesSource;
            NodeResult result = pass
                    ? NodeResult.pass("doc.sync.force.clobbers")
                    : NodeResult.fail("doc.sync.force.clobbers",
                            "rc=" + proc.exitCode() + " readOk=" + readOk
                                    + " matchesSource=" + matchesSource);
            return result
                    .process(proc)
                    .assertion("dest_matches_source_after_force", matchesSource)
                    .metric("exitCode", proc.exitCode());
        });
    }
}
