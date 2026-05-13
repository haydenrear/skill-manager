///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Instantiate the smoke harness with all three location flags passed
 * explicitly — {@code --claude-config-dir}, {@code --codex-home},
 * {@code --project-dir} — and verify every projection lands at the
 * agent-discoverable path (not a sandbox dead-letter dir):
 *
 * <ul>
 *   <li>{@code <claudeConfigDir>/skills/pip-cli-skill}  — claude skill symlink</li>
 *   <li>{@code <codexHome>/skills/pip-cli-skill}        — codex skill symlink</li>
 *   <li>{@code <claudeConfigDir>/plugins/hello-plugin}  — claude plugin symlink</li>
 *   <li>{@code <projectDir>/docs/agents/<file>.md}      — tracked doc copies</li>
 *   <li>{@code <projectDir>/CLAUDE.md} + {@code AGENTS.md} — managed imports</li>
 * </ul>
 *
 * <p>Also verifies the lock file at
 * {@code <store>/harnesses/instances/<id>/.harness-instance.json}
 * captures the resolved paths so {@code sync harness:<name>} can
 * re-plan with the same layout.
 */
public class HarnessInstanceMaterialized {
    static final NodeSpec SPEC = NodeSpec.of("harness.instance.materialized")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("harness.transitive.installed")
            .tags("instantiate", "harness", "ticket-47")
            .timeout("60s")
            .output("instanceId", "string")
            .output("claudeConfigDir", "string")
            .output("codexHome", "string")
            .output("projectDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("harness.instance.materialized", "missing env.prepared.home");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // Pick distinct dirs OUTSIDE the sandbox to prove the
            // instantiator writes at the explicit paths, not at its
            // sandbox-subdir fallbacks.
            Path claudeConfigDir = Path.of(home, "agent-harness-claude");
            Path codexHome = Path.of(home, "agent-harness-codex");
            Path projectDir = Path.of(home, "agent-harness-project");
            String instanceId = "smoke-instance";

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "harness", "instantiate", "smoke-harness",
                    "--id", instanceId,
                    "--claude-config-dir", claudeConfigDir.toString(),
                    "--codex-home", codexHome.toString(),
                    "--project-dir", projectDir.toString());
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "instantiate", pb);
            int rc = proc.exitCode();

            // Per-agent symlinks land where the agents actually look.
            Path claudeSkill = claudeConfigDir.resolve("skills/pip-cli-skill");
            Path codexSkill = codexHome.resolve("skills/pip-cli-skill");
            Path claudePlugin = claudeConfigDir.resolve("plugins/hello-plugin");

            boolean claudeSkillSym = Files.isSymbolicLink(claudeSkill)
                    && Files.exists(claudeSkill, LinkOption.NOFOLLOW_LINKS);
            boolean codexSkillSym = Files.isSymbolicLink(codexSkill)
                    && Files.exists(codexSkill, LinkOption.NOFOLLOW_LINKS);
            boolean claudePluginSym = Files.isSymbolicLink(claudePlugin)
                    && Files.exists(claudePlugin, LinkOption.NOFOLLOW_LINKS);

            // Plugin must NOT land in codex — Codex doesn't load plugins
            // and a stray symlink there would be misleading.
            Path codexPluginGhost = codexHome.resolve("plugins/hello-plugin");
            boolean noCodexPlugin = !Files.exists(codexPluginGhost, LinkOption.NOFOLLOW_LINKS);

            // Docs land in projectDir, not the sandbox or agent dirs.
            Path reviewDoc = projectDir.resolve("docs/agents/review-stance.md");
            Path buildDoc = projectDir.resolve("docs/agents/build-instructions.md");
            Path claudeMd = projectDir.resolve("CLAUDE.md");
            Path agentsMd = projectDir.resolve("AGENTS.md");
            boolean reviewTracked = Files.isRegularFile(reviewDoc);
            boolean buildTracked = Files.isRegularFile(buildDoc);
            String claudeContent = Files.isRegularFile(claudeMd) ? Files.readString(claudeMd) : "";
            String agentsContent = Files.isRegularFile(agentsMd) ? Files.readString(agentsMd) : "";
            boolean claudeHasReview = claudeContent.contains("@docs/agents/review-stance.md");
            boolean claudeHasBuild = claudeContent.contains("@docs/agents/build-instructions.md");
            boolean agentsHasReview = agentsContent.contains("@docs/agents/review-stance.md");

            // Confirm the OLD sandbox layout is NOT used — earlier
            // versions wrote skills/plugins into <sandbox>/<id>/skills/,
            // which agents never read.
            Path sandboxStub = Path.of(home, "harnesses", "instances", instanceId);
            boolean noSandboxSkills = !Files.exists(sandboxStub.resolve("skills/pip-cli-skill"),
                    LinkOption.NOFOLLOW_LINKS);
            boolean noSandboxPlugins = !Files.exists(sandboxStub.resolve("plugins/hello-plugin"),
                    LinkOption.NOFOLLOW_LINKS);

            // Lock file persists the resolved paths so `sync` can recover them.
            Path lock = sandboxStub.resolve(".harness-instance.json");
            boolean lockPresent = Files.isRegularFile(lock);
            String lockJson = lockPresent ? Files.readString(lock) : "";
            boolean lockCarriesClaudePath = lockJson.contains(claudeConfigDir.toString());
            boolean lockCarriesCodexPath = lockJson.contains(codexHome.toString());
            boolean lockCarriesProjectPath = lockJson.contains(projectDir.toString());

            // Ledger sanity.
            Path docLedger = Path.of(home, "installed", "hello-doc-repo.projections.json");
            String docLedgerJson = Files.isRegularFile(docLedger) ? Files.readString(docLedger) : "";
            boolean harnessBindingIds = docLedgerJson.contains(
                    "\"harness:" + instanceId + ":hello-doc-repo:");
            boolean sourceIsHarness = docLedgerJson.contains("\"HARNESS\"");

            boolean pass = rc == 0
                    && claudeSkillSym && codexSkillSym && claudePluginSym
                    && noCodexPlugin
                    && reviewTracked && buildTracked
                    && claudeHasReview && claudeHasBuild && agentsHasReview
                    && noSandboxSkills && noSandboxPlugins
                    && lockPresent && lockCarriesClaudePath
                    && lockCarriesCodexPath && lockCarriesProjectPath
                    && harnessBindingIds && sourceIsHarness;
            NodeResult result = pass
                    ? NodeResult.pass("harness.instance.materialized")
                    : NodeResult.fail("harness.instance.materialized",
                            "rc=" + rc
                                    + " claudeSkill=" + claudeSkillSym
                                    + " codexSkill=" + codexSkillSym
                                    + " claudePlugin=" + claudePluginSym
                                    + " noCodexPlugin=" + noCodexPlugin
                                    + " reviewTracked=" + reviewTracked
                                    + " buildTracked=" + buildTracked
                                    + " claudeImports=" + (claudeHasReview && claudeHasBuild)
                                    + " agentsImport=" + agentsHasReview
                                    + " noSandboxSkills=" + noSandboxSkills
                                    + " noSandboxPlugins=" + noSandboxPlugins
                                    + " lockPresent=" + lockPresent
                                    + " lockPaths=" + (lockCarriesClaudePath
                                            && lockCarriesCodexPath
                                            && lockCarriesProjectPath)
                                    + " ledgerScoped=" + harnessBindingIds
                                    + " sourceHARNESS=" + sourceIsHarness);
            return result
                    .process(proc)
                    .assertion("instantiate_ok", rc == 0)
                    .assertion("claude_skill_at_claude_config_dir", claudeSkillSym)
                    .assertion("codex_skill_at_codex_home", codexSkillSym)
                    .assertion("claude_plugin_at_claude_config_dir", claudePluginSym)
                    .assertion("plugin_not_in_codex", noCodexPlugin)
                    .assertion("docs_in_project_dir", reviewTracked && buildTracked)
                    .assertion("claude_md_imports_both_docs", claudeHasReview && claudeHasBuild)
                    .assertion("agents_md_imports_codex_doc", agentsHasReview)
                    .assertion("no_dead_letter_sandbox_skills", noSandboxSkills)
                    .assertion("no_dead_letter_sandbox_plugins", noSandboxPlugins)
                    .assertion("lock_file_persisted", lockPresent)
                    .assertion("lock_carries_resolved_paths",
                            lockCarriesClaudePath && lockCarriesCodexPath && lockCarriesProjectPath)
                    .assertion("ledger_binding_ids_are_harness_scoped", harnessBindingIds)
                    .assertion("ledger_source_is_HARNESS", sourceIsHarness)
                    .metric("exitCode", rc)
                    .publish("instanceId", instanceId)
                    .publish("claudeConfigDir", claudeConfigDir.toString())
                    .publish("codexHome", codexHome.toString())
                    .publish("projectDir", projectDir.toString());
        });
    }
}
