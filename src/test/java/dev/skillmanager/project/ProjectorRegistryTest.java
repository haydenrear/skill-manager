package dev.skillmanager.project;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-11 contract: {@link ProjectorRegistry} fans a unit out across
 * every registered projector, applying or removing as appropriate. Each
 * projector decides for itself whether the unit even projects (e.g.
 * Codex skipping plugins) — the registry just iterates.
 */
public final class ProjectorRegistryTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ProjectorRegistryTest");

        suite.test("SKILL unit applies through both projectors → both targets exist", () -> {
            TestHarness h = TestHarness.create();
            Path claudeRoot = Files.createTempDirectory("registry-claude-");
            Path codexRoot = Files.createTempDirectory("registry-codex-");
            ClaudeProjector claude = new ClaudeProjector(
                    claudeRoot.resolve("skills"), claudeRoot.resolve("plugins"));
            CodexProjector codex = new CodexProjector(
                    codexRoot.resolve("skills"), codexRoot.resolve("plugins"));
            ProjectorRegistry registry = new ProjectorRegistry(List.of(claude, codex));

            AgentUnit u = installSkill(h, "widget");
            List<Projection> applied = registry.applyAll(u, h.store());

            assertEquals(2, applied.size(), "both projectors fired");
            assertTrue(Files.exists(claudeRoot.resolve("skills").resolve("widget"),
                    LinkOption.NOFOLLOW_LINKS), "Claude's target exists");
            assertTrue(Files.exists(codexRoot.resolve("skills").resolve("widget"),
                    LinkOption.NOFOLLOW_LINKS), "Codex's target exists");
        });

        suite.test("PLUGIN unit produces no per-agent projection — handled by RefreshHarnessPlugins", () -> {
            TestHarness h = TestHarness.create();
            Path claudeRoot = Files.createTempDirectory("registry-claude-plugin-");
            Path codexRoot = Files.createTempDirectory("registry-codex-plugin-");
            ClaudeProjector claude = new ClaudeProjector(
                    claudeRoot.resolve("skills"), claudeRoot.resolve("plugins"));
            CodexProjector codex = new CodexProjector(
                    codexRoot.resolve("skills"), codexRoot.resolve("plugins"));
            ProjectorRegistry registry = new ProjectorRegistry(List.of(claude, codex));

            AgentUnit u = installPlugin(h, "widget");
            List<Projection> applied = registry.applyAll(u, h.store());

            // Plugins flow through the skill-manager-owned marketplace +
            // harness CLI ({@link
            // dev.skillmanager.effects.SkillEffect.RefreshHarnessPlugins})
            // rather than per-agent symlinks. Both projectors return an
            // empty projection list for plugin kinds.
            assertEquals(0, applied.size(), "no per-agent plugin projection");
            assertFalse(Files.exists(claudeRoot.resolve("plugins").resolve("widget")),
                    "Claude per-plugin namespace untouched");
            assertFalse(Files.exists(codexRoot.resolve("plugins").resolve("widget")),
                    "Codex per-plugin namespace untouched");
            assertFalse(Files.exists(codexRoot.resolve("skills").resolve("widget")),
                    "Codex skills target also absent (plugin doesn't fall through to skills)");
        });

        suite.test("removeAll cleans up every projection apply produced", () -> {
            TestHarness h = TestHarness.create();
            Path claudeRoot = Files.createTempDirectory("registry-remove-claude-");
            Path codexRoot = Files.createTempDirectory("registry-remove-codex-");
            ClaudeProjector claude = new ClaudeProjector(
                    claudeRoot.resolve("skills"), claudeRoot.resolve("plugins"));
            CodexProjector codex = new CodexProjector(
                    codexRoot.resolve("skills"), codexRoot.resolve("plugins"));
            ProjectorRegistry registry = new ProjectorRegistry(List.of(claude, codex));

            AgentUnit u = installSkill(h, "widget");
            registry.applyAll(u, h.store());
            assertTrue(Files.exists(claudeRoot.resolve("skills").resolve("widget"),
                    LinkOption.NOFOLLOW_LINKS), "Claude target there");
            assertTrue(Files.exists(codexRoot.resolve("skills").resolve("widget"),
                    LinkOption.NOFOLLOW_LINKS), "Codex target there");

            List<Projection> removed = registry.removeAll(u, h.store());
            assertEquals(2, removed.size(), "both projector targets removed");
            assertFalse(Files.exists(claudeRoot.resolve("skills").resolve("widget"),
                    LinkOption.NOFOLLOW_LINKS), "Claude target gone");
            assertFalse(Files.exists(codexRoot.resolve("skills").resolve("widget"),
                    LinkOption.NOFOLLOW_LINKS), "Codex target gone");
        });

        suite.test("removeAll on never-applied unit is a no-op (idempotent)", () -> {
            TestHarness h = TestHarness.create();
            Path claudeRoot = Files.createTempDirectory("registry-noop-claude-");
            Path codexRoot = Files.createTempDirectory("registry-noop-codex-");
            ProjectorRegistry registry = new ProjectorRegistry(List.of(
                    new ClaudeProjector(claudeRoot.resolve("skills"), claudeRoot.resolve("plugins")),
                    new CodexProjector(codexRoot.resolve("skills"), codexRoot.resolve("plugins"))));

            AgentUnit u = installSkill(h, "widget");
            // No prior apply.
            List<Projection> removed = registry.removeAll(u, h.store());
            // The "removed" list still contains the planned projections —
            // remove() on a missing target is a no-op, but the registry
            // still records what it tried.
            assertEquals(2, removed.size(), "registry returns planned projections regardless");
            assertFalse(Files.exists(claudeRoot.resolve("skills").resolve("widget"),
                    LinkOption.NOFOLLOW_LINKS), "no Claude target");
            assertFalse(Files.exists(codexRoot.resolve("skills").resolve("widget"),
                    LinkOption.NOFOLLOW_LINKS), "no Codex target");
        });

        return suite.runAll();
    }

    private static AgentUnit installSkill(TestHarness h, String name) throws Exception {
        Path tmp = Files.createTempDirectory("registry-skill-fixture-");
        AgentUnit u = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp, name, DepSpec.empty());
        Fs.ensureDir(h.store().skillsDir());
        Fs.copyRecursive(u.sourcePath(), h.store().skillDir(name));
        return u;
    }

    private static AgentUnit installPlugin(TestHarness h, String name) throws Exception {
        Path tmp = Files.createTempDirectory("registry-plugin-fixture-");
        AgentUnit u = UnitFixtures.buildEquivalent(UnitKind.PLUGIN, tmp, name, DepSpec.empty());
        Fs.ensureDir(h.store().pluginsDir());
        Fs.copyRecursive(u.sourcePath(), h.store().pluginsDir().resolve(name));
        return u;
    }
}
