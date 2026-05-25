package dev.skillmanager.effects;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        suite.test("SyncGit refuses dirty registry install before returning registry failure fact", () -> {
            TestHarness h = TestHarness.create();
            Path dir = h.store().skillDir("registry-dirty");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"),
                    "---\nname: registry-dirty\ndescription: registry dirty\n---\nbody\n");
            run(dir, "git", "init", "-b", "main", "--quiet");
            run(dir, "git", "add", "-A");
            run(dir, "git", "-c", "user.email=test@example.com",
                    "-c", "user.name=test", "commit", "--quiet", "-m", "initial");
            String head = read(dir, "git", "rev-parse", "HEAD");
            new UnitStore(h.store()).write(new InstalledUnit(
                    "registry-dirty", "0.1.0", InstalledUnit.Kind.GIT,
                    InstalledUnit.InstallSource.REGISTRY,
                    "https://example.invalid/repo.git", head, "main",
                    UnitStore.nowIso(), List.of(), UnitKind.SKILL));
            Files.writeString(dir.resolve("SKILL.md"), Files.readString(dir.resolve("SKILL.md")) + "\ndirty\n");

            EffectReceipt r = h.run(new SkillEffect.SyncGit(
                    "registry-dirty", UnitKind.SKILL,
                    InstalledUnit.InstallSource.REGISTRY,
                    false, false));

            assertEquals(EffectStatus.PARTIAL, r.status(), "dirty registry sync refuses");
            assertTrue(r.facts().stream().anyMatch(f -> f instanceof ContextFact.SyncGitRefused),
                    "refusal fact present");
            assertFalse(r.facts().stream().anyMatch(f -> f instanceof ContextFact.SyncGitRegistryUnavailable),
                    "registry-unavailable fact does not mask dirty refusal");
        });

        return suite.runAll();
    }

    private static void run(Path cwd, String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).start();
        p.getInputStream().transferTo(System.out);
        int rc = p.waitFor();
        assertEquals(0, rc, String.join(" ", argv));
    }

    private static String read(Path cwd, String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        assertEquals(0, rc, String.join(" ", argv));
        return out.trim();
    }
}
