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
 * Drive the {@link dev.skillmanager.bindings.SyncDecision#LOCALLY_EDITED}
 * cell: dest has user edits, source unchanged since last bound.
 * {@code sync} (without {@code --force}) must preserve the user's
 * dest bytes and surface a warning. Exit code is non-zero (warning
 * tier per the CLI exit contract) but no error.
 */
public class DocSyncLocalEditPreserved {
    static final NodeSpec SPEC = NodeSpec.of("doc.sync.local.edit.preserved")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("doc.sync.upgrade")
            .tags("sync", "doc-repo", "ticket-48")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String project = ctx.get("doc.bound.to.project", "projectRoot").orElse(null);
            if (home == null || project == null) {
                return NodeResult.fail("doc.sync.local.edit.preserved", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path tracked = Path.of(project, "docs/agents/review-stance.md");

            // Edit the dest locally — sync should see currentDest != bound,
            // currentSource == bound, and route to LOCALLY_EDITED.
            String localEdit = "USER-LOCAL-EDIT-do-not-clobber\n";
            try {
                Files.writeString(tracked, localEdit);
            } catch (Exception e) {
                return NodeResult.fail("doc.sync.local.edit.preserved",
                        "could not edit dest: " + e.getMessage());
            }

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", "hello-doc-repo");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "sync", pb);
            // Non-zero rc is expected on warning (SyncCommand surfaces
            // doc-binding warnings through the receipt's PARTIAL status).
            // We assert preservation regardless of rc — that's the
            // user-facing contract.

            String after = "";
            boolean readOk = false;
            try {
                after = Files.readString(tracked);
                readOk = true;
            } catch (Exception ignored) {}
            boolean preserved = after.equals(localEdit);

            boolean pass = readOk && preserved;
            NodeResult result = pass
                    ? NodeResult.pass("doc.sync.local.edit.preserved")
                    : NodeResult.fail("doc.sync.local.edit.preserved",
                            "rc=" + proc.exitCode() + " readOk=" + readOk + " preserved=" + preserved
                                    + " actual=" + (after.length() > 80 ? after.substring(0, 80) + "..." : after));
            return result
                    .process(proc)
                    .assertion("dest_preserved_without_force", preserved)
                    .metric("exitCode", proc.exitCode());
        });
    }
}
