package dev.skillmanager.effects;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.project.HarnessPluginCli;
import dev.skillmanager.project.PluginMarketplace;
import dev.skillmanager.source.InstalledUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * {@link SkillEffect.RefreshHarnessPlugins} handler integration:
 *
 * <ul>
 *   <li>Always regenerates the {@code <store>/plugin-marketplace/}
 *       layout from the installed-plugin set.</li>
 *   <li>When neither harness CLI is on PATH (the default for unit tests
 *       — {@code claude} / {@code codex} aren't installed in the
 *       sandbox), records {@code HARNESS_CLI_UNAVAILABLE} on every
 *       plugin with a brew-install hint.</li>
 *   <li>Legacy cleanup: pre-existing
 *       {@code <agentPluginsDir>/<plugin>} symlinks/dirs are removed.
 *       (The agents' real {@code pluginsDir} is system-wide so this
 *       arm is exercised only inasmuch as the handler doesn't error
 *       when those dirs don't exist; full coverage lives in
 *       {@code PluginMarketplaceTest}.)</li>
 * </ul>
 *
 * <p>The CLI-available branch is not exercised here — it requires
 * fakes for the {@link
 * dev.skillmanager.project.HarnessPluginCli.Runner} the handler
 * doesn't expose. The handler itself routes through the live runner
 * and {@code defaultDrivers()}; the driver-level branching is covered
 * exhaustively in {@link
 * dev.skillmanager.project.HarnessPluginCliTest}.
 */
public final class RefreshHarnessPluginsTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("RefreshHarnessPluginsTest");

        suite.test("regenerates marketplace.json + behavior depends on harness CLI presence", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("alpha", UnitKind.PLUGIN);
            h.seedUnit("alpha", UnitKind.PLUGIN);
            h.scaffoldUnitDir("beta", UnitKind.PLUGIN);
            h.seedUnit("beta", UnitKind.PLUGIN);

            EffectReceipt r = h.run(SkillEffect.RefreshHarnessPlugins.reinstallAll(
                    List.of("alpha", "beta")));

            // Marketplace generation is unconditional — no CLI needed.
            PluginMarketplace mp = new PluginMarketplace(h.store());
            assertTrue(Files.isRegularFile(mp.manifestPath()), "manifest written");
            assertTrue(Files.exists(mp.pluginsLinkDir().resolve("alpha"), LinkOption.NOFOLLOW_LINKS),
                    "alpha symlink created");
            assertTrue(Files.exists(mp.pluginsLinkDir().resolve("beta"), LinkOption.NOFOLLOW_LINKS),
                    "beta symlink created");
            boolean sawRegenerated = false;
            int missingCount = 0;
            for (ContextFact f : r.facts()) {
                if (f instanceof ContextFact.PluginMarketplaceRegenerated) sawRegenerated = true;
                if (f instanceof ContextFact.HarnessCliMissing) missingCount++;
            }
            assertTrue(sawRegenerated, "PluginMarketplaceRegenerated fact emitted");

            // RunTests sets skill-manager.harness-cli.disabled=true so
            // both drivers always report unavailable in the unit-test
            // sandbox — the handler must record HARNESS_CLI_UNAVAILABLE
            // on every plugin. (Live behavior with claude/codex on
            // PATH is covered by the test_graph layer.)
            assertTrue(missingCount > 0, "disabled drivers → HarnessCliMissing fact emitted");
            Optional<InstalledUnit> alphaSrc = h.sourceOf("alpha");
            assertTrue(alphaSrc.isPresent(), "alpha installed-record present");
            assertTrue(alphaSrc.get().hasError(InstalledUnit.ErrorKind.HARNESS_CLI_UNAVAILABLE),
                    "missing CLI recorded as HARNESS_CLI_UNAVAILABLE on alpha");
        });

        suite.test("regenerates with empty plugin set when nothing is installed", () -> {
            TestHarness h = TestHarness.create();

            EffectReceipt r = h.run(SkillEffect.RefreshHarnessPlugins.reinstallAll(List.of()));

            PluginMarketplace mp = new PluginMarketplace(h.store());
            assertTrue(Files.isRegularFile(mp.manifestPath()), "manifest written even with no plugins");
            assertTrue(r.status() == EffectStatus.OK || r.status() == EffectStatus.PARTIAL,
                    "completes (possibly partial when CLIs missing)");
        });

        suite.test("removing-mode regenerates without the removed plugin", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("alpha", UnitKind.PLUGIN);
            h.seedUnit("alpha", UnitKind.PLUGIN);

            // Caller is expected to call this AFTER RemoveUnitFromStore;
            // simulate by deleting the store dir before the effect.
            dev.skillmanager.shared.util.Fs.deleteRecursive(
                    h.store().unitDir("alpha", UnitKind.PLUGIN));

            EffectReceipt r = h.run(SkillEffect.RefreshHarnessPlugins.removing("alpha"));

            PluginMarketplace mp = new PluginMarketplace(h.store());
            // alpha must not appear in the regenerated symlink set.
            assertFalse(Files.exists(mp.pluginsLinkDir().resolve("alpha"), LinkOption.NOFOLLOW_LINKS),
                    "alpha symlink absent");
            assertTrue(r.status() == EffectStatus.OK || r.status() == EffectStatus.PARTIAL,
                    "effect completed");
        });

        suite.test("ValidateAndClearError(HARNESS_CLI_UNAVAILABLE) keeps error when CLIs disabled", () -> {
            TestHarness h = TestHarness.create();
            h.seedUnit("foo", UnitKind.PLUGIN);
            h.context().addError("foo", InstalledUnit.ErrorKind.HARNESS_CLI_UNAVAILABLE,
                    "claude CLI not on PATH");

            // RunTests forces drivers to "unavailable" via the system
            // property, so ValidateAndClear's probe must NOT clear the
            // error.
            EffectReceipt r = h.run(new SkillEffect.ValidateAndClearError(
                    "foo", InstalledUnit.ErrorKind.HARNESS_CLI_UNAVAILABLE));

            ContextFact.ErrorValidated validated = null;
            for (ContextFact f : r.facts()) if (f instanceof ContextFact.ErrorValidated v) validated = v;
            assertTrue(validated != null, "validated fact emitted");
            assertFalse(validated.cleared(),
                    "drivers report unavailable → error stays put");
            Optional<InstalledUnit> src = h.sourceOf("foo");
            assertTrue(src.map(u -> u.hasError(
                    InstalledUnit.ErrorKind.HARNESS_CLI_UNAVAILABLE)).orElse(false),
                    "error still on the source record");
        });

        // Regression: HARNESS_CLI_UNAVAILABLE must reflect the union of
        // missing drivers, not the iteration order. Previously, when
        // the handler iterated [missing Claude, available Codex], Codex's
        // success branch cleared the error Claude had just set —
        // leaving the plugin appearing healthy even though Claude
        // couldn't install it.
        suite.test("HARNESS_CLI_UNAVAILABLE survives a later available driver in the same loop", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("alpha", UnitKind.PLUGIN);
            h.seedUnit("alpha", UnitKind.PLUGIN);

            HarnessPluginCli.overrideDriversForTesting(List.of(
                    new FakeDriver("claude", false, "brew install claude"),
                    new FakeDriver("codex", true, "brew install codex")));
            try {
                EffectReceipt r = h.run(SkillEffect.RefreshHarnessPlugins.reinstallAll(List.of("alpha")));
                assertTrue(r.status() == EffectStatus.OK || r.status() == EffectStatus.PARTIAL,
                        "effect completed (status=" + r.status() + ")");

                Optional<InstalledUnit> alphaSrc = h.sourceOf("alpha");
                assertTrue(alphaSrc.isPresent(), "alpha record present");
                assertTrue(alphaSrc.get().hasError(
                                InstalledUnit.ErrorKind.HARNESS_CLI_UNAVAILABLE),
                        "missing claude must surface as HARNESS_CLI_UNAVAILABLE even when "
                                + "codex is available later in the loop");
            } finally {
                HarnessPluginCli.clearDriverOverrideForTesting();
            }
        });

        // Regression: AGENT_SYNC_FAILED set by Claude's failure must not
        // be erased by Codex's vacuous success. Codex.reinstallPlugin is
        // a no-op (the CLI doesn't support non-interactive install), and
        // it returns ok=true. The handler used to call tryClearError on
        // every ok result, which meant Codex's no-op overwrote a real
        // Claude failure on the same plugin in the same loop.
        suite.test("Claude-install failure stays put when Codex no-op succeeds in the same loop", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("alpha", UnitKind.PLUGIN);
            h.seedUnit("alpha", UnitKind.PLUGIN);

            FakeDriver claude = new FakeDriver("claude", true, "brew install claude");
            claude.failReinstall = true;
            FakeDriver codex = new FakeDriver("codex", true, "brew install codex");
            // codex is a no-op-success driver — matches production Codex
            // semantics.
            HarnessPluginCli.overrideDriversForTesting(List.of(claude, codex));
            try {
                EffectReceipt r = h.run(SkillEffect.RefreshHarnessPlugins.reinstallAll(List.of("alpha")));
                assertTrue(r.status() == EffectStatus.PARTIAL,
                        "claude failure surfaces as PARTIAL receipt (was: " + r.status() + ")");

                Optional<InstalledUnit> alphaSrc = h.sourceOf("alpha");
                assertTrue(alphaSrc.isPresent(), "alpha record present");
                assertTrue(alphaSrc.get().hasError(
                                InstalledUnit.ErrorKind.AGENT_SYNC_FAILED),
                        "claude install failure must remain recorded as AGENT_SYNC_FAILED "
                                + "even though codex returned ok=true on its no-op reinstall");
            } finally {
                HarnessPluginCli.clearDriverOverrideForTesting();
            }
        });

        suite.test("AGENT_SYNC_FAILED clears when every driver actually succeeds", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("alpha", UnitKind.PLUGIN);
            h.seedUnit("alpha", UnitKind.PLUGIN);
            // Pre-seed a stale failure from a hypothetical earlier sync.
            h.context().addError("alpha", InstalledUnit.ErrorKind.AGENT_SYNC_FAILED,
                    "stale claude failure");

            FakeDriver claude = new FakeDriver("claude", true, "brew install claude");
            FakeDriver codex = new FakeDriver("codex", true, "brew install codex");
            HarnessPluginCli.overrideDriversForTesting(List.of(claude, codex));
            try {
                h.run(SkillEffect.RefreshHarnessPlugins.reinstallAll(List.of("alpha")));

                Optional<InstalledUnit> alphaSrc = h.sourceOf("alpha");
                assertTrue(alphaSrc.isPresent(), "alpha record present");
                assertFalse(alphaSrc.get().hasError(
                                InstalledUnit.ErrorKind.AGENT_SYNC_FAILED),
                        "stale AGENT_SYNC_FAILED clears once every driver succeeds in this run");
            } finally {
                HarnessPluginCli.clearDriverOverrideForTesting();
            }
        });

        return suite.runAll();
    }

    /**
     * Test driver that lets each test pin availability + per-call
     * outcomes without spawning real claude/codex subprocesses.
     */
    private static final class FakeDriver implements HarnessPluginCli.Driver {
        private final String agentId;
        private final boolean available;
        private final String installHint;
        boolean failReinstall;
        final List<String> reinstalls = new ArrayList<>();

        FakeDriver(String agentId, boolean available, String installHint) {
            this.agentId = agentId;
            this.available = available;
            this.installHint = installHint;
        }
        @Override public String agentId() { return agentId; }
        @Override public String binary() { return agentId; }
        @Override public String installHint() { return installHint; }
        @Override public boolean available() { return available; }
        @Override public HarnessPluginCli.Result ensureMarketplaceAdded(Path marketplaceRoot) {
            return new HarnessPluginCli.Result(0, "added", "");
        }
        @Override public HarnessPluginCli.Result refreshMarketplace(Path marketplaceRoot) {
            return new HarnessPluginCli.Result(0, "updated", "");
        }
        @Override public HarnessPluginCli.Result reinstallPlugin(String pluginName) throws IOException {
            reinstalls.add(pluginName);
            if (failReinstall) {
                return new HarnessPluginCli.Result(1, "", agentId + " install failed");
            }
            return new HarnessPluginCli.Result(0, agentId + " installed " + pluginName, "");
        }
        @Override public HarnessPluginCli.Result uninstallPlugin(String pluginName) {
            return new HarnessPluginCli.Result(0, "uninstalled", "");
        }
    }
}
