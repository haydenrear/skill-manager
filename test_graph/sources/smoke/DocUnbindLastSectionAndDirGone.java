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
 * Unbind the LAST remaining binding (build-instructions) and verify:
 *
 * <ul>
 *   <li>{@code <project>/docs/agents/build-instructions.md} removed.</li>
 *   <li>{@code <project>/CLAUDE.md} either gone (if the managed section
 *       was its only content) or carries no skill-manager import lines
 *       and no managed markers.</li>
 *   <li>The {@code docs/agents/} dir is pruned because it's now empty.
 *       Its parent {@code docs/} dir is also pruned (both owned by the
 *       doc-repo binder convention).</li>
 *   <li>The per-unit projection-ledger file is removed
 *       ({@link dev.skillmanager.bindings.BindingStore#write} drops it
 *       when the bindings list goes empty).</li>
 * </ul>
 */
public class DocUnbindLastSectionAndDirGone {
    static final NodeSpec SPEC = NodeSpec.of("doc.unbind.last.section.and.dir.gone")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("doc.unbind.one.of.two")
            .tags("unbind", "doc-repo", "ticket-48", "ticket-49")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String project = ctx.get("doc.bind.two.sources", "projectRoot").orElse(null);
            if (home == null || project == null) {
                return NodeResult.fail("doc.unbind.last.section.and.dir.gone",
                        "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            Path ledger = Path.of(home, "installed", "hello-doc-repo.projections.json");
            String ledgerJson;
            try {
                ledgerJson = Files.readString(ledger);
            } catch (Exception e) {
                return NodeResult.fail("doc.unbind.last.section.and.dir.gone",
                        "cannot read ledger: " + e.getMessage());
            }
            Matcher m = Pattern.compile("\"bindingId\"\\s*:\\s*\"([0-9A-HJKMNP-TV-Z]{26})\"")
                    .matcher(ledgerJson);
            if (!m.find()) {
                return NodeResult.fail("doc.unbind.last.section.and.dir.gone",
                        "could not find binding id");
            }
            String bindingId = m.group(1);

            ProcessBuilder pb = new ProcessBuilder(sm.toString(), "unbind", bindingId);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "unbind-build", pb);
            int rc = proc.exitCode();

            Path docsAgentsDir = Path.of(project, "docs/agents");
            Path docsDir = Path.of(project, "docs");
            Path buildTracked = docsAgentsDir.resolve("build-instructions.md");
            Path claudeMd = Path.of(project, "CLAUDE.md");

            boolean buildGone = !Files.exists(buildTracked);
            boolean agentsDirPruned = !Files.exists(docsAgentsDir);
            boolean docsDirPruned = !Files.exists(docsDir);

            // CLAUDE.md should either be gone OR carry no skill-manager
            // markers — the managed section was the only content the
            // binder wrote, so removeLine -> empty -> reverseProjection
            // deletes the file.
            boolean claudeGoneOrClean;
            if (!Files.exists(claudeMd)) {
                claudeGoneOrClean = true;
            } else {
                String content = Files.readString(claudeMd);
                claudeGoneOrClean = !content.contains("<!-- skill-manager:imports start -->")
                        && !content.contains("@docs/agents/");
            }

            // Per-unit ledger file should be gone — BindingStore.write
            // drops the file when bindings list goes empty.
            boolean ledgerFileGone = !Files.exists(ledger);

            boolean pass = rc == 0 && buildGone && agentsDirPruned && docsDirPruned
                    && claudeGoneOrClean && ledgerFileGone;
            NodeResult result = pass
                    ? NodeResult.pass("doc.unbind.last.section.and.dir.gone")
                    : NodeResult.fail("doc.unbind.last.section.and.dir.gone",
                            "rc=" + rc + " buildGone=" + buildGone
                                    + " agentsDirPruned=" + agentsDirPruned
                                    + " docsDirPruned=" + docsDirPruned
                                    + " claudeGoneOrClean=" + claudeGoneOrClean
                                    + " ledgerFileGone=" + ledgerFileGone);
            return result
                    .process(proc)
                    .assertion("unbind_ok", rc == 0)
                    .assertion("build_tracked_removed", buildGone)
                    .assertion("docs_agents_dir_pruned", agentsDirPruned)
                    .assertion("docs_dir_pruned", docsDirPruned)
                    .assertion("claude_md_gone_or_clean", claudeGoneOrClean)
                    .assertion("ledger_file_dropped", ledgerFileGone)
                    .metric("exitCode", rc);
        });
    }
}
