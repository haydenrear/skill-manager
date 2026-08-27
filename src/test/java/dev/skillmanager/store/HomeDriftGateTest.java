package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.artifacts.ArtifactLedger;
import dev.skillmanager.commands.ExecCommand;
import dev.skillmanager.commands.HomeCommand;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The drift digest and the change-awareness gate — the fitness functions for
 * "a pull that changed a skill the agent is relying on must not be silent".
 *
 * <h2>Why the oracle here is hand-rolled</h2>
 *
 * <p>This epic has twice been caught by checks that verify a report against
 * itself. The spec work ran the pre-epic overwrite policy with only
 * {@code EveryPassReportsExactlyTheHeldBackUnits} and got "No error has been
 * found": after an overwrite the unit is no longer modified, so both sides of the
 * equivalence go false and the equivalence holds. The graph work reproduced it —
 * disabling the hold-back check failed
 * {@code prune_did_not_destroy_the_agent_edit} while
 * {@code child_home_holds_only_claimed_or_held_back_units} passed
 * <em>vacuously</em>.
 *
 * <p>So the central test below does not ask whether {@link DriftReport} agrees
 * with {@link HomeDigest}. It reads every file in the home twice with plain
 * {@code Files.readAllBytes} + SHA-256, computes the set of paths whose bytes
 * actually differ, and requires the report to name exactly that set. The oracle
 * shares no code with the thing it is checking, so a digest that stopped looking
 * at content could not satisfy both.
 *
 * <p>And the gate is asserted by whether a child process ran, not by an exit
 * code: a gate that refuses after spawning has already failed to gate.
 */
public final class HomeDriftGateTest {

    private static final String SYSTEM_BINS =
            File.pathSeparator + "/usr/bin" + File.pathSeparator + "/bin";

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("HomeDriftGateTest");

        // ------------------------------------- what is not the home's content

        suite.test("a re-derivable tree is not drift, and a tracked file still is", () -> {
            Home home = Home.create("drift-rederivable-");
            home.installSkill("delta", Map.of(
                    "SKILL.md", skillMd("delta"),
                    "pyproject.toml", "[project]\nname = \"delta\"\n"));

            HomeDigest before = HomeDigest.compute(home.store);

            // Exactly what `pip install -e .` and a venv build leave behind.
            // Every other reader in the system -- HomeCloner, ChildHomeMaterializer,
            // ArtifactPrune -- already agrees these are not unit content;
            // HomeDigest was the one that did not ask, so a venv rebuild read as
            // the home changing underneath the operator.
            write(home.unitFile("delta", ".venv/bin/python"), "#!/bin/sh\n");
            write(home.unitFile("delta", "__pycache__/mod.cpython-311.pyc"), "\0\0bytecode\n");
            write(home.unitFile("delta", "build/lib/delta/__init__.py"), "\n");

            DriftReport quiet = DriftReport.between(before, HomeDigest.compute(home.store));
            assertTrue(quiet.units().isEmpty(),
                    "a venv, a __pycache__ and a build/ tree are re-derivable, so none of them "
                            + "is a change the operator has to acknowledge — got " + quiet.units());

            // The same walk must still see real content, or the exclusion above
            // has simply blinded the digest.
            write(home.unitFile("delta", "references/real.md"), "a real edit\n");
            DriftReport loud = DriftReport.between(before, HomeDigest.compute(home.store));
            assertTrue(!loud.units().isEmpty(),
                    "an ordinary file the unit does not exclude is still drift");
        });

        suite.test("a path the unit's own .gitignore excludes is not drift", () -> {
            Home home = Home.create("drift-gitignore-");
            home.installSkill("epsilon", Map.of(
                    "SKILL.md", skillMd("epsilon"),
                    ".gitignore", "**/*.egg-info\nscripts/logs/\n"));
            // NO READABLE INDEX MEANS NOTHING IS IGNORED, so the declaration
            // only counts once the unit is a repository -- see GitIgnoreRules.
            initRepoAndCommit(home.store.skillDir("epsilon"));

            HomeDigest before = HomeDigest.compute(home.store);

            // The two shapes measured in the operator's root home on 2026-08-27,
            // which together were 22 of the 33 files gating that home: setuptools
            // metadata, and a unit-local run-log directory.
            write(home.unitFile("epsilon", "src/epsilon.egg-info/PKG-INFO"), "Name: epsilon\n");
            write(home.unitFile("epsilon", "scripts/logs/run-01.jsonl"), "{}\n");

            DriftReport quiet = DriftReport.between(before, HomeDigest.compute(home.store));
            assertTrue(quiet.units().isEmpty(),
                    "the unit's author declared both of these not to be content, so neither is "
                            + "something to acknowledge — got " + quiet.units());
        });

        suite.test("a digest from an older schema is not diffed — it re-baselines", () -> {
            Home home = Home.create("drift-schema-");
            home.installSkill("zeta", Map.of("SKILL.md", skillMd("zeta")));
            HomeDigest.compute(home.store).write(home.store);
            assertTrue(HomeDigest.read(home.store).isPresent(), "a current record reads back");

            // Rewrite it as the previous schema, byte for byte otherwise. This
            // is what every existing home holds at the moment of the upgrade,
            // and its entries were computed over a set of files that included
            // .venv, build/ and every gitignored path.
            Path f = HomeDigest.file(home.store);
            Files.writeString(f, Files.readString(f)
                    .replace("\"schemaVersion\" : 2", "\"schemaVersion\" : 1"));

            assertTrue(HomeDigest.read(home.store).isEmpty(),
                    "an older record is not evidence about these bytes, so it reads as absent "
                            + "and the caller records a fresh baseline — rather than reporting "
                            + "every newly-excluded path as a deletion");
        });

        // -------------------------------------------- the report vs the bytes

        suite.test("the drift report names exactly the files whose bytes changed", () -> {
            Home home = Home.create("drift-oracle-");
            home.installSkill("alpha", Map.of(
                    "SKILL.md", skillMd("alpha"),
                    "references/one.md", "one\n",
                    "references/two.md", "two\n"));
            home.installSkill("beta", Map.of(
                    "SKILL.md", skillMd("beta"),
                    "references/keep.md", "keep\n"));

            Map<String, String> bytesBefore = readEveryUnitFile(home);
            HomeDigest before = HomeDigest.compute(home.store);

            // Three different kinds of change, in two units, plus one unit left
            // completely alone.
            Files.writeString(home.unitFile("alpha", "references/one.md"), "one, revised\n");
            Files.delete(home.unitFile("alpha", "references/two.md"));
            Files.writeString(home.unitFile("alpha", "references/three.md"), "three\n");
            home.installSkill("gamma", Map.of("SKILL.md", skillMd("gamma")));

            Map<String, String> bytesAfter = readEveryUnitFile(home);
            DriftReport report = DriftReport.between(before, HomeDigest.compute(home.store));

            // The oracle: paths whose bytes genuinely differ, computed here with
            // nothing from the production digest.
            java.util.Set<String> reallyChanged = new TreeSet<>();
            for (String key : union(bytesBefore.keySet(), bytesAfter.keySet())) {
                if (!java.util.Objects.equals(bytesBefore.get(key), bytesAfter.get(key))) {
                    reallyChanged.add(key);
                }
            }
            assertEquals(new TreeSet<>(List.of(
                            "alpha/references/one.md",
                            "alpha/references/three.md",
                            "alpha/references/two.md",
                            "gamma/SKILL.md")),
                    reallyChanged,
                    "the oracle itself sees the expected byte-level change set");

            assertEquals(reallyChanged, reportedPaths(report),
                    "the drift report names exactly the paths whose bytes changed — "
                            + "no more, and crucially no fewer");
            assertFalse(report.unitNames().contains("beta"),
                    "and the untouched unit is not named at all");
        });

        suite.test("the rollup is one line per unit, and the paths are still reachable", () -> {
            // Issue #212, measured on the operator's root home: one pending
            // record, 7 units, 87,054 bytes. The old rendering emitted one line
            // per changed FILE -- 889 lines, ~87,200 characters, roughly 21,800
            // tokens -- and re-emitted the whole block on every project sync,
            // every exec launch gate and every home drift. One unit,
            // spec-double-compiler, was 776 of the 889 on its own.
            //
            // The fixture below is that shape in miniature: one unit with many
            // changed paths, one with few. The assertion is on the RATIO and the
            // per-unit bound, not on an absolute character count, because the
            // fixture is not the operator's home and a number copied from one to
            // the other would be a measurement of nothing.
            List<String> many = new ArrayList<>();
            for (int i = 0; i < 200; i++) many.add("src/generated/file-" + i + ".txt");

            DriftReport report = new DriftReport("aaa", "bbb", List.of(
                    new DriftReport.UnitDrift("noisy-unit", "SKILL",
                            DriftReport.Change.MODIFIED, "0123456789abcdef",
                            many, List.of(), List.of("SKILL.md")),
                    new DriftReport.UnitDrift("quiet-unit", "SKILL",
                            DriftReport.Change.MODIFIED, "fedcba9876543210",
                            List.of(), List.of(), List.of("notes.md"))));

            List<String> rolled = report.render();
            List<String> detailed = report.renderDetailed();

            // CLAUSE 1: one line per unit, plus one total.
            assertEquals(3, rolled.size(),
                    "the rollup is one line per changed unit plus a total line");
            assertTrue(rolled.get(0).contains("skill:noisy-unit"), "names the unit");
            assertTrue(rolled.get(0).contains("01234567"),
                    "carries the unit's short digest -- the thing asked for in place of the paths");
            assertTrue(rolled.get(0).contains("201 files"),
                    "states how many paths moved without listing them");
            assertTrue(rolled.get(2).contains("2 units, 202 files changed"), "totals both units");

            // No path escapes into the rollup. This is the actual regression:
            // one stray `for (String rel : ...)` anywhere and the firehose is
            // back, and the line count above would still pass if the paths were
            // appended to the unit lines rather than emitted as their own.
            for (String line : rolled) {
                assertFalse(line.contains("src/generated/file-"),
                        "no changed path appears in the rollup: " + line);
            }

            int rolledChars = String.join("\n", rolled).length();
            int detailedChars = String.join("\n", detailed).length();
            assertTrue(rolledChars * 20 < detailedChars,
                    "the rollup is at least 20x smaller than the per-file list ("
                            + rolledChars + " vs " + detailedChars + " chars)");

            // CLAUSE 2: collapsing is a default, not a deletion. An operator who
            // wants the paths gets exactly the paths they got before.
            // 2 unit headers + 202 paths.
            assertEquals(204, detailed.size(),
                    "renderDetailed still emits a header per unit and a line per path");
            assertTrue(detailed.contains("    + src/generated/file-0.txt"),
                    "an added path is still listed verbatim");
            assertTrue(detailed.contains("    ~ SKILL.md"),
                    "a modified path is still listed verbatim");
            assertTrue(detailed.get(0).startsWith("modified  skill:noisy-unit"),
                    "the detailed header is unchanged");
        });

        suite.test("the committed baseline fixture renders inside GOAL-sync-quiet's bound", () -> {
            // THE GOAL'S OWN MEASUREMENT, run against the real record rather
            // than a fixture that resembles it. results/epic-home-integrity-sync/
            // baseline/root-home-drift.json is a verbatim copy of the operator's
            // ~/.skill-manager/home.drift.json as it stood at the epic base
            // commit, before any ticket landed: 7 units, 87,054 bytes, and 889
            // per-file lines out of the old rendering.
            //
            // Asserted here so the number cannot quietly regress between now and
            // the terminal evaluation ticket. Skipped rather than failed when the
            // file is absent, because a unit suite that depends on a results
            // directory being present is a suite that breaks in a worktree.
            Path fixture = Path.of("results/epic-home-integrity-sync/baseline/root-home-drift.json");
            if (!Files.isRegularFile(fixture)) return;

            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(Files.readString(fixture));
            DriftReport report = new com.fasterxml.jackson.databind.ObjectMapper()
                    .treeToValue(root.get("report"), DriftReport.class);

            assertEquals(7, report.units().size(), "the baseline record holds 7 units");

            List<String> rolled = report.render();
            List<String> detailed = report.renderDetailed();
            int rolledChars = String.join("\n", rolled).length();
            int detailedChars = String.join("\n", detailed).length();

            // TARGET CLAUSE 1: at most one line per changed unit plus one total
            // -- at most 10 lines over this fixture -- and at least a 99%
            // character reduction.
            assertTrue(rolled.size() <= 10,
                    "the 7-unit baseline renders in at most 10 lines, got " + rolled.size());
            assertTrue(rolledChars * 100 < detailedChars,
                    "at least a 99% character reduction: " + rolledChars + " vs "
                            + detailedChars + " chars");

            // And the paths are still all there, on demand.
            assertTrue(detailed.size() > 800,
                    "the per-file rendering still lists every path, got " + detailed.size());
        });

        suite.test("a record predating the digest field is not reported as a deleted unit", () -> {
            // The committed baseline fixture is exactly this shape: written by
            // the old code, so every unit's digest is null. Reading that as
            // "gone" would tell an operator seven units had been deleted.
            DriftReport report = new DriftReport("aaa", "bbb", List.of(
                    new DriftReport.UnitDrift("legacy-record-unit", "SKILL",
                            DriftReport.Change.MODIFIED, null,
                            List.of(), List.of(), List.of("SKILL.md"))));
            String line = report.render().get(0);
            assertTrue(line.contains("no-digest"),
                    "an absent digest on a MODIFIED unit says so plainly: " + line);
            assertFalse(line.contains("gone"),
                    "and is not confused with a removed unit: " + line);
        });

        suite.test("a REMOVED unit has no after-digest, and says so rather than printing null", () -> {
            DriftReport report = new DriftReport("aaa", "bbb", List.of(
                    new DriftReport.UnitDrift("gone-unit", "SKILL",
                            DriftReport.Change.REMOVED, null,
                            List.of(), List.of("SKILL.md"), List.of())));
            List<String> rolled = report.render();
            assertTrue(rolled.get(0).contains("gone"),
                    "a removed unit reads as gone, not as a null digest: " + rolled.get(0));
            assertFalse(rolled.get(0).contains("null"), "never the word null");
        });

        suite.test("a home nobody touched produces no drift and no gate", () -> {
            Home home = Home.create("drift-quiet-");
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));

            DriftGate.recordSince(home.store, HomeDigest.compute(home.store), "test").orElse(null);

            assertTrue(DriftGate.pending(home.store).isEmpty(),
                    "nothing to acknowledge, so nothing is gated");
            assertFalse(Files.exists(DriftGate.file(home.store)),
                    "and no gate file was written at all");
        });

        suite.test("a first digest is not treated as everything having changed", () -> {
            Home home = Home.create("drift-first-");
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));

            DriftGate.recordSince(home.store, null, "first record");

            assertTrue(DriftGate.pending(home.store).isEmpty(),
                    "a home with no baseline has not drifted — it has only just been measured");
            assertTrue(HomeDigest.read(home.store).isPresent(), "but the baseline is now recorded");
        });

        suite.test("git bookkeeping inside a unit is not reported as drift", () -> {
            // A .git directory rewrites itself on read-only commands. Reporting it
            // would fire the gate on every sync, and a gate that always fires is a
            // gate nobody reads.
            Home home = Home.create("drift-git-");
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));
            Path git = home.store.skillDir("alpha").resolve(".git");
            Fs.ensureDir(git);
            Files.writeString(git.resolve("index"), "before\n");
            HomeDigest before = HomeDigest.compute(home.store);

            Files.writeString(git.resolve("index"), "after, longer\n");
            Files.writeString(git.resolve("ORIG_HEAD"), "deadbeef\n");

            assertTrue(DriftReport.between(before, HomeDigest.compute(home.store)).isEmpty(),
                    "git's own churn is excluded");
        });

        // --------------------------------------------------------- the gate

        suite.test("a launch is refused while the change is unread, and nothing runs", () -> {
            Home home = Home.create("drift-gate-refuse-");
            home.writeDescriptor();
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));
            HomeDigest before = HomeDigest.compute(home.store);
            Files.writeString(home.unitFile("alpha", "SKILL.md"),
                    skillMd("alpha") + "the trunk changed this\n");
            DriftGate.recordSince(home.store, before, "project sync");

            Path sentinel = home.root.resolve("child-ran");
            Path touch = home.writeTouch(sentinel);
            Result result = captureBoth(() -> new CommandLine(
                    new ExecCommand(home.store, "/usr/bin" + SYSTEM_BINS))
                    .execute("--no-reconcile", touch.toString()));

            assertFalse(Files.exists(sentinel),
                    "the child never started — the file it would create is absent");
            assertEquals(DriftGate.EXIT_CODE, result.rc, "refused with the drift code");
            assertContains(result.err, "has not been read", "the refusal says why");
            assertContains(result.err, "skill:alpha", "and names the unit that moved");
            // NOT the file. #212: the refusal used to print one line per changed
            // path, and re-print the whole block on every launch attempt. A
            // launch gate is one decision -- which units moved, are they ones I
            // was following -- and the paths were crowding out the answer.
            // `home drift --detail` still has them.
            assertFalse(result.err.contains("    ~ SKILL.md"),
                    "the refusal does not list changed paths any more");
            assertContains(result.err, "1 file", "it says how many paths moved");
        });

        suite.test("the second surfacing of one gate is a line, and a new change re-opens it", () -> {
            // HIS-3 / #213. The loop: exec refuses, the operator reads, exec
            // refuses again, and the whole report is re-printed every time. The
            // count is PERSISTED rather than decided at the call site because
            // that sequence is three separate JVMs -- nothing in memory can
            // answer "has anybody seen this yet".
            Home home = Home.create("drift-surfacing-");
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha"),
                    "references/one.md", "one\n"));
            HomeDigest before = HomeDigest.compute(home.store);
            Files.writeString(home.unitFile("alpha", "references/one.md"), "one, revised\n");
            DriftGate.recordSince(home.store, before, "project sync");

            DriftGate fresh = DriftGate.pending(home.store).orElseThrow();
            assertTrue(fresh.firstSurfacing(), "a gate nobody has seen is a first surfacing");

            Result first = captureBoth(() -> new CommandLine(new HomeCommand.DriftCmd(home.store))
                    .execute());
            assertEquals(DriftGate.EXIT_CODE, first.rc, "still gates");
            assertContains(first.err + first.out, "skill:alpha",
                    "the first surfacing carries the report");

            Result second = captureBoth(() -> new CommandLine(new HomeCommand.DriftCmd(home.store))
                    .execute());
            assertEquals(DriftGate.EXIT_CODE, second.rc,
                    "a collapsed surfacing still gates -- this ticket changes how often the "
                            + "report is PRINTED, never when the gate is RETIRED");
            String secondOut = second.err + second.out;
            assertContains(secondOut, "still unread", "the second surfacing is the reminder");
            assertContains(secondOut, "--ack", "which still names the remedy");
            assertFalse(secondOut.contains("skill:alpha"),
                    "and does not re-print the report: " + secondOut);

            // FAILURE MODE 1, the one that would break the safety: a gate that
            // stays collapsed across a change the agent has never seen.
            HomeDigest mid = HomeDigest.compute(home.store);
            Files.writeString(home.unitFile("alpha", "SKILL.md"), skillMd("alpha") + "\nmore\n");
            DriftGate.recordSince(home.store, mid, "project sync");

            assertTrue(DriftGate.pending(home.store).orElseThrow().firstSurfacing(),
                    "a genuinely new change resets the count");
            Result reopened = captureBoth(() ->
                    new CommandLine(new HomeCommand.DriftCmd(home.store)).execute());
            assertContains(reopened.err + reopened.out, "skill:alpha",
                    "and re-opens the full report");
        });

        suite.test("--detail always answers in full, however often the gate has been shown", () -> {
            Home home = Home.create("drift-detail-always-");
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha"),
                    "references/one.md", "one\n"));
            HomeDigest before = HomeDigest.compute(home.store);
            Files.writeString(home.unitFile("alpha", "references/one.md"), "one, revised\n");
            DriftGate.recordSince(home.store, before, "project sync");

            captureBoth(() -> new CommandLine(new HomeCommand.DriftCmd(home.store)).execute());
            captureBoth(() -> new CommandLine(new HomeCommand.DriftCmd(home.store)).execute());

            // Collapsed by now. `--detail` is an explicit ask; the collapse is
            // about what an agent gets when it did NOT ask.
            Result detailed = captureBoth(() ->
                    new CommandLine(new HomeCommand.DriftCmd(home.store)).execute("--detail"));
            assertContains(detailed.err + detailed.out, "references/one.md",
                    "--detail still names every changed path");
        });

        suite.test("an ack does not leave a count behind for the next gate to inherit", () -> {
            // If surfacedCount survived an ack into a later re-pend, the FIRST
            // sight of a change the agent had never seen would be a one-line
            // reminder. That is failure mode 1 arriving by a different route.
            Home home = Home.create("drift-ack-resets-");
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));
            HomeDigest before = HomeDigest.compute(home.store);
            Files.writeString(home.unitFile("alpha", "SKILL.md"), skillMd("alpha") + "\none\n");
            DriftGate.recordSince(home.store, before, "project sync");

            captureBoth(() -> new CommandLine(new HomeCommand.DriftCmd(home.store)).execute());
            captureBoth(() -> new CommandLine(new HomeCommand.DriftCmd(home.store)).execute());
            DriftGate.acknowledge(home.store);
            assertTrue(DriftGate.pending(home.store).isEmpty(), "the ack retired the gate");

            HomeDigest mid = HomeDigest.compute(home.store);
            Files.writeString(home.unitFile("alpha", "SKILL.md"), skillMd("alpha") + "\ntwo\n");
            DriftGate.recordSince(home.store, mid, "project sync");

            assertTrue(DriftGate.pending(home.store).orElseThrow().firstSurfacing(),
                    "the gate after an ack starts unseen");
        });

        suite.test("a record written before surfacedCount existed degrades to full-once", () -> {
            // The compat rule, asserted rather than assumed: a v1 record has no
            // field, Jackson yields 0, 0 means "not yet surfaced", and the home
            // behaves exactly as it did before the field existed.
            Home home = Home.create("drift-v1-record-");
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));
            HomeDigest before = HomeDigest.compute(home.store);
            Files.writeString(home.unitFile("alpha", "SKILL.md"), skillMd("alpha") + "\nedit\n");
            DriftGate.recordSince(home.store, before, "project sync");

            Path record = DriftGate.file(home.store);
            String v1 = Files.readString(record)
                    .replaceAll("\\s*\"surfacedCount\"\\s*:\\s*\\d+,?", "")
                    .replaceAll("\"schemaVersion\"\\s*:\\s*2", "\"schemaVersion\" : 1");
            Files.writeString(record, v1);
            assertFalse(Files.readString(record).contains("surfacedCount"),
                    "the fixture really is a pre-HIS-3 record");

            DriftGate reread = DriftGate.pending(home.store).orElseThrow();
            assertTrue(reread.firstSurfacing(),
                    "an old record reads as not yet surfaced, which is today's behaviour");
        });

        suite.test("acknowledging clears the gate and the launch proceeds", () -> {
            Home home = Home.create("drift-gate-ack-");
            home.writeDescriptor();
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));
            HomeDigest before = HomeDigest.compute(home.store);
            Files.writeString(home.unitFile("alpha", "SKILL.md"), skillMd("alpha") + "moved\n");
            DriftGate.recordSince(home.store, before, "project sync");

            int ackRc = new CommandLine(new HomeCommand.DriftCmd(home.store)).execute("--ack");
            assertEquals(0, ackRc, "ack rc");
            assertTrue(DriftGate.pending(home.store).isEmpty(), "the gate is clear");

            Path sentinel = home.root.resolve("child-ran");
            Path touch = home.writeTouch(sentinel);
            int rc = new CommandLine(new ExecCommand(home.store, "/usr/bin" + SYSTEM_BINS))
                    .execute("--no-reconcile", touch.toString());

            assertEquals(0, rc, "the launch now succeeds");
            assertTrue(Files.exists(sentinel), "and the child really ran");
        });

        suite.test("--ack-drift acknowledges in passing rather than blocking", () -> {
            Home home = Home.create("drift-gate-inline-");
            home.writeDescriptor();
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));
            HomeDigest before = HomeDigest.compute(home.store);
            Files.writeString(home.unitFile("alpha", "SKILL.md"), skillMd("alpha") + "moved\n");
            DriftGate.recordSince(home.store, before, "project sync");

            Path sentinel = home.root.resolve("child-ran");
            Path touch = home.writeTouch(sentinel);
            Result result = captureBoth(() -> new CommandLine(
                    new ExecCommand(home.store, "/usr/bin" + SYSTEM_BINS))
                    .execute("--no-reconcile", "--ack-drift", touch.toString()));

            assertEquals(0, result.rc, "the launch proceeds");
            assertTrue(Files.exists(sentinel), "the child ran");
            assertContains(result.err, "acknowledged drift", "having printed what it acknowledged");
            assertTrue(DriftGate.pending(home.store).isEmpty(), "and the gate is clear afterwards");
        });

        // -------------------------------------------------- a clone is not drifted
        //
        // The measured defect: every worktree home `new-change.sh` provisions is
        // a clone, `bootstrap-home.sh` baselines it with `home drift --record`,
        // and the launch it then tells the operator to run refused with exit 8
        // over "6 unit(s) changed" — on two independent virgin worktrees against
        // which nothing else had ever run.
        //
        // Nothing had moved. The clone copied `home.digest.json` verbatim, so the
        // baseline the copy was measured against was a statement about the SOURCE
        // at an earlier moment, recorded by an earlier build. Both shapes of that
        // are below, plus the two properties that must survive the fix: a real
        // change after the clone still gates, and the source's own gate is not
        // inherited.
        //
        // Every case here runs the operator's sequence — clone, `home drift
        // --record`, launch — and asserts on whether the CHILD PROCESS RAN, not
        // on an exit code. The defect is a refused launch, and a refusal is only
        // observable as the thing that did not happen.

        suite.test("a clone does not report the source's unrecorded change as its own", () -> {
            // The general shape, with no schema change anywhere: a source that
            // edits a unit and does not run `home drift --record` before being
            // cloned hands every future clone its own change to answer for.
            Home source = Home.create("drift-clone-stale-src-");
            source.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));
            HomeDigest.compute(source.store).write(source.store);
            Files.writeString(source.unitFile("alpha", "SKILL.md"),
                    skillMd("alpha") + "the source changed this and never recorded it\n");

            Home copy = source.cloneTo("drift-clone-stale-dst-");
            new CommandLine(new HomeCommand.DriftCmd(copy.store)).execute("--record");

            assertTrue(DriftGate.pending(copy.store).isEmpty(),
                    "the copy has nothing to acknowledge — the change happened in another home,"
                            + " before this one existed");
            assertTrue(copy.launchRan(),
                    "so the very first launch of the new home is not refused");
        });

        suite.test("a clone is not drifted by a baseline written under an older content rule", () -> {
            // The shape actually measured. The source's baseline predated
            // walkPlain learning to skip re-derivable trees (c2d535c), so it
            // enumerated .venv entries that the current definition of unit
            // content excludes — and the diff between two definitions of
            // "content" came out as thousands of deletions of files the copy
            // demonstrably still had on disk.
            Home source = Home.create("drift-clone-schema-src-");
            source.installSkill("alpha", Map.of(
                    "SKILL.md", skillMd("alpha"),
                    ".venv/pyvenv.cfg", "home = /usr/bin\n"));

            // A baseline as the older build would have written it: the same unit,
            // plus the entries that rule counted, under a digest that therefore
            // does not match what this build computes.
            HomeDigest fresh = HomeDigest.compute(source.store);
            HomeDigest.UnitDigest alpha = fresh.unit("alpha").orElseThrow();
            Map<String, String> withVenv = new LinkedHashMap<>(alpha.entries());
            withVenv.put(".venv", "d41d8cd98f00b204e9800998ecf8427e");
            withVenv.put(".venv/pyvenv.cfg", "b026324c6904b2a9cb4b88d6d61c81d1");
            new HomeDigest(HomeDigest.SCHEMA_VERSION, fresh.computedAt(), "stale-home-digest",
                    List.of(new HomeDigest.UnitDigest(alpha.name(), alpha.kind(),
                            "stale-unit-digest-from-an-older-content-rule", withVenv)))
                    .write(source.store);
            assertTrue(Files.exists(source.unitFile("alpha", ".venv/pyvenv.cfg")),
                    "the file the stale baseline names is really there — the defect reported it"
                            + " as REMOVED from a copy that still had it");

            Home copy = source.cloneTo("drift-clone-schema-dst-");
            // ARTI-07 changed the second half of this case and not the first.
            // The copy no longer CARRIES the virtualenv — it declares it, and
            // `uv` rebuilds it from the lockfile beside it on first use — and
            // the property this case exists for is untouched, because the
            // digest never counted a virtualenv on either side:
            // ChildHomeMaterializer.walkPlain drops every Rederivable.isDerived
            // path. Deferring one therefore moves nothing the gate can see,
            // which is what the assertions below measure.
            assertFalse(Files.exists(copy.unitFile("alpha", ".venv/pyvenv.cfg")),
                    "the copy declares the virtualenv rather than carrying it");
            // The ROW, not the file. `declareArtifacts` writes
            // artifacts.lock.toml on every clone whether or not it deferred
            // anything, so asserting the file exists is a claim about the
            // cloner's unconditional behaviour and says nothing about this
            // virtualenv — the assertion has to name the tree it dropped.
            assertTrue(ArtifactLedger.load(copy.store).rows().stream()
                            .anyMatch(row -> row.outputs().contains("skills/alpha/.venv")),
                    "and records THAT TREE, so nothing was dropped silently");
            new CommandLine(new HomeCommand.DriftCmd(copy.store)).execute("--record");

            DriftGate pending = DriftGate.pending(copy.store).orElse(null);
            assertTrue(pending == null,
                    "a pristine copy is not drifted"
                            + (pending == null ? "" : " — reported: " + pending.report().render()));
            assertTrue(copy.launchRan(), "and its first launch is not refused");
        });

        suite.test("a clone does not inherit the source's unacknowledged gate", () -> {
            // The sharper half. An unread change in the source is a fact about
            // agents working in the SOURCE; inheriting it refuses the first
            // launch of a home that did not exist when the change happened.
            // Deleted rather than acknowledged: an acknowledgement is a receipt
            // saying somebody read this home's change, and writing one for a
            // change this home never had would be a false receipt.
            Home source = Home.create("drift-clone-gate-src-");
            source.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));
            HomeDigest before = HomeDigest.compute(source.store);
            Files.writeString(source.unitFile("alpha", "SKILL.md"), skillMd("alpha") + "moved\n");
            DriftGate.recordSince(source.store, before, "project sync");
            assertTrue(DriftGate.pending(source.store).isPresent(), "the source really is gated");

            Home copy = source.cloneTo("drift-clone-gate-dst-");

            assertTrue(DriftGate.pending(copy.store).isEmpty(), "the copy is not");
            assertFalse(Files.exists(DriftGate.file(copy.store)),
                    "and carries no record at all, acknowledged or otherwise");
            assertTrue(DriftGate.pending(source.store).isPresent(),
                    "while the SOURCE's gate is untouched — being cloned is not being read");
            assertTrue(copy.launchRan(), "so the copy launches");
        });

        suite.test("a change made AFTER the clone still gates the clone's launch", () -> {
            // The property the fix must not cost. The gate exists so an agent
            // cannot keep acting on a skill that moved underneath it, and it is
            // only from the clone onwards that anything can move underneath
            // anybody in this home. Without this case, "a clone is never
            // drifted" would be satisfied by deleting the gate.
            Home source = Home.create("drift-clone-later-src-");
            source.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));
            Home copy = source.cloneTo("drift-clone-later-dst-");
            new CommandLine(new HomeCommand.DriftCmd(copy.store)).execute("--record");
            assertTrue(DriftGate.pending(copy.store).isEmpty(), "clean to begin with");

            Files.writeString(copy.unitFile("alpha", "SKILL.md"),
                    skillMd("alpha") + "a sync moved this under the agent\n");
            new CommandLine(new HomeCommand.DriftCmd(copy.store)).execute("--record");

            DriftGate pending = DriftGate.pending(copy.store).orElseThrow();
            assertTrue(pending.report().unitNames().contains("alpha"),
                    "the change is recorded against the copy's own baseline");
            assertFalse(copy.launchRan(), "and the launch is refused, as it must be");
        });

        // ------------------------------------------- the vacuity trap itself

        suite.test("a second measurement does not retire an unread change", () -> {
            // The failure mode this epic keeps hitting, in gate form: after the
            // first sync the home is perfectly self-consistent again, so a gate
            // that asked "does the home match its digest?" would find nothing and
            // clear itself — while the change that invalidated the agent's
            // knowledge is still unread. The pending record is a fact about
            // something that happened, not a statement about the home's current
            // agreement with itself.
            Home home = Home.create("drift-not-vacuous-");
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));
            HomeDigest before = HomeDigest.compute(home.store);
            Files.writeString(home.unitFile("alpha", "SKILL.md"), skillMd("alpha") + "moved\n");
            DriftGate.recordSince(home.store, before, "first sync");
            assertTrue(DriftGate.pending(home.store).isPresent(), "the first change is pending");

            // A second sync that changes nothing. The home now matches its digest.
            DriftGate.recordSince(home.store, HomeDigest.read(home.store).orElseThrow(),
                    "second sync");

            DriftGate still = DriftGate.pending(home.store).orElse(null);
            assertTrue(still != null, "the unread change is STILL pending");
            assertTrue(still.report().unitNames().contains("alpha"),
                    "and still names the unit that moved");
        });

        suite.test("a change arriving on top of an unread one is added, not substituted", () -> {
            Home home = Home.create("drift-merge-");
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));
            home.installSkill("beta", Map.of("SKILL.md", skillMd("beta")));

            HomeDigest first = HomeDigest.compute(home.store);
            Files.writeString(home.unitFile("alpha", "SKILL.md"), skillMd("alpha") + "moved\n");
            DriftGate.recordSince(home.store, first, "first sync");

            Files.writeString(home.unitFile("beta", "SKILL.md"), skillMd("beta") + "moved\n");
            DriftGate.recordSince(home.store, HomeDigest.read(home.store).orElseThrow(),
                    "second sync");

            DriftGate pending = DriftGate.pending(home.store).orElseThrow();
            assertTrue(pending.report().unitNames().contains("alpha"),
                    "the first unread change survives the second sync");
            assertTrue(pending.report().unitNames().contains("beta"),
                    "alongside the new one");
        });

        suite.test("an unreadable gate file blocks rather than reading as nothing to do", () -> {
            Home home = Home.create("drift-corrupt-");
            Files.writeString(DriftGate.file(home.store), "{ not json");

            assertTrue(DriftGate.pending(home.store).isPresent(),
                    "a record we cannot parse is not an absence of a record — "
                            + "the permissive direction is the silent one");
        });

        // ------------------------------------------------------------ command

        suite.test("`home drift` shows the pending change and exits non-zero", () -> {
            Home home = Home.create("drift-cmd-");
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha"),
                    "references/one.md", "one\n"));
            HomeDigest before = HomeDigest.compute(home.store);
            Files.writeString(home.unitFile("alpha", "references/one.md"), "one, revised\n");
            DriftGate.recordSince(home.store, before, "project sync");

            Result shown = captureBoth(() -> new CommandLine(new HomeCommand.DriftCmd(home.store))
                    .execute());
            assertEquals(DriftGate.EXIT_CODE, shown.rc, "unread drift is a non-zero exit");
            assertContains(shown.err + shown.out, "skill:alpha", "the changed unit is named");
            assertFalse((shown.err + shown.out).contains("references/one.md"),
                    "but not every changed path -- that is the #212 firehose");

            // CLAUSE 2 of GOAL-sync-quiet, through the CLI rather than the
            // record: collapsing is a default, not a deletion. The paths an
            // operator used to get by default are still one flag away, and
            // this is the assertion that keeps them reachable.
            Result detailed = captureBoth(() -> new CommandLine(new HomeCommand.DriftCmd(home.store))
                    .execute("--detail"));
            assertEquals(DriftGate.EXIT_CODE, detailed.rc, "--detail still reports the gate");
            assertContains(detailed.err + detailed.out, "references/one.md",
                    "and --detail names the changed file");

            Result clean = captureBoth(() -> new CommandLine(new HomeCommand.DriftCmd(home.store))
                    .execute("--ack"));
            assertEquals(0, clean.rc, "ack rc");
            Result after = captureBoth(() -> new CommandLine(new HomeCommand.DriftCmd(home.store))
                    .execute());
            assertEquals(0, after.rc, "and the gate is clear afterwards");
        });

        suite.test("`home drift --record` measures, records, and refreshes the baseline", () -> {
            Home home = Home.create("drift-cmd-record-");
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));
            new CommandLine(new HomeCommand.DriftCmd(home.store)).execute("--record");
            assertTrue(DriftGate.pending(home.store).isEmpty(), "first record: nothing pending");

            Files.writeString(home.unitFile("alpha", "SKILL.md"), skillMd("alpha") + "moved\n");
            new CommandLine(new HomeCommand.DriftCmd(home.store)).execute("--record");

            DriftGate pending = DriftGate.pending(home.store).orElseThrow();
            assertTrue(pending.report().unitNames().contains("alpha"), "the change is recorded");
            assertEquals(HomeDigest.compute(home.store).digest(),
                    HomeDigest.read(home.store).orElseThrow().digest(),
                    "and the baseline is now current");
        });

        suite.test("a frozen home can be inspected for drift without being written to", () -> {
            Home home = Home.create("drift-frozen-");
            home.installSkill("alpha", Map.of("SKILL.md", skillMd("alpha")));
            HomePolicy.write(home.store, HomePolicy.FROZEN);

            Result result = captureBoth(() -> new CommandLine(new HomeCommand.DriftCmd(home.store))
                    .execute());

            assertEquals(0, result.rc, "nothing pending, nothing refused");
            assertFalse(Files.exists(DriftGate.file(home.store)), "and nothing was written");
        });

        return suite.runAll();
    }

    // -------------------------------------------------- independent oracle

    /**
     * Every file under every unit directory, as {@code <unit>/<rel>} → SHA-256 of
     * its bytes.
     *
     * <p>Deliberately implemented here with nothing but {@code Files.walk} and
     * {@code MessageDigest}. It shares no code with {@link HomeDigest}, so the two
     * cannot be wrong together: a digest that stopped hashing content, or that
     * skipped a directory, would disagree with this immediately.
     */
    private static Map<String, String> readEveryUnitFile(Home home) throws Exception {
        Map<String, String> out = new TreeMap<>();
        Path skills = home.store.skillsDir();
        if (!Files.isDirectory(skills)) return out;
        try (var walk = Files.walk(skills)) {
            for (Path p : walk.toList()) {
                if (!Files.isRegularFile(p)) continue;
                String rel = skills.relativize(p).toString().replace(File.separatorChar, '/');
                if (rel.contains("/.git/")) continue;
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                out.put(rel, hex(sha.digest(Files.readAllBytes(p))));
            }
        }
        return out;
    }

    /** Every {@code <unit>/<rel>} path the report mentions, in any category. */
    private static java.util.Set<String> reportedPaths(DriftReport report) {
        java.util.Set<String> out = new TreeSet<>();
        for (DriftReport.UnitDrift unit : report.units()) {
            for (String rel : unit.allFiles()) {
                // Directory entries are structure, not content; the oracle only
                // knows about files.
                if (rel.contains(".")) out.add(unit.name() + "/" + rel);
            }
        }
        return out;
    }

    private static java.util.Set<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xf, 16))
                .append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }

    // ------------------------------------------------------------- fixtures


    /** Write a file, creating parents. */
    private static void write(Path target, String body) throws Exception {
        Fs.ensureDir(target.getParent());
        Files.writeString(target, body);
    }

    /**
     * Make a unit directory a real repository with its declaration committed.
     * Required because {@code GitIgnoreRules} refuses to ignore anything for a
     * unit with no readable index — the index is what rescues a path the
     * declaration would otherwise hide.
     */
    private static void initRepoAndCommit(Path unitDir) throws Exception {
        run(unitDir, "git", "init", "--initial-branch=main");
        run(unitDir, "git", "config", "user.email", "drift@test.invalid");
        run(unitDir, "git", "config", "user.name", "drift");
        run(unitDir, "git", "add", "-A");
        run(unitDir, "git", "commit", "--quiet", "-m", "fixture");
    }

    private static void run(Path cwd, String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).directory(cwd.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", argv) + ": " + out);
        }
    }

    private record Home(Path root, SkillStore store) {

        static Home create(String prefix) throws Exception {
            Path root = Files.createTempDirectory(prefix);
            SkillStore store = new SkillStore(root.resolve(".skill-manager"));
            store.init();
            return new Home(root, store);
        }

        void installSkill(String name, Map<String, String> files) throws Exception {
            Path dir = store.skillDir(name);
            Fs.ensureDir(dir);
            for (Map.Entry<String, String> file : new LinkedHashMap<>(files).entrySet()) {
                Path target = dir.resolve(file.getKey());
                Fs.ensureDir(target.getParent());
                Files.writeString(target, file.getValue());
            }
            new UnitStore(store).write(new InstalledUnit(
                    name, "0.1.0", InstalledUnit.Kind.LOCAL_DIR,
                    InstalledUnit.InstallSource.LOCAL_FILE, null, null, null,
                    UnitStore.nowIso(), List.of(), UnitKind.SKILL));
        }

        Path unitFile(String unit, String rel) {
            return store.skillDir(unit).resolve(rel);
        }

        void writeDescriptor() throws Exception {
            new HomeDescriptor(
                    root, HomePolicy.LIVE.wire(),
                    HomeDescriptor.envFor(root, store.root()), null,
                    new HomeDescriptor.Gateway("http://127.0.0.1:51717", true),
                    List.of(), Map.of()).write(store.root());
        }

        Path writeTouch(Path sentinel) throws Exception {
            Path file = root.resolve("touch");
            Files.writeString(file, "#!/usr/bin/env bash\ntouch '" + sentinel + "'\n");
            file.toFile().setExecutable(true);
            return file;
        }

        /**
         * Copy this home to a fresh root, exactly as {@code home clone} does, and
         * give the copy its own descriptor.
         *
         * <p>The descriptor is rewritten rather than inherited because the copied
         * one names the SOURCE's agent directories, and {@code exec} refuses a
         * launch whose {@code CLAUDE_CONFIG_DIR} points outside the home it is
         * launching. {@code HomeCommand.CloneCmd} does the same thing for the
         * same reason; without it every launch assertion below would be
         * satisfied by the wrong refusal.
         */
        Home cloneTo(String prefix) throws Exception {
            Path destRoot = Files.createTempDirectory(prefix);
            HomeCloner.Report report =
                    HomeCloner.cloneHome(store.root(), destRoot.resolve(".skill-manager"));
            assertTrue(report.clean(), "the fixture clone is clean: " + report.leaks());
            Home copy = new Home(destRoot, new SkillStore(destRoot.resolve(".skill-manager")));
            copy.writeDescriptor();
            return copy;
        }

        /**
         * Launch through {@code exec} and report whether the child really ran.
         *
         * <p>A boolean about a process, not an exit code, because that is the
         * only honest oracle for a gate: one that refuses <em>after</em> spawning
         * has already lost, and one that returns non-zero for an unrelated reason
         * looks identical to one that gated.
         */
        boolean launchRan() throws Exception {
            Path sentinel = Files.createTempFile(root, "launch-", ".sentinel");
            Files.delete(sentinel);
            Path touch = writeTouch(sentinel);
            captureBoth(() -> new CommandLine(new ExecCommand(store, "/usr/bin" + SYSTEM_BINS))
                    .execute("--no-reconcile", touch.toString()));
            return Files.exists(sentinel);
        }
    }

    private static String skillMd(String name) {
        return "---\nname: " + name + "\ndescription: drift fixture\n---\nbody\n";
    }

    // ------------------------------------------------------------- plumbing

    private static Result captureBoth(ThrowingInt op) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int rc = op.run();
            return new Result(rc, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @FunctionalInterface
    private interface ThrowingInt { int run() throws Exception; }

    private record Result(int rc, String out, String err) {}
}
