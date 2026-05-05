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
import java.nio.file.StandardOpenOption;

/**
 * Happy path for {@code skill-manager sync … --merge}: with the store
 * still dirty from {@code source.sync.refuses_on_dirty}, advance the
 * fixture with a non-conflicting commit, run sync with {@code --merge},
 * and assert the local edit was snapshotted, upstream was merged in,
 * and the source-record's {@code gitHash} now points at the new
 * combined HEAD.
 */
public class SourceSyncMergesClean {
    static final NodeSpec SPEC = NodeSpec.of("source.sync.merges_clean")
            .kind(NodeSpec.Kind.ASSERTION)
            // After both refuse-tests so they all share the same dirty
            // store and don't race each other.
            .dependsOn("source.sync.refuses_without_from")
            .tags("source-tracking", "sync", "merge")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String fixtureDir = ctx.get("source.fixture.published", "skillDir").orElse(null);
            String skillName = ctx.get("source.fixture.published", "skillName").orElse(null);
            String storeDir = ctx.get("source.fixture.installed", "storeDir").orElse(null);
            String initialHash = ctx.get("source.fixture.published", "initialHash").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || fixtureDir == null
                    || skillName == null || storeDir == null || initialHash == null) {
                return NodeResult.fail("source.sync.merges_clean", "missing upstream context");
            }

            // Add a NEW file in the fixture (won't conflict with the local
            // edit in SKILL.md the previous node made) and commit it.
            Path fixturePath = Path.of(fixtureDir);
            Files.writeString(fixturePath.resolve("CHANGELOG.md"),
                    "Upstream advancement from source.sync.merges_clean\n");
            int addRc = SourceFixturePublished.git(fixturePath, "add", "-A");
            int commitRc = SourceFixturePublished.git(fixturePath,
                    "-c", "user.email=fixture@skillmanager.local",
                    "-c", "user.name=fixture",
                    "commit", "--quiet", "-m", "upstream-advance-clean");
            String upstreamHash = SourceFixturePublished.readHead(fixturePath);
            if (addRc != 0 || commitRc != 0 || upstreamHash == null) {
                return NodeResult.fail("source.sync.merges_clean",
                        "fixture-advance failed (addRc=" + addRc + " commitRc=" + commitRc + ")");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", skillName, "--from", fixtureDir, "--merge")
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

            // Local edit from previous node should still be in SKILL.md
            // (snapshot commit + merge preserves it).
            Path skillMd = Path.of(storeDir).resolve("SKILL.md");
            String afterMd = Files.readString(skillMd);
            boolean editPreserved = afterMd.contains("local-edit-from-test-graph");
            // Upstream's CHANGELOG.md must now be in the store.
            boolean upstreamApplied = Files.isRegularFile(Path.of(storeDir).resolve("CHANGELOG.md"));

            // Source record's gitHash must have advanced past the install-time hash.
            Path sourceJson = Path.of(home).resolve("installed").resolve(skillName + ".json");
            JsonNode n = new ObjectMapper().readTree(sourceJson.toFile());
            String recordedHash = n.get("gitHash") == null ? null : n.get("gitHash").asText();
            boolean hashAdvanced = recordedHash != null && !recordedHash.equals(initialHash);

            boolean pass = rc == 0 && editPreserved && upstreamApplied && hashAdvanced;
            return (pass
                    ? NodeResult.pass("source.sync.merges_clean")
                    : NodeResult.fail("source.sync.merges_clean",
                            "rc=" + rc + " editPreserved=" + editPreserved
                                    + " upstreamApplied=" + upstreamApplied
                                    + " hashAdvanced=" + hashAdvanced
                                    + " (initial=" + initialHash + " recorded=" + recordedHash + ")"))
                    .assertion("merge_exit_zero", rc == 0)
                    .assertion("local_edit_preserved", editPreserved)
                    .assertion("upstream_applied", upstreamApplied)
                    .assertion("source_hash_advanced", hashAdvanced);
        });
    }
}
