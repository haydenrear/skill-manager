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
 * Install examples/hello-doc-repo via the local-install path
 * ({@code install file://...}) so the resolver's doc-repo detection
 * ({@link dev.skillmanager.model.DocRepoParser#looksLikeDocRepo}) runs
 * against a real on-disk layout.
 *
 * <p>Asserts the unit landed under {@code $home/docs/hello-doc-repo/}
 * (the {@link dev.skillmanager.store.SkillStore} routing for
 * {@link dev.skillmanager.model.UnitKind#DOC}) and that the installed
 * record carries the right kind. Doc-repos don't project into agent
 * dirs, so {@code SyncAgents} should NOT have written any symlinks
 * under {@code .claude/skills/} or {@code .codex/skills/} for this
 * unit.
 */
public class DocRepoInstalled {
    static final NodeSpec SPEC = NodeSpec.of("doc.repo.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("env.prepared")
            .tags("install", "doc-repo", "ticket-48")
            .timeout("60s")
            .output("repoName", "string")
            .output("storeDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) return NodeResult.fail("doc.repo.installed", "missing env.prepared.home");

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path docRepo = repoRoot.resolve("examples/hello-doc-repo");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "file://" + docRepo.toString(), "--yes");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            ProcessRecord proc = Procs.run(ctx, "install", pb);
            int rc = proc.exitCode();

            Path storeDir = Path.of(home, "docs", "hello-doc-repo");
            boolean inStore = Files.isDirectory(storeDir);
            boolean tomlPresent = Files.isRegularFile(storeDir.resolve("skill-manager.toml"));
            boolean reviewMdPresent = Files.isRegularFile(storeDir.resolve("claude-md/review-stance.md"));

            // Verify NO agent symlinks landed for this unit — doc-repos
            // are explicitly bound via `bind`, not auto-projected.
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            boolean noClaudeSymlink = claudeHome == null
                    || !Files.exists(Path.of(claudeHome, ".claude", "skills", "hello-doc-repo"));
            boolean noCodexSymlink = codexHome == null
                    || !Files.exists(Path.of(codexHome, "skills", "hello-doc-repo"));

            boolean pass = rc == 0 && inStore && tomlPresent && reviewMdPresent
                    && noClaudeSymlink && noCodexSymlink;
            NodeResult result = pass
                    ? NodeResult.pass("doc.repo.installed")
                    : NodeResult.fail("doc.repo.installed",
                            "install exit=" + rc + " inStore=" + inStore
                                    + " toml=" + tomlPresent + " reviewMd=" + reviewMdPresent
                                    + " noClaudeSym=" + noClaudeSymlink + " noCodexSym=" + noCodexSymlink);
            return result
                    .process(proc)
                    .assertion("install_ok", rc == 0)
                    .assertion("store_dir_present", inStore)
                    .assertion("manifest_in_store", tomlPresent)
                    .assertion("source_in_store", reviewMdPresent)
                    .assertion("no_claude_symlink", noClaudeSymlink)
                    .assertion("no_codex_symlink", noCodexSymlink)
                    .metric("exitCode", rc)
                    .publish("repoName", "hello-doc-repo")
                    .publish("storeDir", storeDir.toString());
        });
    }
}
