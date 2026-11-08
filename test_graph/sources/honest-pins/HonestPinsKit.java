//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared fixture code for the {@code honest-pins} graph (epic honest-pins,
 * HP-0). NOT a node: nothing in {@code build.gradle.kts} names it; each node
 * pulls it in with {@code //SOURCES HonestPinsKit.java}. It names
 * {@code ../lib/SmEnv.java} in its OWN {@code //SOURCES} because it spawns the
 * CLI: {@code sandbox.env.contract} (home-tripwire) walks each file's own
 * closure and flags a CLI resolver that does not reach the helper
 * (DEF-HP9-001), the same shape as {@code HomeSyncSupport}.
 *
 * <p>Every node in the graph plants ONE shape in a scratch home of its own,
 * under {@code <env.prepared home>/honest-pins/<shape>}, so the nodes share no
 * state and any one of them can be run alone with {@code --run-only-node}.
 * The tickets that fix a shape edit only that shape's node file; nothing in
 * here should need to change for them.
 *
 * <p>The convention every node follows, so a red node says WHICH half is red:
 * <ul>
 *   <li>{@code setup_*} assertions: the shape was planted. A red one is a
 *       harness defect, not a baseline.</li>
 *   <li>{@code control_*} assertions: the neighbouring behaviour that must stay
 *       as it is, red neither before nor after the fix.</li>
 *   <li>{@code shape_*} assertions: the HONEST outcome. These are the ones
 *       expected red until the shape's ticket lands.</li>
 * </ul>
 */
final class HonestPinsKit {

    private HonestPinsKit() {}

    /** A fresh, empty scratch directory for one shape, plus sandboxed agent homes. */
    static Path scratch(NodeContext ctx, String shape) throws IOException {
        String envHome = ctx.get("env.prepared", "home").orElse(null);
        if (envHome == null) throw new IOException("missing env.prepared context");
        Path root = Path.of(envHome).resolve("honest-pins").resolve(shape).toAbsolutePath().normalize();
        deleteTree(root);
        Files.createDirectories(root.resolve("agents/claude"));
        Files.createDirectories(root.resolve("agents/codex"));
        Files.createDirectories(root.resolve("agents/gemini"));
        return root;
    }

    /** Run the checkout's {@code ./skill-manager} against {@code home}, agent homes sandboxed under {@code root}. */
    static ProcessRecord sm(NodeContext ctx, String label, Path root, Path home, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(SmEnv.cli().toString());
        argv.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(argv).directory(SmEnv.repoRoot().toFile());
        Path agents = root.resolve("agents");
        SmEnv.apply(pb, home.toString(), SmEnv.sandbox(
                agents.resolve("claude"), agents.resolve("codex"), agents.resolve("gemini")));
        return Procs.run(ctx, label, pb);
    }

    /** What a CLI run printed, from its captured log; empty when unreadable. */
    static String output(NodeContext ctx, ProcessRecord record) {
        if (record == null) return "";
        try {
            return Files.readString(Procs.logFile(ctx, record.label()));
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ git

    static void git(Path dir, String... args) throws IOException, InterruptedException {
        Result r = run(dir, args);
        if (r.exit != 0) throw new IOException("git " + String.join(" ", args) + " (rc=" + r.exit + "): " + r.out);
    }

    static String gitOut(Path dir, String... args) throws IOException, InterruptedException {
        Result r = run(dir, args);
        if (r.exit != 0) throw new IOException("git " + String.join(" ", args) + " (rc=" + r.exit + "): " + r.out);
        return r.out.trim();
    }

    /** HEAD of {@code dir}, or a parenthesised reason; never throws. */
    static String head(Path dir) {
        try {
            Result r = run(dir, "rev-parse", "HEAD");
            return r.exit == 0 ? r.out.trim() : "(no HEAD: " + r.out.trim() + ")";
        } catch (Exception e) {
            return "(no HEAD: " + e.getMessage() + ")";
        }
    }

    static void commitAll(Path dir, String message) throws IOException, InterruptedException {
        git(dir, "add", "-A");
        git(dir, "-c", "user.email=honest-pins@skillmanager.local", "-c", "user.name=honest-pins",
                "commit", "--quiet", "-m", message);
    }

    private record Result(int exit, String out) {}

    private static Result run(Path dir, String... args) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        Process p = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out);
    }

    // -------------------------------------------------------------- fixtures

    /** A minimal skill unit at {@code dir}: SKILL.md carrying {@code marker}, manifest at {@code version}. */
    static void writeSkill(Path dir, String name, String version, String marker) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: honest-pins graph fixture.
                ---

                # %s

                %s
                """.formatted(name, name, marker));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "%s"
                description = "honest-pins graph fixture"
                """.formatted(name, version));
    }

    static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** The {@code "version"} field of {@code installed/<name>.json}, or null. */
    static String recordedVersion(Path home, String name) {
        return jsonString(read(home.resolve("installed").resolve(name + ".json")), "version");
    }

    /** The {@code "gitHash"} field of {@code installed/<name>.json}, or null. */
    static String recordedGitHash(Path home, String name) {
        return jsonString(read(home.resolve("installed").resolve(name + ".json")), "gitHash");
    }

    /** The {@code version = "..."} line of a unit's {@code skill-manager.toml}, or null. */
    static String manifestVersion(Path unitDir) {
        for (String line : read(unitDir.resolve("skill-manager.toml")).split("\n")) {
            String t = line.trim();
            if (t.startsWith("version") && t.contains("=")) {
                String v = t.substring(t.indexOf('=') + 1).trim();
                return v.replace("\"", "");
            }
        }
        return null;
    }

    /** {@code key = "value"} inside the {@code [[units]]} block of {@code units.lock.toml} naming {@code unit}. */
    static String lockField(Path home, String unit, String key) {
        String text = read(home.resolve("units.lock.toml"));
        for (String block : text.split("\\[\\[units]]")) {
            if (!block.contains("name = \"" + unit + "\"")) continue;
            for (String line : block.split("\n")) {
                String t = line.trim();
                if (t.startsWith(key + " ") || t.startsWith(key + "=")) {
                    return t.substring(t.indexOf('=') + 1).trim().replace("\"", "");
                }
            }
        }
        return null;
    }

    /** First {@code "key" : "value"} in a JSON text. Enough for the flat records read here. */
    static String jsonString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** The last line of {@code text} that parses as a JSON object, or "". */
    static String lastJsonLine(String text) {
        String found = "";
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("{") && t.endsWith("}")) found = t;
        }
        return found;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
