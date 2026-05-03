///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES GlsFixtureBootstrapped.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * With the install caught up after {@code gls.fast_forwards}, add a
 * non-conflicting local commit (a brand-new file). Then run
 * {@code skill-manager sync <name> --git-latest} (no {@code --merge}).
 * The dirty-baseline check must refuse with exit 7 and a banner that:
 *
 * <ul>
 *   <li>Mentions "extra local changes"</li>
 *   <li>Suggests {@code skill-manager sync <name> --git-latest --merge}
 *       — preserving the {@code --git-latest} flag the caller used,
 *       not silently dropping it.</li>
 * </ul>
 */
public class GlsRefusesOnLocalCommit {
    static final NodeSpec SPEC = NodeSpec.of("gls.refuses_on_local_commit")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("gls.fast_forwards")
            .tags("git-latest-source-tracking", "sync", "git-latest", "abort")
            .timeout("60s");

    private static final String LOCAL_FILE = "GLS_LOCAL.md";

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
                return NodeResult.fail("gls.refuses_on_local_commit", "missing upstream context");
            }
            Path storeDir = Path.of(storeDirStr);

            // Make a local commit on top of the post-fast-forward HEAD.
            // New file → won't conflict with anything upstream might add later.
            Files.writeString(storeDir.resolve(LOCAL_FILE),
                    "Created by gls.refuses_on_local_commit — should survive --merge.\n");
            int addRc = GlsFixtureBootstrapped.git(storeDir, "add", LOCAL_FILE);
            int commitRc = GlsFixtureBootstrapped.git(storeDir,
                    "-c", "user.email=test-graph@skillmanager.local",
                    "-c", "user.name=test-graph",
                    "commit", "--quiet", "-m", "test-graph: local commit on top of upstream");
            if (addRc != 0 || commitRc != 0) {
                return NodeResult.fail("gls.refuses_on_local_commit",
                        "git add/commit failed (add=" + addRc + " commit=" + commitRc + ")");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", skillName, "--git-latest")
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
            // Recipe must preserve --git-latest. Plain `--merge` (without
            // --git-latest) would be wrong since it would re-introduce the
            // server-versioned default behavior.
            boolean recipePreservesGitLatest = body.contains(
                    "skill-manager sync " + skillName + " --git-latest --merge");
            boolean localFileStillThere = Files.exists(storeDir.resolve(LOCAL_FILE));

            boolean pass = exitedSeven && mentionsExtraChanges
                    && recipePreservesGitLatest && localFileStillThere;
            return (pass
                    ? NodeResult.pass("gls.refuses_on_local_commit")
                    : NodeResult.fail("gls.refuses_on_local_commit",
                            "rc=" + rc + " extra=" + mentionsExtraChanges
                                    + " recipeOk=" + recipePreservesGitLatest
                                    + " localFile=" + localFileStillThere))
                    .assertion("exited_with_rc_7", exitedSeven)
                    .assertion("banner_mentions_extra_local_changes", mentionsExtraChanges)
                    .assertion("recipe_preserves_git_latest_flag", recipePreservesGitLatest)
                    .assertion("local_commit_preserved", localFileStillThere);
        });
    }
}
