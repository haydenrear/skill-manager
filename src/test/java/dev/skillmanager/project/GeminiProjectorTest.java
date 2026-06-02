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
 * Gemini consumes Agent Skills directly from {@code ~/.gemini/skills}.
 * This mirrors the Codex projector contract for ordinary SKILL units
 * while leaving plugin handling explicitly unsupported for now.
 */
public final class GeminiProjectorTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("GeminiProjectorTest");

        suite.test("SKILL -> one projection routing to skillsDir", () -> {
            TestHarness h = TestHarness.create();
            Path agentRoot = Files.createTempDirectory("gemini-proj-skill-");
            GeminiProjector p = new GeminiProjector(
                    agentRoot.resolve("skills"), agentRoot.resolve("plugins"));
            AgentUnit u = installSkill(h, "widget");

            List<Projection> projs = p.planProjection(u, h.store());
            assertEquals(1, projs.size(), "one projection");
            Projection proj = projs.get(0);
            assertEquals("gemini", proj.agentId(), "agent");
            assertEquals(UnitKind.SKILL, proj.kind(), "kind");
            assertEquals(agentRoot.resolve("skills").resolve("widget"), proj.target(),
                    "target = geminiHome/skills/widget");
        });

        suite.test("SKILL - apply creates symlink, remove cleans up", () -> {
            TestHarness h = TestHarness.create();
            Path agentRoot = Files.createTempDirectory("gemini-proj-apply-");
            GeminiProjector p = new GeminiProjector(
                    agentRoot.resolve("skills"), agentRoot.resolve("plugins"));
            AgentUnit u = installSkill(h, "widget");

            Projection proj = p.planProjection(u, h.store()).get(0);
            p.apply(proj);
            assertTrue(Files.exists(proj.target(), LinkOption.NOFOLLOW_LINKS), "applied");

            p.remove(proj);
            assertFalse(Files.exists(proj.target(), LinkOption.NOFOLLOW_LINKS), "removed");
        });

        suite.test("PLUGIN - planProjection returns empty list", () -> {
            TestHarness h = TestHarness.create();
            Path agentRoot = Files.createTempDirectory("gemini-proj-plugin-skip-");
            GeminiProjector p = new GeminiProjector(
                    agentRoot.resolve("skills"), agentRoot.resolve("plugins"));
            AgentUnit pluginUnit = installPlugin(h, "widget");

            List<Projection> projs = p.planProjection(pluginUnit, h.store());
            assertEquals(0, projs.size(), "no projection - Gemini plugin policy is separate");
            assertFalse(Files.exists(agentRoot.resolve("plugins")), "plugins dir not materialized");
        });

        return suite.runAll();
    }

    private static AgentUnit installSkill(TestHarness h, String name) throws Exception {
        Path tmp = Files.createTempDirectory("gemini-skill-fixture-");
        AgentUnit u = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp, name, DepSpec.empty());
        Fs.ensureDir(h.store().skillsDir());
        Fs.copyRecursive(u.sourcePath(), h.store().skillDir(name));
        return u;
    }

    private static AgentUnit installPlugin(TestHarness h, String name) throws Exception {
        Path tmp = Files.createTempDirectory("gemini-plugin-fixture-");
        AgentUnit u = UnitFixtures.buildEquivalent(UnitKind.PLUGIN, tmp, name, DepSpec.empty());
        Fs.ensureDir(h.store().pluginsDir());
        Fs.copyRecursive(u.sourcePath(), h.store().pluginsDir().resolve(name));
        return u;
    }
}
