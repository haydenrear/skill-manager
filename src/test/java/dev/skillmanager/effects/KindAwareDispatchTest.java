package dev.skillmanager.effects;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-08 contract: handlers that needed kind-aware dispatch route to
 * the right on-disk location based on the unit's {@link UnitKind}.
 *
 * <p>Three pin points covered here:
 * <ul>
 *   <li>{@link SkillEffect.RemoveUnitFromStore}: SKILL → {@code skills/<name>},
 *       PLUGIN → {@code plugins/<name>}.</li>
 *   <li>{@link SkillEffect.UnlinkAgentUnit}: handler picks the right base
 *       (agent.skillsDir / agent.pluginsDir) per kind. Tested at the
 *       record level — exercising the live agent dirs requires a writable
 *       CLAUDE_HOME, deferred to integration coverage.</li>
 *   <li>{@link SkillEffect.RejectIfAlreadyInstalled}: was pinned in ticket 06
 *       (HandlerSubstitutabilityTest); kind-aware halt path verified there.</li>
 * </ul>
 *
 * <p>Plugin install end-to-end (graph → CommitUnitsToStore lands files in
 * {@code plugins/<name>}) is exercised in {@link HandlerSubstitutabilityTest}
 * via the existing scaffold helpers; pinning it here would duplicate that
 * coverage. The test focus here is the divergence — that the handler
 * picks {@code plugins/} for PLUGIN units, never {@code skills/}.
 */
public final class KindAwareDispatchTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("KindAwareDispatchTest");

        suite.test("RemoveUnitFromStore(SKILL) deletes skills/<name>", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("widget", UnitKind.SKILL);
            Path skillDir = h.store().skillDir("widget");
            assertTrue(Files.isDirectory(skillDir), "precondition: skills/widget exists");

            EffectReceipt r = h.run(new SkillEffect.RemoveUnitFromStore("widget", UnitKind.SKILL));
            assertEquals(EffectStatus.OK, r.status(), "remove status");
            assertFalse(Files.exists(skillDir), "skills/widget removed");
        });

        suite.test("RemoveUnitFromStore(PLUGIN) deletes plugins/<name>, leaves skills/ untouched", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("widget", UnitKind.PLUGIN);
            Path pluginDir = h.store().pluginsDir().resolve("widget");
            Path skillDir = h.store().skillDir("widget");
            assertTrue(Files.isDirectory(pluginDir), "precondition: plugins/widget exists");
            assertFalse(Files.exists(skillDir), "precondition: skills/widget does NOT exist");

            EffectReceipt r = h.run(new SkillEffect.RemoveUnitFromStore("widget", UnitKind.PLUGIN));
            assertEquals(EffectStatus.OK, r.status(), "remove status");
            assertFalse(Files.exists(pluginDir), "plugins/widget removed");
            assertFalse(Files.exists(skillDir), "skills/widget still does NOT exist");
        });

        suite.test("RemoveUnitFromStore(PLUGIN) when only skills/<name> exists is a no-op", () -> {
            // Asymmetry guard: if a PLUGIN-kind effect fires for a name
            // that's actually under skills/, the handler must NOT reach
            // into skills/ — that would be a kind-mismatch deletion bug.
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("widget", UnitKind.SKILL);
            Path skillDir = h.store().skillDir("widget");

            EffectReceipt r = h.run(new SkillEffect.RemoveUnitFromStore("widget", UnitKind.PLUGIN));
            assertEquals(EffectStatus.SKIPPED, r.status(), "skipped (plugins/widget absent)");
            assertTrue(Files.isDirectory(skillDir), "skills/widget left intact");
        });

        suite.test("UnlinkAgentUnit field carries kind for handler dispatch", () -> {
            // Record-level pin: handler reads e.kind() to pick agent.skillsDir
            // vs agent.pluginsDir. Exercising the live agent dirs requires a
            // writable CLAUDE_HOME / CODEX_HOME (integration coverage).
            SkillEffect.UnlinkAgentUnit skill = new SkillEffect.UnlinkAgentUnit("claude", "widget", UnitKind.SKILL);
            SkillEffect.UnlinkAgentUnit plugin = new SkillEffect.UnlinkAgentUnit("claude", "widget", UnitKind.PLUGIN);
            assertEquals(UnitKind.SKILL, skill.kind(), "skill arm carries SKILL");
            assertEquals(UnitKind.PLUGIN, plugin.kind(), "plugin arm carries PLUGIN");
        });

        suite.test("SyncGit field carries kind for unitDir routing", () -> {
            SkillEffect.SyncGit skillSync = new SkillEffect.SyncGit(
                    "widget", UnitKind.SKILL,
                    dev.skillmanager.source.InstalledUnit.InstallSource.GIT,
                    false, false);
            SkillEffect.SyncGit pluginSync = new SkillEffect.SyncGit(
                    "widget", UnitKind.PLUGIN,
                    dev.skillmanager.source.InstalledUnit.InstallSource.GIT,
                    false, false);
            assertEquals(UnitKind.SKILL, skillSync.kind(), "skill arm carries SKILL");
            assertEquals(UnitKind.PLUGIN, pluginSync.kind(), "plugin arm carries PLUGIN");
        });

        return suite.runAll();
    }
}
