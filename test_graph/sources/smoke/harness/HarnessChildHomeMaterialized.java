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
 * Instantiate the smoke harness as a child Skill Manager home and verify
 * the public CLI creates a usable project-local harness root:
 *
 * <ul>
 *   <li>{@code <target>/.skill-manager} is a real SkillStore with installed records.</li>
 *   <li>{@code <target>/.codex}, {@code .claude}, and {@code .gemini} receive agent projections.</li>
 *   <li>Harness projections point at the child store, not the parent store.</li>
 *   <li>Parent CLI shims selected by the harness units are mirrored under the child home.</li>
 * </ul>
 */
public class HarnessChildHomeMaterialized {
    static final NodeSpec SPEC = NodeSpec.of("harness.child.home.materialized")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("harness.instance.materialized")
            .tags("harness", "child-home", "issue-75")
            .timeout("60s")
            .output("childHomeDir", "string")
            .output("childSkillManagerHome", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String harnessName = ctx.get("harness.transitive.installed", "harnessName").orElse(null);
            if (home == null || harnessName == null) {
                return NodeResult.fail("harness.child.home.materialized", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path target = Path.of(home, "child-harness-project");
            String instanceId = "child-smoke-instance";

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "harness", "instantiate", harnessName,
                    "--id", instanceId,
                    "--child-home-dir", target.toString());
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "instantiate-child-home", pb);
            int rc = proc.exitCode();

            Path childHome = target.resolve(".skill-manager");
            Path childSkill = childHome.resolve("skills/pip-cli-skill/latest");
            Path childPlugin = childHome.resolve("plugins/hello-plugin");
            Path childDoc = childHome.resolve("docs/hello-doc-repo");
            Path childHarness = childHome.resolve("harnesses/" + harnessName);
            boolean childStore = Files.isDirectory(childHome)
                    && Files.isDirectory(childHome.resolve("installed"))
                    && Files.isRegularFile(childHome.resolve("installed/pip-cli-skill.json"))
                    && Files.isRegularFile(childHome.resolve("installed/hello-plugin.json"))
                    && Files.isRegularFile(childHome.resolve("installed/hello-doc-repo.json"))
                    && Files.isRegularFile(childHome.resolve("installed/" + harnessName + ".json"));
            boolean childUnits = existsNoFollow(childSkill)
                    && existsNoFollow(childPlugin)
                    && existsNoFollow(childDoc)
                    && existsNoFollow(childHarness);
            boolean childAgentHomes = Files.isDirectory(target.resolve(".codex"))
                    && Files.isDirectory(target.resolve(".claude"))
                    && Files.isDirectory(target.resolve(".gemini"));

            Path codexSkill = target.resolve(".codex/skills/pip-cli-skill");
            Path claudeSkill = target.resolve(".claude/skills/pip-cli-skill");
            Path geminiSkill = target.resolve(".gemini/skills/pip-cli-skill");
            Path claudePlugin = target.resolve(".claude/plugins/hello-plugin");
            boolean agentProjections = Files.isSymbolicLink(codexSkill)
                    && Files.isSymbolicLink(claudeSkill)
                    && Files.isSymbolicLink(geminiSkill)
                    && Files.isSymbolicLink(claudePlugin);
            boolean projectionsUseChildStore = pointsTo(codexSkill, childSkill)
                    && pointsTo(claudeSkill, childSkill)
                    && pointsTo(geminiSkill, childSkill)
                    && pointsTo(claudePlugin, childPlugin);

            Path reviewDoc = target.resolve("docs/agents/review-stance.md");
            Path buildDoc = target.resolve("docs/agents/build-instructions.md");
            String claudeMd = Files.isRegularFile(target.resolve("CLAUDE.md"))
                    ? Files.readString(target.resolve("CLAUDE.md")) : "";
            String agentsMd = Files.isRegularFile(target.resolve("AGENTS.md"))
                    ? Files.readString(target.resolve("AGENTS.md")) : "";
            boolean docsBound = Files.isRegularFile(reviewDoc)
                    && Files.isRegularFile(buildDoc)
                    && claudeMd.contains("@docs/agents/review-stance.md")
                    && claudeMd.contains("@docs/agents/build-instructions.md")
                    && agentsMd.contains("@docs/agents/review-stance.md");

            Path cliShim = childHome.resolve("bin/cli/pycowsay");
            boolean cliShimMirrored = existsNoFollow(cliShim);

            Path lock = Path.of(home, "harnesses", "instances", instanceId, ".harness-instance.json");
            boolean lockPresent = Files.isRegularFile(lock);
            String lockJson = lockPresent ? Files.readString(lock) : "";
            boolean lockCarriesChildPaths = lockJson.contains(target.resolve(".claude").toString())
                    && lockJson.contains(target.resolve(".codex").toString())
                    && lockJson.contains(target.resolve(".gemini").toString())
                    && lockJson.contains(target.toString());

            Path childHomeRecord = Path.of(home, "child-homes", instanceId, "child-home.json");
            boolean childHomeRecordPresent = Files.isRegularFile(childHomeRecord);
            String childHomeRecordJson = childHomeRecordPresent ? Files.readString(childHomeRecord) : "";
            boolean childHomeClaimsSkill = childHomeRecordJson.contains("\"pip-cli-skill\"")
                    && childHomeRecordJson.contains("\"" + harnessName + "\"")
                    && childHomeRecordJson.contains(childHome.toString());

            Path skillLedger = Path.of(home, "installed", "pip-cli-skill.projections.json");
            String skillLedgerJson = Files.isRegularFile(skillLedger) ? Files.readString(skillLedger) : "";
            boolean parentLedgerTracksChild = skillLedgerJson.contains("\"harness:" + instanceId + ":pip-cli-skill\"")
                    && skillLedgerJson.contains(target.toString());

            ProcessBuilder removePb = new ProcessBuilder(sm.toString(), "remove", "pip-cli-skill");
            removePb.environment().put("SKILL_MANAGER_HOME", home);
            removePb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord removeProc = Procs.run(ctx, "remove-child-claimed-skill", removePb);
            String removeLog = Files.isRegularFile(Procs.logFile(ctx, "remove-child-claimed-skill"))
                    ? Files.readString(Procs.logFile(ctx, "remove-child-claimed-skill")) : "";
            boolean removeRejected = removeProc.exitCode() != 0
                    && removeLog.contains("child skill-manager home");

            boolean pass = rc == 0
                    && childStore
                    && childUnits
                    && childAgentHomes
                    && agentProjections
                    && projectionsUseChildStore
                    && docsBound
                    && cliShimMirrored
                    && lockPresent
                    && lockCarriesChildPaths
                    && childHomeRecordPresent
                    && childHomeClaimsSkill
                    && parentLedgerTracksChild
                    && removeRejected;
            NodeResult result = pass
                    ? NodeResult.pass("harness.child.home.materialized")
                    : NodeResult.fail("harness.child.home.materialized",
                            "rc=" + rc
                                    + " childStore=" + childStore
                                    + " childUnits=" + childUnits
                                    + " childAgentHomes=" + childAgentHomes
                                    + " agentProjections=" + agentProjections
                                    + " projectionsUseChildStore=" + projectionsUseChildStore
                                    + " docsBound=" + docsBound
                                    + " cliShimMirrored=" + cliShimMirrored
                                    + " lockPresent=" + lockPresent
                                    + " lockCarriesChildPaths=" + lockCarriesChildPaths
                                    + " childHomeRecordPresent=" + childHomeRecordPresent
                                    + " childHomeClaimsSkillAndHarness=" + childHomeClaimsSkill
                                    + " parentLedgerTracksChild=" + parentLedgerTracksChild
                                    + " removeRejected=" + removeRejected);
            return result
                    .process(proc)
                    .process(removeProc)
                    .assertion("instantiate_ok", rc == 0)
                    .assertion("child_store_initialized", childStore)
                    .assertion("child_units_projected_from_parent", childUnits)
                    .assertion("child_agent_homes_created", childAgentHomes)
                    .assertion("agent_projections_created", agentProjections)
                    .assertion("agent_projections_point_at_child_store", projectionsUseChildStore)
                    .assertion("docs_bound_into_child_project_root", docsBound)
                    .assertion("cli_shim_mirrored_into_child_home", cliShimMirrored)
                    .assertion("harness_instance_lock_present", lockPresent)
                    .assertion("harness_instance_lock_uses_child_paths", lockCarriesChildPaths)
                    .assertion("parent_child_home_registry_present", childHomeRecordPresent)
                    .assertion("child_home_registry_claims_skill_and_harness", childHomeClaimsSkill)
                    .assertion("parent_ledger_tracks_child_projection", parentLedgerTracksChild)
                    .assertion("plain_remove_rejects_child_home_claimed_skill", removeRejected)
                    .metric("exitCode", rc)
                    .metric("removeExitCode", removeProc.exitCode())
                    .publish("childHomeDir", target.toString())
                    .publish("childSkillManagerHome", childHome.toString());
        });
    }

    private static boolean existsNoFollow(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean pointsTo(Path symlink, Path expected) {
        try {
            if (!Files.isSymbolicLink(symlink)) return false;
            Path raw = Files.readSymbolicLink(symlink);
            Path resolved = raw.isAbsolute()
                    ? raw.normalize()
                    : symlink.getParent().resolve(raw).normalize();
            return resolved.equals(expected.toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }
}
