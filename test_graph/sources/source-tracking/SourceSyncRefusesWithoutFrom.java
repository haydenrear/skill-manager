///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * Counterpart to {@code source.sync.refuses_on_dirty} that exercises
 * the implicit-origin path: with the store still dirty from the
 * previous node, run {@code skill-manager sync <name>} (no
 * {@code --from}, no {@code --merge}). Install pinned the fixture
 * directory's path as the source-record origin, so sync should:
 *
 * <ul>
 *   <li>Detect the dirty state via the recorded baseline.</li>
 *   <li>Exit {@code 7}.</li>
 *   <li>Print "use --merge" instructions naming the fixture path.</li>
 * </ul>
 *
 * <p>This is the more realistic UX than {@code --from} — the user
 * doesn't have to remember where the install came from, sync looks
 * it up.
 */
public class SourceSyncRefusesWithoutFrom {
    static final NodeSpec SPEC = NodeSpec.of("source.sync.refuses_without_from")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("source.sync.refuses_on_dirty")
            .tags("source-tracking", "sync", "implicit-origin", "abort")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String skillName = ctx.get("source.fixture.published", "skillName").orElse(null);
            String fixtureDir = ctx.get("source.fixture.published", "skillDir").orElse(null);
            if (home == null || claudeHome == null || codexHome == null
                    || skillName == null || fixtureDir == null) {
                return NodeResult.fail("source.sync.refuses_without_from", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // --git-latest because the fixture isn't in the registry;
            // without it sync would warn+skip ("no server git_sha") and
            // never exercise the dirty-refuse path we're testing.
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
            boolean mentionsMergeFlag = body.contains("--merge");
            // Refusal recipe should reference the recorded origin (fixture
            // path), not just an opaque "<upstream>" placeholder.
            boolean banneNamesUpstream = body.contains(fixtureDir);

            boolean pass = exitedSeven && mentionsExtraChanges && mentionsMergeFlag
                    && banneNamesUpstream;
            return (pass
                    ? NodeResult.pass("source.sync.refuses_without_from")
                    : NodeResult.fail("source.sync.refuses_without_from",
                            "rc=" + rc + " extra=" + mentionsExtraChanges
                                    + " merge=" + mentionsMergeFlag
                                    + " upstream=" + banneNamesUpstream))
                    .assertion("exited_with_rc_7", exitedSeven)
                    .assertion("banner_mentions_extra_local_changes", mentionsExtraChanges)
                    .assertion("banner_includes_merge_flag", mentionsMergeFlag)
                    .assertion("banner_names_implicit_origin", banneNamesUpstream);
        });
    }
}
