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
 * Teardown for a harness instance created with --child-home-dir.
 * The parent-side child-home registry claim must be removed with the
 * harness instance, otherwise plain remove stays blocked by a stale
 * child home record.
 */
public class HarnessChildHomeRemoved {
    static final NodeSpec SPEC = NodeSpec.of("harness.child.home.removed")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("harness.child.home.materialized", "harness.command.coverage")
            .tags("harness", "child-home", "rm", "issue-75")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String harnessName = ctx.get("harness.transitive.installed", "harnessName").orElse(null);
            String targetDir = ctx.get("harness.child.home.materialized", "childHomeDir").orElse(null);
            String childHome = ctx.get("harness.child.home.materialized", "childSkillManagerHome").orElse(null);
            if (home == null || harnessName == null || targetDir == null || childHome == null) {
                return NodeResult.fail("harness.child.home.removed", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            String instanceId = "child-smoke-instance";

            ProcessBuilder rmPb = new ProcessBuilder(sm.toString(), "harness", "rm", instanceId);
            rmPb.environment().put("SKILL_MANAGER_HOME", home);
            rmPb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord rmProc = Procs.run(ctx, "rm-child-home-harness", rmPb);
            int rmRc = rmProc.exitCode();

            Path sandbox = Path.of(home, "harnesses", "instances", instanceId);
            boolean sandboxGone = !Files.exists(sandbox);

            Path childHomeRecord = Path.of(home, "child-homes", instanceId, "child-home.json");
            boolean childHomeRecordGone = !Files.exists(childHomeRecord);

            Path target = Path.of(targetDir);
            boolean childAgentProjectionsGone = !existsNoFollow(target.resolve(".codex/skills/pip-cli-skill"))
                    && !existsNoFollow(target.resolve(".claude/skills/pip-cli-skill"))
                    && !existsNoFollow(target.resolve(".gemini/skills/pip-cli-skill"))
                    && !existsNoFollow(target.resolve(".claude/plugins/hello-plugin"));

            boolean childStoreStays = Files.isDirectory(Path.of(childHome))
                    && Files.isRegularFile(Path.of(childHome, "installed/pip-cli-skill.json"))
                    && Files.isRegularFile(Path.of(childHome, "installed/" + harnessName + ".json"));

            boolean ledgersCleaned = true;
            Path installedDir = Path.of(home, "installed");
            try (var stream = Files.newDirectoryStream(installedDir, "*.projections.json")) {
                for (Path f : stream) {
                    if (Files.readString(f).contains("harness:" + instanceId + ":")) {
                        ledgersCleaned = false;
                    }
                }
            }

            ProcessBuilder dryRunRemovePb = new ProcessBuilder(
                    sm.toString(), "remove", "pip-cli-skill", "--dry-run");
            dryRunRemovePb.environment().put("SKILL_MANAGER_HOME", home);
            dryRunRemovePb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord dryRunRemoveProc = Procs.run(ctx, "remove-after-child-home-rm", dryRunRemovePb);
            String dryRunRemoveLog = Files.isRegularFile(Procs.logFile(ctx, "remove-after-child-home-rm"))
                    ? Files.readString(Procs.logFile(ctx, "remove-after-child-home-rm")) : "";
            boolean staleClaimReleased = dryRunRemoveProc.exitCode() == 0
                    && !dryRunRemoveLog.contains("child skill-manager home");

            boolean pass = rmRc == 0
                    && sandboxGone
                    && childHomeRecordGone
                    && childAgentProjectionsGone
                    && childStoreStays
                    && ledgersCleaned
                    && staleClaimReleased;
            NodeResult result = pass
                    ? NodeResult.pass("harness.child.home.removed")
                    : NodeResult.fail("harness.child.home.removed",
                            "rmRc=" + rmRc
                                    + " sandboxGone=" + sandboxGone
                                    + " childHomeRecordGone=" + childHomeRecordGone
                                    + " childAgentProjectionsGone=" + childAgentProjectionsGone
                                    + " childStoreStays=" + childStoreStays
                                    + " ledgersCleaned=" + ledgersCleaned
                                    + " staleClaimReleased=" + staleClaimReleased);
            return result
                    .process(rmProc)
                    .process(dryRunRemoveProc)
                    .assertion("harness_rm_ok", rmRc == 0)
                    .assertion("sandbox_removed", sandboxGone)
                    .assertion("child_home_registry_removed", childHomeRecordGone)
                    .assertion("child_agent_projections_removed", childAgentProjectionsGone)
                    .assertion("child_store_survives_teardown", childStoreStays)
                    .assertion("parent_ledgers_released_child_home_bindings", ledgersCleaned)
                    .assertion("plain_remove_no_longer_sees_child_home_claim", staleClaimReleased)
                    .metric("rmExitCode", rmRc)
                    .metric("dryRunRemoveExitCode", dryRunRemoveProc.exitCode());
        });
    }

    private static boolean existsNoFollow(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }
}
