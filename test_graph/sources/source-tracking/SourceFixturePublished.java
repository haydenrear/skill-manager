///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bootstraps a self-contained git skill in {@code $home/source-fixture/}
 * for the source-tracking graph to install and exercise. Two reasons it's
 * its own node:
 *
 * <ul>
 *   <li>Initialising a real git repo is slow enough on macOS runners that
 *       each downstream node would pay the cost if it inlined this; one
 *       dependency lets every assertion reuse the same checkout.</li>
 *   <li>Downstream nodes mutate this dir (advance HEAD with new commits
 *       to test the merge path). Doing it once and threading the path
 *       through ctx.publish keeps the moves visible in node logs.</li>
 * </ul>
 *
 * <p>Publishes {@code skillName}, {@code skillDir}, and {@code initialHash}
 * so downstream nodes can identify the install + assert against the
 * recorded baseline.
 */
public class SourceFixturePublished {
    static final NodeSpec SPEC = NodeSpec.of("source.fixture.published")
            .kind(NodeSpec.Kind.FIXTURE)
            .dependsOn("env.prepared")
            .tags("source-tracking", "fixture", "git")
            .sideEffects("fs:write", "proc:spawn")
            .timeout("30s")
            .output("skillName", "string")
            .output("skillDir", "string")
            .output("initialHash", "string");

    private static final String SKILL_NAME = "source-tracking-fixture";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("source.fixture.published", "missing env.prepared context");
            }
            Path dir = Path.of(home).resolve("source-fixture");
            if (Files.exists(dir)) deleteRecursive(dir);
            Files.createDirectories(dir);

            Files.writeString(dir.resolve("SKILL.md"),
                    "---\n"
                            + "name: " + SKILL_NAME + "\n"
                            + "description: Source-tracking smoke fixture (initial revision).\n"
                            + "version: 0.0.1\n"
                            + "---\n\n"
                            + "# " + SKILL_NAME + "\n"
                            + "Initial revision.\n");
            Files.writeString(dir.resolve("skill-manager.toml"),
                    "skill_references = []\n"
                            + "[skill]\nname = \"" + SKILL_NAME + "\"\nversion = \"0.0.1\"\n");

            // Real git repo so install lands with .git in the store and
            // the source-tracking flow flips into kind=GIT mode.
            // -b main pins the default branch so downstream nodes can
            // assert/sync against a known branch name regardless of the
            // host machine's `init.defaultBranch` setting.
            int initRc = git(dir, "init", "-b", "main", "--quiet");
            int addRc = git(dir, "add", "-A");
            int commitRc = git(dir,
                    "-c", "user.email=fixture@skillmanager.local",
                    "-c", "user.name=fixture",
                    "commit", "--quiet", "-m", "initial");
            if (initRc != 0 || addRc != 0 || commitRc != 0) {
                return NodeResult.fail("source.fixture.published",
                        "git init/add/commit failed (rc=" + initRc + "/" + addRc + "/" + commitRc + ")");
            }
            String hash = readHead(dir);
            if (hash == null) {
                return NodeResult.fail("source.fixture.published", "could not read HEAD after commit");
            }
            return NodeResult.pass("source.fixture.published")
                    .assertion("git_init_ok", initRc == 0)
                    .assertion("commit_ok", commitRc == 0)
                    .assertion("head_readable", hash.length() == 40)
                    .publish("skillName", SKILL_NAME)
                    .publish("skillDir", dir.toString())
                    .publish("initialHash", hash);
        });
    }

    static int git(Path dir, String... args) throws IOException, InterruptedException {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add("git");
        for (String a : args) argv.add(a);
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.directory(dir.toFile());
        Process p = pb.start();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) System.out.println(line);
        }
        return p.waitFor();
    }

    static String readHead(Path dir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD")
                .redirectErrorStream(true);
        pb.directory(dir.toFile());
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line);
        }
        int rc = p.waitFor();
        return rc == 0 ? out.toString().trim() : null;
    }

    private static void deleteRecursive(Path p) throws IOException {
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) {} });
        }
    }
}
