package dev.skillmanager.lock;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.effects.Compensation;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-10b contract #3: a partial-failure rollback leaves the lock
 * byte-identical to the pre-program state.
 *
 * <p>Two scenarios:
 * <ol>
 *   <li><b>UpdateUnitsLock then fault</b>: the lock was successfully
 *       written, then a downstream effect failed. The Executor's LIFO
 *       walk runs {@link Compensation.RestoreUnitsLock} which atomically
 *       writes the pre-image back. Post-rollback bytes match the
 *       pre-program file.</li>
 *   <li><b>Fault before UpdateUnitsLock</b>: an earlier effect failed,
 *       so UpdateUnitsLock never ran. The lock is untouched on disk.
 *       No compensation needed.</li>
 * </ol>
 */
public final class LockAtomicityTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("LockAtomicityTest");

        suite.test("UpdateUnitsLock + fault → byte-identical pre-image restored", () -> {
            TestHarness h = TestHarness.create();
            Path lockPath = h.store().root().resolve(UnitsLockReader.FILENAME);

            // Pre-state: lock with one row.
            UnitsLock pre = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    new LockedUnit("alpha", UnitKind.SKILL, "1.0.0",
                            InstalledUnit.InstallSource.GIT, "u", "main", "sha-pre")));
            UnitsLockWriter.atomicWrite(pre, lockPath);
            String preBytes = Files.readString(lockPath);

            // Program: UpdateUnitsLock(target=different) at idx 0, fault at idx 1.
            UnitsLock target = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    new LockedUnit("alpha", UnitKind.SKILL, "2.0.0",
                            InstalledUnit.InstallSource.GIT, "u", "main", "sha-new"),
                    new LockedUnit("beta", UnitKind.SKILL, "1.0.0",
                            InstalledUnit.InstallSource.GIT, "u", "main", "sha-b")));
            Program<Void> program = new Program<>(
                    "atomicity-rollback",
                    List.of(
                            new SkillEffect.UpdateUnitsLock(target, lockPath),
                            new SkillEffect.AddUnitError("ghost",
                                    InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE, "trigger")
                    ),
                    receipts -> null);

            Executor.Outcome<Void> outcome = new Executor(h.store(), null)
                    .withFaultInjection(i -> i == 1)
                    .runWithContext(program, h.context());

            assertTrue(outcome.rolledBack(), "rollback fired");
            // Bytes match — RestoreUnitsLock wrote the original back.
            String postBytes = Files.readString(lockPath);
            assertEquals(preBytes, postBytes, "lock bytes byte-identical to pre-program state");

            // And the parsed view is the pre-image too.
            UnitsLock post = UnitsLockReader.read(lockPath);
            assertEquals(1, post.units().size(), "still one row");
            assertEquals("sha-pre", post.get("alpha").orElseThrow().resolvedSha(),
                    "alpha sha back to pre");
            assertFalse(post.get("beta").isPresent(), "beta not present");
        });

        suite.test("fault BEFORE UpdateUnitsLock → lock untouched on disk", () -> {
            TestHarness h = TestHarness.create();
            Path lockPath = h.store().root().resolve(UnitsLockReader.FILENAME);

            UnitsLock pre = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    new LockedUnit("alpha", UnitKind.SKILL, "1.0.0",
                            InstalledUnit.InstallSource.GIT, "u", "main", "sha-pre")));
            UnitsLockWriter.atomicWrite(pre, lockPath);
            String preBytes = Files.readString(lockPath);

            UnitsLock target = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    new LockedUnit("alpha", UnitKind.SKILL, "2.0.0",
                            InstalledUnit.InstallSource.GIT, "u", "main", "sha-new")));
            Program<Void> program = new Program<>(
                    "atomicity-pre-fault",
                    List.of(
                            new SkillEffect.AddUnitError("ghost",
                                    InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE, "trigger"),
                            new SkillEffect.UpdateUnitsLock(target, lockPath)
                    ),
                    receipts -> null);

            Executor.Outcome<Void> outcome = new Executor(h.store(), null)
                    .withFaultInjection(i -> i == 0)
                    .runWithContext(program, h.context());

            assertTrue(outcome.rolledBack(), "rollback fired");
            // Lock never touched.
            String postBytes = Files.readString(lockPath);
            assertEquals(preBytes, postBytes, "lock bytes unchanged — UpdateUnitsLock never ran");
        });

        suite.test("clean run (no fault) → lock advances to target, persists", () -> {
            TestHarness h = TestHarness.create();
            Path lockPath = h.store().root().resolve(UnitsLockReader.FILENAME);

            UnitsLock target = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    new LockedUnit("alpha", UnitKind.SKILL, "1.0.0",
                            InstalledUnit.InstallSource.GIT, "u", "main", "sha-1")));
            Program<Void> program = new Program<>(
                    "atomicity-clean",
                    List.of(new SkillEffect.UpdateUnitsLock(target, lockPath)),
                    receipts -> null);

            Executor.Outcome<Void> outcome = new Executor(h.store(), null)
                    .runWithContext(program, h.context());

            assertFalse(outcome.rolledBack(), "no rollback");
            UnitsLock post = UnitsLockReader.read(lockPath);
            assertEquals("sha-1", post.get("alpha").orElseThrow().resolvedSha(),
                    "lock advanced to target");
        });

        return suite.runAll();
    }
}
