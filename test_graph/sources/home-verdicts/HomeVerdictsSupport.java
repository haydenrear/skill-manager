// Declared here as well as on every node that includes this file, because
// `sandbox.env.contract` walks each file's OWN //SOURCES closure: this class
// resolves the CLI (SmEnv.cli()), so it has to reach the helper on its own terms.
//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared machinery for the {@code home-verdicts} graph (OHV-0, #356): lay out a
 * home FROM NOTHING, plant one defect shape in it, and ask both verdict
 * commands about it.
 *
 * <h2>Why a hand-laid home and not a real install</h2>
 *
 * <p>{@code home-integrity} installs real units, because its invariants relate
 * two things the product wrote. This graph's subject is different: it is what
 * {@code home verify} and {@code home repair} SAY about a known damaged shape.
 * The shapes were all measured on real homes (see the graph's header), and each
 * one is a few bytes on disk. Planting them in a home laid out with
 * {@code SkillStore.init()}'s directories keeps every node independent of the
 * gateway venv, the network and the other nodes' state, and it keeps the
 * fixture's own damage out of any home a law could find (below).
 *
 * <h2>How the damaged homes stay out of the laws' way (#344 is OHV-5's)</h2>
 *
 * <p>{@code HomeFixpointLaw} and {@code HomeMembershipLaw} discover homes from
 * UPSTREAM CONTEXT VALUES, not from the filesystem. So a shape node never
 * publishes a path: it builds its subject and neighbour under the fixture's
 * {@code scratchRoot/<shape>/}, and deletes that directory in a {@code finally}.
 * The only home this graph publishes is the fixture's clean one, which holds no
 * unit (a unit on disk with no {@code installed/} record would be the membership
 * law's GAINED finding, correctly).
 *
 * <h2>stdout and stderr are read SEPARATELY</h2>
 *
 * <p>{@code home repair --json} prints its JSON on stdout and may print a
 * recorded-errors banner on stderr. The kickoff fleet script read the two as
 * one stream and lost four homes' finding counts. {@link #sm} writes each
 * stream to its own node log.
 */
final class HomeVerdictsSupport {

    private HomeVerdictsSupport() {}

    /** The unit every subject and neighbour holds, so a mis-anchored link RESOLVES. */
    static final String UNIT = "hv-unit";

    static final String FIXTURE = "home.verdicts.fixture";

    /** {@code SkillStore.init()}'s directories, in its order. */
    static final List<String> STORE_DIRS = List.of(
            "skills", "plugins", "docs", "harnesses", "projects",
            "bin", "bin/cli", "bin/mcp", "venvs", "npm", "cache", "installed");

    // ------------------------------------------------------------- layout

    /**
     * {@code <root>/.skill-manager} beside {@code <root>/.claude|.codex|.gemini}.
     * ROOT-SHAPED, so the agent directories are derived from the store
     * structurally, the way {@code HomeRepair.agentDirsOf} derives them.
     */
    static Path layOutHome(Path root) throws IOException {
        Path store = root.resolve(".skill-manager");
        for (String dir : STORE_DIRS) Files.createDirectories(store.resolve(dir));
        for (String agent : List.of(".claude", ".codex", ".gemini")) {
            Files.createDirectories(root.resolve(agent).resolve("skills"));
        }
        return store;
    }

    /** Put {@link #UNIT} in the store and project it into the home's own {@code .claude}. */
    static void holdUnit(Path store) throws IOException {
        Path unit = Files.createDirectories(store.resolve("skills").resolve(UNIT));
        Files.writeString(unit.resolve("SKILL.md"), "---\nname: " + UNIT
                + "\ndescription: home-verdicts fixture\n---\n");
        Path link = store.getParent().resolve(".claude/skills").resolve(UNIT);
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, unit);
    }

    /** An executable {@code venvs/<venv>/bin/<tool>}; returns its absolute path. */
    static Path venvTool(Path store, String venv, String tool) throws IOException {
        Path bin = Files.createDirectories(store.resolve("venvs").resolve(venv).resolve("bin"));
        Path exe = bin.resolve(tool);
        Files.writeString(exe, "#!/bin/sh\nexit 0\n");
        exe.toFile().setExecutable(true);
        return exe;
    }

    /** A regular-file wrapper in {@code bin/cli} that execs {@code target} LITERALLY. */
    static void literalShim(Path store, String name, Path target) throws IOException {
        Path shim = store.resolve("bin/cli").resolve(name);
        Files.writeString(shim, "#!/usr/bin/env bash\nexec \"" + target + "\" \"$@\"\n",
                StandardCharsets.UTF_8);
        shim.toFile().setExecutable(true);
    }

    // ------------------------------------------------------------- the CLI

    /** One CLI invocation, both streams kept apart. */
    record Verdict(ProcessRecord proc, int exit, String stdout, String stderr) {
        String both() { return stdout + "\n" + stderr; }
    }

    /**
     * Run this repository's skill-manager against {@code home}, sandboxed through
     * {@link SmEnv} like every other node, with stdout and stderr captured to
     * two node logs ({@code <label>.stdout.log}, {@code <label>.stderr.log}).
     */
    static Verdict sm(NodeContext ctx, String label, Path home, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(SmEnv.cli().toString());
        argv.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(argv);
        SmEnv.apply(ctx, pb, home);
        Instant started = Instant.now();
        Path out;
        Path err;
        try {
            out = Procs.logFile(ctx, label + ".stdout");
            err = Procs.logFile(ctx, label + ".stderr");
        } catch (IOException e) {
            return new Verdict(new ProcessRecord(label, argv, started, Instant.now(), -1, null,
                    null, "could not allocate log files: " + e.getMessage()), -1, "", "");
        }
        pb.redirectOutput(out.toFile());
        pb.redirectError(err.toFile());
        try {
            Process p = pb.start();
            int exit = p.waitFor();
            return new Verdict(new ProcessRecord(label, argv, started, Instant.now(), exit,
                    p.pid(), Procs.relativeToReport(ctx, out), null),
                    exit, read(out), read(err));
        } catch (IOException e) {
            return new Verdict(new ProcessRecord(label, argv, started, Instant.now(), -1, null,
                    Procs.relativeToReport(ctx, err), "spawn failed: " + e.getMessage()),
                    -1, "", "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Verdict(new ProcessRecord(label, argv, started, Instant.now(), -1, null,
                    Procs.relativeToReport(ctx, err), "interrupted"), -1, "", "");
        }
    }

    static Verdict repairJson(NodeContext ctx, String label, Path store, String... extra) {
        List<String> args = new ArrayList<>(List.of("home", "repair", "--home", store.toString(),
                "--json"));
        args.addAll(List.of(extra));
        return sm(ctx, label, store, args.toArray(String[]::new));
    }

    static Verdict verify(NodeContext ctx, String label, Path store) {
        return sm(ctx, label, store, "home", "verify", "--home", store.toString());
    }

    // ------------------------------------------------------------- reading

    /** stdout, alone, is exactly one JSON object. */
    static boolean isOneJsonObject(String stdout) {
        String s = stdout.strip();
        return s.startsWith("{") && s.endsWith("}") && s.indexOf('\n') < 0;
    }

    /** The finding {@code kind} about {@code subject}, as {@code reportJson} spells it. */
    static boolean reportsFinding(String json, String kind, String subject) {
        return json.contains("{\"kind\":\"" + kind + "\",\"subject\":\"" + subject + "\"");
    }

    static int findingCount(String json) {
        int n = 0;
        for (int at = json.indexOf("{\"kind\":\""); at >= 0; at = json.indexOf("{\"kind\":\"", at + 1)) n++;
        return n;
    }

    // ------------------------------------------------------------- one shape

    /** Plants a shape in {@code subject}; returns the home-relative subject a finding names. */
    interface Plant {
        String plant(Path subject, Path neighbour) throws IOException;
    }

    /**
     * What today's tree does with one shape.
     *
     * @param kind              the {@code HomeRepair.Kind} {@code home repair} must name
     * @param verifyExitToday   {@code home verify}'s exit on the planted home TODAY.
     *                          OHV-2 (#339) makes verify fail whenever repair reports;
     *                          it must change every 0 here to 1.
     * @param verifyNamesToday  whether {@code home verify}'s output names the kind today
     */
    record Shape(String dir, String kind, int verifyExitToday, boolean verifyNamesToday) {}

    /**
     * Plant, detect, ask verify, repair, detect again, delete. Seven separate CLI
     * processes, because a detector that repairs is no longer a detector (DEF-067).
     */
    static NodeResult runShape(NodeContext ctx, NodeSpec spec, Shape shape, Plant plant) {
        String id = spec.id();
        String scratchStr = ctx.get(FIXTURE, "scratchRoot").orElse(null);
        if (scratchStr == null) {
            return NodeResult.fail(id, "missing " + FIXTURE + " context (scratchRoot)");
        }
        Path work = Path.of(scratchStr).resolve(shape.dir());
        List<String> failures = new ArrayList<>();
        NodeResult result;
        try {
            deleteRecursively(work);
            Path subject = layOutHome(work.resolve("subject"));
            Path neighbour = layOutHome(work.resolve("neighbour"));
            holdUnit(subject);
            holdUnit(neighbour);

            // CONTROL: the same home before the plant. Without it a node that
            // "finds" the kind would also find it in a layout that is itself
            // damaged, and the assertion would be about the fixture.
            Verdict control = repairJson(ctx, "control", subject);
            boolean controlClean = control.exit() == 0 && control.stdout().contains("\"clean\":true");

            String rel = plant.plant(subject, neighbour);

            Verdict detect = repairJson(ctx, "detect", subject);
            Verdict detectAgain = repairJson(ctx, "detect-again", subject);
            Verdict verify = verify(ctx, "verify", subject);
            Verdict fix = repairJson(ctx, "fix", subject, "--fix");
            Verdict after = repairJson(ctx, "detect-after-fix", subject);
            Verdict verifyAfter = verify(ctx, "verify-after-fix", subject);

            boolean repairRed = detect.exit() == 1 && detectAgain.exit() == 1;
            boolean stdoutIsJson = isOneJsonObject(detect.stdout());
            boolean namesShape = reportsFinding(detect.stdout(), shape.kind(), rel);
            boolean detectionIsStable = detect.stdout().equals(detectAgain.stdout());
            boolean verifyExitAsToday = verify.exit() == shape.verifyExitToday();
            boolean verifyNames = verify.both().contains(shape.kind() + " " + rel);
            boolean verifyNamesAsToday = verifyNames == shape.verifyNamesToday();
            boolean fixCleared = after.exit() == 0 && after.stdout().contains("\"clean\":true");
            boolean verifyCleanAfterFix = verifyAfter.exit() == 0;

            if (!controlClean) failures.add("control: the unplanted subject was not clean: exit "
                    + control.exit() + " " + control.stdout().strip());
            if (!repairRed) failures.add("`home repair --json` exited " + detect.exit() + "/"
                    + detectAgain.exit() + " on a planted " + shape.kind() + ", expected 1");
            if (!stdoutIsJson) failures.add("stdout of `home repair --json` is not one JSON object: "
                    + head(detect.stdout()));
            if (!namesShape) failures.add("`home repair --json` does not name " + shape.kind()
                    + " on " + rel + ": " + head(detect.stdout()));
            if (!detectionIsStable) failures.add("two bare detections disagree — detection changed the home");
            if (!verifyExitAsToday) failures.add("`home verify` exited " + verify.exit() + ", today's tree "
                    + "exits " + shape.verifyExitToday() + " on this shape. If OHV-2 landed, update "
                    + "this node's Shape: that change is the point");
            if (!verifyNamesAsToday) failures.add("`home verify` " + (verifyNames ? "names" : "does not name")
                    + " " + shape.kind() + " " + rel + ", unlike today's tree");
            if (!fixCleared) failures.add("after `home repair --fix` a separate detection still exits "
                    + after.exit() + ": " + head(after.stdout()));
            if (!verifyCleanAfterFix) failures.add("after the fix `home verify` exits " + verifyAfter.exit());

            result = (failures.isEmpty() ? NodeResult.pass(id) : NodeResult.fail(id, String.join(" | ", failures)))
                    .process(control.proc()).process(detect.proc()).process(detectAgain.proc())
                    .process(verify.proc()).process(fix.proc()).process(after.proc())
                    .process(verifyAfter.proc())
                    .assertion("control_the_unplanted_home_is_clean", controlClean)
                    .assertion("home_repair_json_exits_1_on_the_planted_shape", repairRed)
                    .assertion("home_repair_json_stdout_alone_is_one_json_object", stdoutIsJson)
                    .assertion("home_repair_names_" + shape.kind() + "_on_the_planted_subject", namesShape)
                    .assertion("detection_alone_changes_nothing", detectionIsStable)
                    .assertion("TODAY_home_verify_exits_" + shape.verifyExitToday(), verifyExitAsToday)
                    .assertion("TODAY_home_verify_" + (shape.verifyNamesToday() ? "names" : "does_not_name")
                            + "_the_shape", verifyNamesAsToday)
                    .assertion("home_repair_fix_then_a_separate_detection_is_clean", fixCleared)
                    .assertion("home_verify_is_clean_after_the_fix", verifyCleanAfterFix)
                    .metric("verify.exit", verify.exit())
                    .metric("repair.exit", detect.exit())
                    .metric("repair.findings", findingCount(detect.stdout()))
                    .log("shape=" + shape.kind() + " subject=" + rel
                            + " | repair=" + detect.exit() + " verify=" + verify.exit()
                            + " (names it: " + verifyNames + ") fix=" + fix.exit()
                            + " after=" + after.exit() + " verify-after=" + verifyAfter.exit()
                            + "\nrepair --json stdout: " + detect.stdout().strip());
        } catch (IOException | RuntimeException e) {
            result = NodeResult.error(id, e);
        } finally {
            try {
                deleteRecursively(work);
            } catch (IOException ignored) {
                // reported below
            }
        }
        boolean cleaned = !Files.exists(work, LinkOption.NOFOLLOW_LINKS);
        return result.assertion("the_damaged_homes_are_deleted", cleaned);
    }

    // ------------------------------------------------------------------ fs

    static String read(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    static String head(String s) {
        String t = s.strip();
        return t.length() > 400 ? t.substring(0, 400) + "…" : t;
    }

    /** Delete without following links: a planted link points into another home. */
    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
