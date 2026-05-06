package dev.skillmanager.command;

import dev.skillmanager._lib.fixtures.ContainedSkillSpec;
import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.RemoveUseCase;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.PluginUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-09c contract #4: uninstall must not leave orphan registrations
 * behind. Sweeps {@code (UnitKind × pre-state with deps held by other
 * units × dep mix)} and asserts the program {@code RemoveUseCase}
 * builds emits exactly the {@link SkillEffect.UnregisterMcpOrphan}
 * effects it should — one per dep that no surviving unit still claims,
 * zero for deps a sibling holds.
 *
 * <p>The plugin re-walk contract is the marquee case: an MCP server
 * declared <em>by a contained skill</em> must show up in the orphan
 * scan even though the plugin's top-level toml never names it. Without
 * the re-walk in {@code RemoveUseCase.recoverEffectiveMcpDeps},
 * {@code store.load(name)} returns nothing for a plugin (it only loads
 * skills) and the orphan unregister silently disappears.
 *
 * <p>The test inspects the program's effect list directly — it doesn't
 * actually execute the program. End-to-end coverage with a real
 * gateway is integration territory (test_graph) and the failure-injection
 * sweep lands in 09d once {@code FakeGateway} arrives.
 */
public final class UninstallScenarioTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("UninstallScenarioTest");
        GatewayConfig gw = GatewayConfig.of(URI.create("http://127.0.0.1:51717"));

        // ---------------------------------------------------------- SKILL kind

        suite.test("SKILL with one MCP dep, no other claimants → unregister", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "alpha", DepSpec.of().mcp("srv-a").build());
            h.seedUnit("alpha", UnitKind.SKILL);

            Set<String> orphans = orphansFromProgram(h, "alpha", gw);
            assertEquals(1, orphans.size(), "one orphan emitted");
            assertTrue(orphans.contains("srv-a"), "srv-a is the orphan");
        });

        suite.test("SKILL with MCP dep claimed by sibling → no orphan emitted", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "alpha", DepSpec.of().mcp("srv-a").build());
            installSkill(h, "beta", DepSpec.of().mcp("srv-a").build());
            h.seedUnit("alpha", UnitKind.SKILL);
            h.seedUnit("beta", UnitKind.SKILL);

            Set<String> orphans = orphansFromProgram(h, "alpha", gw);
            assertTrue(orphans.isEmpty(), "no orphans — beta still claims srv-a");
        });

        suite.test("SKILL with no MCP deps → no orphans (regardless of siblings)", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "alpha", DepSpec.empty());
            installSkill(h, "beta", DepSpec.of().mcp("srv-a").build());
            h.seedUnit("alpha", UnitKind.SKILL);
            h.seedUnit("beta", UnitKind.SKILL);

            Set<String> orphans = orphansFromProgram(h, "alpha", gw);
            assertTrue(orphans.isEmpty(), "no MCP deps to begin with");
        });

        suite.test("SKILL with multiple MCP deps, one claimed by sibling → only unclaimed unregister", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "alpha", DepSpec.of().mcp("srv-a").mcp("srv-b").build());
            installSkill(h, "beta", DepSpec.of().mcp("srv-a").build());
            h.seedUnit("alpha", UnitKind.SKILL);
            h.seedUnit("beta", UnitKind.SKILL);

            Set<String> orphans = orphansFromProgram(h, "alpha", gw);
            assertEquals(1, orphans.size(), "one orphan");
            assertTrue(orphans.contains("srv-b"), "srv-b is the orphan");
            assertFalse(orphans.contains("srv-a"), "srv-a survives — beta claims it");
        });

        // ---------------------------------------------------------- PLUGIN kind

        suite.test("PLUGIN re-walk: contained skill's MCP dep surfaces as orphan", () -> {
            // The plugin-level toml declares no MCP deps. Its single contained
            // skill declares srv-a. Without the re-walk, store.load("widget")
            // returns nothing for a plugin and the orphan unregister never
            // gets emitted. With the re-walk, PluginUnit's union'd
            // mcpDependencies surfaces srv-a.
            TestHarness h = TestHarness.create();
            installPlugin(h, "widget", DepSpec.empty(),
                    new ContainedSkillSpec("widget-impl", DepSpec.of().mcp("srv-a").build()));
            h.seedUnit("widget", UnitKind.PLUGIN);

            Set<String> orphans = orphansFromProgram(h, "widget", gw);
            assertEquals(1, orphans.size(), "one orphan from contained-skill dep");
            assertTrue(orphans.contains("srv-a"), "srv-a is the orphan");
        });

        suite.test("PLUGIN re-walk: plugin-level + contained-skill MCP deps both unioned", () -> {
            TestHarness h = TestHarness.create();
            installPlugin(h, "widget",
                    DepSpec.of().mcp("srv-plugin").build(),
                    new ContainedSkillSpec("widget-impl", DepSpec.of().mcp("srv-contained").build()));
            h.seedUnit("widget", UnitKind.PLUGIN);

            Set<String> orphans = orphansFromProgram(h, "widget", gw);
            assertEquals(2, orphans.size(), "plugin-level and contained both surface");
            assertTrue(orphans.contains("srv-plugin"), "plugin-level dep");
            assertTrue(orphans.contains("srv-contained"), "contained-skill dep");
        });

        suite.test("PLUGIN with no MCP deps → no orphans", () -> {
            TestHarness h = TestHarness.create();
            installPlugin(h, "widget", DepSpec.empty(),
                    new ContainedSkillSpec("widget-impl", DepSpec.empty()));
            h.seedUnit("widget", UnitKind.PLUGIN);

            Set<String> orphans = orphansFromProgram(h, "widget", gw);
            assertTrue(orphans.isEmpty(), "no MCP deps anywhere in the plugin tree");
        });

        // ---------------------------------------------------------- emitted effects shape

        suite.test("Uninstall emits RemoveUnitFromStore with correct kind", () -> {
            TestHarness h = TestHarness.create();
            installPlugin(h, "widget", DepSpec.empty(),
                    new ContainedSkillSpec("widget-impl", DepSpec.empty()));
            h.seedUnit("widget", UnitKind.PLUGIN);

            Program<RemoveUseCase.Report> program = RemoveUseCase.buildProgram(
                    h.store(), gw, "widget", null, true);
            SkillEffect.RemoveUnitFromStore remove = program.effects().stream()
                    .filter(e -> e instanceof SkillEffect.RemoveUnitFromStore)
                    .map(e -> (SkillEffect.RemoveUnitFromStore) e)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no RemoveUnitFromStore in program"));
            assertEquals(UnitKind.PLUGIN, remove.kind(), "PLUGIN routing through RemoveUnitFromStore");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- helpers

    private static Set<String> orphansFromProgram(TestHarness h, String unitName, GatewayConfig gw)
            throws java.io.IOException {
        Program<RemoveUseCase.Report> program = RemoveUseCase.buildProgram(
                h.store(), gw, unitName, null, true);
        return program.effects().stream()
                .filter(e -> e instanceof SkillEffect.UnregisterMcpOrphan)
                .map(e -> ((SkillEffect.UnregisterMcpOrphan) e).serverId())
                .collect(Collectors.toSet());
    }

    private static void installSkill(TestHarness h, String name, DepSpec deps) throws Exception {
        Path tmp = Files.createTempDirectory("uninstall-scenario-skill-");
        AgentUnit u = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp, name, deps);
        Path src = u.sourcePath();
        Path dst = h.store().skillDir(name);
        Fs.ensureDir(h.store().skillsDir());
        Fs.copyRecursive(src, dst);
    }

    /**
     * Set up a plugin under {@code plugins/<name>/} so {@link
     * dev.skillmanager.model.PluginParser#load} can re-walk its contents.
     * The whole plugin tree (plugin.json + skill-manager-plugin.toml +
     * skills/&lt;contained&gt;/...) is copied verbatim.
     */
    private static void installPlugin(TestHarness h, String name,
                                       DepSpec pluginLevelDeps,
                                       ContainedSkillSpec... contained) throws Exception {
        Path tmp = Files.createTempDirectory("uninstall-scenario-plugin-");
        PluginUnit p = UnitFixtures.scaffoldPlugin(tmp, name, pluginLevelDeps, contained);
        Path src = p.sourcePath();
        Path dst = h.store().pluginsDir().resolve(name);
        Fs.ensureDir(h.store().pluginsDir());
        Fs.copyRecursive(src, dst);
    }
}
