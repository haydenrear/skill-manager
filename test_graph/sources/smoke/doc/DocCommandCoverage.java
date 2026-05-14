///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Regression coverage for kind-aware CLI commands while a doc-repo is
 * installed and has a live source binding.
 */
public class DocCommandCoverage {
    static final NodeSpec SPEC = NodeSpec.of("doc.command.coverage")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("doc.rebind.after.all.removed")
            .tags("doc-repo", "commands", "list", "show", "deps", "lock", "upgrade")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("doc.command.coverage", "missing env.prepared.home");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            String repoName = "hello-doc-repo";

            ProcessRecord list = run(ctx, "list", home, repoRoot, sm, "list");
            String listBody = readLog(ctx, "list");
            boolean listShowsDoc = list.exitCode() == 0
                    && listBody.contains("BINDINGS")
                    && listHasRow(listBody, repoName, "doc", "1");

            ProcessRecord show = run(ctx, "show", home, repoRoot, sm, "show", repoName);
            String showBody = readLog(ctx, "show");
            boolean showDisplaysDoc = show.exitCode() == 0
                    && showBody.contains("DOC  " + repoName + "@")
                    && showBody.contains("sources:")
                    && showBody.contains("review-stance");

            ProcessRecord deps = run(ctx, "deps", home, repoRoot, sm, "deps", repoName);
            String depsBody = readLog(ctx, "deps");
            boolean depsDisplaysDoc = deps.exitCode() == 0
                    && depsBody.contains(repoName + " (doc)");

            ProcessRecord refresh = run(ctx, "sync-refresh", home, repoRoot, sm,
                    "sync", "--refresh");

            ProcessRecord lock = run(ctx, "lock-status", home, repoRoot, sm,
                    "lock", "status");
            String lockBody = readLog(ctx, "lock-status");
            boolean lockIncludesDoc = refresh.exitCode() == 0
                    && lock.exitCode() == 0
                    && lockBody.contains("units.lock.toml is in sync");

            ProcessRecord upgrade = run(ctx, "upgrade", home, repoRoot, sm,
                    "upgrade", repoName);
            String upgradeBody = readLog(ctx, "upgrade");
            boolean upgradeRejectsDoc = upgrade.exitCode() == 5
                    && upgradeBody.contains("doc units do not support `upgrade`");

            boolean pass = listShowsDoc && showDisplaysDoc && depsDisplaysDoc
                    && lockIncludesDoc && upgradeRejectsDoc;
            NodeResult result = pass
                    ? NodeResult.pass("doc.command.coverage")
                    : NodeResult.fail("doc.command.coverage",
                            "list=" + listShowsDoc
                                    + " show=" + showDisplaysDoc
                                    + " deps=" + depsDisplaysDoc
                                    + " lock=" + lockIncludesDoc
                                    + " upgrade=" + upgradeRejectsDoc);
            return result
                    .process(list)
                    .process(show)
                    .process(deps)
                    .process(refresh)
                    .process(lock)
                    .process(upgrade)
                    .assertion("list_shows_doc_kind_and_binding_count", listShowsDoc)
                    .assertion("show_displays_doc_repo", showDisplaysDoc)
                    .assertion("deps_displays_doc_repo", depsDisplaysDoc)
                    .assertion("lock_status_accounts_for_doc_repo", lockIncludesDoc)
                    .assertion("upgrade_rejects_doc_repo_with_sync_hint", upgradeRejectsDoc)
                    .metric("listExitCode", list.exitCode())
                    .metric("showExitCode", show.exitCode())
                    .metric("depsExitCode", deps.exitCode())
                    .metric("refreshExitCode", refresh.exitCode())
                    .metric("lockExitCode", lock.exitCode())
                    .metric("upgradeExitCode", upgrade.exitCode());
        });
    }

    private static ProcessRecord run(NodeContext ctx, String label, String home,
                                     Path repoRoot, Path sm, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = sm.toString();
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        return Procs.run(ctx, label, pb);
    }

    private static String readLog(NodeContext ctx, String label) {
        try {
            return Files.readString(Procs.logFile(ctx, label));
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean listHasRow(String body, String name, String kind, String bindings) {
        for (String line : body.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 4 && parts[0].equals(name) && parts[1].equals(kind)) {
                return bindings == null || parts[3].equals(bindings);
            }
        }
        return false;
    }
}
