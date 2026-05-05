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
 * Clean fast-forward: advance the fixture upstream with a non-conflicting
 * commit, then run {@code skill-manager sync <name> --git-latest}
 * (no merge needed, working tree clean). The CLI fetches the fixture's
 * new HEAD, fast-forwards the install to it, and refreshes the
 * source-record gitHash.
 *
 * <p>This is the everyday case: someone published a new commit
 * upstream and the user just wants their install caught up.
 */
public class GlsFastForwards {
    static final NodeSpec SPEC = NodeSpec.of("gls.fast_forwards")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("gls.fixture.installed")
            .tags("git-latest-source-tracking", "sync", "git-latest", "clean")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String fixtureDir = ctx.get("gls.fixture.bootstrapped", "skillDir").orElse(null);
            String skillName = ctx.get("gls.fixture.bootstrapped", "skillName").orElse(null);
            String storeDir = ctx.get("gls.fixture.installed", "storeDir").orElse(null);
            String initialHash = ctx.get("gls.fixture.bootstrapped", "initialHash").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || fixtureDir == null
                    || skillName == null || storeDir == null || initialHash == null) {
                return NodeResult.fail("gls.fast_forwards", "missing upstream context");
            }

            // Advance the fixture with a non-conflicting commit.
            Path fixturePath = Path.of(fixtureDir);
            Files.writeString(fixturePath.resolve("UPSTREAM_NOTE.md"),
                    "Added by gls.fast_forwards.\n");
            int addRc = GlsFixtureBootstrapped.git(fixturePath, "add", "-A");
            int commitRc = GlsFixtureBootstrapped.git(fixturePath,
                    "-c", "user.email=fixture@skillmanager.local",
                    "-c", "user.name=fixture",
                    "commit", "--quiet", "-m", "upstream-fast-forward");
            String upstreamHash = GlsFixtureBootstrapped.readHead(fixturePath);
            if (addRc != 0 || commitRc != 0 || upstreamHash == null) {
                return NodeResult.fail("gls.fast_forwards",
                        "fixture-advance failed (add=" + addRc + " commit=" + commitRc + ")");
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

            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) System.out.println(line);
            }
            int rc = p.waitFor();

            // Install must now have the upstream file and HEAD == upstreamHash.
            boolean upstreamFileLanded = Files.exists(Path.of(storeDir).resolve("UPSTREAM_NOTE.md"));
            String installHead = GlsFixtureBootstrapped.readHead(Path.of(storeDir));
            boolean installAtUpstream = upstreamHash.equals(installHead);

            // Source record gitHash must now match the new upstream sha.
            Path sourceJson = Path.of(home).resolve("installed").resolve(skillName + ".json");
            JsonNode n = new ObjectMapper().readTree(sourceJson.toFile());
            String recordedHash = n.get("gitHash") == null ? null : n.get("gitHash").asText();
            boolean recordRefreshed = upstreamHash.equals(recordedHash)
                    && !upstreamHash.equals(initialHash);

            boolean pass = rc == 0 && upstreamFileLanded && installAtUpstream && recordRefreshed;
            return (pass
                    ? NodeResult.pass("gls.fast_forwards")
                    : NodeResult.fail("gls.fast_forwards",
                            "rc=" + rc + " upstreamFile=" + upstreamFileLanded
                                    + " installAtUpstream=" + installAtUpstream
                                    + " (head=" + installHead + " want=" + upstreamHash + ")"
                                    + " recordRefreshed=" + recordRefreshed))
                    .assertion("sync_exit_zero", rc == 0)
                    .assertion("upstream_file_landed_in_store", upstreamFileLanded)
                    .assertion("install_head_at_upstream", installAtUpstream)
                    .assertion("source_record_hash_refreshed", recordRefreshed);
        });
    }
}
