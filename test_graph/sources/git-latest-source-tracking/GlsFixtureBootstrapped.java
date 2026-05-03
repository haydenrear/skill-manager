///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Self-contained git fixture for the {@code git-latest-source-tracking}
 * graph: stamp a tiny skill (SKILL.md + skill-manager.toml) into a
 * temp dir under {@code $home/gls-fixture}, init it as a git repo,
 * commit. Lives in the per-run {@code SKILL_MANAGER_HOME} so it never
 * touches the user's real install.
 *
 * <p>Publishes {@code skillName}, {@code skillDir}, {@code initialHash}
 * for every downstream node. This graph deliberately doesn't talk to
 * the registry — it's the {@code --git-latest} path under test, which
 * skips the registry entirely and pulls origin HEAD instead.
 */
public class GlsFixtureBootstrapped {
    static final NodeSpec SPEC = NodeSpec.of("gls.fixture.bootstrapped")
            .kind(NodeSpec.Kind.FIXTURE)
            .dependsOn("env.prepared")
            .tags("git-latest-source-tracking", "fixture", "git")
            .sideEffects("fs:write", "proc:spawn")
            .timeout("30s")
            .output("skillName", "string")
            .output("skillDir", "string")
            .output("initialHash", "string");

    static final String SKILL_NAME = "git-latest-fixture";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("gls.fixture.bootstrapped", "missing env.prepared context");
            }
            Path dir = Path.of(home).resolve("gls-fixture");
            if (Files.exists(dir)) deleteRecursive(dir);
            Files.createDirectories(dir);

            Files.writeString(dir.resolve("SKILL.md"),
                    "---\n"
                            + "name: " + SKILL_NAME + "\n"
                            + "description: git-latest-source-tracking fixture (initial revision).\n"
                            + "version: 0.0.1\n"
                            + "---\n\n"
                            + "# " + SKILL_NAME + "\n"
                            + "Initial revision.\n");
            Files.writeString(dir.resolve("skill-manager.toml"),
                    "skill_references = []\n"
                            + "[skill]\nname = \"" + SKILL_NAME + "\"\nversion = \"0.0.1\"\n");

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
                return NodeResult.fail("gls.fixture.bootstrapped",
                        "git init/add/commit failed (rc=" + initRc + "/" + addRc + "/" + commitRc + ")");
            }
            String hash = readHead(dir);
            if (hash == null) {
                return NodeResult.fail("gls.fixture.bootstrapped", "could not read HEAD after commit");
            }
            return NodeResult.pass("gls.fixture.bootstrapped")
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
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
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
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line);
        }
        return p.waitFor() == 0 ? out.toString().trim() : null;
    }

    private static void deleteRecursive(Path p) throws IOException {
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) {} });
        }
    }
}
