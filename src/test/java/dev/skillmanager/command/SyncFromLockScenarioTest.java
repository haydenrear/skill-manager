package dev.skillmanager.command;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.LockCommand;
import dev.skillmanager.lock.LockDiff;
import dev.skillmanager.lock.LockedUnit;
import dev.skillmanager.lock.UnitsLock;
import dev.skillmanager.lock.UnitsLockReader;
import dev.skillmanager.lock.UnitsLockWriter;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-10c contract #8: idempotence — {@code sync --lock <path>}
 * applied twice yields the same disk state. Plus the read-only paths
 * {@code lock status} and {@code sync --refresh} surface drift correctly.
 *
 * <p>The scenario test focuses on the contract (idempotence + drift
 * detection) without exercising the full Resolver-driven install path
 * for missing units — that reverse-engineering of install coords from
 * a {@link LockedUnit} row's origin/ref is its own ticket. What's
 * pinned here:
 *
 * <ul>
 *   <li>{@link LockCommand#readLiveState} reflects what's actually in
 *       {@code installed/}.</li>
 *   <li>{@link LockDiff#between}(lock, live) is empty when disk and
 *       lock agree — the idempotence baseline.</li>
 *   <li>{@code sync --refresh} writes a lock that round-trips equal to
 *       the live state, so a follow-up {@code lock status} is clean.</li>
 *   <li>Drift bucketing: added / removed / bumped each surface from the
 *       right pre-state.</li>
 * </ul>
 *
 * <p>Sweep: {@code (UnitKind × pre-state shape × dep mix)} per the spec.
 * Kinds are SKILL only here for the same pre-ticket-11 reason as the
 * earlier orphan tests — plugin contained-skill claims aren't in
 * {@code listInstalled} until ticket 11. The lock-side coverage is kind-
 * agnostic by design (LockedUnit carries {@code kind}); end-to-end live
 * coverage of plugins waits for the listing widening.
 */
public final class SyncFromLockScenarioTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("SyncFromLockScenarioTest");

        // ----------------------------------------------- idempotence baseline

        suite.test("empty store + empty lock → no drift (idempotent baseline)", () -> {
            TestHarness h = TestHarness.create();
            UnitsLock live = LockCommand.readLiveState(h.store());
            UnitsLock onDisk = UnitsLockReader.read(UnitsLockReader.defaultPath(h.store()));
            assertTrue(LockDiff.between(onDisk, live).isEmpty(),
                    "empty store + empty lock are in sync");
        });

        suite.test("install + refresh + status → empty diff (round-trip idempotent)", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "alpha", DepSpec.empty(), "1.0.0", "sha-a");

            // sync --refresh: write live state to lock.
            UnitsLock live = LockCommand.readLiveState(h.store());
            UnitsLockWriter.atomicWrite(live, UnitsLockReader.defaultPath(h.store()));

            // lock status would now show no drift.
            UnitsLock onDisk = UnitsLockReader.read(UnitsLockReader.defaultPath(h.store()));
            assertTrue(LockDiff.between(onDisk, live).isEmpty(),
                    "post-refresh: lock and live agree");

            // Re-running refresh produces the same lock bytes.
            String first = Files.readString(UnitsLockReader.defaultPath(h.store()));
            UnitsLock live2 = LockCommand.readLiveState(h.store());
            UnitsLockWriter.atomicWrite(live2, UnitsLockReader.defaultPath(h.store()));
            String second = Files.readString(UnitsLockReader.defaultPath(h.store()));
            assertEquals(first, second, "refresh is idempotent — same bytes both times");
        });

        // ----------------------------------------------- drift detection

        suite.test("install without lock update → status shows added drift", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "alpha", DepSpec.empty(), "1.0.0", "sha-a");

            // Lock is empty (we didn't write it after install).
            UnitsLock live = LockCommand.readLiveState(h.store());
            UnitsLock onDisk = UnitsLockReader.read(UnitsLockReader.defaultPath(h.store()));
            LockDiff drift = LockDiff.between(onDisk, live);

            assertFalse(drift.isEmpty(), "drift detected");
            assertEquals(1, drift.added().size(), "alpha is added in live state");
            assertEquals("alpha", drift.added().get(0).name(), "alpha by name");
            assertEquals(0, drift.removed().size(), "nothing removed");
            assertEquals(0, drift.bumped().size(), "no bump");
        });

        suite.test("uninstall without lock update → status shows removed drift", () -> {
            TestHarness h = TestHarness.create();
            // Pre-state: lock claims alpha is installed; disk doesn't have it.
            UnitsLock seeded = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    new LockedUnit("alpha", UnitKind.SKILL, "1.0.0",
                            InstalledUnit.InstallSource.GIT, "u", "main", "sha-a")));
            UnitsLockWriter.atomicWrite(seeded, UnitsLockReader.defaultPath(h.store()));

            UnitsLock live = LockCommand.readLiveState(h.store());
            UnitsLock onDisk = UnitsLockReader.read(UnitsLockReader.defaultPath(h.store()));
            LockDiff drift = LockDiff.between(onDisk, live);

            assertEquals(1, drift.removed().size(), "alpha removed from live");
            assertEquals("alpha", drift.removed().get(0).name(), "alpha by name");
        });

        suite.test("sha drift → status shows bumped drift", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "alpha", DepSpec.empty(), "1.0.0", "sha-disk");

            // Lock has the same unit but with an older sha.
            UnitsLock seeded = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    new LockedUnit("alpha", UnitKind.SKILL, "1.0.0",
                            InstalledUnit.InstallSource.GIT, null, null, "sha-old")));
            UnitsLockWriter.atomicWrite(seeded, UnitsLockReader.defaultPath(h.store()));

            UnitsLock live = LockCommand.readLiveState(h.store());
            UnitsLock onDisk = UnitsLockReader.read(UnitsLockReader.defaultPath(h.store()));
            LockDiff drift = LockDiff.between(onDisk, live);

            assertEquals(1, drift.bumped().size(), "sha drift bumped");
            LockDiff.Bump b = drift.bumped().get(0);
            assertEquals("sha-old", b.before().resolvedSha(), "before sha");
            assertEquals("sha-disk", b.after().resolvedSha(), "after sha");
        });

        // ----------------------------------------------- mixed sweep

        suite.test("mixed: added + removed + bumped surface together", () -> {
            TestHarness h = TestHarness.create();
            // Disk has alpha (sha-disk) and beta (newly installed, no lock).
            installSkill(h, "alpha", DepSpec.empty(), "1.0.0", "sha-disk");
            installSkill(h, "beta", DepSpec.empty(), "1.0.0", "sha-b");

            // Lock claims alpha (sha-old, drifted) and gone (uninstalled).
            UnitsLock seeded = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    new LockedUnit("alpha", UnitKind.SKILL, "1.0.0",
                            InstalledUnit.InstallSource.GIT, null, null, "sha-old"),
                    new LockedUnit("gone", UnitKind.SKILL, "1.0.0",
                            InstalledUnit.InstallSource.GIT, "u", "main", "sha-g")));
            UnitsLockWriter.atomicWrite(seeded, UnitsLockReader.defaultPath(h.store()));

            UnitsLock live = LockCommand.readLiveState(h.store());
            UnitsLock onDisk = UnitsLockReader.read(UnitsLockReader.defaultPath(h.store()));
            LockDiff drift = LockDiff.between(onDisk, live);

            assertEquals(1, drift.added().size(), "beta added (in live, not in lock)");
            assertEquals("beta", drift.added().get(0).name(), "beta");
            assertEquals(1, drift.removed().size(), "gone removed");
            assertEquals("gone", drift.removed().get(0).name(), "gone");
            assertEquals(1, drift.bumped().size(), "alpha bumped");
            assertEquals("alpha", drift.bumped().get(0).before().name(), "alpha bump");
        });

        // ----------------------------------------------- refresh writes deterministic bytes

        suite.test("refresh against multiple installs writes deterministic, sorted output", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "zeta", DepSpec.empty(), "1.0.0", "sha-z");
            installSkill(h, "alpha", DepSpec.empty(), "1.0.0", "sha-a");
            installSkill(h, "middle", DepSpec.empty(), "1.0.0", "sha-m");

            UnitsLock live = LockCommand.readLiveState(h.store());
            UnitsLockWriter.atomicWrite(live, UnitsLockReader.defaultPath(h.store()));

            String content = Files.readString(UnitsLockReader.defaultPath(h.store()));
            int alphaIdx = content.indexOf("name = \"alpha\"");
            int middleIdx = content.indexOf("name = \"middle\"");
            int zetaIdx = content.indexOf("name = \"zeta\"");
            assertTrue(alphaIdx >= 0 && middleIdx > alphaIdx && zetaIdx > middleIdx,
                    "rows sorted alphabetically");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- helpers

    /**
     * "Install" a skill into the store: copy the scaffolded bytes into
     * {@code skills/<name>/} and write an {@code installed/<name>.json}
     * record with the supplied resolved-sha. Mirrors what the install
     * pipeline produces, minus the side effects (CLI install, MCP
     * register, agent symlinks).
     */
    private static void installSkill(TestHarness h, String name, DepSpec deps,
                                      String version, String sha) throws Exception {
        Path tmp = Files.createTempDirectory("sync-from-lock-fixture-");
        AgentUnit u = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp, name, deps);
        Path src = u.sourcePath();
        Path dst = h.store().skillDir(name);
        Fs.ensureDir(h.store().skillsDir());
        Fs.copyRecursive(src, dst);

        InstalledUnit rec = new InstalledUnit(
                name, version, InstalledUnit.Kind.GIT,
                InstalledUnit.InstallSource.GIT,
                null, sha, null, UnitStore.nowIso(), null,
                UnitKind.SKILL);
        new UnitStore(h.store()).write(rec);
    }
}
