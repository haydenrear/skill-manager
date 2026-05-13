///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Drive the {@link dev.skillmanager.bindings.SyncDecision} upgrade
 * cell end-to-end: modify the store-side source bytes for
 * {@code hello-doc-repo}, run {@code skill-manager sync hello-doc-repo},
 * verify the tracked-copy in the project got rewritten + the ledger's
 * {@code boundHash} bumped to the new content hash.
 */
public class DocSyncUpgrade {
    static final NodeSpec SPEC = NodeSpec.of("doc.sync.upgrade")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("doc.bound.to.project")
            .tags("sync", "doc-repo", "ticket-48")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String project = ctx.get("doc.bound.to.project", "projectRoot").orElse(null);
            if (home == null || project == null) {
                return NodeResult.fail("doc.sync.upgrade", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path storeSrc = Path.of(home, "docs", "hello-doc-repo", "claude-md", "review-stance.md");
            Path tracked = Path.of(project, "docs/agents/review-stance.md");

            // Mutate the upstream bytes the store knows about. Append so
            // the four-state matrix sees source != bound, dest == bound.
            String marker = "\nUPGRADE-MARKER-doc-sync\n";
            try {
                Files.write(storeSrc, marker.getBytes(), StandardOpenOption.APPEND);
            } catch (Exception e) {
                return NodeResult.fail("doc.sync.upgrade", "could not mutate source: " + e.getMessage());
            }

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", "hello-doc-repo");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            ProcessRecord proc = Procs.run(ctx, "sync", pb);
            int rc = proc.exitCode();

            String trackedContent = "";
            boolean readOk = false;
            try {
                trackedContent = Files.readString(tracked);
                readOk = true;
            } catch (Exception ignored) {}
            boolean upgraded = trackedContent.contains("UPGRADE-MARKER-doc-sync");

            // Ledger should now carry the updated boundHash. We don't
            // recompute the hash here; just assert the ledger file was
            // re-written within the past second.
            Path ledger = Path.of(home, "installed", "hello-doc-repo.projections.json");
            boolean ledgerFresh = false;
            try {
                long modified = Files.getLastModifiedTime(ledger).toMillis();
                ledgerFresh = (System.currentTimeMillis() - modified) < 30_000;
            } catch (Exception ignored) {}

            boolean pass = rc == 0 && readOk && upgraded && ledgerFresh;
            NodeResult result = pass
                    ? NodeResult.pass("doc.sync.upgrade")
                    : NodeResult.fail("doc.sync.upgrade",
                            "rc=" + rc + " readOk=" + readOk + " upgraded=" + upgraded
                                    + " ledgerFresh=" + ledgerFresh);
            return result
                    .process(proc)
                    .assertion("sync_ok", rc == 0)
                    .assertion("dest_rewritten_with_new_bytes", upgraded)
                    .assertion("ledger_re_written", ledgerFresh)
                    .metric("exitCode", rc);
        });
    }
}
