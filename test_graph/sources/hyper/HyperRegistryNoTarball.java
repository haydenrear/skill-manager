///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../common/TestDb.java
//DEPS org.postgresql:postgresql:42.7.4

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.stream.Stream;

/**
 * Verifies the registry stored only a github pointer for hyper-experiments —
 * no tarball bytes were ever written. Two checks:
 *
 * <ol>
 *   <li>Every {@code skill_versions} row for {@code hyper-experiments} has
 *       {@code sha256 IS NULL} and {@code size_bytes IS NULL}, with
 *       {@code github_url} + {@code git_sha} populated.</li>
 *   <li>No {@code skill.tar.gz} exists under the registry root for
 *       {@code hyper-experiments} — the on-disk metadata.json sits alone.</li>
 * </ol>
 *
 * <p>Together these prove the server is operating as a metadata cache and
 * not serving any payload for github-registered skills.
 */
public class HyperRegistryNoTarball {
    static final NodeSpec SPEC = NodeSpec.of("hyper.registry.no.tarball")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hyper.published")
            .tags("hyper", "registry", "github")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("hyper.registry.no.tarball", "missing env.prepared.home");
            }

            // RegistryUp's per-run home points the server at
            // ${home}/registry-data — that's where any tarball would have
            // landed. Walk the hyper-experiments subtree and assert no
            // skill.tar.gz files exist.
            Path registryRoot = Path.of(home).resolve("registry-data");
            Path hyperDir = registryRoot.resolve("hyper-experiments");
            int tarballsOnDisk = 0;
            if (Files.isDirectory(hyperDir)) {
                try (Stream<Path> s = Files.walk(hyperDir)) {
                    tarballsOnDisk = (int) s.filter(p -> p.getFileName() != null
                            && p.getFileName().toString().equals("skill.tar.gz"))
                            .count();
                }
            }
            boolean noTarballOnDisk = tarballsOnDisk == 0;

            // DB check — every row for hyper-experiments must be a github
            // pointer (sha256/size_bytes null, github_url/git_sha set).
            int totalRows = 0;
            int nullSha = 0;
            int nullSize = 0;
            int withGithubUrl = 0;
            int withGitSha = 0;
            try (TestDb db = TestDb.open();
                 PreparedStatement ps = db.connection().prepareStatement(
                         "SELECT sha256, size_bytes, github_url, git_sha "
                                 + "FROM skill_versions WHERE name = ?")) {
                ps.setString(1, "hyper-experiments");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        totalRows++;
                        rs.getString(1); if (rs.wasNull()) nullSha++;
                        rs.getLong(2);   if (rs.wasNull()) nullSize++;
                        String url = rs.getString(3);
                        if (url != null && !url.isBlank()) withGithubUrl++;
                        String sha = rs.getString(4);
                        if (sha != null && !sha.isBlank()) withGitSha++;
                    }
                }
            }

            boolean allRowsAreGithub = totalRows > 0
                    && nullSha == totalRows
                    && nullSize == totalRows
                    && withGithubUrl == totalRows
                    && withGitSha == totalRows;
            boolean pass = noTarballOnDisk && allRowsAreGithub;

            NodeResult result = pass
                    ? NodeResult.pass("hyper.registry.no.tarball")
                    : NodeResult.fail("hyper.registry.no.tarball",
                            "tarballsOnDisk=" + tarballsOnDisk
                                    + " rows=" + totalRows
                                    + " nullSha=" + nullSha
                                    + " nullSize=" + nullSize
                                    + " withGithubUrl=" + withGithubUrl
                                    + " withGitSha=" + withGitSha);
            return result
                    .assertion("no_tarball_on_disk", noTarballOnDisk)
                    .assertion("all_rows_github_pointer", allRowsAreGithub)
                    .metric("tarballsOnDisk", tarballsOnDisk)
                    .metric("hyperRows", totalRows)
                    .metric("nullSha", nullSha)
                    .metric("nullSize", nullSize)
                    .metric("withGithubUrl", withGithubUrl)
                    .metric("withGitSha", withGitSha);
        });
    }
}
