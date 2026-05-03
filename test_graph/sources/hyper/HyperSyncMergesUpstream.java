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
 * End-to-end exercise of the git-source-tracking + {@code sync --merge}
 * flow against the real {@code hyper-experiments} install:
 *
 * <ol>
 *   <li>Capture the current install HEAD (the {@code installedHash} we
 *       want to end up at).</li>
 *   <li>Clone the install's own {@code .git/} into a side directory —
 *       this gives us a self-contained "upstream" with full history,
 *       no network dependency, and deterministic content.</li>
 *   <li>{@code git reset --hard HEAD~1} the install in place, simulating
 *       drift to an older revision.</li>
 *   <li>Run {@code skill-manager sync hyper-experiments --from
 *       <clone> --merge}.</li>
 *   <li>Assert the install HEAD is back at the captured hash, the
 *       working tree is clean, and the source-record JSON's
 *       {@code gitHash} now matches.</li>
 * </ol>
 *
 * <p>Skipped (with a clear status) when the install only has one
 * commit (no {@code HEAD~1}) — shouldn't happen for a real
 * github-published skill but keeps the assertion honest.
 */
public class HyperSyncMergesUpstream {
    static final NodeSpec SPEC = NodeSpec.of("hyper.sync.merges.upstream")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hyper.source.recorded")
            .tags("hyper", "source-tracking", "git", "merge")
            .timeout("120s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String installedHash = ctx.get("hyper.source.recorded", "installedHash").orElse(null);
            if (home == null || claudeHome == null || codexHome == null
                    || installedHash == null || installedHash.isBlank()) {
                return NodeResult.fail("hyper.sync.merges.upstream", "missing upstream context");
            }

            Path storeDir = Path.of(home).resolve("skills").resolve("hyper-experiments");

            // Capture the parent commit before we touch anything; if the
            // repo only has one commit there's nothing to test here.
            String parentHash = run(storeDir, List.of("git", "rev-parse", "HEAD~1")).trim();
            if (parentHash.isBlank() || parentHash.equals(installedHash)) {
                return NodeResult.fail("hyper.sync.merges.upstream",
                        "could not resolve HEAD~1 in install (parent=" + parentHash + ")");
            }

            // Clone the install's .git into a side directory — that's our
            // self-contained "upstream" for the merge.
            Path mirror = Path.of(home).resolve("hyper-source-mirror");
            if (Files.exists(mirror)) deleteRecursive(mirror);
            int cloneRc = runRc(null, List.of(
                    "git", "clone", "--quiet", storeDir.toString(), mirror.toString()));
            if (cloneRc != 0) {
                return NodeResult.fail("hyper.sync.merges.upstream",
                        "git clone of install failed (rc=" + cloneRc + ")");
            }
            String mirrorHead = run(mirror, List.of("git", "rev-parse", "HEAD")).trim();
            boolean mirrorAtInstalledHash = mirrorHead.equals(installedHash);

            // Now reset the install backwards to the parent commit.
            int resetRc = runRc(storeDir, List.of(
                    "git", "reset", "--hard", "--quiet", parentHash));
            if (resetRc != 0) {
                return NodeResult.fail("hyper.sync.merges.upstream",
                        "git reset --hard " + parentHash + " failed (rc=" + resetRc + ")");
            }
            String afterResetHash = run(storeDir, List.of("git", "rev-parse", "HEAD")).trim();
            boolean resetWorked = parentHash.equals(afterResetHash);

            // Run skill-manager sync … --merge.
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", "hyper-experiments",
                    "--from", mirror.toString(), "--merge")
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
            int syncRc = p.waitFor();

            // Validate the post-state.
            String postSyncHead = run(storeDir, List.of("git", "rev-parse", "HEAD")).trim();
            boolean wtClean = run(storeDir, List.of("git", "status", "--porcelain")).isBlank();
            boolean restoredToInstalled = postSyncHead.equals(installedHash);

            Path sourceJson = Path.of(home).resolve("sources").resolve("hyper-experiments.json");
            JsonNode n = new ObjectMapper().readTree(sourceJson.toFile());
            String recordedHash = n.get("gitHash") == null ? null : n.get("gitHash").asText();
            boolean recordRefreshed = recordedHash != null && recordedHash.equals(installedHash);

            boolean pass = mirrorAtInstalledHash && resetWorked && syncRc == 0
                    && restoredToInstalled && wtClean && recordRefreshed;
            return (pass
                    ? NodeResult.pass("hyper.sync.merges.upstream")
                    : NodeResult.fail("hyper.sync.merges.upstream",
                            "syncRc=" + syncRc + " mirror=" + mirrorAtInstalledHash
                                    + " reset=" + resetWorked + " restored=" + restoredToInstalled
                                    + " (post=" + postSyncHead + " want=" + installedHash + ")"
                                    + " wtClean=" + wtClean + " jsonRefreshed=" + recordRefreshed))
                    .assertion("mirror_at_installed_hash", mirrorAtInstalledHash)
                    .assertion("reset_to_parent_commit", resetWorked)
                    .assertion("sync_merge_exit_zero", syncRc == 0)
                    .assertion("install_back_at_installed_hash", restoredToInstalled)
                    .assertion("working_tree_clean_after_merge", wtClean)
                    .assertion("source_record_hash_refreshed", recordRefreshed);
        });
    }

    private static String run(Path dir, List<String> argv) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            if (dir != null) pb.directory(dir.toFile());
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

    private static int runRc(Path dir, List<String> argv) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            if (dir != null) pb.directory(dir.toFile());
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) System.out.println(line);
            }
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void deleteRecursive(Path p) throws java.io.IOException {
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(x -> { try { Files.deleteIfExists(x); } catch (java.io.IOException ignored) {} });
        }
    }
}
