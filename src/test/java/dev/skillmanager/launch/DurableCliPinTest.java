package dev.skillmanager.launch;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.HomeCommand;
import dev.skillmanager.store.HomeCloner;
import dev.skillmanager.store.HomeRepair;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * HIS-19 (#246) / DEF-027 — the CAUSE under DEF-012.
 *
 * <p>A home's CLI pin was an absolute VERSIONED path into the Homebrew Cellar,
 * so {@code brew upgrade} deleted the file it named and the home's front door
 * could only produce exit 127 from then on. HIS-12 made the resolver step past
 * a dead pin; HIS-13 made {@code home repair} report and re-pin it. Neither
 * stopped the product from writing the same pin again, so the repair was a
 * treadmill. This suite is the fix and the three assertions the ticket is
 * graded on.
 *
 * <h2>The fixture is the operator's machine, reproduced</h2>
 *
 * <p>{@link Brew} builds the exact link shape measured on the machine where
 * DEF-012 happened:
 *
 * <pre>
 * &lt;prefix&gt;/Cellar/skill-manager/0.23.0/libexec/bin/skill-manager   (the real file)
 * &lt;prefix&gt;/Cellar/skill-manager/0.23.0/bin/skill-manager -&gt; ../libexec/bin/skill-manager
 * &lt;prefix&gt;/bin/skill-manager                            -&gt; ../Cellar/…/0.23.0/bin/skill-manager
 * &lt;prefix&gt;/opt/skill-manager                            -&gt; ../Cellar/skill-manager/0.23.0
 * </pre>
 *
 * <p>and {@link Brew#upgrade} does what {@code brew upgrade} does: install the
 * next keg, DELETE the old one, re-point both aliases. The build a keg holds
 * prints its own version, so every claim below is settled by what actually ran
 * and not by an exit code — the discipline {@code LauncherShims.cliScript}'s
 * javadoc records, where a shim that refused unconditionally satisfied a
 * one-sided assertion perfectly.
 *
 * <h2>The discriminating pair, and why it is one disk state</h2>
 *
 * <p>Vacuity ledger row 13: an oracle that cannot express the defect makes every
 * probe against it blind, however many there are. So the durable home and the
 * versioned home are built side by side over ONE sandbox, differing in exactly
 * one thing — the string on the pin line — and every claim is asserted on both.
 * A change that made the two agree in either direction turns half the pairs red.
 */
public final class DurableCliPinTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("DurableCliPinTest");

        // ------------------------------------------------- the three acceptance claims

        suite.test("CLAIM 1: a home pinned BEFORE a version change still runs a CLI after it",
                () -> {
                    Brew brew = Brew.install("0.23.0");
                    Path home = brew.home("durable");
                    LauncherShims.write(new SkillStore(home), brew.located());

                    // PRECONDITIONS, asserted apart from the claim and — the
                    // part mechanism A actually asks for — BLIND to the change
                    // under test. Every one of these is true both before and
                    // after HIS-19, so reverting the fix cannot move them, and
                    // a red below is therefore always the claim.
                    //
                    // "the pin is versionless" deliberately is NOT here. It is
                    // downstream of the change, so as a precondition it would
                    // abort the run before the claim was ever evaluated — a
                    // probe that reddens the setup and credits the claim. It is
                    // its own case, next.
                    assertTrue(DurableCliPin.namesAVersion(brew.located()),
                            "precondition: the build RunningCli located IS versioned, "
                                    + "or there is no defect to fix: " + brew.located());
                    assertFalse(DurableCliPin.namesAVersion(brew.sandbox()),
                            "precondition: the sandbox path itself names no version, so "
                                    + "'versionless' is a statement about the pin and not "
                                    + "about where the fixture happens to live: " + brew.sandbox());
                    assertEquals("BUILD 0.23.0", run(entrypoint(home)).out().strip(),
                            "precondition: before the upgrade it runs the build it was pinned at");

                    brew.upgrade("0.24.0");

                    // THE CLAIM, settled by what actually ran.
                    assertEquals("BUILD 0.24.0", run(entrypoint(home)).out().strip(),
                            "after the upgrade the home's front door still opens, onto the "
                                    + "build that replaced the one it was provisioned with");
                });

        suite.test("the writer records a VERSIONLESS pin, which is the mechanism claim 1 rests on",
                () -> {
                    Brew brew = Brew.install("0.23.0");
                    Path home = brew.home("durable");
                    LauncherShims.write(new SkillStore(home), brew.located());

                    Path pin = LauncherShims.pinnedCliIn(entrypoint(home)).orElse(null);

                    assertNotNull(pin, "the generated entrypoint carries a readable pin");
                    assertFalse(DurableCliPin.namesAVersion(pin),
                            "and it names no version: " + pin);
                    assertEquals(brew.prefix().resolve("bin/skill-manager"), pin,
                            "specifically, the prefix entry brew re-points on every upgrade");
                });

        suite.test("CLAIM 1 CONTROL: the SAME home with a versioned pin is dead after it", () -> {
            // The fixture that fails when the pin is written versioned. Same
            // sandbox, same upgrade, same generator — LauncherShims.cliScript
            // renders both files — differing only in the path handed to it,
            // which is the pre-HIS-19 bytes exactly.
            Brew brew = Brew.install("0.23.0");
            Path durable = brew.home("durable");
            Path versioned = brew.home("versioned");
            LauncherShims.write(new SkillStore(durable), brew.located());
            writeVersionedPin(versioned, brew.located());

            assertEquals("BUILD 0.23.0", run(entrypoint(versioned)).out().strip(),
                    "precondition: the versioned pin works BEFORE the upgrade — the two "
                            + "homes are indistinguishable at this point");

            brew.upgrade("0.24.0");

            Result dead = run(entrypoint(versioned));
            assertEquals(127, dead.rc(),
                    "the versioned pin is exit 127 after the upgrade: " + dead.out());
            assertContains(dead.out(), "is missing",
                    "and says which file is gone rather than falling through to PATH");
            assertEquals("BUILD 0.24.0", run(entrypoint(durable)).out().strip(),
                    "and its twin over the same sandbox still runs — one disk state, "
                            + "two pins, two verdicts");
        });

        suite.test("CLAIM 2: HIS-13's DANGLING_CLI_PIN does not fire on the new writer's home",
                () -> {
                    Brew brew = Brew.install("0.23.0");
                    Path durable = brew.home("durable");
                    Path versioned = brew.home("versioned");
                    LauncherShims.write(new SkillStore(durable), brew.located());
                    writeVersionedPin(versioned, brew.located());
                    brew.upgrade("0.24.0");

                    HomeRepair.Report clean = HomeRepair.detect(durable, brew.located());
                    HomeRepair.Report damaged = HomeRepair.detect(versioned, brew.located());

                    // The question was ASKED. Without this, "no finding" is
                    // equally consistent with the check never having run —
                    // mechanism C.
                    assertTrue(clean.examined() >= 1,
                            "precondition: detection examined at least the pin subject");
                    assertTrue(danglingPins(damaged).size() == 1,
                            "precondition: the same detector, over the same upgrade, DOES "
                                    + "fire on the versioned pin — so a green above is a "
                                    + "verdict and not a silence");
                    // THE CLAIM.
                    assertTrue(danglingPins(clean).isEmpty(),
                            "no DANGLING_CLI_PIN on a home pinned by the new writer: "
                                    + danglingPins(clean));
                });

        suite.test("CLAIM 3: `home verify` no longer exits 0 on a home whose pin is dead", () -> {
            Brew brew = Brew.install("0.23.0");
            Path durable = brew.home("durable");
            Path versioned = brew.home("versioned");
            LauncherShims.write(new SkillStore(durable), brew.located());
            writeVersionedPin(versioned, brew.located());

            // PRECONDITION, and it is the 0.24.0 incident's own shape: BEFORE
            // the upgrade both homes verify clean. A `home verify` that refused
            // this fixture for some unrelated reason would make the red below
            // meaningless.
            assertEquals(0, verify(versioned).rc(),
                    "precondition: the versioned home verifies clean before the upgrade");

            brew.upgrade("0.24.0");

            Result broken = verify(versioned);
            Result healthy = verify(durable);

            // THE CLAIM. This is the regression test for the incident: `home
            // verify` returned EXIT 0 on the root home brew had just broken.
            assertEquals(1, broken.rc(),
                    "a home whose pin does not resolve is a FAILURE: " + broken.err());
            assertContains(broken.err(), "name a build that is not there",
                    "and the report says what is wrong");
            assertContains(broken.err(), "home repair --home " + versioned + " --fix",
                    "and names HIS-13's repairer, with this home, runnably");
            assertEquals(0, healthy.rc(),
                    "while the durably-pinned twin over the same sandbox still passes: "
                            + healthy.err());
        });

        // --------------------------------------------------- migration, through HIS-13

        suite.test("MIGRATION: `home repair --fix` re-pins an existing home DURABLY, so the "
                + "next upgrade does not break it again", () -> {
            Brew brew = Brew.install("0.23.0");
            Path home = brew.home("legacy");
            writeVersionedPin(home, brew.located());
            brew.upgrade("0.24.0");

            assertTrue(danglingPins(HomeRepair.detect(home, brew.located())).size() == 1,
                    "precondition: the legacy home is damaged, which is what invites a repair");

            HomeRepair.Outcome outcome = HomeRepair.repair(home, brew.located());

            assertEquals(1, outcome.repaired().size(),
                    "the repair carried out the pin finding: " + outcome.failed());
            assertTrue(outcome.after().clean(),
                    "and detection afterwards says so: " + outcome.after().findings());
            Path pin = LauncherShims.pinnedCliIn(entrypoint(home)).orElse(null);
            assertNotNull(pin, "the repaired entrypoint still carries a readable pin");
            assertFalse(DurableCliPin.namesAVersion(pin),
                    "and the repair wrote a DURABLE pin, not the located keg path: " + pin);

            // THE POINT OF THE TICKET. HIS-13 repaired this home before; what
            // it could not do was stop the next upgrade undoing the repair.
            brew.upgrade("0.25.0");
            assertEquals("BUILD 0.25.0", run(entrypoint(home)).out().strip(),
                    "a SECOND upgrade leaves the repaired home working — the treadmill "
                            + "is what this ticket exists to stop");
        });

        suite.test("ONE ANSWER: the repairer's target is the pin the writer would write", () -> {
            // GOAL-one-home-one-answer, as a guard. Two callers of one pure
            // function must not become two answers to "which build does this
            // home run".
            Brew brew = Brew.install("0.23.0");
            Path home = brew.home("legacy");
            writeVersionedPin(home, brew.located());
            brew.upgrade("0.24.0");

            List<HomeRepair.Finding> found = danglingPins(HomeRepair.detect(home, brew.located()));
            assertEquals(1, found.size(), "precondition: one finding to compare against");
            Path target = found.get(0).target();

            Path other = brew.home("fresh");
            LauncherShims.write(new SkillStore(other), brew.located());
            Path written = LauncherShims.pinnedCliIn(entrypoint(other)).orElse(null);

            assertEquals(written, target,
                    "the remedy names the path the repair will actually write");
            assertContains(found.get(0).remedy(), target.toString(),
                    "and the printed remedy names it too, rather than the located keg path");
        });

        suite.test("MAJOR 1: `home shims` PRINTS the pin it wrote, byte for byte", () -> {
            // THE READER DISAGREEMENT THIS FIX ITSELF CREATED, and until the
            // review of #250 it was caught only by my having noticed it once.
            // Reverting HomeCommand's `pin = result.pin();` reddened NOTHING in
            // 1402 cases. A class of defect this epic exists to remove must not
            // be defended by a memory.
            //
            // It matters most here of all places: this is the command an
            // operator runs to REPAIR a broken pin, and the same value is the
            // `--json` "cli" field that a script reads.
            Brew brew = Brew.install("0.23.0");
            Path home = brew.home("reported");

            // Precondition, and BLIND to the change under test: a substitution
            // must actually happen, or "printed == written" is trivially true
            // of a run where the two could not differ.
            assertTrue(DurableCliPin.choose(brew.located()).substituted(),
                    "precondition: this fixture substitutes, so the printed and the "
                            + "located paths are genuinely different strings");

            Result shims = shims(home, brew.located());
            assertEquals(0, shims.rc(), "home shims succeeded: " + shims.err());

            Path written = LauncherShims.pinnedCliIn(entrypoint(home)).orElse(null);
            assertNotNull(written, "the entrypoint carries a readable pin");
            String printed = reportedPin(shims.out());
            assertNotNull(printed, "the run printed a `pinned CLI:` line: " + shims.out());

            assertEquals(written.toString(), printed,
                    "the printed `pinned CLI:` is byte-equal to the pin inside "
                            + "bin/cli/skill-manager");
            assertFalse(printed.equals(brew.located().toString()),
                    "and specifically it is NOT the located build, which is what it "
                            + "reported before this assertion existed");
        });

        suite.test("MAJOR 1: the --json `cli` field is the pin that was written too", () -> {
            // The same value on the machine-readable surface. A script that
            // records which build a home was bound to reads THIS.
            Brew brew = Brew.install("0.23.0");
            Path home = brew.home("reported-json");

            Result shims = shims(home, brew.located(), "--json");
            assertEquals(0, shims.rc(), "home shims --json succeeded: " + shims.err());

            Path written = LauncherShims.pinnedCliIn(entrypoint(home)).orElse(null);
            assertNotNull(written, "the entrypoint carries a readable pin");
            assertContains(shims.out(), "\"cli\":\"" + written + "\"",
                    "the json `cli` field is the pin that was written");
            assertFalse(shims.out().contains("\"cli\":\"" + brew.located() + "\""),
                    "and not the located build");
        });

        suite.test("HONEST LIMIT: a package-manager alias FOLLOWS a deliberate downgrade", () -> {
            // MEASURED, AGAINST THIS CLASS'S OWN EARLIER CLAIM. The javadoc used
            // to say a substitution "cannot produce" an older build answering.
            // The review of #250 challenged that for the PATH arm; measuring it
            // showed it false for the DERIVABLE arm as well, which is a stronger
            // statement than the review made and is why the sentence was
            // rewritten rather than narrowed. Asserted here so the corrected
            // contract is pinned by a test and not only by prose.
            Brew brew = Brew.install("0.24.0");
            brew.installKegOnly("0.19.2");
            Path home = brew.home("downgrade");
            LauncherShims.write(new SkillStore(home), brew.located());
            assertEquals("BUILD 0.24.0", run(entrypoint(home)).out().strip(),
                    "precondition: the home runs the build it was pinned at");

            brew.relink("0.19.2");   // `brew link --overwrite skill-manager@0.19.2`

            assertEquals("BUILD 0.19.2", run(entrypoint(home)).out().strip(),
                    "the home follows the operator's own installation backwards — this "
                            + "is a LIMIT of the design, deliberately recorded. It is not "
                            + "issue #61: nothing was resolved at launch, and the path the "
                            + "home holds is this machine's package manager's alias for "
                            + "this formula, moved by an explicit operator command");
        });

        suite.test("IDEMPOTENT: feeding the chosen pin back in returns it unchanged", () -> {
            Brew brew = Brew.install("0.23.0");
            Path once = DurableCliPin.forPin(brew.located());
            Path twice = DurableCliPin.forPin(once);
            assertEquals(once, twice,
                    "the writer and the repairer can each apply it without a second "
                            + "application moving the answer");
        });

        // -------------------------------------------------- one control per branch

        suite.test("BRANCH NO_VERSION_TO_LOSE: a build that names no version is pinned as-is",
                () -> {
                    // A CHECKOUT INSIDE A HOMEBREW PREFIX, so the short-circuit
                    // is the only thing stopping a substitution. The first
                    // version used a PATH decoy; with the PATH arm gone that
                    // fixture could not distinguish anything, so the decoy is
                    // now a DERIVABLE one — `<prefix>/bin/skill-manager` exists,
                    // is the same file, and is versionless.
                    Brew brew = Brew.install("0.23.0");
                    Path cli = executable(
                            brew.sandbox().resolve("checkout/skill-manager"), "checkout");
                    Path alias = brew.prefix().resolve("bin/skill-manager");
                    Files.delete(alias);
                    Files.createSymbolicLink(alias, cli);
                    assertTrue(alias.toRealPath().equals(cli.toRealPath()),
                            "precondition: the decoy really is the same file");

                    DurableCliPin.Choice choice = DurableCliPin.choose(cli);

                    assertEquals(DurableCliPin.Source.NO_VERSION_TO_LOSE, choice.source(),
                            "a source checkout has nothing to gain and is not rewritten");
                    assertEquals(cli, choice.pin(), "so the pin is the located path itself");
                });

        suite.test("BRANCH HOMEBREW_LINKED: the prefix's linked name wins, with no PATH at all",
                () -> {
                    Brew brew = Brew.install("0.23.0");

                    DurableCliPin.Choice choice =
                            DurableCliPin.choose(brew.located());

                    assertEquals(DurableCliPin.Source.HOMEBREW_LINKED, choice.source(),
                            "derived from the located path alone: a `home shims` run with an "
                                    + "empty environment gets the same answer as a login shell");
                    assertEquals(brew.prefix().resolve("bin/skill-manager"), choice.pin(),
                            "and it is the prefix entry brew re-points on every upgrade");
                });

        suite.test("BRANCH HOMEBREW_KEG: a keg-only formula falls to the opt alias", () -> {
            Brew brew = Brew.install("0.23.0");
            Files.delete(brew.prefix().resolve("bin/skill-manager"));   // not linked into the prefix

            DurableCliPin.Choice choice = DurableCliPin.choose(brew.located());

            assertEquals(DurableCliPin.Source.HOMEBREW_KEG, choice.source(),
                    "opt/<formula> is maintained for keg-only formulae too");
            assertEquals(brew.prefix().resolve("opt/skill-manager/bin/skill-manager"),
                    choice.pin(), "and it embeds the keg layout below the version");
        });

        suite.test("BRANCH NO_DURABLE_ALIAS: with nothing versionless pointing at it, the "
                + "versioned path is kept", () -> {
            Brew brew = Brew.install("0.23.0");
            Files.delete(brew.prefix().resolve("bin/skill-manager"));
            Files.delete(brew.prefix().resolve("opt/skill-manager"));

            DurableCliPin.Choice choice = DurableCliPin.choose(brew.located());

            assertEquals(DurableCliPin.Source.NO_DURABLE_ALIAS, choice.source(),
                    "an alias is never invented");
            assertEquals(brew.located(), choice.pin(),
                    "and the fallback is exactly the pre-HIS-19 behaviour, with HIS-13's "
                            + "DANGLING_CLI_PIN still the net under it");
        });

        suite.test("GATE: a versionless alias naming a DIFFERENT build is never taken", () -> {
            // Issue #61's defect, which this class must not reintroduce: the one
            // outcome worse than a dead pin is a live pin onto somebody else's
            // build. Measured shape — DEF-006/DEF-068, where the released CLI on
            // PATH is not the build the home was provisioned with.
            //
            // The decoy is a DERIVABLE candidate now, not a PATH entry: brew's
            // own `<prefix>/bin/skill-manager` linked at something that is not
            // this keg. That is a realer fixture than the PATH one it replaced —
            // `brew link` genuinely puts a different formula's binary there —
            // and it is the only shape left that can reach this gate.
            Brew brew = Brew.install("0.23.0");
            Path linked = brew.prefix().resolve("bin/skill-manager");
            Files.delete(linked);
            Path decoy = executable(brew.sandbox().resolve("other/skill-manager"), "SOMEONE-ELSE");
            Files.createSymbolicLink(linked, decoy);
            Files.delete(brew.prefix().resolve("opt/skill-manager"));

            DurableCliPin.Choice choice = DurableCliPin.choose(brew.located());

            // The decoy REACHED the gate. Without this, a run in which no
            // candidate was produced at all is indistinguishable from one in
            // which the gate refused it — mechanism C, and the reason this is
            // spelled `precondition:` so the probe harness classifies its red as
            // setup rather than crediting it to this gate.
            assertTrue(Files.isExecutable(linked), "precondition: the decoy is executable");
            assertTrue(choice.considered().containsKey(linked.toString()),
                    "precondition: the decoy was considered as a candidate: "
                            + choice.considered().keySet());

            assertEquals(DurableCliPin.Source.NO_DURABLE_ALIAS, choice.source(),
                    "a versionless executable of the right NAME is not the right FILE");
            assertEquals(brew.located(), choice.pin(), "so nothing is substituted");
            assertContains(choice.considered().get(linked.toString()),
                    "which is a DIFFERENT build",
                    "and the reason it was refused is recorded, not silent");
        });

        suite.test("GATE: an alias that ALSO names a version is not an improvement", () -> {
            // The gate that makes the claim checkable. Without it this would
            // happily "stabilise" onto a second versioned path and every
            // durability assertion would be about nothing.
            //
            // AIMED AT THE VERSION GATE, and two earlier drafts were not. The
            // first pointed PATH at the keg's own `bin/`, whose entry IS the
            // located path, so the (then-present) identity gate refused it
            // first — ledger row 16. The second used a PATH entry at all, which
            // no longer produces candidates. Both are fixed the same way: make
            // the DERIVABLE candidate itself carry a version, by installing the
            // whole prefix under a versioned directory. Real shape — a
            // side-by-side toolchain root — and the only one that reaches here.
            Brew brew = Brew.install("0.23.0", "brew-3.6.0");

            assertTrue(DurableCliPin.namesAVersion(brew.prefix()),
                    "precondition: the PREFIX names a version, so its own bin/ does too: "
                            + brew.prefix());

            DurableCliPin.Choice choice = DurableCliPin.choose(brew.located());

            Path linked = brew.prefix().resolve("bin/skill-manager");
            assertTrue(choice.considered().containsKey(linked.toString()),
                    "precondition: the versioned alias was considered as a candidate: "
                            + choice.considered().keySet());
            assertTrue(linked.toRealPath().equals(brew.located().toRealPath()),
                    "precondition: and it IS the same file, so only the version gate "
                            + "can refuse it");

            assertEquals(DurableCliPin.Source.NO_DURABLE_ALIAS, choice.source(),
                    "an alias under a versioned prefix is no more durable than the keg");
            assertContains(choice.considered().get(linked.toString()),
                    "names a version too",
                    "and the rejection says which segment made it so");
        });

        suite.test("GATE: a build that does not resolve is left exactly as it was", () -> {
            Path gone = Path.of("/nonexistent/Cellar/skill-manager/0.23.0/bin/skill-manager");

            DurableCliPin.Choice choice = DurableCliPin.choose(gone);

            assertEquals(DurableCliPin.Source.NO_DURABLE_ALIAS, choice.source(),
                    "there is nothing to compare an alias against");
            assertEquals(gone, choice.pin(), "and guessing would be worse than keeping it");
            // THE ASSERTION THIS CASE WAS MISSING, and the reason the review of
            // #250 could delete the branch with the suite green: the VERDICT is
            // reached either way (with `real` null every candidate fails the
            // same-file gate), so a verdict assertion could never see this
            // branch. What the branch decides is the REASON, so that is what is
            // asserted. Ledger row 18.
            assertContains(String.join("\n", choice.auditLines()),
                    "does not resolve, so there is nothing to compare an alias against",
                    "and the operator is told the build they named is absent, not that "
                            + "some alias failed to match");
        });

        suite.test("the version test errs narrow: a worktree, a temp dir and a bare number "
                + "are not versions", () -> {
            // Mechanism B, applied to the instrument. If this predicate were
            // loose, every fixture above would take the wrong branch — and a
            // false positive is silent, because the fallback is the old
            // behaviour. Each string below is a real path shape from this epic.
            for (String not : List.of("wt-246-his-19", "libexec", "bin", "x86_64",
                    "rsz3g6wn4hg8zl2bwmdx61q00000gn", "skill-manager", "T", "20260819")) {
                assertFalse(DurableCliPin.isVersionSegment(not), not + " is not a version");
            }
            // The positive control this file's zeros are worthless without.
            for (String is : List.of("0.23.0", "0.24.0_1", "v1.2.3", "1.4.0-rc2",
                    "skill-manager-0.24.0", "3.11")) {
                assertTrue(DurableCliPin.isVersionSegment(is), is + " IS a version");
            }
        });

        suite.test("the library verdict and the command agree about one home", () -> {
            // GOAL-one-home-one-answer again, on the other pair of readers:
            // `HomeCloner.verify` is the library and `home verify` is the
            // command, and the whole of DEF-012's exit-0 was those two agreeing
            // about a home neither had asked the right question about.
            Brew brew = Brew.install("0.23.0");
            Path versioned = brew.home("versioned");
            writeVersionedPin(versioned, brew.located());
            brew.upgrade("0.24.0");

            HomeCloner.Verification v = HomeCloner.verify(versioned, false);

            assertEquals(1, v.danglingCliPins().size(),
                    "the library reports it: " + v.danglingCliPins());
            assertContains(v.danglingCliPins().get(0), "bin/cli/skill-manager",
                    "naming the subject home-relatively");
            assertEquals(1, verify(versioned).rc(), "and the command exits on it");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A Homebrew prefix, its kegs and its two aliases — the link shape measured
     * on the machine DEF-012 happened on, and the {@code brew upgrade} that
     * broke it.
     */
    private static final class Brew {
        private final Path sandbox;
        private final Path prefix;
        /** Mutable on purpose: {@link #upgrade} moves the installation forward. */
        private String version;

        private Brew(Path sandbox, Path prefix, String version) {
            this.sandbox = sandbox;
            this.prefix = prefix;
            this.version = version;
        }

        static Brew install(String version) throws Exception {
            return install(version, "prefix");
        }

        /**
         * As {@link #install(String)}, with the prefix DIRECTORY NAME chosen.
         *
         * <p>Exists for one case: a prefix that itself names a version
         * ({@code brew-3.6.0}), which is the only shape in which a DERIVABLE
         * candidate carries a version segment and so the only shape that can
         * reach the version gate now that the PATH arm is gone.
         */
        static Brew install(String version, String prefixName) throws Exception {
            Path sandbox = Files.createTempDirectory("his19-brew-");
            Brew brew = new Brew(sandbox,
                    Files.createDirectories(sandbox.resolve(prefixName)), version);
            brew.installKeg(version);
            brew.link(version);
            return brew;
        }

        Path sandbox() { return sandbox; }

        Path prefix() { return prefix; }

        Path keg(String version) {
            return prefix.resolve("Cellar").resolve("skill-manager").resolve(version);
        }

        /** What {@code RunningCli.locate} answers on a Homebrew install. */
        Path located() { return keg(version).resolve("bin/skill-manager"); }

        private void installKeg(String version) throws Exception {
            Path keg = keg(version);
            executable(keg.resolve("libexec/bin/skill-manager"), version);
            Files.createDirectories(keg.resolve("bin"));
            Files.createSymbolicLink(keg.resolve("bin/skill-manager"),
                    Path.of("../libexec/bin/skill-manager"));
        }

        private void link(String version) throws Exception {
            Files.createDirectories(prefix.resolve("bin"));
            Files.createDirectories(prefix.resolve("opt"));
            Files.createSymbolicLink(prefix.resolve("bin/skill-manager"),
                    Path.of("../Cellar/skill-manager/" + version + "/bin/skill-manager"));
            Files.createSymbolicLink(prefix.resolve("opt/skill-manager"),
                    Path.of("../Cellar/skill-manager/" + version));
        }

        /**
         * {@code brew upgrade}: install the new keg, DELETE the old one, and
         * re-point both aliases at it. The deletion is the whole defect — the
         * pin's target stops existing while the pin does not.
         */
        void upgrade(String to) throws Exception {
            installKeg(to);
            Files.delete(prefix.resolve("bin/skill-manager"));
            Files.delete(prefix.resolve("opt/skill-manager"));
            deleteTree(keg(version));
            version = to;
            link(to);
        }

        /** Install a keg WITHOUT linking it — a second version side by side. */
        void installKegOnly(String version) throws Exception { installKeg(version); }

        /**
         * Re-point both aliases at an already-installed keg, deleting nothing.
         *
         * <p>`brew link --overwrite skill-manager@<older>`, or a `brew` downgrade.
         * Distinct from {@link #upgrade}, which also DELETES the old keg: here
         * both builds stay on disk and only the alias moves.
         */
        void relink(String to) throws Exception {
            Files.delete(prefix.resolve("bin/skill-manager"));
            Files.delete(prefix.resolve("opt/skill-manager"));
            version = to;
            link(to);
        }

        /** A fresh Skill Manager home under this sandbox. */
        Path home(String name) throws Exception {
            Path root = Files.createDirectories(sandbox.resolve("homes").resolve(name));
            SkillStore store = new SkillStore(root.resolve(".skill-manager"));
            store.init();
            return store.root();
        }
    }

    /**
     * The pre-HIS-19 bytes: {@code LauncherShims.write}'s output with the pin
     * left exactly as the running build was located.
     *
     * <p>Written through {@code cliScript} — the same generator production uses
     * — so the ONLY difference between this file and a durable one is the path
     * on the pin line. A hand-written control would differ in ways the claim is
     * not about.
     */
    private static void writeVersionedPin(Path store, Path located) throws Exception {
        LauncherShims.write(new SkillStore(store), located);
        Path entrypoint = entrypoint(store);
        Files.writeString(entrypoint, LauncherShims.cliScript(located));
        entrypoint.toFile().setExecutable(true);
        // The control asserts its own precondition: this really is the
        // versioned shape, or the pair below is not discriminating at all.
        Path pin = LauncherShims.pinnedCliIn(entrypoint).orElse(null);
        assertEquals(located, pin, "the control pins the located, versioned build");
    }

    private static Path entrypoint(Path store) {
        return store.resolve("bin").resolve("cli").resolve("skill-manager");
    }

    private static List<HomeRepair.Finding> danglingPins(HomeRepair.Report report) {
        return report.findings().stream()
                .filter(f -> f.kind() == HomeRepair.Kind.DANGLING_CLI_PIN)
                .toList();
    }

    /** A stand-in build that prints which version it is. */
    private static Path executable(Path file, String version) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/usr/bin/env bash\nprintf 'BUILD %s\\n' '" + version + "'\n");
        file.toFile().setExecutable(true);
        return file;
    }

    private record Result(int rc, String out, String err) {}

    /**
     * Run a generated entrypoint with a hermetic environment.
     *
     * <p>{@code SKILL_MANAGER_HOME} and {@code SKILL_MANAGER_CLI} are cleared
     * explicitly rather than inherited: the entrypoint refuses a home it was not
     * told to edit (exit 79) and honours an explicit CLI over its pin, so a
     * suite run from inside a worktree home would measure the environment
     * instead of the pin. DEF-074, as a fixture.
     */
    private static Result run(Path script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(script.toString());
        pb.environment().remove("SKILL_MANAGER_HOME");
        pb.environment().remove("SKILL_MANAGER_CLI");
        pb.environment().put("PATH", "/usr/bin:/bin");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("the entrypoint did not terminate: " + script);
        }
        String out = new String(p.getInputStream().readAllBytes());
        return new Result(p.exitValue(), out, out);
    }

    /** {@code skill-manager home shims --home <store>}, with its output captured. */
    private static Result shims(Path store, Path pin, String... extra) throws Exception {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<String> argv = new java.util.ArrayList<>(List.of("--home", store.toString()));
        argv.addAll(List.of(extra));
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            // ShimsCmd resolves the build through RunningCli, which cannot find
            // a launcher under a test runner — so the fixture build is injected,
            // the same seam RepairCmd carries and for the same reason. The
            // COMMAND is what is under test here, not LauncherShims.
            int rc = new CommandLine(new HomeCommand.ShimsCmd(null, pin))
                    .execute(argv.toArray(new String[0]));
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }

    /** The path on the run's {@code pinned CLI:} line, or null. */
    private static String reportedPin(String out) {
        for (String line : out.split("\n", -1)) {
            int at = line.indexOf("pinned CLI:");
            if (at >= 0) return line.substring(at + "pinned CLI:".length()).strip();
        }
        return null;
    }

    /** {@code skill-manager home verify --home <store>}, with its output captured. */
    private static Result verify(Path store) throws Exception {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            int rc = new CommandLine(new HomeCommand())
                    .execute("verify", "--home", store.toString());
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
