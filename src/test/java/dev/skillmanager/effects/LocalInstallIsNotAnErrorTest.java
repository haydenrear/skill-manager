package dev.skillmanager.effects;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;

import java.util.List;
import java.util.Optional;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * <b>A deliberate {@code file:} install is a state, not an outstanding error.</b>
 *
 * <h2>The incoherent contract this pins shut</h2>
 *
 * <p>{@code skill-project.toml} accepts {@code source = "file:///abs/path"}.
 * {@code project resolve} installed it and exited 0, printing
 * {@code ✓ installed <unit>} and a summary. From then on <b>every</b>
 * invocation — {@code list}, {@code --help}, {@code exec},
 * {@code home describe}, {@code bindings list} — appended
 *
 * <pre>
 * ⚠ skills with outstanding errors (1) — 1 distinct cause(s) — re-run after fixing:
 *   &lt;unit&gt;:
 *     - NEEDS_GIT_MIGRATION: not git-tracked; file/local installs do not sync …
 * </pre>
 *
 * <p>and {@code sync} exited non-zero because of it, with a remedy
 * ("reinstall from a git source") that undoes the thing the operator asked
 * for. Accept, install, celebrate, then error forever is not a contract.
 *
 * <h2>The companion — the discriminator, not just the absence</h2>
 *
 * <p>"No error was recorded" is what a handler that stopped recording
 * anything would also produce. So every case here has its twin: the same
 * on-disk shape (a store directory with no {@code .git}) reached by ACCIDENT
 * rather than by choice — a unit whose provenance is {@code UNKNOWN}, which is
 * what the reconciler writes for a directory found in the store — must still
 * record the error, in the same run. The property under test is that the two
 * are told apart, and a fix that simply deleted the error would fail the
 * second half of every case below.
 */
public final class LocalInstallIsNotAnErrorTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("LocalInstallIsNotAnErrorTest");

        suite.test("sync of a file:-installed unit records no error and says why", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("loc-unit", UnitKind.SKILL);
            write(h, "loc-unit", InstalledUnit.InstallSource.LOCAL_FILE, List.of());

            EffectReceipt r = h.run(syncOf("loc-unit", InstalledUnit.InstallSource.LOCAL_FILE));

            assertEquals(EffectStatus.OK, r.status(),
                    "a local install has nothing upstream, which is not a partial sync");
            assertTrue(r.facts().stream()
                            .anyMatch(f -> f instanceof ContextFact.SyncGitLocalInstall),
                    "and it still SAYS so, so 'this will not update' stays visible");
            assertFalse(hasMigrationError(h, "loc-unit"),
                    "no outstanding error record: " + errorsOf(h, "loc-unit"));
        });

        suite.test("COMPANION: the same shape with no provenance still records the error", () -> {
            // Identical on disk — a store directory with no .git. The only
            // difference is that nobody chose it.
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("found-unit", UnitKind.SKILL);
            write(h, "found-unit", InstalledUnit.InstallSource.UNKNOWN, List.of());

            EffectReceipt r = h.run(syncOf("found-unit", InstalledUnit.InstallSource.UNKNOWN));

            assertEquals(EffectStatus.PARTIAL, r.status(),
                    "a unit that cannot sync and nobody chose is still a partial sync");
            assertTrue(r.facts().stream()
                            .anyMatch(f -> f instanceof ContextFact.SyncGitNotGitTracked),
                    "and keeps the fact that names the problem");
            assertTrue(hasMigrationError(h, "found-unit"),
                    "the error record survives for the case it was written for");
        });

        suite.test("a home that ALREADY carries the record heals itself", () -> {
            // The 'forever' half. Every file: install made before this change
            // carries the record, and nothing would ever have cleared it:
            // the old probe was `is it a git repo now`, and a local install
            // never becomes one.
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("stale-unit", UnitKind.SKILL);
            write(h, "stale-unit", InstalledUnit.InstallSource.LOCAL_FILE,
                    List.of(migrationError()));
            assertTrue(hasMigrationError(h, "stale-unit"), "precondition: the record is there");

            EffectReceipt r = h.run(new SkillEffect.ValidateAndClearError(
                    "stale-unit", InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION));

            assertEquals(EffectStatus.OK, r.status(), "the probe ran");
            assertFalse(hasMigrationError(h, "stale-unit"),
                    "and cleared it: " + errorsOf(h, "stale-unit"));
        });

        suite.test("COMPANION: the self-heal does not clear a record it cannot account for", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("stale-found", UnitKind.SKILL);
            write(h, "stale-found", InstalledUnit.InstallSource.UNKNOWN,
                    List.of(migrationError()));

            h.run(new SkillEffect.ValidateAndClearError(
                    "stale-found", InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION));

            assertTrue(hasMigrationError(h, "stale-found"),
                    "a provenance-less unit is still not git-tracked and still cannot sync");
        });

        return suite.runAll();
    }

    // --------------------------------------------------------------- helpers

    private static SkillEffect.SyncGit syncOf(String name, InstalledUnit.InstallSource source) {
        return new SkillEffect.SyncGit(name, UnitKind.SKILL, source, false, false);
    }

    private static InstalledUnit.UnitError migrationError() {
        return new InstalledUnit.UnitError(
                InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION,
                "not git-tracked; file/local installs do not sync — reinstall from github: "
                        + "or git+ source, or add a git remote",
                UnitStore.nowIso());
    }

    private static void write(TestHarness h, String name, InstalledUnit.InstallSource source,
                              List<InstalledUnit.UnitError> errors) throws Exception {
        new UnitStore(h.store()).write(new InstalledUnit(
                name, "0.1.0", InstalledUnit.Kind.LOCAL_DIR, source,
                source == InstalledUnit.InstallSource.LOCAL_FILE ? "file:///tmp/" + name : null,
                null, null, UnitStore.nowIso(), errors, UnitKind.SKILL));
    }

    private static List<InstalledUnit.UnitError> errorsOf(TestHarness h, String name)
            throws Exception {
        Optional<InstalledUnit> unit = new UnitStore(h.store()).read(name);
        return unit.map(InstalledUnit::errors).orElse(List.of());
    }

    private static boolean hasMigrationError(TestHarness h, String name) throws Exception {
        return errorsOf(h, name).stream()
                .anyMatch(e -> e.kind() == InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION);
    }
}
