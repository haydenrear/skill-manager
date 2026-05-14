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
 * {@code harness rm <instance>} walks every binding whose id matches
 * the {@code harness:<instance>:} prefix, emits
 * {@link dev.skillmanager.effects.SkillEffect.UnmaterializeProjection}
 * +
 * {@link dev.skillmanager.effects.SkillEffect.RemoveBinding}, then
 * deletes the sandbox dir.
 *
 * <p>Assertions after rm:
 * <ul>
 *   <li>{@code <home>/harnesses/instances/<id>/} is gone.</li>
 *   <li>No harness:&lt;id&gt;:* ids remain in the per-unit
 *       projection ledgers — `harness bindings list` would return
 *       zero rows for the instance.</li>
 *   <li>The harness template itself stays installed
 *       ({@code <home>/harnesses/<name>/} survives) — rm only tears
 *       down the instance.</li>
 *   <li>The transitive skill / plugin / doc-repo units stay in the
 *       store; they were installed independently of the harness
 *       template and other instances or explicit binds may need them.</li>
 * </ul>
 */
public class HarnessInstanceRemoved {
    static final NodeSpec SPEC = NodeSpec.of("harness.instance.removed")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("harness.instance.materialized", "harness.command.coverage")
            .tags("harness", "rm", "ticket-47", "ticket-49")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String instanceId = ctx.get("harness.instance.materialized", "instanceId").orElse(null);
            String claudeConfigDir = ctx.get("harness.instance.materialized", "claudeConfigDir").orElse(null);
            String codexHome = ctx.get("harness.instance.materialized", "codexHome").orElse(null);
            String projectDir = ctx.get("harness.instance.materialized", "projectDir").orElse(null);
            if (home == null || instanceId == null || claudeConfigDir == null
                    || codexHome == null || projectDir == null) {
                return NodeResult.fail("harness.instance.removed", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "harness", "rm", instanceId);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "harness-rm", pb);
            int rc = proc.exitCode();

            Path sandbox = Path.of(home, "harnesses", "instances", instanceId);
            boolean sandboxGone = !Files.exists(sandbox);

            // Every projection the instantiate node materialized must be
            // unmaterialized — across all three target paths.
            boolean claudeSkillGone = !Files.exists(
                    Path.of(claudeConfigDir, "skills/pip-cli-skill"),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            boolean codexSkillGone = !Files.exists(
                    Path.of(codexHome, "skills/pip-cli-skill"),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            boolean claudePluginGone = !Files.exists(
                    Path.of(claudeConfigDir, "plugins/hello-plugin"),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            // Doc projections — the project root's CLAUDE.md / AGENTS.md
            // managed sections + tracked copies should be gone.
            Path projectClaudeMd = Path.of(projectDir, "CLAUDE.md");
            Path projectAgentsMd = Path.of(projectDir, "AGENTS.md");
            boolean projectDocsCleared;
            if (!Files.exists(projectClaudeMd) && !Files.exists(projectAgentsMd)) {
                projectDocsCleared = true;
            } else {
                String claudeMdAfter = Files.exists(projectClaudeMd)
                        ? Files.readString(projectClaudeMd) : "";
                String agentsMdAfter = Files.exists(projectAgentsMd)
                        ? Files.readString(projectAgentsMd) : "";
                projectDocsCleared = !claudeMdAfter.contains("@docs/agents/")
                        && !agentsMdAfter.contains("@docs/agents/");
            }

            // Walk every projection-ledger file under installed/; none
            // should still carry a harness:<instanceId>: id.
            Path installedDir = Path.of(home, "installed");
            boolean[] anyHarnessIdRemains = {false};
            try (var stream = Files.newDirectoryStream(installedDir, "*.projections.json")) {
                for (Path f : stream) {
                    String content = Files.readString(f);
                    if (content.contains("harness:" + instanceId + ":")) {
                        anyHarnessIdRemains[0] = true;
                    }
                }
            } catch (Exception ignored) {}
            boolean ledgersCleaned = !anyHarnessIdRemains[0];

            // Template + transitive units survive.
            boolean templateStays = Files.isRegularFile(
                    Path.of(home, "harnesses", "smoke-harness", "harness.toml"));
            boolean skillStays = Files.isRegularFile(
                    Path.of(home, "skills", "pip-cli-skill", "SKILL.md"));
            boolean pluginStays = Files.isRegularFile(
                    Path.of(home, "plugins", "hello-plugin", ".claude-plugin/plugin.json"));
            boolean docStays = Files.isRegularFile(
                    Path.of(home, "docs", "hello-doc-repo", "skill-manager.toml"));

            boolean pass = rc == 0 && sandboxGone && ledgersCleaned
                    && claudeSkillGone && codexSkillGone && claudePluginGone
                    && projectDocsCleared
                    && templateStays && skillStays && pluginStays && docStays;
            NodeResult result = pass
                    ? NodeResult.pass("harness.instance.removed")
                    : NodeResult.fail("harness.instance.removed",
                            "rc=" + rc + " sandboxGone=" + sandboxGone
                                    + " ledgersCleaned=" + ledgersCleaned
                                    + " claudeSkillGone=" + claudeSkillGone
                                    + " codexSkillGone=" + codexSkillGone
                                    + " claudePluginGone=" + claudePluginGone
                                    + " projectDocsCleared=" + projectDocsCleared
                                    + " templateStays=" + templateStays
                                    + " skillStays=" + skillStays
                                    + " pluginStays=" + pluginStays
                                    + " docStays=" + docStays);
            return result
                    .process(proc)
                    .assertion("rm_ok", rc == 0)
                    .assertion("sandbox_dir_removed", sandboxGone)
                    .assertion("harness_ids_purged_from_ledger", ledgersCleaned)
                    .assertion("claude_skill_unmaterialized", claudeSkillGone)
                    .assertion("codex_skill_unmaterialized", codexSkillGone)
                    .assertion("claude_plugin_unmaterialized", claudePluginGone)
                    .assertion("project_dir_doc_imports_cleared", projectDocsCleared)
                    .assertion("template_survives_rm", templateStays)
                    .assertion("transitive_units_survive_rm",
                            skillStays && pluginStays && docStays)
                    .metric("exitCode", rc);
        });
    }
}
