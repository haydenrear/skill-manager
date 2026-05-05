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
 * Validates source-provenance recording end-to-end against the real
 * {@code hyper-experiments} install. Because the registry resolved this
 * skill to a github pointer and {@link dev.skillmanager.store.Fetcher}
 * cloned via JGit, the installed copy must land with {@code .git/}
 * intact and the source record must report it as {@code kind=GIT}
 * with the github URL pinned to {@code origin} and {@code gitHash}
 * matching the actual {@code HEAD} of the install dir.
 *
 * <p>Publishes {@code installedHash} so the downstream merge node can
 * later assert the install was reset to a prior commit and that the
 * {@code --merge} path restored it to this exact hash.
 */
public class HyperSourceRecorded {
    static final NodeSpec SPEC = NodeSpec.of("hyper.source.recorded")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hyper.installed")
            .tags("hyper", "source-tracking", "git")
            .timeout("30s")
            .output("installedHash", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("hyper.source.recorded", "missing env.prepared context");
            }
            Path storeDir = Path.of(home).resolve("skills").resolve("hyper-experiments");
            Path sourceJson = Path.of(home).resolve("installed").resolve("hyper-experiments.json");

            boolean storeHasGit = Files.isDirectory(storeDir.resolve(".git"));
            boolean sourceJsonExists = Files.isRegularFile(sourceJson);
            String kind = null;
            String hash = null;
            String origin = null;
            if (sourceJsonExists) {
                JsonNode n = new ObjectMapper().readTree(sourceJson.toFile());
                kind = textOrNull(n, "kind");
                hash = textOrNull(n, "gitHash");
                origin = textOrNull(n, "origin");
            }
            String headHash = readHead(storeDir);

            boolean kindIsGit = "GIT".equals(kind);
            boolean hashMatchesHead = hash != null && hash.equals(headHash);
            boolean originGithub = origin != null
                    && origin.contains("github.com")
                    && origin.contains("hyper-experiments");

            boolean pass = storeHasGit && sourceJsonExists && kindIsGit
                    && hashMatchesHead && originGithub;
            return (pass
                    ? NodeResult.pass("hyper.source.recorded")
                    : NodeResult.fail("hyper.source.recorded",
                            "storeHasGit=" + storeHasGit + " sourceJson=" + sourceJsonExists
                                    + " kind=" + kind + " hashMatchesHead=" + hashMatchesHead
                                    + " (json=" + hash + " head=" + headHash + ")"
                                    + " origin=" + origin))
                    .assertion("install_has_git_dir", storeHasGit)
                    .assertion("source_json_written", sourceJsonExists)
                    .assertion("source_kind_is_git", kindIsGit)
                    .assertion("source_hash_matches_head", hashMatchesHead)
                    .assertion("source_origin_is_github_hyper", originGithub)
                    .publish("installedHash", headHash == null ? "" : headHash);
        });
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String readHead(Path dir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true);
            pb.directory(dir.toFile());
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line);
            }
            return p.waitFor() == 0 ? out.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
