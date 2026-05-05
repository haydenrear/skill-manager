///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../common/TestDb.java
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2
//DEPS org.postgresql:postgresql:42.7.4

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Round-trip integrity check for the server-versioned sync model:
 * the {@code git_sha} the server stored at publish time, the install
 * dir's actual {@code git rev-parse HEAD}, and the source-record
 * JSON's {@code gitHash} all have to agree. If any of the three
 * disagrees, server-versioned sync would either no-op when it
 * shouldn't, or attempt to merge the wrong commit.
 *
 * <p>Reads the version label off the source-record JSON (also
 * written by install) so the DB lookup hits the exact published
 * row, not just whatever happens to be {@code latest}.
 */
public class HyperServerHashMatchesInstall {
    static final NodeSpec SPEC = NodeSpec.of("hyper.server.hash.matches.install")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hyper.installed", "hyper.published")
            .tags("hyper", "source-tracking", "server-versioned", "db")
            .timeout("30s")
            .retries(2);

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("hyper.server.hash.matches.install",
                        "missing env.prepared context");
            }
            Path storeDir = Path.of(home).resolve("skills").resolve("hyper-experiments");
            Path sourceJson = Path.of(home).resolve("installed").resolve("hyper-experiments.json");
            if (!Files.isRegularFile(sourceJson)) {
                return NodeResult.fail("hyper.server.hash.matches.install",
                        "missing source record at " + sourceJson);
            }

            JsonNode src = new ObjectMapper().readTree(sourceJson.toFile());
            String srcRecHash = src.get("gitHash") == null ? null : src.get("gitHash").asText();
            String srcRecVersion = src.get("version") == null ? null : src.get("version").asText();

            String installHead = readHead(storeDir);

            String dbGitSha = null;
            int matchingRows = 0;
            try (TestDb db = TestDb.open();
                 PreparedStatement ps = db.connection().prepareStatement(
                         "SELECT git_sha FROM skill_versions WHERE name = ? AND version = ?")) {
                ps.setString(1, "hyper-experiments");
                ps.setString(2, srcRecVersion);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        matchingRows++;
                        dbGitSha = rs.getString(1);
                    }
                }
            }

            boolean srcHashMatchesHead = srcRecHash != null && srcRecHash.equals(installHead);
            boolean dbRowFound = matchingRows == 1;
            boolean dbShaMatchesSrc = dbGitSha != null && dbGitSha.equals(srcRecHash);

            boolean pass = srcHashMatchesHead && dbRowFound && dbShaMatchesSrc;
            return (pass
                    ? NodeResult.pass("hyper.server.hash.matches.install")
                    : NodeResult.fail("hyper.server.hash.matches.install",
                            "srcMatchesHead=" + srcHashMatchesHead
                                    + " dbRowFound=" + dbRowFound + " (n=" + matchingRows + ")"
                                    + " dbMatchesSrc=" + dbShaMatchesSrc
                                    + " (head=" + installHead + " src=" + srcRecHash
                                    + " db=" + dbGitSha + " version=" + srcRecVersion + ")"))
                    .assertion("source_record_hash_matches_install_head", srcHashMatchesHead)
                    .assertion("postgres_row_for_published_version_exists", dbRowFound)
                    .assertion("postgres_git_sha_matches_source_record", dbShaMatchesSrc);
        });
    }

    private static String readHead(Path dir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true);
            pb.directory(dir.toFile());
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (var r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line);
            }
            return p.waitFor() == 0 ? out.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
