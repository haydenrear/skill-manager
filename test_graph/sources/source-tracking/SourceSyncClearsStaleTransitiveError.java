///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A persisted {@code TRANSITIVE_RESOLVE_FAILED} must not outlive its
 * cause (issue #128). Self-contained fixture, end-to-end via the CLI:
 *
 * <ol>
 *   <li>Stamp + install a clean git skill (no references).</li>
 *   <li>Advance upstream to declare an unresolvable direct-git
 *       reference; {@code sync} fast-forwards, fails the resolve, and
 *       records the error in {@code installed/<name>.json}.</li>
 *   <li>Advance upstream to REMOVE the offending reference;
 *       {@code sync} fast-forwards again and — with zero refs left to
 *       resolve — must clear the persisted error ("self-clears on the
 *       next pass where every ref resolves").</li>
 * </ol>
 */
public class SourceSyncClearsStaleTransitiveError {
    static final NodeSpec SPEC = NodeSpec.of("source.sync.clears_stale_transitive_error")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("source.sync.all_aggregates")
            .tags("source-tracking", "sync", "errors", "git")
            .sideEffects("fs:tmp")
            .timeout("180s");

    private static final String SKILL_NAME = "stale-ref-fixture";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || geminiHome == null) {
                return NodeResult.fail("source.sync.clears_stale_transitive_error",
                        "missing upstream context");
            }

            // 1. Fixture with no references; install it.
            Path dir = Path.of(home).resolve("stale-ref-fixture-upstream");
            if (Files.exists(dir)) deleteRecursive(dir);
            Files.createDirectories(dir);
            writeFixture(dir, null);
            int initRc = git(dir, "init", "-b", "main", "--quiet");
            if (initRc != 0 || commit(dir, "initial") != 0) {
                return NodeResult.fail("source.sync.clears_stale_transitive_error",
                        "fixture git bootstrap failed");
            }

            int installRc = skillManager(home, claudeHome, codexHome, geminiHome,
                    "install", "file:" + dir, "--yes");
            if (installRc != 0) {
                return NodeResult.fail("source.sync.clears_stale_transitive_error",
                        "install rc=" + installRc);
            }
            Path sourceJson = Path.of(home).resolve("installed").resolve(SKILL_NAME + ".json");
            if (!Files.isRegularFile(sourceJson)) {
                return NodeResult.fail("source.sync.clears_stale_transitive_error",
                        "no installed record after install");
            }

            // 2. Upstream now declares an unresolvable direct-git ref;
            //    sync fast-forwards and must record the resolve error.
            String badRef = "git+" + Path.of(home).resolve("no-such-upstream").toUri() + "#deadbeef";
            writeFixture(dir, badRef);
            if (commit(dir, "add unresolvable reference") != 0) {
                return NodeResult.fail("source.sync.clears_stale_transitive_error",
                        "could not commit bad-ref revision");
            }
            int badSyncRc = skillManager(home, claudeHome, codexHome, geminiHome,
                    "sync", SKILL_NAME);
            boolean errorRecorded = hasTransitiveError(sourceJson);

            // 3. The user's fix: upstream REMOVES the offending
            //    reference. The next sync must clear the stale error.
            writeFixture(dir, null);
            if (commit(dir, "remove offending reference") != 0) {
                return NodeResult.fail("source.sync.clears_stale_transitive_error",
                        "could not commit ref-removed revision");
            }
            int clearSyncRc = skillManager(home, claudeHome, codexHome, geminiHome,
                    "sync", SKILL_NAME);
            boolean errorCleared = !hasTransitiveError(sourceJson);

            boolean pass = errorRecorded && errorCleared && clearSyncRc == 0;
            NodeResult result = pass
                    ? NodeResult.pass("source.sync.clears_stale_transitive_error")
                    : NodeResult.fail("source.sync.clears_stale_transitive_error",
                            "errorRecorded=" + errorRecorded
                                    + " errorCleared=" + errorCleared
                                    + " badSyncRc=" + badSyncRc
                                    + " clearSyncRc=" + clearSyncRc);
            return result
                    .assertion("failing_sync_records_error", errorRecorded)
                    .assertion("ref_removed_sync_clears_error", errorCleared)
                    .assertion("ref_removed_sync_exits_zero", clearSyncRc == 0);
        });
    }

    private static void writeFixture(Path dir, String reference) throws IOException {
        Files.writeString(dir.resolve("SKILL.md"),
                "---\n"
                        + "name: " + SKILL_NAME + "\n"
                        + "description: Stale transitive-error clearing fixture.\n"
                        + "version: 0.0.1\n"
                        + "---\n\n"
                        + "# " + SKILL_NAME + "\n");
        String references = reference == null
                ? "skill_references = []\n"
                : "skill_references = [\"" + reference + "\"]\n";
        Files.writeString(dir.resolve("skill-manager.toml"),
                references
                        + "[skill]\nname = \"" + SKILL_NAME + "\"\nversion = \"0.0.1\"\n");
    }

    private static int commit(Path dir, String message) throws IOException, InterruptedException {
        int addRc = git(dir, "add", "-A");
        if (addRc != 0) return addRc;
        return git(dir,
                "-c", "user.email=fixture@skillmanager.local",
                "-c", "user.name=fixture",
                "commit", "--quiet", "-m", message);
    }

    private static int skillManager(String home, String claudeHome, String codexHome,
                                    String geminiHome, String... args)
            throws IOException, InterruptedException {
        Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(repoRoot.resolve("skill-manager").toString());
        for (String a : args) argv.add(a);
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        pb.environment().put("CLAUDE_HOME", claudeHome);
        pb.environment().put("CODEX_HOME", codexHome);
        pb.environment().put("GEMINI_HOME", geminiHome);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) System.out.println(line);
        }
        return p.waitFor();
    }

    private static boolean hasTransitiveError(Path sourceJson) throws IOException {
        if (!Files.isRegularFile(sourceJson)) return false;
        JsonNode errors = new ObjectMapper().readTree(sourceJson.toFile()).path("errors");
        if (!errors.isArray()) return false;
        for (JsonNode err : errors) {
            if ("TRANSITIVE_RESOLVE_FAILED".equals(err.path("kind").asText())) return true;
        }
        return false;
    }

    private static int git(Path dir, String... args) throws IOException, InterruptedException {
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

    private static void deleteRecursive(Path p) throws IOException {
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) {} });
        }
    }
}
