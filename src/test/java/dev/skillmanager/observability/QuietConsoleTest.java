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

                // ----------------------------------------------- provisioning

                .test("30 provisioning items fit in 2 console lines AND state their split", () -> {
                    // Measured on the operator's real 20-unit home:
                    // `sync git-integration-repo -y` printed 4 tool lines and
                    // 26 cli lines, of which 28 reported that NOTHING HAPPENED
                    // — "already on PATH", "ready", "scripts unchanged since
                    // last install". Those scale with what a home DECLARES, not
                    // with what the run did, so they are the same class as the
                    // per-(unit × agent) lines the first pass demoted.
                    Capture c = renderProvisioning(false);

                    // --- the budget half, stated two ways.
                    // Not one no-op survives per item...
                    List<String> noOps = c.lines().stream()
                            .filter(l -> l.contains("already on PATH")
                                    || l.contains("scripts unchanged"))
                            .toList();
                    assertTrue(noOps.isEmpty(),
                            "no per-item 'nothing happened' line reaches the console; got "
                                    + noOps);
                    // ...and the whole provisioning surface is 2 rollups plus
                    // the 3 events we deliberately keep.
                    List<String> provisioning = c.lines().stream()
                            .filter(l -> l.contains("cli:") || l.contains("tool"))
                            .toList();
                    assertTrue(provisioning.size() <= 5,
                            "30 provisioning items reduce to 2 rollups + 3 events; got "
                                    + provisioning.size() + ":\n"
                                    + String.join("\n", provisioning));

                    // --- the anti-vacuity half, in the SAME case. A rollup
                    //     that silently dropped its counts satisfies the budget
                    //     above; these are what make it evidence. Both
                    //     categories, always: "2 installed" alone cannot
                    //     distinguish a 26-dep home from a 2-dep one.
                    assertContains(c.text(), "cli: 25 already present, 1 installed, 1 failed",
                            "the cli rollup states every category — a reader has to be able "
                                    + "to tell a run that installed nothing from one that "
                                    + "installed everything, and to see the failure");
                    assertContains(c.text(), "tools: 3 already present, 1 installed",
                            "and so does the tools rollup");

                    // --- the actionable cases stay per-item on the console
                    assertContains(c.text(), "cli: dep-24 [tar] installed for unit-a",
                            "an install is an EVENT and stays on the console");
                    assertContains(c.text(), "cli: dep-99 install failed",
                            "and so does a failure");

                    // --- and the no-ops are written down, not dropped
                    assertNotNull(c.log(), "the run log path is named");
                    String body = Files.readString(c.log(), StandardCharsets.UTF_8);
                    assertContains(body, "cli: dep-00 already on PATH",
                            "the demoted no-op is in the log");
                    assertContains(body, "tool: brew on PATH",
                            "and so is the tool presence check");
                })

                .test("--verbose restores every provisioning line", () -> {
                    Capture loud = renderProvisioning(true);
                    assertContains(loud.text(), "cli: dep-00 already on PATH",
                            "the demoted no-op is back on the console");
                    assertContains(loud.text(), "tool: brew on PATH",
                            "and the tool presence check with it");
                    assertTrue(loud.lines().size() > 25,
                            "verbose is the per-item output, not a slightly longer summary; got "
                                    + loud.lines().size());
                })

                .test("the backend itself demotes the no-op — console silent, log has it", () -> {
                    // THE PRINT SITE, not a stand-in for it. The rollup cases
                    // above build their tally from facts, so a mutation that
                    // put `Log.ok` back inside the backends left them green:
                    // the fixture was emitting the lines, not the backend. This
                    // case drives TarBackend directly.
                    //
                    // TarBackend is the one backend that reaches both branches
                    // with no network: a dep whose binary is already in bin/cli
                    // is ALREADY_PRESENT, and one with no install target for
                    // this platform is SKIPPED.
                    SkillStore store = tempStore("classify");
                    java.nio.file.Files.createDirectories(store.cliBinDir());
                    java.nio.file.Path alreadyThere = store.cliBinDir().resolve("already-there");
                    java.nio.file.Files.writeString(alreadyThere, "#!/bin/sh\n");
                    // Executable, which this fixture omitted while the presence
                    // check was Files.exists. It is Files.isExecutable now
                    // (CliPresence), because a file in bin/cli that cannot be
                    // run is not a provisioned tool — it is the same
                    // "present but broken" state a dangling shim is, and the
                    // backend must reinstall over it rather than report it as
                    // provisioning. "A binary already in bin/cli", which is
                    // what this case says it is asserting, is an executable one.
                    alreadyThere.toFile().setExecutable(true, false);
                    dev.skillmanager.cli.installer.TarBackend backend =
                            new dev.skillmanager.cli.installer.TarBackend();

                    dev.skillmanager.cli.installer.InstallOutcome[] outcome =
                            new dev.skillmanager.cli.installer.InstallOutcome[3];
                    Capture c = capture(() -> {
                        outcome[0] = backend.install(dep("already-there"), store, "unit-a");
                        outcome[1] = backend.install(dep("no-target-here"), store, "unit-a");
                        // The OTHER no-op branch, and the one the real home hits
                        // 18 times: a dep declaring an `on_path` binary that is
                        // already there. `sh` is on PATH on every host this runs on.
                        outcome[2] = backend.install(onPathDep("sh"), store, "unit-a");
                    });

                    // The classification.
                    assertEquals(dev.skillmanager.cli.installer.InstallOutcome.ALREADY_PRESENT,
                            outcome[0], "a binary already in bin/cli is a STATE, not an event");
                    assertEquals(dev.skillmanager.cli.installer.InstallOutcome.SKIPPED,
                            outcome[1], "and a dep with no install target is neither");
                    assertEquals(dev.skillmanager.cli.installer.InstallOutcome.ALREADY_PRESENT,
                            outcome[2], "and a declared on_path binary that is present is a state");

                    // The routing. BOTH no-op branches are off the console —
                    // these are the two lines that made up 18 of the 26 on the
                    // real home, and each is asserted separately because a
                    // mutation that reverts one leaves the other.
                    //
                    // The two lines are worded differently since CliPresence
                    // split the one question the backends used to ask ("is it
                    // anywhere on PATH") into the two it actually meant: "this
                    // home already provisioned it" and "the system provides it,
                    // do not install over it". Both are still states, both are
                    // still demoted, and they are still asserted separately.
                    assertFalse(c.text().contains("already provisioned in this home"),
                            "the already-provisioned line is off the console; got:\n" + c.text());
                    assertFalse(c.text().contains("already provided by the system"),
                            "and so is the system-provides line; got:\n" + c.text());
                    // ...but the refusal, which the caller may have to act on, does print.
                    assertContains(c.text(), "no install target for no-target-here",
                            "while the refusal it cannot fix by itself still prints");
                    // ...and both no-ops are written down rather than dropped.
                    assertNotNull(c.log(), "a run log was written");
                    String body = Files.readString(c.log(), StandardCharsets.UTF_8);
                    assertContains(body, "cli: already-there already provisioned in this home",
                            "the demoted already-provisioned line is in the run log");
                    assertContains(body, "cli: sh already provided by the system",
                            "and the demoted system-provides line");
                })

                .test("ToolInstallRecorder demotes a presence check that passed", () -> {
                    // The tool half of the same defect, at ITS print site: four
                    // `✓ tool: … ready / on PATH` lines on the real home, one
                    // per declared tool, on every run. Driven through the real
                    // recorder with a real plan.
                    SkillStore store = tempStore("tools");
                    dev.skillmanager.plan.InstallPlan plan = new dev.skillmanager.plan.InstallPlan();
                    // `sh` is on PATH on every host this runs on — the passing
                    // presence check, which is the line being demoted.
                    plan.add(new dev.skillmanager.plan.PlanAction.EnsureTool(
                            external("sh", "install a shell"), false));
                    // And one that is not there: the event that must survive.
                    plan.add(new dev.skillmanager.plan.PlanAction.EnsureTool(
                            external("definitely-not-a-real-tool", "install the ghost"), true));

                    dev.skillmanager.cli.installer.ProvisionTally[] tally =
                            new dev.skillmanager.cli.installer.ProvisionTally[1];
                    Capture c = capture(() ->
                            tally[0] = dev.skillmanager.tools.ToolInstallRecorder.run(plan, store));

                    // The passing check is counted and demoted.
                    assertEquals(1, tally[0].alreadyPresent(),
                            "the tool that was already there is counted");
                    assertFalse(c.text().contains("tool: sh on PATH"),
                            "and its line is off the console; got:\n" + c.text());
                    assertNotNull(c.log(), "a run log was written");
                    assertContains(Files.readString(c.log(), StandardCharsets.UTF_8),
                            "tool: sh on PATH", "and in the run log");

                    // The missing one is an event the caller must act on, and
                    // it still prints, with the hint the dependency declared.
                    assertEquals(1, tally[0].missing(), "the missing tool is counted as missing");
                    assertContains(c.text(), "definitely-not-a-real-tool missing on PATH",
                            "and named on the console");
                    assertContains(c.text(), "install the ghost",
                            "with its remedy");
                })

                .test("a sync refusal still names the unit and the command that clears it", () -> {
                    SkillStore store = tempStore("refusal");
                    Capture c = capture(() -> {
                        ConsoleProgramRenderer r = new ConsoleProgramRenderer(store, gateway());
                        r.onReceipt(EffectReceipt.partial(
                                new SkillEffect.SyncGit("acme-widgets", null, null, false, false),
                                List.of(new ContextFact.SyncGitRefused(
                                        "acme-widgets", "git@github.com:acme/widgets", false, false)),
                                "extra local changes"));
                        r.onComplete();
                    });
                    assertContains(c.text(), "acme-widgets has extra local changes",
                            "the refusal names the unit");
                    assertContains(c.text(), "skill-manager sync acme-widgets --merge",
                            "and the command that resolves it, runnable as printed");
                    assertContains(c.text(), "git@github.com:acme/widgets",
                            "and the source that command would merge — the one part of the "
                                    + "recipe a reader cannot derive from the unit's name");
                    assertTrue(c.lines().size() <= 6,
                            "in a handful of lines, not fourteen: got " + c.lines().size()
                                    + "\n" + c.text());
                })

                .test("a --from refusal offers the SAME sync it refused, not a different one", () -> {
                    // The regression this pins. `sync <name> --from <dir>` was
                    // refused, and the printed remedy read
                    // `skill-manager sync <name> --merge` — no `--from`. Run as
                    // printed on a unit whose recorded origin is github, that
                    // merges github, not the directory the caller named. It is
                    // the shape skill-dev documents (`sync <unit> --from
                    // skill-dev/<unit> --merge`), so the remedy for the exact
                    // flow the product ships was a different operation.
                    SkillStore store = tempStore("refusal-from");
                    String from = "/tmp/skill-dev/acme-widgets";
                    Capture c = capture(() -> {
                        ConsoleProgramRenderer r = new ConsoleProgramRenderer(store, gateway());
                        r.onReceipt(EffectReceipt.partial(
                                new SkillEffect.SyncFromLocalDir(
                                        "acme-widgets", Path.of(from), false, false),
                                List.of(new ContextFact.SyncGitRefused(
                                        "acme-widgets", from, false, true)),
                                "extra local changes"));
                        r.onComplete();
                    });
                    assertContains(c.text(), "skill-manager sync acme-widgets --from " + from
                                    + " --merge",
                            "the re-run keeps --from, so it merges what the refused command "
                                    + "was going to merge");
                    assertContains(c.text(), "merges " + from,
                            "and states which tree that is");
                    assertTrue(c.lines().size() <= 6,
                            "still a handful of lines: got " + c.lines().size()
                                    + "\n" + c.text());
                })

                .test("COMPANION: an implicit sync must NOT grow a --from it was never given", () -> {
                    // Without this, "--from is printed" could be satisfied by
                    // printing --from unconditionally, which would make the
                    // implicit-origin remedy unrunnable in the other direction.
                    SkillStore store = tempStore("refusal-implicit");
                    Capture c = capture(() -> {
                        ConsoleProgramRenderer r = new ConsoleProgramRenderer(store, gateway());
                        r.onReceipt(EffectReceipt.partial(
                                new SkillEffect.SyncGit("acme-widgets", null, null, true, false),
                                List.of(new ContextFact.SyncGitRefused(
                                        "acme-widgets", "https://github.com/acme/widgets.git",
                                        true, false)),
                                "extra local changes"));
                        r.onComplete();
                    });
                    assertContains(c.text(), "skill-manager sync acme-widgets --git-latest --merge",
                            "the implicit form keeps --git-latest and stays contiguous");
                    assertFalse(c.text().contains("--from"),
                            "and gains no --from it was never given:\n" + c.text());
                    assertContains(c.text(), "https://github.com/acme/widgets.git",
                            "while still naming the origin it would merge");
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
                    // ARTI-06 replaced the VERB here (`sync --force-scripts` →
                    // `build --stale`); this case is about the ENV PREFIX, which
                    // is unchanged and is the whole of #145.
                    assertContains(c.text(), "build --stale",
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

    /**
     * The provisioning shape measured on the operator's real 20-unit home,
     * with the two actionable cases planted so the "already present" counts
     * cannot be the only thing under test: 25 CLI deps of which 24 were
     * already there, one that installed and one that failed, plus 4 tools of
     * which 3 were already there.
     */
    private static List<EffectReceipt> provisioningReceipts() {
        dev.skillmanager.cli.installer.ProvisionTally cli =
                dev.skillmanager.cli.installer.ProvisionTally.EMPTY;
        for (int i = 0; i < 24; i++) {
            // The line the console no longer carries. Emitted here the way the
            // backends emit it, so the log assertion is about real output.
            Log.detail("✓ cli: dep-%02d already on PATH", i);
            cli = cli.plus(dev.skillmanager.cli.installer.InstallOutcome.ALREADY_PRESENT);
        }
        Log.detail("✓ cli: skill-script computeq — scripts unchanged since last install (skipping)");

        dev.skillmanager.cli.installer.ProvisionTally tools =
                dev.skillmanager.cli.installer.ProvisionTally.EMPTY;
        for (String t : List.of("brew", "npm", "uv")) {
            Log.detail("✓ tool: %s on PATH  → /opt/homebrew/bin/%s", t, t);
            tools = tools.plus(dev.skillmanager.cli.installer.InstallOutcome.ALREADY_PRESENT);
        }
        Log.ok("tool: npx installed  → /home/pm/node/bin/npx");
        tools = tools.plus(dev.skillmanager.cli.installer.InstallOutcome.INSTALLED);
        // The 25th cli dep is the skipping skill-script counted above.
        cli = cli.plus(dev.skillmanager.cli.installer.InstallOutcome.ALREADY_PRESENT);

        return List.of(
                EffectReceipt.ok(new SkillEffect.InstallTools(List.of()),
                        List.of(new ContextFact.ToolsInstalledFor(20, tools))),
                EffectReceipt.ok(new SkillEffect.InstallCli(List.of()),
                        List.of(new ContextFact.CliInstalledFor(20, cli))),
                // The two events, through the decomposed path's own facts, so
                // the rollup is proven to merge both sources.
                EffectReceipt.ok(new SkillEffect.RunCliInstall("unit-a", null, false),
                        List.of(new ContextFact.CliInstalled("unit-a", "dep-24", "tar"))),
                EffectReceipt.partial(new SkillEffect.RunCliInstall("unit-b", null, false),
                        List.of(new ContextFact.CliInstallFailed(
                                "unit-b", "dep-99", "exit 2")),
                        "one cli dep failed"));
    }

    private static Capture renderProvisioning(boolean verbose) throws Exception {
        SkillStore store = tempStore(verbose ? "prov-verbose" : "prov-quiet");
        boolean was = Log.isVerbose();
        try {
            Log.setVerbose(verbose);
            return capture(() -> {
                ConsoleProgramRenderer r = new ConsoleProgramRenderer(store, gateway());
                for (EffectReceipt receipt : provisioningReceipts()) r.onReceipt(receipt);
                r.onComplete();
            });
        } finally {
            Log.setVerbose(was);
        }
    }

    /** A tar-backed dep whose binary is {@code name} and which has no targets. */
    private static dev.skillmanager.model.CliDependency dep(String name) {
        return new dev.skillmanager.model.CliDependency(
                name, "tar:" + name, null, null, null, false, java.util.Map.of());
    }

    /** The same, declaring {@code onPath} as its already-satisfied binary. */
    private static dev.skillmanager.model.CliDependency onPathDep(String onPath) {
        return new dev.skillmanager.model.CliDependency(
                onPath, "tar:" + onPath, null, null, onPath, false, java.util.Map.of());
    }

    /** An external tool dependency: realized by a presence check, never installed. */
    private static dev.skillmanager.tools.ToolDependency external(String id, String hint) {
        return new dev.skillmanager.tools.ToolDependency.External(
                id, id, dev.skillmanager.pm.PackageManager.DOCKER, hint, java.util.Set.of());
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
