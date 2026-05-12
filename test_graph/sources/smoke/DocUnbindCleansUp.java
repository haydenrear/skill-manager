///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * After a successful bind + sync, {@code unbind <bindingId>} must:
 * <ul>
 *   <li>remove the tracked copy at {@code <project>/docs/agents/review-stance.md},</li>
 *   <li>drop the {@code @docs/agents/review-stance.md} line from the
 *       managed section in {@code CLAUDE.md} (and remove the section
 *       entirely if it had no other imports),</li>
 *   <li>delete the binding row from
 *       {@code <home>/installed/hello-doc-repo.projections.json}.</li>
 * </ul>
 *
 * <p>Pulls the binding id out of {@code bindings list} via a regex on
 * the {@code 01HA...}/{@code 01KR...}-shaped ULID strings the
 * {@link dev.skillmanager.bindings.BindingStore} mints.
 */
public class DocUnbindCleansUp {
    static final NodeSpec SPEC = NodeSpec.of("doc.unbind.cleans.up")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("doc.sync.force.clobbers")
            .tags("unbind", "doc-repo", "ticket-48", "ticket-49")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String project = ctx.get("doc.bound.to.project", "projectRoot").orElse(null);
            if (home == null || project == null) {
                return NodeResult.fail("doc.unbind.cleans.up", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // Resolve the binding id by reading the per-unit ledger file
            // directly — the JSON shape is stable
            // (BindingJson + ProjectionLedger). Easier than scraping the
            // bindings-list CLI output, which would require capturing
            // subprocess stdout via the log file.
            Path ledgerPath = Path.of(home, "installed", "hello-doc-repo.projections.json");
            String ledgerJson;
            try {
                ledgerJson = Files.readString(ledgerPath);
            } catch (Exception e) {
                return NodeResult.fail("doc.unbind.cleans.up",
                        "could not read ledger " + ledgerPath + ": " + e.getMessage());
            }
            // ULID shape from BindingStore.newBindingId: 26 chars,
            // Crockford-base32 (0-9, A-Z minus I L O U). First match wins.
            Matcher m = Pattern.compile("\"bindingId\"\\s*:\\s*\"([0-9A-HJKMNP-TV-Z]{26})\"")
                    .matcher(ledgerJson);
            if (!m.find()) {
                return NodeResult.fail("doc.unbind.cleans.up",
                        "could not find bindingId in ledger JSON");
            }
            String bindingId = m.group(1);

            ProcessBuilder pb = new ProcessBuilder(sm.toString(), "unbind", bindingId);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "unbind", pb);
            int rc = proc.exitCode();

            Path tracked = Path.of(project, "docs/agents/review-stance.md");
            Path claudeMd = Path.of(project, "CLAUDE.md");
            boolean trackedGone = !Files.exists(tracked);
            // The import line is "gone" if EITHER the markdown file no
            // longer exists (managed section was its only content — the
            // ManagedImports editor cleared everything, so unbind deleted
            // the stub file), OR the file exists but the @-line isn't
            // in it anymore.
            boolean importLineGone;
            if (!Files.exists(claudeMd)) {
                importLineGone = true;
            } else {
                String claudeContent = Files.readString(claudeMd);
                importLineGone = !claudeContent.contains("@docs/agents/review-stance.md");
            }

            // The ledger should no longer carry this bindingId.
            Path ledger = Path.of(home, "installed", "hello-doc-repo.projections.json");
            String ledgerContent = "";
            try { ledgerContent = Files.readString(ledger); } catch (Exception ignored) {}
            boolean ledgerRowGone = !ledgerContent.contains(bindingId);

            boolean pass = rc == 0 && trackedGone && importLineGone && ledgerRowGone;
            NodeResult result = pass
                    ? NodeResult.pass("doc.unbind.cleans.up")
                    : NodeResult.fail("doc.unbind.cleans.up",
                            "rc=" + rc + " trackedGone=" + trackedGone
                                    + " importLineGone=" + importLineGone
                                    + " ledgerRowGone=" + ledgerRowGone);
            return result
                    .process(proc)
                    .assertion("unbind_ok", rc == 0)
                    .assertion("tracked_copy_removed", trackedGone)
                    .assertion("import_line_removed", importLineGone)
                    .assertion("ledger_row_removed", ledgerRowGone)
                    .metric("exitCode", rc);
        });
    }
}
