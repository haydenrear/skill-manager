///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES GlsFixtureBootstrapped.java
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
 * Continuation of {@code gls.refuses_on_local_commit}: with the install
 * carrying both the local commit AND a deliberately-conflicting working
 * tree edit, run {@code skill-manager sync <name> --git-latest --merge}.
 * Advance the fixture upstream with a non-conflicting commit first so
 * there's something to actually merge in.
 *
 * <p>Asserts:
 * <ul>
 *   <li>Exit 0.</li>
 *   <li>The local file from the previous node still exists.</li>
 *   <li>The new upstream file lands in the install.</li>
 *   <li>Working tree clean (real merge happened, not a refusal).</li>
 *   <li>Source-record gitHash refreshed past the previous baseline.</li>
 * </ul>
 */
public class GlsMergesAfterLocalCommit {
    static final NodeSpec SPEC = NodeSpec.of("gls.merges_after_local_commit")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("gls.refuses_on_local_commit")
            .tags("git-latest-source-tracking", "sync", "git-latest", "merge")
            .timeout("60s");

    private static final String LOCAL_FILE = "GLS_LOCAL.md";
    private static final String UPSTREAM_FILE = "GLS_UPSTREAM_2.md";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String fixtureDir = ctx.get("gls.fixture.bootstrapped", "skillDir").orElse(null);
            String skillName = ctx.get("gls.fixture.bootstrapped", "skillName").orElse(null);
            String storeDirStr = ctx.get("gls.fixture.installed", "storeDir").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || fixtureDir == null
                    || skillName == null || storeDirStr == null) {
                return NodeResult.fail("gls.merges_after_local_commit", "missing upstream context");
            }
            Path storeDir = Path.of(storeDirStr);
            Path fixturePath = Path.of(fixtureDir);

            // Advance fixture again so the merge brings in a real new commit.
            Files.writeString(fixturePath.resolve(UPSTREAM_FILE),
                    "Added by gls.merges_after_local_commit (upstream side).\n");
            int addRc = GlsFixtureBootstrapped.git(fixturePath, "add", "-A");
            int commitRc = GlsFixtureBootstrapped.git(fixturePath,
                    "-c", "user.email=fixture@skillmanager.local",
                    "-c", "user.name=fixture",
                    "commit", "--quiet", "-m", "upstream-second-commit");
            String upstreamHash = GlsFixtureBootstrapped.readHead(fixturePath);
            if (addRc != 0 || commitRc != 0 || upstreamHash == null) {
                return NodeResult.fail("gls.merges_after_local_commit",
                        "fixture-advance failed (add=" + addRc + " commit=" + commitRc + ")");
            }

            String preMergeHead = GlsFixtureBootstrapped.readHead(storeDir);

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", skillName, "--git-latest", "--merge")
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);

            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) System.out.println(line);
            }
            int rc = p.waitFor();

            boolean localSurvived = Files.exists(storeDir.resolve(LOCAL_FILE));
            boolean upstreamLanded = Files.exists(storeDir.resolve(UPSTREAM_FILE));
            String porcelain = GlsFixtureBootstrapped.git(storeDir, "status", "--porcelain") == 0
                    ? "" : "??";
            // Working tree should be clean post-merge.
            ProcessBuilder porcelainPb = new ProcessBuilder("git", "status", "--porcelain")
                    .directory(storeDir.toFile()).redirectErrorStream(true);
            Process porcelainProc = porcelainPb.start();
            StringBuilder porcelainOut = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(porcelainProc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) porcelainOut.append(line).append('\n');
            }
            porcelainProc.waitFor();
            boolean wtClean = porcelainOut.toString().isBlank();

            String postMergeHead = GlsFixtureBootstrapped.readHead(storeDir);
            boolean headAdvanced = postMergeHead != null && !postMergeHead.equals(preMergeHead);

            Path sourceJson = Path.of(home).resolve("installed").resolve(skillName + ".json");
            JsonNode n = new ObjectMapper().readTree(sourceJson.toFile());
            String recordedHash = n.get("gitHash") == null ? null : n.get("gitHash").asText();
            boolean recordRefreshed = postMergeHead != null && postMergeHead.equals(recordedHash);

            boolean pass = rc == 0 && localSurvived && upstreamLanded && wtClean
                    && headAdvanced && recordRefreshed;
            return (pass
                    ? NodeResult.pass("gls.merges_after_local_commit")
                    : NodeResult.fail("gls.merges_after_local_commit",
                            "rc=" + rc + " local=" + localSurvived
                                    + " upstream=" + upstreamLanded + " wtClean=" + wtClean
                                    + " headAdvanced=" + headAdvanced
                                    + " recordRefreshed=" + recordRefreshed
                                    + " (pre=" + preMergeHead + " post=" + postMergeHead
                                    + " recorded=" + recordedHash + ")"))
                    .assertion("merge_exit_zero", rc == 0)
                    .assertion("local_commit_survived_merge", localSurvived)
                    .assertion("upstream_commit_applied", upstreamLanded)
                    .assertion("working_tree_clean_after_merge", wtClean)
                    .assertion("install_head_advanced", headAdvanced)
                    .assertion("source_record_hash_refreshed", recordRefreshed);
        });
    }
}
