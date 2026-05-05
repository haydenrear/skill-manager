///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
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
import java.util.List;

/**
 * Continuation of {@code hyper.sync.refuses.on.local.commit}: with the
 * install in a divergent state (HEAD~1 + local commit on top), running
 * {@code skill-manager sync hyper-experiments --merge} (still no
 * {@code --from} — the implicit github origin is the remote) must:
 *
 * <ul>
 *   <li>Exit {@code 0}.</li>
 *   <li>Land a clean working tree (no conflict markers / unmerged paths).</li>
 *   <li>Preserve the unique local file the previous node added.</li>
 *   <li>Refresh the source-record gitHash to the new HEAD (which is a
 *       merge commit combining the local edit with whatever upstream
 *       was at the time of the fetch).</li>
 * </ul>
 */
public class HyperSyncMergesAfterCommit {
    static final NodeSpec SPEC = NodeSpec.of("hyper.sync.merges.after.commit")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hyper.sync.refuses.on.local.commit")
            .tags("hyper", "source-tracking", "sync", "merge")
            .sideEffects("net:remote")
            .timeout("120s");

    private static final String LOCAL_FILE = "SOURCE_TRACKING_TEST.md";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String installedHash = ctx.get("hyper.source.recorded", "installedHash").orElse(null);
            if (home == null || claudeHome == null || codexHome == null
                    || installedHash == null) {
                return NodeResult.fail("hyper.sync.merges.after.commit", "missing upstream context");
            }
            Path storeDir = Path.of(home).resolve("skills").resolve("hyper-experiments");

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", "hyper-experiments", "--merge")
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

            String porcelain = run(storeDir, List.of("git", "status", "--porcelain"));
            boolean wtClean = porcelain.isBlank();
            boolean localFileSurvived = Files.exists(storeDir.resolve(LOCAL_FILE));
            String postHead = run(storeDir, List.of("git", "rev-parse", "HEAD")).trim();

            // Source record must have been refreshed past the install-time
            // hash — the new HEAD is either the merge commit (if local +
            // upstream actually diverged) or the upstream HEAD (if local
            // was a no-op fast-forward).
            Path sourceJson = Path.of(home).resolve("installed").resolve("hyper-experiments.json");
            JsonNode n = new ObjectMapper().readTree(sourceJson.toFile());
            String recordedHash = n.get("gitHash") == null ? null : n.get("gitHash").asText();
            boolean recordRefreshed = recordedHash != null
                    && recordedHash.equals(postHead)
                    && !recordedHash.equals(installedHash);

            boolean pass = rc == 0 && wtClean && localFileSurvived && recordRefreshed;
            return (pass
                    ? NodeResult.pass("hyper.sync.merges.after.commit")
                    : NodeResult.fail("hyper.sync.merges.after.commit",
                            "rc=" + rc + " wtClean=" + wtClean
                                    + " localFile=" + localFileSurvived
                                    + " recordRefreshed=" + recordRefreshed
                                    + " (installed=" + installedHash
                                    + " head=" + postHead
                                    + " recorded=" + recordedHash + ")"))
                    .assertion("merge_exit_zero", rc == 0)
                    .assertion("working_tree_clean_after_merge", wtClean)
                    .assertion("local_file_survived_merge", localFileSurvived)
                    .assertion("source_record_hash_refreshed", recordRefreshed);
        });
    }

    private static String run(Path dir, List<String> argv) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            pb.directory(dir.toFile());
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
