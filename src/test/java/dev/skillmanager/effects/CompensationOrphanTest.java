package dev.skillmanager.effects;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.shared.util.Fs;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-09b contract: {@code *_IfOrphan} compensations consult the live
 * store at apply time and no-op when a surviving installed unit still
 * claims the dep. This is what stops a partial install of A from
 * tearing down B's CLI dep just because B happened to install the same
 * dep first.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code Executor.isClaimedByOtherUnit} — true when a sibling
 *       skill declares the same CLI dep; false when the rolled-back
 *       unit was the sole claimant.</li>
 *   <li>{@code Executor.isMcpClaimedByOtherUnit} — same for MCP.</li>
 *   <li>{@code applyCompensation(UninstallCliIfOrphan)} — leaves the
 *       lock entry intact when a sibling claims the dep; drops it
 *       when the rolled-back unit was alone.</li>
 * </ul>
 */
public final class CompensationOrphanTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("CompensationOrphanTest");

        suite.test("isClaimedByOtherUnit: sibling skill declaring same dep is visible", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "survivor", DepSpec.of().cli("pip:cowsay==6.0").build());
            installSkill(h, "rolled-back", DepSpec.of().cli("pip:cowsay==6.0").build());

            Executor exec = new Executor(h.store(), null);
            assertTrue(exec.isClaimedByOtherUnit("rolled-back", "cowsay"),
                    "survivor's claim is visible");
        });

        suite.test("isClaimedByOtherUnit: no other claimant → false", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "rolled-back", DepSpec.of().cli("pip:cowsay==6.0").build());

            Executor exec = new Executor(h.store(), null);
            assertFalse(exec.isClaimedByOtherUnit("rolled-back", "cowsay"),
                    "no surviving claimant — compensation should run");
        });

        suite.test("isClaimedByOtherUnit: rolled-back unit excluded from its own claim", () -> {
            // Sanity: even though listInstalled returns the rolled-back unit
            // (it's still in skills/ — DeleteUnitDir runs LIFO before this
            // check in the real walk), the helper must exclude it by name.
            TestHarness h = TestHarness.create();
            installSkill(h, "rolled-back", DepSpec.of().cli("pip:cowsay==6.0").build());

            Executor exec = new Executor(h.store(), null);
            assertFalse(exec.isClaimedByOtherUnit("rolled-back", "cowsay"),
                    "rolled-back unit's own claim doesn't count as a surviving claimant");
        });

        suite.test("isMcpClaimedByOtherUnit: sibling skill declaring same MCP server is visible", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "survivor", DepSpec.of().mcp("srv-a").build());
            installSkill(h, "rolled-back", DepSpec.of().mcp("srv-a").build());

            Executor exec = new Executor(h.store(), null);
            assertTrue(exec.isMcpClaimedByOtherUnit("rolled-back", "srv-a"),
                    "survivor's MCP claim is visible");
        });

        suite.test("isMcpClaimedByOtherUnit: no other claimant → false", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "rolled-back", DepSpec.of().mcp("srv-a").build());

            Executor exec = new Executor(h.store(), null);
            assertFalse(exec.isMcpClaimedByOtherUnit("rolled-back", "srv-a"),
                    "no surviving MCP claimant — compensation should run");
        });

        // ------------------------------------- applyCompensation no-op vs apply

        suite.test("applyCompensation(UninstallCliIfOrphan) is a no-op when survivor claims dep", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "survivor", DepSpec.of().cli("pip:cowsay==6.0").build());
            installSkill(h, "rolled-back", DepSpec.of().cli("pip:cowsay==6.0").build());

            // Pre-state: lock declares cowsay claimed by both.
            Path binary = touchCliBin(h, "cowsay");
            CliLock lock = CliLock.load(h.store());
            lock.recordInstall("pip", "cowsay", "6.0", "pip:cowsay==6.0", null, "survivor");
            lock.recordInstall("pip", "cowsay", "6.0", "pip:cowsay==6.0", null, "rolled-back");
            lock.save(h.store());

            CliDependency dep = pip("cowsay", "6.0");
            Executor exec = new Executor(h.store(), null);
            exec.applyCompensation(
                    new Compensation.UninstallCliIfOrphan("rolled-back", dep), h.context());

            CliLock after = CliLock.load(h.store());
            CliLock.Entry entry = after.get("pip", "cowsay");
            assertNotNull(entry, "lock entry survives — survivor still claims cowsay");
            assertEquals(java.util.List.of("survivor"), entry.requestedBy(),
                    "rolled-back requester removed from shared lock entry");
            assertTrue(Files.exists(binary), "shared binary remains");
        });

        suite.test("applyCompensation(UninstallCliIfOrphan) drops lock when no survivor claims dep", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "rolled-back", DepSpec.of().cli("pip:cowsay==6.0").build());
            // Important: simulate the rolled-back unit's bytes being torn
            // down before the CLI compensation runs (the journal walks
            // DeleteUnitDir LIFO before UninstallCliIfOrphan since the
            // latter was recorded later). isClaimedByOtherUnit checks
            // listInstalled() which now excludes the rolled-back unit.
            Fs.deleteRecursive(h.store().skillDir("rolled-back"));

            CliLock lock = CliLock.load(h.store());
            lock.recordInstall("pip", "cowsay", "6.0", "pip:cowsay==6.0", null, "rolled-back");
            lock.save(h.store());
            Path binary = touchCliBin(h, "cowsay");

            CliDependency dep = pip("cowsay", "6.0");
            Executor exec = new Executor(h.store(), null);
            exec.applyCompensation(
                    new Compensation.UninstallCliIfOrphan("rolled-back", dep), h.context());

            CliLock after = CliLock.load(h.store());
            assertEquals(null, after.get("pip", "cowsay"),
                    "lock entry dropped — no surviving claimant");
            assertFalse(Files.exists(binary), "orphaned CLI binary removed");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- helpers

    /**
     * Set up a SKILL unit on disk under {@code skills/<name>/} so
     * {@code store.listInstalled()} surfaces it through {@code SkillParser}
     * with the declared deps.
     */
    private static void installSkill(TestHarness h, String name, DepSpec deps) throws Exception {
        Path tmp = Files.createTempDirectory("orphan-test-fixture-");
        AgentUnit u = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp, name, deps);
        Path src = u.sourcePath();
        Path dst = h.store().skillDir(name);
        Fs.ensureDir(h.store().skillsDir());
        Fs.copyRecursive(src, dst);
    }

    private static CliDependency pip(String name, String version) {
        return new CliDependency(name, "pip:" + name + "==" + version,
                null, null, null, true, java.util.Map.of());
    }

    private static Path touchCliBin(TestHarness h, String name) throws Exception {
        Fs.ensureDir(h.store().cliBinDir());
        Path binary = h.store().cliBinDir().resolve(name);
        Files.writeString(binary, "#!/usr/bin/env sh\n");
        binary.toFile().setExecutable(true, false);
        return binary;
    }
}
