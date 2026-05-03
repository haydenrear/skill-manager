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
 * Installs the source-tracking fixture from a {@code file:} coordinate
 * and asserts the install pipeline:
 *
 * <ul>
 *   <li>Wrote a {@code <store>/sources/<name>.json} record with
 *       {@code kind=GIT}, a non-blank {@code gitHash} matching the
 *       fixture's HEAD, and an {@code origin} that's either the
 *       fixture path or the upstream URL it was cloned from.</li>
 *   <li>Preserved {@code .git/} under the installed skill dir so
 *       downstream sync nodes can run {@code git status} / merge.</li>
 * </ul>
 */
public class SourceFixtureInstalled {
    static final NodeSpec SPEC = NodeSpec.of("source.fixture.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("source.fixture.published", "gateway.up")
            .tags("source-tracking", "install", "git")
            .timeout("60s")
            .output("storeDir", "string")
            .output("sourceJson", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String fixtureDir = ctx.get("source.fixture.published", "skillDir").orElse(null);
            String skillName = ctx.get("source.fixture.published", "skillName").orElse(null);
            String initialHash = ctx.get("source.fixture.published", "initialHash").orElse(null);
            if (home == null || claudeHome == null || codexHome == null
                    || fixtureDir == null || skillName == null || initialHash == null) {
                return NodeResult.fail("source.fixture.installed", "missing upstream context");
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
            if (rc != 0) {
                return NodeResult.fail("source.fixture.installed",
                        "install rc=" + rc);
            }

            Path storeDir = Path.of(home).resolve("skills").resolve(skillName);
            Path sourceJson = Path.of(home).resolve("sources").resolve(skillName + ".json");

            boolean storeHasGit = Files.isDirectory(storeDir.resolve(".git"));
            boolean sourceJsonExists = Files.isRegularFile(sourceJson);
            String kind = null;
            String recordedHash = null;
            String recordedOrigin = null;
            if (sourceJsonExists) {
                JsonNode n = new ObjectMapper().readTree(sourceJson.toFile());
                kind = textOrNull(n, "kind");
                recordedHash = textOrNull(n, "gitHash");
                recordedOrigin = textOrNull(n, "origin");
            }
            boolean kindIsGit = "GIT".equals(kind);
            boolean hashMatches = recordedHash != null && recordedHash.equals(initialHash);
            boolean originPresent = recordedOrigin != null && !recordedOrigin.isBlank();

            boolean pass = storeHasGit && sourceJsonExists && kindIsGit && hashMatches && originPresent;
            return (pass
                    ? NodeResult.pass("source.fixture.installed")
                    : NodeResult.fail("source.fixture.installed",
                            "storeHasGit=" + storeHasGit + " sourceJson=" + sourceJsonExists
                                    + " kind=" + kind + " hashMatches=" + hashMatches
                                    + " origin=" + recordedOrigin))
                    .assertion("install_exit_zero", true)
                    .assertion("store_has_git_dir", storeHasGit)
                    .assertion("source_json_written", sourceJsonExists)
                    .assertion("source_kind_is_git", kindIsGit)
                    .assertion("source_hash_matches_fixture_head", hashMatches)
                    .assertion("source_origin_present", originPresent)
                    .publish("storeDir", storeDir.toString())
                    .publish("sourceJson", sourceJson.toString());
        });
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
