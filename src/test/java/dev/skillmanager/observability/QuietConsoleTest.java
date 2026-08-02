package dev.skillmanager.observability;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.effects.ConsoleProgramRenderer;
import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import dev.skillmanager.util.RunLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * <b>The console is a budget, and the budget is not met by printing nothing.</b>
 *
 * <h2>What was measured</h2>
 *
 * <p>On a six-unit fixture home, {@code sync} printed 42 lines / 2118 bytes and
 * {@code install} 35 lines / 2177 bytes — most of it one sentence per
 * (unit × agent) reporting success. It scales: twenty units across three
 * agents is sixty of those lines, and an agent that reads stdout pays for
 * every one of them on every invocation.
 *
 * <h2>The vacuity this suite is built against</h2>
 *
 * <p>"The output is short" passes trivially for a command that prints nothing,
 * and a command that prints nothing is strictly worse than a chatty one — the
 * caller cannot tell success from silence. So <b>no budget assertion appears
 * without the evidence assertion beside it, in the same case</b>: the same run
 * that must fit in N lines must also contain the counts that make its claim
 * checkable. Delete the rollup and the budget still passes; delete the rollup
 * and THESE cases fail.
 *
 * <p>The same rule applies to the log: "a log path is named" is worthless if
 * the file is empty, so every case that asserts the path also opens the file
 * and asserts a specific demoted line is in it.
 */
public final class QuietConsoleTest {

    private static final int UNITS = 20;
    private static final List<String> AGENTS = List.of("claude", "codex", "gemini");

    public static int run() throws Exception {
        return Tests.suite("QuietConsoleTest")

                // ------------------------------------------------- the budget

                .test("a 20-unit sync fits in 6 console lines AND still states its counts", () -> {
                    Capture c = renderSync(false);

                    // --- the budget half
                    assertTrue(c.lines().size() <= 6,
                            "a 20-unit / 3-agent sync must fit in 6 console lines; got "
                                    + c.lines().size() + ":\n" + c.text());

                    // --- the anti-vacuity half, in the SAME case. Without
                    //     these three the budget above is satisfied by a
                    //     command that says nothing at all.
                    // 20, not 22: two of these units carry BOTH a merge fact and
                    // a local-origin fact, and summing over facts would report
                    // more units than the home has.
                    assertContains(c.text(), "synced 20 unit(s)",
                            "the verdict states how many units were synced");
                    assertContains(c.text(), "20 merged",
                            "and how many of them actually moved");
                    assertContains(c.text(), "2 local-only",
                            "and how many will never see anyone else's work, which is the "
                                    + "state nobody notices");
                    assertContains(c.text(), "agents: 20 unit(s) linked into claude, codex, gemini",
                            "and the projection count, per agent, which is what an agent "
                                    + "launched here can actually read");

                    // --- and the detail is not lost, it is written down
                    assertNotNull(c.log(), "the run log path is named");
                    assertContains(c.footer(), "log: " + c.log(),
                            "and named on the console, once");
                    String body = Files.readString(c.log(), StandardCharsets.UTF_8);
                    assertContains(body, "claude: synced unit-07",
                            "the per-item line the console dropped is in the log");
                    assertContains(body, "gemini: synced unit-19",
                            "all of them, not a sample");
                    assertEquals(UNITS * AGENTS.size(),
                            count(body, ": synced unit-"),
                            "every (unit x agent) line reached the log");
                })

                .test("--verbose restores every line the quiet console dropped", () -> {
                    Capture quiet = renderSync(false);
                    Capture loud = renderSync(true);

                    assertTrue(loud.lines().size() >= UNITS * AGENTS.size(),
                            "--verbose prints at least one line per (unit x agent); got "
                                    + loud.lines().size());
                    assertContains(loud.text(), "claude: synced unit-07",
                            "the demoted line is back on the console");
                    assertContains(loud.text(), "unit-03: merged abc1234",
                            "and so is the per-unit sync outcome");
                    assertTrue(loud.lines().size() > quiet.lines().size() * 5,
                            "verbose is the full output, not a slightly longer summary: "
                                    + loud.lines().size() + " vs " + quiet.lines().size());
                    // Nothing is reachable ONLY through the file: everything in
                    // the log is on the verbose console.
                    String body = Files.readString(quiet.log(), StandardCharsets.UTF_8);
                    for (String line : body.split("\n")) {
                        String stripped = line.replaceFirst("^[>\\s]+", "").strip();
                        if (stripped.isEmpty() || stripped.startsWith("#")) continue;
                        if (stripped.startsWith("✓ synced ") || stripped.startsWith("✓ agents: ")) {
                            // The rollups replace the demoted lines and are not
                            // printed under --verbose, by design.
                            continue;
                        }
                        assertContains(loud.text(), stripped,
                                "everything in the log is on the verbose console");
                    }
                })

                // -------------------------------------------------- the errors

                .test("a 300-entry failure prints a bounded head, the count, and the log", () -> {
                    List<String> leaks = new ArrayList<>();
                    for (int i = 0; i < 300; i++) leaks.add("leak-" + i + " -> /elsewhere/" + i);

                    Capture c = capture(() -> {
                        Log.error("clone verification FAILED — %d path(s) reach outside this copy",
                                leaks.size());
                        Log.errorList("    ", leaks);
                    });

                    // Bounded: not 300 lines.
                    assertTrue(c.lines().size() <= Log.ERROR_SAMPLE + 3,
                            "a 300-entry failure prints a bounded head; got "
                                    + c.lines().size() + " lines");
                    // But NOT silent, and not merely a log path: the head is
                    // real, in order, and the total is stated.
                    assertContains(c.text(), "300 path(s) reach outside this copy",
                            "the count is on the console");
                    assertContains(c.text(), "leak-0 -> /elsewhere/0",
                            "the first entry is on the console");
                    assertContains(c.text(), "leak-11 -> /elsewhere/11",
                            "and so is the twelfth, the stated bound");
                    assertFalse(c.text().contains("leak-12 -> "),
                            "the thirteenth is not; that is what bounded means");
                    assertContains(c.text(), "… 288 more",
                            "and the reader is told how many were withheld");
                    assertNotNull(c.log(), "with the file that has them all");
                    assertContains(Files.readString(c.log(), StandardCharsets.UTF_8),
                            "leak-299 -> /elsewhere/299", "including the last one");
                })

                .test("a sync refusal still names the unit and the command that clears it", () -> {
                    SkillStore store = tempStore("refusal");
                    Capture c = capture(() -> {
                        ConsoleProgramRenderer r = new ConsoleProgramRenderer(store, gateway());
                        r.onReceipt(EffectReceipt.partial(
                                new SkillEffect.SyncGit("acme-widgets", null, null, false, false),
                                List.of(new ContextFact.SyncGitRefused(
                                        "acme-widgets", "git@github.com:acme/widgets", false)),
                                "extra local changes"));
                        r.onComplete();
                    });
                    assertContains(c.text(), "acme-widgets has extra local changes",
                            "the refusal names the unit");
                    assertContains(c.text(), "skill-manager sync acme-widgets --merge",
                            "and the command that resolves it, runnable as printed");
                    assertTrue(c.lines().size() <= 6,
                            "in a handful of lines, not fourteen: got " + c.lines().size()
                                    + "\n" + c.text());
                })

                // ---------------------------------------------------- the JSON

                .test("--json is byte-identical to what it was: no rollup, no log footer", () -> {
                    SkillStore store = tempStore("json");
                    Capture c = capture(() -> {
                        ConsoleProgramRenderer r =
                                new ConsoleProgramRenderer(store, gateway(), true);
                        for (EffectReceipt receipt : syncReceipts()) r.onReceipt(receipt);
                        r.onComplete();
                    });
                    assertContains(c.text(), "\"receipts\"", "the receipts document is emitted");
                    assertContains(c.text(), "\"AgentSkillSynced\"",
                            "with every fact in it, including the ones the console dropped");
                    assertContains(c.text(), "\"summary\"", "and the summary");
                    assertFalse(c.text().contains("✓ synced 20 unit(s)"),
                            "and NOT the human rollup");
                    assertFalse(c.text().contains("log:"),
                            "and no log footer on stdout");
                })

                // ----------------------------------------- where the log lives

                .test("the run log is never written inside a Skill Manager home", () -> {
                    Path dir = RunLog.directory();
                    assertNotNull(dir, "the log directory resolves");
                    // The decision, made executable. A home is any directory
                    // this product would treat as one; the log directory must
                    // not be inside one, and specifically must not be inside
                    // the home a command is operating on.
                    assertFalse(Files.exists(dir.resolve("skills"))
                                    || Files.exists(dir.resolve("installed")),
                            "the log directory is not itself a home: " + dir);
                    RunLog.open("locality");
                    Log.detail("something");
                    Path log = RunLog.path();
                    assertNotNull(log, "a log was written");
                    assertTrue(log.startsWith(dir), "and it is under the log directory: " + log);
                    RunLog.close();
                })

                .test("WHY: a log inside a clone would make that clone fail its own verify", () -> {
                    // The alternative design, run. `home verify` walks the whole
                    // destination byte-wise for the source home's path and does
                    // NOT share the clone's `logs/` skip — so a home-clone log,
                    // which necessarily names the source, would turn a correct
                    // clone into one the product refuses. This is the reason the
                    // log lives outside every home, and it is asserted rather
                    // than asserted-in-a-comment.
                    Path root = Files.createTempDirectory("log-locality-");
                    Path source = newHome(root.resolve("source"));
                    Path dest = newHome(root.resolve("dest"));

                    assertTrue(dev.skillmanager.store.HomeCloner.verify(source, dest, false)
                                    .isolated(),
                            "precondition: the destination verifies clean before the log");

                    Path insideHome = dest.resolve("logs").resolve("cli")
                            .resolve("home-clone-20260802-000000-abcd.log");
                    Files.createDirectories(insideHome.getParent());
                    Files.writeString(insideHome,
                            "> ✓ cloned home to " + dest + " from " + source + "\n");

                    assertFalse(dev.skillmanager.store.HomeCloner.verify(source, dest, false)
                                    .isolated(),
                            "and refuses it once a run log naming the source is written into it "
                                    + "— which is why RunLog.directory() is outside every home");
                })

                // ------------------------------------------- the deleted remedy

                .test("no remedy pins SKILL_MANAGER_HOME without the agent-home variables", () -> {
                    // The spelling that was deleted:
                    //   SKILL_MANAGER_HOME=<home> skill-manager sync --force-scripts
                    // pins where the UNITS live and not where the AGENT CONFIGS
                    // live, so run as printed it reports
                    // `ADDED claude (~/.claude.json)` and writes the operator's
                    // global config — skill-manager#145, and the reason
                    // bootstrap-home.sh stopped printing it verbatim. `home
                    // clone` printed it too; `home verify` is the only place it
                    // is printed now, and it is printed runnable.
                    //
                    // The REAL command is run, against the real defect it
                    // reports on: a home holding a generated shim that points
                    // into a directory a clone skips.
                    Path root = Files.createTempDirectory("remedy-");
                    Path home = newHome(root.resolve("home"));
                    Path shim = Files.createDirectories(home.resolve("bin/cli"))
                            .resolve("computeq");
                    Files.writeString(shim,
                            "#!/bin/sh\nexec " + home + "/cache/skill-script-x/venv/bin/computeq "
                                    + "\"$@\"\n");
                    shim.toFile().setExecutable(true);

                    Capture c = capture(() -> new picocli.CommandLine(
                            new dev.skillmanager.commands.HomeCommand.VerifyCmd())
                            .execute("--home", home.toString()));

                    // Preconditions: this run really did reach the branch that
                    // prints the remedy. Without them the assertions below are
                    // about an empty string.
                    assertContains(c.text(), "do not resolve",
                            "precondition: verify reported the unresolved reference");
                    assertContains(c.text(), "sync --force-scripts",
                            "precondition: and printed the remedy for it");

                    assertContains(c.text(), "SKILL_MANAGER_HOME=" + home,
                            "the remedy names the home");
                    // The other axis, which is the whole of the defect.
                    assertContains(c.text(), "CLAUDE_CONFIG_DIR=",
                            "and where claude reads its config");
                    assertContains(c.text(), "CODEX_HOME=", "and codex");
                    assertContains(c.text(), "GEMINI_HOME=", "and gemini");
                    assertFalse(c.text().contains("SKILL_MANAGER_HOME=" + home + " skill-manager")
                                    || c.text().contains("(SKILL_MANAGER_HOME=" + home + ")"),
                            "and never the one-axis spelling that hijacks the operator's "
                                    + "global agent configs (skill-manager#145):\n" + c.text());
                })

                .runAll();
    }

    // ------------------------------------------------------------ the fixture

    /**
     * Twenty units synced across three agents: eighteen merged from a remote,
     * two whose origin is a local path, each projected into claude, codex and
     * gemini, plus the lock write and the marketplace regeneration a real sync
     * ends with. This is the shape that produced 42 lines on six units.
     */
    private static List<EffectReceipt> syncReceipts() {
        List<ContextFact> git = new ArrayList<>();
        for (int i = 0; i < UNITS; i++) {
            String name = String.format("unit-%02d", i);
            git.add(new ContextFact.SyncGitMerged(name, "abc1234def"));
            if (i >= UNITS - 2) {
                git.add(new ContextFact.SyncGitLocalInstall(name, "/local/src/" + name));
            }
        }
        List<ContextFact> agents = new ArrayList<>();
        for (String agent : AGENTS) {
            for (int i = 0; i < UNITS; i++) {
                agents.add(new ContextFact.AgentSkillSynced(
                        agent, String.format("unit-%02d", i)));
            }
        }
        List<ContextFact> tail = List.of(
                new ContextFact.PluginMarketplaceRegenerated("/home/plugin-marketplace", 0),
                new ContextFact.UnitsLockUpdated("/home/units.lock.toml", UNITS));
        return List.of(
                EffectReceipt.ok(new SkillEffect.SyncGit("unit-00", null, null, false, false), git),
                EffectReceipt.ok(new SkillEffect.SyncAgents(List.of(), null), agents),
                EffectReceipt.ok(new SkillEffect.UpdateUnitsLock(null, null), tail));
    }

    private static Capture renderSync(boolean verbose) throws Exception {
        SkillStore store = tempStore(verbose ? "verbose" : "quiet");
        boolean was = Log.isVerbose();
        try {
            Log.setVerbose(verbose);
            return capture(() -> {
                ConsoleProgramRenderer r = new ConsoleProgramRenderer(store, gateway());
                for (EffectReceipt receipt : syncReceipts()) r.onReceipt(receipt);
                r.onComplete();
            });
        } finally {
            Log.setVerbose(was);
        }
    }

    private static GatewayConfig gateway() {
        return GatewayConfig.of(java.net.URI.create("http://127.0.0.1:51717"));
    }

    private static SkillStore tempStore(String label) throws Exception {
        Path root = Files.createTempDirectory("quiet-console-" + label + "-");
        SkillStore store = new SkillStore(root);
        store.init();
        return store;
    }

    private static Path newHome(Path root) throws Exception {
        new SkillStore(root).init();
        return root;
    }

    // ------------------------------------------------------------ the capture

    /**
     * One run's console (stdout + stderr, in order) and its run log.
     *
     * <p>The log directory is redirected under {@code java.io.tmpdir} for the
     * duration, which {@link RunLog#directory()} reads on every open — so a
     * test never touches the operator's real log directory, and never touches
     * {@code ~/.skill-manager}, {@code ~/.claude}, {@code ~/.codex} or
     * {@code ~/.gemini}: nothing here resolves an ambient home at all.
     */
    private record Capture(String text, Path log, String footer) {
        List<String> lines() {
            List<String> out = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                if (!line.isBlank()) out.add(line);
            }
            return out;
        }
    }

    private static Capture capture(Tests.Body body) throws Exception {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        String tmpWas = System.getProperty("java.io.tmpdir");
        Path sandbox = Files.createTempDirectory("quiet-console-tmp-");
        try {
            System.setProperty("java.io.tmpdir", sandbox.toString());
            PrintStream both = new PrintStream(buffer, true, StandardCharsets.UTF_8);
            System.setOut(both);
            System.setErr(both);
            RunLog.open("test");
            try {
                body.run();
            } finally {
                Path log = RunLog.path();
                String footer = "";
                if (RunLog.demoted() > 0 && log != null) {
                    footer = "  log: " + log;
                    System.err.println(footer);
                }
                RunLog.close();
                System.setOut(realOut);
                System.setErr(realErr);
                return new Capture(buffer.toString(StandardCharsets.UTF_8), log, footer);
            }
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
            if (tmpWas != null) System.setProperty("java.io.tmpdir", tmpWas);
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}
