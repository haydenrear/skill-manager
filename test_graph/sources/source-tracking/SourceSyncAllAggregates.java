///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * Multi-skill aggregation contract for {@code skill-manager sync}
 * (no name, no {@code --merge}): every git-tracked installed skill
 * runs through the implicit-origin pull, refused outcomes are
 * accumulated, and the CLI prints one aggregate summary at the end
 * naming each skill that needs follow-up — with the exact
 * {@code skill-manager sync <name> --merge} command per row.
 *
 * <p>By the time this node runs, the source-tracking fixture is in a
 * post-conflict-abort state: working tree clean but HEAD ahead of
 * the install-time baseline (the snapshot commit from
 * {@code source.sync.produces_conflict} stuck around after the
 * {@code git merge --abort}). That's exactly the "extra commits
 * locally" shape the implicit-origin path is supposed to refuse.
 *
 * <p>Asserts:
 * <ul>
 *   <li>Exit {@code 7}.</li>
 *   <li>Output contains the structured "sync summary" header.</li>
 *   <li>The fixture's name appears in the per-skill follow-up list,
 *       wrapped in the literal {@code skill-manager sync … --merge}
 *       recipe.</li>
 * </ul>
 */
public class SourceSyncAllAggregates {
    static final NodeSpec SPEC = NodeSpec.of("source.sync.all_aggregates")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("source.sync.produces_conflict")
            .tags("source-tracking", "sync", "aggregate")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String skillName = ctx.get("source.fixture.published", "skillName").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || skillName == null) {
                return NodeResult.fail("source.sync.all_aggregates", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(sm.toString(), "sync")
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
            boolean summaryHeader = body.contains("sync summary:")
                    && body.contains("skill(s) need attention");
            String expectedRecipe = "skill-manager sync " + skillName + " --merge";
            boolean fixtureListed = body.contains(expectedRecipe);

            boolean pass = exitedSeven && summaryHeader && fixtureListed;
            return (pass
                    ? NodeResult.pass("source.sync.all_aggregates")
                    : NodeResult.fail("source.sync.all_aggregates",
                            "rc=" + rc + " summaryHeader=" + summaryHeader
                                    + " fixtureListed=" + fixtureListed
                                    + " (expected `" + expectedRecipe + "`)"))
                    .assertion("exited_with_rc_7", exitedSeven)
                    .assertion("aggregate_summary_header_present", summaryHeader)
                    .assertion("fixture_listed_with_merge_recipe", fixtureListed);
        });
    }
}
