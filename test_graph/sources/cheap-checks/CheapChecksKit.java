//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared fixture code for the {@code cheap-checks} graph (epic cheap-checks #414,
 * built by CC-0 #441). NOT a node: nothing in {@code build.gradle.kts} names it;
 * each node pulls it in with {@code //SOURCES CheapChecksKit.java}. Tickets fixing
 * a clause edit that clause's node file only; nothing here should need to change.
 *
 * <p>Convention, so a red node says WHICH half is red:
 * <ul>
 *   <li>{@code setup_*}: the fixture exists. Red is a harness defect.</li>
 *   <li>{@code control_*}: neighbouring behaviour that must hold before AND after
 *       the fix, and proves the instrument can see what it counts.</li>
 *   <li>{@code shape_*}: the clause's target. Expected red until its ticket lands.</li>
 * </ul>
 */
final class CheapChecksKit {

    private CheapChecksKit() {}

    /** A fresh empty scratch root for one clause, with sandboxed agent homes. */
    static Path scratch(NodeContext ctx, String clause) throws IOException {
        String envHome = ctx.get("env.prepared", "home").orElse(null);
        if (envHome == null) throw new IOException("missing env.prepared context");
        Path root = Path.of(envHome).resolve("cheap-checks").resolve(clause).toAbsolutePath().normalize();
        deleteTree(root);
        Files.createDirectories(root.resolve("agents/claude"));
        Files.createDirectories(root.resolve("agents/codex"));
        Files.createDirectories(root.resolve("agents/gemini"));
        return root;
    }

    /** The checkout's own project home: the fixture source for the timing clauses. */
    static Path projectHome() {
        return SmEnv.repoRoot().resolve(".skill-manager");
    }

    /** Write the permissive test policy, so unattended installs do not prompt. */
    static void writePolicy(Path home) throws IOException {
        Files.createDirectories(home);
        Files.writeString(home.resolve("policy.toml"), """
                require_confirmation = false
                [install]
                require_confirmation_for_hooks = false
                require_confirmation_for_mcp = false
                require_confirmation_for_cli_deps = false
                require_confirmation_for_executable_commands = false
                """);
    }

    private static ProcessBuilder builder(Path root, Path home, List<String> argv) {
        ProcessBuilder pb = new ProcessBuilder(argv).directory(SmEnv.repoRoot().toFile());
        Path agents = root.resolve("agents");
        SmEnv.apply(pb, home.toString(), SmEnv.sandbox(
                agents.resolve("claude"), agents.resolve("codex"), agents.resolve("gemini")));
        return pb;
    }

    /** Run the checkout's CLI against {@code home}. */
    static ProcessRecord sm(NodeContext ctx, String label, Path root, Path home, String... args) {
        return sm(ctx, label, root, home, null, args);
    }

    /** {@link #sm} with {@code binDir} first on PATH: how fake agent CLIs are reached. */
    static ProcessRecord sm(NodeContext ctx, String label, Path root, Path home, Path binDir, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(SmEnv.cli().toString());
        argv.addAll(List.of(args));
        ProcessBuilder pb = builder(root, home, argv);
        if (binDir != null) pb.environment().merge("PATH", binDir + ":", (old, add) -> add + old);
        return Procs.run(ctx, label, pb);
    }

    /** {@code home clone}: the worktree-provisioning path. {@code from} is read, never written. */
    static ProcessRecord cloneHome(NodeContext ctx, String label, Path root, Path from, Path to) {
        return sm(ctx, label, root, to, "home", "clone", "--from", from.toString(), "--to", to.toString());
    }

    static String output(NodeContext ctx, ProcessRecord record) {
        if (record == null) return "";
        try {
            return Files.readString(Procs.logFile(ctx, record.label()));
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ timing

    /** One {@code /usr/bin/time -l} reading. Seconds and max RSS are -1 when unparseable. */
    record Timing(ProcessRecord process, double seconds, double rssMb, String output) {
        boolean measured() { return seconds >= 0 && rssMb >= 0; }
    }

    private static final Pattern REAL = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+real\\b");
    private static final Pattern RSS = Pattern.compile("(\\d+)\\s+maximum resident set size");

    /**
     * The CLI under BSD {@code /usr/bin/time -l} (macOS; GNU time has no such flag, so
     * off macOS the reading is -1 and the node's setup assertion says so).
     * {@code time} waits on the jbang launcher, which execs the application JVM in
     * place, so the RSS is the JVM's; git subprocesses are far smaller.
     */
    static Timing timed(NodeContext ctx, String label, Path root, Path home, String... args) {
        List<String> argv = new ArrayList<>(List.of("/usr/bin/time", "-l", SmEnv.cli().toString()));
        argv.addAll(List.of(args));
        ProcessRecord record = Procs.run(ctx, label, builder(root, home, argv));
        String out = output(ctx, record);
        Matcher real = REAL.matcher(out);
        Matcher rss = RSS.matcher(out);
        double seconds = -1;
        while (real.find()) seconds = Double.parseDouble(real.group(1));
        double mb = -1;
        while (rss.find()) mb = Double.parseDouble(rss.group(1)) / (1024 * 1024);
        return new Timing(record, seconds, mb, out);
    }

    static double median(double... values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    /** Write {@code json} to {@code <report>/evidence/<name>.json}; returns the path. */
    static Path evidence(NodeContext ctx, String name, String json) throws IOException {
        Path dir = ctx.reportDir().resolve("evidence");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".json");
        Files.writeString(file, json);
        return file;
    }

    // ------------------------------------------------------------------ git

    private record Result(int exit, String out) {}

    private static Result run(Path dir, String... args) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        Process p = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out);
    }

    static void git(Path dir, String... args) throws IOException, InterruptedException {
        Result r = run(dir, args);
        if (r.exit != 0) throw new IOException("git " + String.join(" ", args) + " (rc=" + r.exit + "): " + r.out);
    }

    static String gitOut(Path dir, String... args) throws IOException, InterruptedException {
        Result r = run(dir, args);
        if (r.exit != 0) throw new IOException("git " + String.join(" ", args) + " (rc=" + r.exit + "): " + r.out);
        return r.out.trim();
    }

    /** git's exit code and output, never throwing: for the checklist that reports each op. */
    static String[] gitTry(Path dir, String... args) {
        try {
            Result r = run(dir, args);
            return new String[]{String.valueOf(r.exit), r.out.trim()};
        } catch (Exception e) {
            return new String[]{"-1", String.valueOf(e)};
        }
    }

    static void commitAll(Path dir, String message) throws IOException, InterruptedException {
        git(dir, "add", "-A");
        git(dir, "-c", "user.email=cheap-checks@skillmanager.local", "-c", "user.name=cheap-checks",
                "commit", "--quiet", "-m", message);
    }

    /** True when {@code dir} is a git checkout with no porcelain status. */
    static boolean cleanCheckout(Path dir) {
        String[] r = gitTry(dir, "status", "--porcelain");
        return "0".equals(r[0]) && r[1].isEmpty();
    }

    // -------------------------------------------------------------- fixtures

    static void writeSkill(Path dir, String name, String version, String marker) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: cheap-checks graph fixture.
                ---

                # %s

                %s
                """.formatted(name, name, marker));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "%s"
                description = "cheap-checks graph fixture"
                """.formatted(name, version));
    }

    /** A plugin unit with one contained skill. */
    static void writePlugin(Path dir, String name, String marker) throws IOException {
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"" + name + "\",\"version\":\"0.0.1\",\"description\":\"cheap-checks fixture.\"}\n");
        Files.writeString(dir.resolve("skill-manager-plugin.toml"), """
                [plugin]
                name = "%s"
                version = "0.0.1"
                description = "cheap-checks fixture."
                """.formatted(name));
        Path contained = dir.resolve("skills").resolve(name + "-impl");
        Files.createDirectories(contained);
        Files.writeString(contained.resolve("SKILL.md"),
                "---\nname: " + name + "-impl\ndescription: Contained fixture skill.\n---\n\n" + marker + "\n");
        Files.writeString(contained.resolve("skill-manager.toml"), """
                [skill]
                name = "%s-impl"
                version = "0.0.1"
                description = "Contained fixture skill."
                """.formatted(name));
    }

    static void writeExecutable(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        file.toFile().setExecutable(true);
    }

    static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Non-empty lines of {@code file}; empty when it does not exist. */
    static List<String> lines(Path file) {
        return read(file).lines().filter(l -> !l.isBlank()).toList();
    }

    static long fileCount(Path dir) throws IOException {
        if (!Files.exists(dir)) return 0;
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /** {@code claude} remembers added marketplaces so its re-list confirms them; {@code codex} just logs. */
    static void writeFakeAgents(Path bin, Path log, Path state) throws Exception {
        String head = "#!/usr/bin/env bash\necho \"$(basename \"$0\") $*\" >> \"" + log + "\"\n";
        writeExecutable(bin.resolve("codex"), head + "exit 0\n");
        writeExecutable(bin.resolve("claude"), head + """
                if [ "$1 $2 $3" = "plugin marketplace add" ]; then
                  printf '%s\\n' "$4" >> "@STATE@"; exit 0
                fi
                if [ "$1 $2 $3" = "plugin marketplace list" ]; then
                  printf '['; sep=''
                  if [ -f "@STATE@" ]; then
                    while IFS= read -r p; do
                      n=$(sed -n 's/.*"name" *: *"\\([^"]*\\)".*/\\1/p' "$p/.claude-plugin/marketplace.json" | head -1)
                      printf '%s{"name":"%s","path":"%s"}' "$sep" "$n" "$p"; sep=','
                    done < "@STATE@"
                  fi
                  printf ']\\n'; exit 0
                fi
                if [ "$1 $2" = "plugin list" ]; then printf '[]\\n'; exit 0; fi
                exit 0
                """.replace("@STATE@", state.toString()));
    }

    /** Assertion-name-safe list of names. */
    static Set<String> setOf(String... names) {
        return Set.of(names);
    }
}
