///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uninstall the hello-doc-repo unit and verify the store cleanup
 * (#48 + #49):
 *
 * <ul>
 *   <li>{@code <home>/docs/hello-doc-repo/} dir removed.</li>
 *   <li>{@code <home>/installed/hello-doc-repo.json} record removed.</li>
 *   <li>{@code <home>/installed/hello-doc-repo.projections.json}
 *       ledger removed (uninstall walks the ledger first, then drops
 *       the file).</li>
 *   <li>Active binding into {@code <project>} (from the rebind step)
 *       gets torn down — tracked copy + CLAUDE.md import gone.</li>
 * </ul>
 *
 * <p>Exercises the ledger-walk path in
 * {@link dev.skillmanager.app.RemoveUseCase}: uninstall emits one
 * {@link dev.skillmanager.effects.SkillEffect.UnmaterializeProjection}
 * +
 * {@link dev.skillmanager.effects.SkillEffect.RemoveBinding} per
 * recorded binding before
 * {@link dev.skillmanager.effects.SkillEffect.RemoveUnitFromStore}.
 */
public class DocRepoUninstalled {
    static final NodeSpec SPEC = NodeSpec.of("doc.repo.uninstalled")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("doc.rebind.after.all.removed")
            .tags("uninstall", "doc-repo", "ticket-48", "ticket-49")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String project = ctx.get("doc.bind.two.sources", "projectRoot").orElse(null);
            if (home == null || project == null) {
                return NodeResult.fail("doc.repo.uninstalled", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "uninstall", "hello-doc-repo");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "uninstall", pb);
            int rc = proc.exitCode();

            Path storeDir = Path.of(home, "docs", "hello-doc-repo");
            Path installedRec = Path.of(home, "installed", "hello-doc-repo.json");
            Path ledger = Path.of(home, "installed", "hello-doc-repo.projections.json");
            Path tracked = Path.of(project, "docs/agents/review-stance.md");
            Path claudeMd = Path.of(project, "CLAUDE.md");

            boolean storeGone = !Files.exists(storeDir);
            boolean recGone = !Files.exists(installedRec);
            boolean ledgerGone = !Files.exists(ledger);
            boolean trackedGone = !Files.exists(tracked);
            // CLAUDE.md: managed section was the only content the binder
            // wrote, so uninstall's ledger walk drops the file.
            boolean claudeGoneOrClean;
            if (!Files.exists(claudeMd)) {
                claudeGoneOrClean = true;
            } else {
                String content = Files.readString(claudeMd);
                claudeGoneOrClean = !content.contains("@docs/agents/");
            }

            boolean pass = rc == 0 && storeGone && recGone && ledgerGone
                    && trackedGone && claudeGoneOrClean;
            NodeResult result = pass
                    ? NodeResult.pass("doc.repo.uninstalled")
                    : NodeResult.fail("doc.repo.uninstalled",
                            "rc=" + rc + " storeGone=" + storeGone
                                    + " recGone=" + recGone + " ledgerGone=" + ledgerGone
                                    + " trackedGone=" + trackedGone
                                    + " claudeGoneOrClean=" + claudeGoneOrClean);
            return result
                    .process(proc)
                    .assertion("uninstall_ok", rc == 0)
                    .assertion("store_dir_removed", storeGone)
                    .assertion("installed_record_removed", recGone)
                    .assertion("ledger_file_removed", ledgerGone)
                    .assertion("active_binding_torn_down", trackedGone && claudeGoneOrClean)
                    .metric("exitCode", rc);
        });
    }
}
