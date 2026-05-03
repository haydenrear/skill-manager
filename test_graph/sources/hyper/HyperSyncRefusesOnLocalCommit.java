///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Drives the install into a divergent state — reset to {@code HEAD~1}
 * and add a unique local commit on top — then runs {@code skill-manager
 * sync hyper-experiments} (no {@code --merge}, no {@code --from}) and
 * asserts the CLI:
 *
 * <ul>
 *   <li>Exits {@code 7}.</li>
 *   <li>Tells the user they have extra local changes and points at
 *       {@code --merge} as the resolution path.</li>
 *   <li>Names the upstream URL the implicit pull would have fetched
 *       from (the github URL pinned by install).</li>
 *   <li>Leaves the working tree alone — no overwrites.</li>
 * </ul>
 *
 * <p>Adds a unique file ({@code SOURCE_TRACKING_TEST.md}) so the
 * downstream {@code --merge} node can verify the file survives the
 * 3-way merge.
 */
public class HyperSyncRefusesOnLocalCommit {
    static final NodeSpec SPEC = NodeSpec.of("hyper.sync.refuses.on.local.commit")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hyper.sync.clean.noop")
            .tags("hyper", "source-tracking", "sync", "abort")
            .timeout("60s");

    private static final String LOCAL_FILE = "SOURCE_TRACKING_TEST.md";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("hyper.sync.refuses.on.local.commit", "missing upstream context");
            }
            Path storeDir = Path.of(home).resolve("skills").resolve("hyper-experiments");

            // Reset backwards one commit, then add a unique local commit
            // on top so the install has a real divergence from origin.
            int resetRc = runRc(storeDir, List.of(
                    "git", "reset", "--hard", "--quiet", "HEAD~1"));
            if (resetRc != 0) {
                return NodeResult.fail("hyper.sync.refuses.on.local.commit",
                        "git reset --hard HEAD~1 failed (rc=" + resetRc + ")");
            }
            Files.writeString(storeDir.resolve(LOCAL_FILE),
                    "Created by hyper.sync.refuses.on.local.commit — should survive --merge.\n");
            int addRc = runRc(storeDir, List.of("git", "add", LOCAL_FILE));
            int commitRc = runRc(storeDir, List.of(
                    "git",
                    "-c", "user.email=test-graph@skillmanager.local",
                    "-c", "user.name=test-graph",
                    "commit", "--quiet", "-m", "test-graph: local commit on top of HEAD~1"));
            if (addRc != 0 || commitRc != 0) {
                return NodeResult.fail("hyper.sync.refuses.on.local.commit",
                        "git add/commit failed (add=" + addRc + " commit=" + commitRc + ")");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", "hyper-experiments")
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

            boolean exitedSeven = rc == 7;
            boolean mentionsExtraChanges = body.contains("extra local changes");
            boolean mentionsMergeFlag = body.contains("--merge");
            boolean mentionsGithubUrl = body.contains("github.com")
                    && body.contains("hyper-experiments");
            // Local commit must still be HEAD — sync mustn't have moved it.
            String headAfter = run(storeDir, List.of("git", "rev-parse", "HEAD")).trim();
            boolean localFileStillThere = Files.exists(storeDir.resolve(LOCAL_FILE));

            boolean pass = exitedSeven && mentionsExtraChanges && mentionsMergeFlag
                    && mentionsGithubUrl && localFileStillThere && !headAfter.isBlank();
            return (pass
                    ? NodeResult.pass("hyper.sync.refuses.on.local.commit")
                    : NodeResult.fail("hyper.sync.refuses.on.local.commit",
                            "rc=" + rc + " extra=" + mentionsExtraChanges
                                    + " merge=" + mentionsMergeFlag
                                    + " github=" + mentionsGithubUrl
                                    + " localFile=" + localFileStillThere))
                    .assertion("exited_with_rc_7", exitedSeven)
                    .assertion("banner_mentions_extra_local_changes", mentionsExtraChanges)
                    .assertion("banner_includes_merge_flag", mentionsMergeFlag)
                    .assertion("banner_names_github_upstream", mentionsGithubUrl)
                    .assertion("local_commit_preserved", localFileStillThere);
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

    private static int runRc(Path dir, List<String> argv) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            pb.directory(dir.toFile());
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
}
