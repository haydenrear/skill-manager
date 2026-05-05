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

/**
 * Install the git-latest fixture from the {@code file:} coordinate.
 * Asserts the install pipeline:
 *
 * <ul>
 *   <li>Wrote {@code <home>/installed/<name>.json} with kind=GIT,
 *       gitHash matching the fixture's HEAD, and origin pinned to
 *       the fixture path (so {@code sync --git-latest} has somewhere
 *       to fetch from without --from).</li>
 *   <li>Preserved {@code .git/} under the install dir.</li>
 * </ul>
 */
public class GlsFixtureInstalled {
    static final NodeSpec SPEC = NodeSpec.of("gls.fixture.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("gls.fixture.bootstrapped", "gateway.up")
            .tags("git-latest-source-tracking", "install", "git")
            .timeout("60s")
            .output("storeDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String fixtureDir = ctx.get("gls.fixture.bootstrapped", "skillDir").orElse(null);
            String skillName = ctx.get("gls.fixture.bootstrapped", "skillName").orElse(null);
            String initialHash = ctx.get("gls.fixture.bootstrapped", "initialHash").orElse(null);
            if (home == null || claudeHome == null || codexHome == null
                    || fixtureDir == null || skillName == null || initialHash == null) {
                return NodeResult.fail("gls.fixture.installed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "file:" + fixtureDir)
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
            if (rc != 0) return NodeResult.fail("gls.fixture.installed", "install rc=" + rc);

            Path storeDir = Path.of(home).resolve("skills").resolve(skillName);
            Path sourceJson = Path.of(home).resolve("installed").resolve(skillName + ".json");

            boolean storeHasGit = Files.isDirectory(storeDir.resolve(".git"));
            boolean sourceJsonExists = Files.isRegularFile(sourceJson);
            String kind = null, hash = null, origin = null, gitRef = null;
            if (sourceJsonExists) {
                JsonNode n = new ObjectMapper().readTree(sourceJson.toFile());
                kind = n.get("kind") == null ? null : n.get("kind").asText();
                hash = n.get("gitHash") == null ? null : n.get("gitHash").asText();
                origin = n.get("origin") == null ? null : n.get("origin").asText();
                gitRef = n.get("gitRef") == null ? null : n.get("gitRef").asText();
            }
            boolean kindIsGit = "GIT".equals(kind);
            boolean hashMatches = hash != null && hash.equals(initialHash);
            boolean originIsFixturePath = origin != null && origin.equals(fixtureDir);
            // Fixture was bootstrapped with `git init -b main`; the
            // install must detect "main" so `sync --git-latest` knows
            // which branch to fetch instead of falling back to remote
            // HEAD.
            boolean gitRefIsMain = "main".equals(gitRef);

            boolean pass = storeHasGit && sourceJsonExists && kindIsGit
                    && hashMatches && originIsFixturePath && gitRefIsMain;
            return (pass
                    ? NodeResult.pass("gls.fixture.installed")
                    : NodeResult.fail("gls.fixture.installed",
                            "storeHasGit=" + storeHasGit + " json=" + sourceJsonExists
                                    + " kind=" + kind + " hashMatches=" + hashMatches
                                    + " origin=" + origin + " gitRef=" + gitRef))
                    .assertion("install_exit_zero", true)
                    .assertion("store_has_git_dir", storeHasGit)
                    .assertion("source_json_written", sourceJsonExists)
                    .assertion("source_kind_is_git", kindIsGit)
                    .assertion("source_hash_matches_fixture_head", hashMatches)
                    .assertion("source_origin_pins_fixture_path", originIsFixturePath)
                    .assertion("source_git_ref_is_main", gitRefIsMain)
                    .publish("storeDir", storeDir.toString());
        });
    }
}
