///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES CheapChecksKit.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * GOAL-checking-costs-what-changed clause (a), #410: {@code artifacts stale --json}
 * under 6 s, reading no file contents for a clean git checkout.
 *
 * <p>Fixture: a {@code home clone} of this checkout's project home, which holds the
 * real {@code tla-spec-dev} plugin (a git checkout, ~430 MB / ~26k files). The clone
 * is what a worktree home actually is, and it does not depend on the machine's root.
 *
 * <p>Instrument for "no bytes read": a JFR recording of {@code jdk.FileRead}
 * (threshold 0), carried by the CLI's own JVM through {@code JAVA_TOOL_OPTIONS}. It
 * counts JVM reads through {@code FileInputStream}/{@code FileChannel}, so a read git
 * does in a subprocess is not counted — which is the design under test (ask git, do
 * not hash) rather than a gap to hide. The recording arm is separate from the timing
 * arms so JFR overhead is not billed to the wall clock.
 *
 * <p>Counted: files under {@code plugins/tla-spec-dev/}, excluding {@code .git/} and
 * the manifests the CLI reads to know what a unit is ({@link #MANIFESTS}): those say
 * what the unit declares, and reading them is not fingerprinting bytes.
 *
 * <p>CONTROL: the same instrument on the same checkout with one tracked file edited
 * must see reads, because a dirty tree falls back to the walk (before and after CC-1).
 * It is what makes a zero meaningful.
 */
public class StaleTime {
    static final String ID = "cheap.stale-time";
    static final String PLUGIN = "tla-spec-dev";
    static final Set<String> MANIFESTS = Set.of("skill-manager.toml", "skill-manager-plugin.toml", "plugin.json", "SKILL.md");
    static final double TARGET_SECONDS = 6.0;
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("cheap-checks", "artifacts-stale", "410", "timing")
            .timeout("900s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path home;
            ProcessRecord clone;
            try {
                root = CheapChecksKit.scratch(ctx, "stale-time");
                home = root.resolve("home");
                clone = CheapChecksKit.cloneHome(ctx, "clone", root, CheapChecksKit.projectHome(), home);
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }
            Path plugin = home.resolve("plugins").resolve(PLUGIN);
            boolean setupCloned = clone.exitCode() == 0 && Files.isDirectory(plugin);
            boolean setupCleanCheckout = CheapChecksKit.cleanCheckout(plugin);
            long pluginFiles;
            try {
                pluginFiles = CheapChecksKit.fileCount(plugin);
            } catch (Exception e) {
                pluginFiles = -1;
            }

            // Warm-up: jbang's first compile of the CLI must not be billed to a reading.
            CheapChecksKit.sm(ctx, "warmup", root, home, "artifacts", "stale", "--json");

            double[] seconds = new double[3];
            double[] rss = new double[3];
            boolean measured = true;
            boolean exitsOk = true;
            for (int i = 0; i < 3; i++) {
                CheapChecksKit.Timing t = CheapChecksKit.timed(ctx, "stale_" + i, root, home,
                        "artifacts", "stale", "--json");
                seconds[i] = t.seconds();
                rss[i] = t.rssMb();
                measured &= t.measured();
                exitsOk &= t.process().exitCode() == 0;
            }
            double median = CheapChecksKit.median(seconds);
            double medianRss = CheapChecksKit.median(rss);

            Reads clean = recordReads(ctx, "jfr_clean", root, home, plugin);
            boolean controlDirtyEdit;
            Reads dirty;
            try {
                Path readme = plugin.resolve("README.md");
                if (!Files.isRegularFile(readme)) throw new IllegalStateException("no README.md in the plugin");
                Files.writeString(readme, Files.readString(readme) + "\ncheap-checks dirty\n");
                dirty = recordReads(ctx, "jfr_dirty", root, home, plugin);
                controlDirtyEdit = dirty.ok && dirty.counted > 0;
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed planting the dirty control: " + e)
                        .assertion("setup_ok", false);
            }

            boolean setup = setupCloned && setupCleanCheckout && measured;
            boolean timeOk = median < TARGET_SECONDS;
            boolean noReads = clean.ok && clean.counted == 0;
            String json = "{\"fixture\":\"home clone of the project home holding tla-spec-dev\","
                    + "\"plugin_files\":" + pluginFiles + ","
                    + "\"seconds\":" + java.util.Arrays.toString(seconds) + ",\"median_seconds\":" + median + ","
                    + "\"rss_mb\":" + java.util.Arrays.toString(rss) + ",\"median_rss_mb\":" + medianRss + ","
                    + "\"target_seconds\":" + TARGET_SECONDS + ","
                    + "\"clean_files_read\":" + clean.counted + ",\"clean_manifest_reads\":" + clean.manifestReads + ","
                    + "\"dirty_files_read\":" + dirty.counted + ",\"jfr_recordings\":" + clean.recordings + "}";
            String detail;
            try {
                detail = "evidence=" + CheapChecksKit.evidence(ctx, "stale-time", json) + " " + json;
            } catch (Exception e) {
                detail = json;
            }
            NodeResult result = setup && exitsOk && controlDirtyEdit && timeOk && noReads
                    ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(clone)
                    .metric("median_seconds", median)
                    .metric("median_rss_mb", medianRss)
                    .metric("clean_files_read", clean.counted)
                    .metric("dirty_files_read", dirty.counted)
                    .metric("plugin_files", pluginFiles)
                    .assertion("setup_cloned_home_holds_plugin", setupCloned)
                    .assertion("setup_plugin_is_clean_git_checkout", setupCleanCheckout)
                    .assertion("setup_time_l_parsed", measured)
                    .assertion("control_stale_exits_zero", exitsOk)
                    .assertion("control_instrument_sees_reads_on_a_dirty_checkout", controlDirtyEdit)
                    .assertion("shape_stale_under_6s", timeOk)
                    .assertion("shape_clean_checkout_reads_no_file_contents", noReads)
                    .log(detail);
        });
    }

    record Reads(boolean ok, long counted, long manifestReads, int recordings, List<String> sample) {}

    /** One {@code artifacts stale} under JFR; counts distinct plugin files the JVM read. */
    static Reads recordReads(NodeContext ctx, String label, Path root, Path home, Path plugin) {
        try {
            Path dir = root.resolve("jfr").resolve(label);
            Files.createDirectories(dir);
            Path settings = root.resolve("file-read.jfc");
            Files.writeString(settings, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <configuration version="2.0" label="file-read">
                      <event name="jdk.FileRead">
                        <setting name="enabled">true</setting>
                        <setting name="threshold">0 ns</setting>
                        <setting name="stackTrace">false</setting>
                      </event>
                    </configuration>
                    """);
            // -Xlog:jfr*=off: JFR's startup line goes to stdout, and jbang's launcher JVM
            // (which also inherits this variable) prints the command to exec on stdout, so
            // that line reached bash's `exec` and the CLI never ran. The dirty-checkout
            // control went red on exactly this when the instrument was first written.
            String jfrOption = "-Xlog:jfr*=off -XX:StartFlightRecording=settings=" + settings
                    + ",filename=" + dir.resolve("r-%p.jfr") + ",dumponexit=true";
            List<String> argv = new ArrayList<>(List.of(SmEnv.cli().toString(), "artifacts", "stale", "--json"));
            ProcessBuilder pb = new ProcessBuilder(argv).directory(SmEnv.repoRoot().toFile());
            Path agents = root.resolve("agents");
            SmEnv.apply(pb, home.toString(), SmEnv.sandbox(
                    agents.resolve("claude"), agents.resolve("codex"), agents.resolve("gemini")));
            String inherited = System.getenv("JAVA_TOOL_OPTIONS");
            pb.environment().put("JAVA_TOOL_OPTIONS",
                    (inherited == null || inherited.isBlank() ? "" : inherited + " ") + jfrOption);
            ProcessRecord run = Procs.run(ctx, label, pb);

            Set<String> counted = new TreeSet<>();
            Set<String> manifests = new TreeSet<>();
            int recordings = 0;
            Path realPlugin = plugin.toRealPath();
            try (var files = Files.list(dir)) {
                for (Path jfr : files.filter(f -> f.toString().endsWith(".jfr")).toList()) {
                    recordings++;
                    for (RecordedEvent e : RecordingFile.readAllEvents(jfr)) {
                        if (!e.getEventType().getName().equals("jdk.FileRead")) continue;
                        String path = e.getString("path");
                        if (path == null) continue;
                        Path p = Path.of(path);
                        Path rel;
                        if (p.startsWith(realPlugin)) rel = realPlugin.relativize(p);
                        else if (p.startsWith(plugin)) rel = plugin.relativize(p);
                        else continue;
                        if (rel.startsWith(".git")) continue;
                        if (MANIFESTS.contains(rel.getFileName().toString())) manifests.add(rel.toString());
                        else counted.add(rel.toString());
                    }
                }
            }
            boolean ran = run.exitCode() == 0 && CheapChecksKit.output(ctx, run).contains("\"summary\"");
            return new Reads(ran && recordings > 0, counted.size(), manifests.size(),
                    recordings, counted.stream().limit(20).toList());
        } catch (Exception e) {
            return new Reads(false, -1, -1, 0, List.of(String.valueOf(e)));
        }
    }
}
