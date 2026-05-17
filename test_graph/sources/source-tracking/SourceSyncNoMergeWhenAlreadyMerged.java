///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES SourceFixturePublished.java
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Regression coverage for a stale source-record hash after the user resolves
 * the upstream merge outside skill-manager. The installed checkout is clean and
 * already contains the upstream commit, so a plain sync must refresh the source
 * record instead of printing a no-op {@code --merge} recipe.
 */
public class SourceSyncNoMergeWhenAlreadyMerged {
    static final NodeSpec SPEC = NodeSpec.of("source.sync.no_merge_when_already_merged")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("source.sync.merges_clean")
            .tags("source-tracking", "sync", "implicit-origin", "no-op-merge")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String fixtureDir = ctx.get("source.fixture.published", "skillDir").orElse(null);
            String skillName = ctx.get("source.fixture.published", "skillName").orElse(null);
            String storeDirStr = ctx.get("source.fixture.installed", "storeDir").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || fixtureDir == null
                    || skillName == null || storeDirStr == null) {
                return NodeResult.fail("source.sync.no_merge_when_already_merged", "missing upstream context");
            }

            Path storeDir = Path.of(storeDirStr);
            Path fixturePath = Path.of(fixtureDir);

            Files.writeString(storeDir.resolve("MANUAL_LOCAL.md"),
                    "Local commit made outside skill-manager.\n");
            int localAddRc = SourceFixturePublished.git(storeDir, "add", "-A");
            int localCommitRc = SourceFixturePublished.git(storeDir,
                    "-c", "user.email=fixture@skillmanager.local",
                    "-c", "user.name=fixture",
                    "commit", "--quiet", "-m", "manual-local-change");

            Files.writeString(fixturePath.resolve("MANUAL_UPSTREAM.md"),
                    "Upstream commit merged outside skill-manager.\n");
            int upstreamAddRc = SourceFixturePublished.git(fixturePath, "add", "-A");
            int upstreamCommitRc = SourceFixturePublished.git(fixturePath,
                    "-c", "user.email=fixture@skillmanager.local",
                    "-c", "user.name=fixture",
                    "commit", "--quiet", "-m", "manual-upstream-change");
            String upstreamHash = SourceFixturePublished.readHead(fixturePath);

            int fetchRc = SourceFixturePublished.git(storeDir, "fetch", "--no-tags", "--quiet", fixtureDir, "main");
            int mergeRc = SourceFixturePublished.git(storeDir,
                    "-c", "user.email=fixture@skillmanager.local",
                    "-c", "user.name=fixture",
                    "merge", "--no-edit", "FETCH_HEAD");
            String manuallyMergedHead = SourceFixturePublished.readHead(storeDir);
            if (localAddRc != 0 || localCommitRc != 0 || upstreamAddRc != 0 || upstreamCommitRc != 0
                    || upstreamHash == null || fetchRc != 0 || mergeRc != 0 || manuallyMergedHead == null) {
                return NodeResult.fail("source.sync.no_merge_when_already_merged",
                        "manual setup failed (local=" + localAddRc + "/" + localCommitRc
                                + " upstream=" + upstreamAddRc + "/" + upstreamCommitRc
                                + " fetch=" + fetchRc + " merge=" + mergeRc + ")");
            }

            boolean upstreamContained = gitRc(storeDir, "merge-base", "--is-ancestor", upstreamHash, "HEAD") == 0;
            boolean worktreeCleanBeforeSync = gitOutput(storeDir, "status", "--porcelain").isBlank();

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            ProcessBuilder pb = new ProcessBuilder(sm.toString(), "sync", skillName, "--git-latest")
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);

            StringBuilder out = new StringBuilder();
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println(line);
                    out.append(line).append('\n');
                }
            }
            int rc = p.waitFor();
            String body = out.toString();

            Path sourceJson = Path.of(home).resolve("installed").resolve(skillName + ".json");
            JsonNode n = new ObjectMapper().readTree(sourceJson.toFile());
            String recordedHash = n.get("gitHash") == null ? null : n.get("gitHash").asText();

            boolean exitedZero = rc == 0;
            boolean noMergeRecipe = !body.contains("--merge") && !body.contains("extra local changes");
            boolean sourceRecordRefreshed = manuallyMergedHead.equals(recordedHash);

            boolean pass = exitedZero && noMergeRecipe && upstreamContained
                    && worktreeCleanBeforeSync && sourceRecordRefreshed;
            return (pass
                    ? NodeResult.pass("source.sync.no_merge_when_already_merged")
                    : NodeResult.fail("source.sync.no_merge_when_already_merged",
                            "rc=" + rc + " noMergeRecipe=" + noMergeRecipe
                                    + " upstreamContained=" + upstreamContained
                                    + " cleanBefore=" + worktreeCleanBeforeSync
                                    + " sourceRecordRefreshed=" + sourceRecordRefreshed
                                    + " (head=" + manuallyMergedHead + " recorded=" + recordedHash + ")"))
                    .assertion("sync_exit_zero", exitedZero)
                    .assertion("output_has_no_merge_recipe", noMergeRecipe)
                    .assertion("upstream_commit_already_contained", upstreamContained)
                    .assertion("worktree_clean_before_sync", worktreeCleanBeforeSync)
                    .assertion("source_record_refreshed_to_current_head", sourceRecordRefreshed);
        });
    }

    private static int gitRc(Path dir, String... args) throws Exception {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add("git");
        for (String a : args) argv.add(a);
        Process p = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true).start();
        try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            while (r.readLine() != null) {}
        }
        return p.waitFor();
    }

    private static String gitOutput(Path dir, String... args) throws Exception {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add("git");
        for (String a : args) argv.add(a);
        Process p = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        p.waitFor();
        return out.toString();
    }
}
