package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
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
            assertContains(result.err, "SKILL.md", "and the file");
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
            assertTrue(Files.exists(copy.store.root().resolve("artifacts.lock.toml")),
                    "and records it, so nothing was dropped silently");
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
            assertContains(shown.err + shown.out, "references/one.md", "the changed file is named");

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
