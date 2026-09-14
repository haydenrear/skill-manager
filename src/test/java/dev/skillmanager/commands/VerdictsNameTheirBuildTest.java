package dev.skillmanager.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.BuildIdentity;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * <b>Every verdict names the build that produced it</b> — asserted on ONE home
 * judged by TWO builds (skill-manager#338).
 *
 * <h2>Why two builds and not one</h2>
 *
 * <p>A single-build test cannot tell "the build is printed" from "a constant is
 * printed": both put the same bytes on the same line. So every case here runs a
 * verdict command twice over the same home, once as if launched from checkout A
 * and once from checkout B ({@link BuildIdentity#judgedFrom}), and asserts
 * three things:
 *
 * <ol>
 *   <li>each run names ITS build's commit and not the other's;</li>
 *   <li>the two outputs differ; and</li>
 *   <li>with the build removed, the two outputs are identical — the build is
 *       the only thing that moved, so no verdict changed.</li>
 * </ol>
 */
public final class VerdictsNameTheirBuildTest {

    private static final String SHA_A = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";
    private static final String SHA_B = "ffff6666000077778888999900001111aaaa2222";
    private static final ObjectMapper JSON = new ObjectMapper();

    public static int run() throws Exception {
        return Tests.suite("VerdictsNameTheirBuildTest")

                .test("home repair: text names the build, and only the build differs", () -> {
                    SkillStore store = home();
                    textAcrossTwoBuilds("home repair",
                            () -> new HomeCommand.RepairCmd(store));
                })
                .test("home repair --json: a build field, and only it differs", () -> {
                    SkillStore store = home();
                    jsonAcrossTwoBuilds("home repair --json",
                            () -> new HomeCommand.RepairCmd(store), "--json");
                })
                .test("home drift: text names the build, and only the build differs", () -> {
                    SkillStore store = home();
                    textAcrossTwoBuilds("home drift",
                            () -> new HomeCommand.DriftCmd(store));
                })
                .test("home drift --json: a build field, and only it differs", () -> {
                    SkillStore store = home();
                    jsonAcrossTwoBuilds("home drift --json",
                            () -> new HomeCommand.DriftCmd(store), "--json");
                })
                .test("home verify: text names the build, and only the build differs", () -> {
                    SkillStore store = home();
                    textAcrossTwoBuilds("home verify",
                            HomeCommand.VerifyCmd::new, "--home", store.root().toString());
                })
                .test("artifacts list: text names the build, and only the build differs", () -> {
                    SkillStore store = home();
                    textAcrossTwoBuilds("artifacts list", () -> listing(store));
                })
                .test("artifacts list --json: a build field, and only it differs", () -> {
                    SkillStore store = home();
                    jsonAcrossTwoBuilds("artifacts list --json", () -> listing(store), "--json");
                })
                .test("the stamp is --version's two lines, not a third derivation", () -> {
                    try (AutoCloseable ignored = BuildIdentity.judgedFrom(checkout("same", SHA_A))) {
                        String[] version = BuildIdentity.lines();
                        String stamp = BuildIdentity.stamp();
                        assertTrue(stamp.startsWith(version[0]),
                                "the stamp begins with --version's first line: " + stamp);
                        assertTrue(stamp.endsWith(version[1].substring("build:".length()).trim()),
                                "and ends with its build: line: " + stamp);
                    }
                })

                .runAll();
    }

    // ------------------------------------------------------------- the checks

    private static void textAcrossTwoBuilds(String what, Supplier<Callable<Integer>> command,
                                            String... args) throws Exception {
        Result a = runAs(checkout("a", SHA_A), command, args);
        Result b = runAs(checkout("b", SHA_B), command, args);
        String shortA = SHA_A.substring(0, 12);
        String shortB = SHA_B.substring(0, 12);

        assertContains(a.out, "build: ", what + " prints a build line under A: " + a.out);
        assertContains(a.out, shortA, what + " names build A under A: " + a.out);
        assertFalse(a.all().contains(shortB), what + " does not name build B under A");
        assertContains(b.out, shortB, what + " names build B under B: " + b.out);
        assertFalse(b.all().contains(shortA), what + " does not name build A under B");
        assertFalse(a.out.equals(b.out), what + ": two builds, two outputs");

        assertEquals(a.rc, b.rc, what + ": the exit code is the verdict's, not the build's");
        assertEquals(withoutBuild(a.all(), a.stamp), withoutBuild(b.all(), b.stamp),
                what + ": with the build removed the two verdicts are identical");
    }

    private static void jsonAcrossTwoBuilds(String what, Supplier<Callable<Integer>> command,
                                            String... args) throws Exception {
        Result a = runAs(checkout("a", SHA_A), command, args);
        Result b = runAs(checkout("b", SHA_B), command, args);

        JsonNode docA = JSON.readTree(a.out);
        JsonNode docB = JSON.readTree(b.out);
        assertTrue(docA.hasNonNull("build"), what + " carries a build field: " + a.out);
        assertEquals(a.stamp, docA.get("build").asText(), what + ": the field is build A's stamp");
        assertEquals(b.stamp, docB.get("build").asText(), what + ": the field is build B's stamp");
        assertContains(docA.get("build").asText(), SHA_A.substring(0, 12), what + " under A");
        assertContains(docB.get("build").asText(), SHA_B.substring(0, 12), what + " under B");
        assertFalse(docA.get("build").equals(docB.get("build")), what + ": two builds, two fields");

        assertEquals(a.rc, b.rc, what + ": the exit code is the verdict's, not the build's");
        ((ObjectNode) docA).remove("build");
        ((ObjectNode) docB).remove("build");
        assertEquals(docA, docB, what + ": every other field is identical across the two builds");
    }

    // ---------------------------------------------------------------- fixture

    private static Callable<Integer> listing(SkillStore store) {
        ArtifactsCommand.ListArtifacts list = new ArtifactsCommand.ListArtifacts();
        list.injectedStore = store;
        return list;
    }

    private static SkillStore home() throws Exception {
        SkillStore store = new SkillStore(Files.createTempDirectory("verdict-build-home-"));
        store.init();
        return store;
    }

    /**
     * A checkout {@link BuildIdentity} accepts as this program's own: it holds
     * {@code SkillManager.java} and a {@code .git} whose branch is at {@code sha}.
     */
    private static Path checkout(String label, String sha) throws Exception {
        Path root = Files.createTempDirectory("verdict-build-" + label + "-");
        Files.writeString(root.resolve("SkillManager.java"), "// fixture\n");
        Path git = Files.createDirectories(root.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.createDirectories(git.resolve("refs/heads"));
        Files.writeString(git.resolve("refs/heads/main"), sha + "\n");
        return root;
    }

    private record Result(int rc, String out, String err, String stamp) {
        String all() { return out + err; }
    }

    private static Result runAs(Path checkout, Supplier<Callable<Integer>> command,
                                String... args) throws Exception {
        try (AutoCloseable ignored = BuildIdentity.judgedFrom(checkout)) {
            PrintStream realOut = System.out;
            PrintStream realErr = System.err;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            try {
                System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
                System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
                int rc = new CommandLine(command.get()).execute(args);
                return new Result(rc, out.toString(StandardCharsets.UTF_8),
                        err.toString(StandardCharsets.UTF_8), BuildIdentity.stamp());
            } finally {
                System.setOut(realOut);
                System.setErr(realErr);
            }
        }
    }

    /** The output with its build stamp and any temp-checkout path taken out. */
    private static String withoutBuild(String text, String stamp) {
        return text.lines()
                .map(line -> line.replace(stamp, "<build>"))
                .collect(Collectors.joining("\n"));
    }
}
