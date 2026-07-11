///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES SkillDevGraphSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SkillDevConflictResolved {
    static final NodeSpec SPEC = NodeSpec.of("skill-dev.conflict.resolved")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("skill-dev.units.installed")
            .tags("skill-dev", "merge-conflict")
            .timeout("120s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElseThrow();
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElseThrow();
            String codexHome = ctx.get("env.prepared", "codexHome").orElseThrow();
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElseThrow();
            String gatewayPort = ctx.get("env.prepared", "gatewayPort").orElseThrow();
            String registryUrl = ctx.get("registry.up", "baseUrl").orElseThrow();
            Path project = Path.of(ctx.get("skill-dev.units.installed", "projectDir").orElseThrow());
            Path h = Path.of(home);
            Map<String, String> env = SkillDevGraphSupport.env(
                    home, claudeHome, codexHome, geminiHome,
                    "http://127.0.0.1:" + gatewayPort, registryUrl);
            List<ProcessRecord> procs = new ArrayList<>();
            String unit = SkillDevGraphSupport.CONFLICT;
            Path skillDev = SkillDevGraphSupport.skillDev(h);
            Path store = h.resolve("skills").resolve(unit).resolve("latest");
            Path storeFile = store.resolve("SKILL.md");

            ProcessRecord open = SkillDevGraphSupport.run(ctx, "conflict-open", env, project,
                    skillDev.toString(), "open", unit);
            procs.add(open);
            Path worktree = project.resolve("skill-dev").resolve(unit);
            Path worktreeFile = worktree.resolve("SKILL.md");

            Files.writeString(worktreeFile, Files.readString(worktreeFile)
                    .replace("Conflict line: initial.", "Conflict line: worktree."));
            ProcessRecord worktreeCommit = SkillDevGraphSupport.run(ctx, "conflict-worktree-commit", env, worktree,
                    "git", "-c", "user.email=skill-dev@skillmanager.local",
                    "-c", "user.name=skill-dev", "commit", "-am", "worktree conflict edit");
            procs.add(worktreeCommit);

            Files.writeString(storeFile, Files.readString(storeFile)
                    .replace("Conflict line: initial.", "Conflict line: installed."));
            ProcessRecord storeCommit = SkillDevGraphSupport.run(ctx, "conflict-store-commit", env, store,
                    "git", "-c", "user.email=skill-dev@skillmanager.local",
                    "-c", "user.name=skill-dev", "commit", "-am", "installed conflict edit");
            procs.add(storeCommit);

            ProcessRecord conflictSync = SkillDevGraphSupport.run(ctx, "conflict-sync", env, project,
                    skillDev.toString(), "sync", unit);
            procs.add(conflictSync);
            String conflicted = Files.readString(storeFile);
            boolean conflictDetected = conflictSync.exitCode() == 8
                    && conflicted.contains("<<<<<<<")
                    && conflicted.contains("Conflict line: worktree.")
                    && conflicted.contains("Conflict line: installed.");

            Files.writeString(storeFile, conflicted
                    .replaceAll("(?s)<<<<<<<.*?=======\\R", "")
                    .replaceAll("(?s)>>>>>>>.*?\\R", "")
                    .replace("Conflict line: installed.", "Conflict line: resolved.")
                    .replace("Conflict line: worktree.", "Conflict line: resolved."));
            ProcessRecord resolveCommit = SkillDevGraphSupport.run(ctx, "conflict-resolve-commit", env, store,
                    "git", "add", "SKILL.md");
            procs.add(resolveCommit);
            ProcessRecord mergeCommit = SkillDevGraphSupport.run(ctx, "conflict-merge-commit", env, store,
                    "git", "-c", "user.email=skill-dev@skillmanager.local",
                    "-c", "user.name=skill-dev", "commit", "--no-edit");
            procs.add(mergeCommit);

            ProcessRecord syncAfterResolve = SkillDevGraphSupport.run(ctx, "conflict-sync-after-resolve", env, project,
                    skillDev.toString(), "sync", unit);
            procs.add(syncAfterResolve);
            ProcessRecord close = SkillDevGraphSupport.run(ctx, "conflict-close", env, project,
                    skillDev.toString(), "close", unit);
            procs.add(close);

            boolean resolved = syncAfterResolve.exitCode() == 0
                    && close.exitCode() == 0
                    && !Files.exists(worktree)
                    && Files.readString(storeFile).contains("Conflict line: resolved.")
                    && !Files.readString(storeFile).contains("<<<<<<<");
            boolean setupOk = open.exitCode() == 0 && worktreeCommit.exitCode() == 0 && storeCommit.exitCode() == 0;
            NodeResult result = setupOk && conflictDetected && resolved
                    ? NodeResult.pass("skill-dev.conflict.resolved")
                    : NodeResult.fail("skill-dev.conflict.resolved",
                    "setup=" + setupOk + " conflict=" + conflictDetected + " resolved=" + resolved);
            for (ProcessRecord p : procs) result.process(p);
            return result
                    .assertion("conflict_setup_ok", setupOk)
                    .assertion("sync_reported_conflict", conflictDetected)
                    .assertion("manual_resolution_committed_and_synced", resolved);
        });
    }
}
