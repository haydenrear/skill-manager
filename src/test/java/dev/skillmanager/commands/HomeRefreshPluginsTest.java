package dev.skillmanager.commands;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.project.HarnessPluginCli;
import dev.skillmanager.project.PluginMarketplace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * `home refresh-plugins` exists because a home CLONE carries plugin bytes
 * but no harness registration (projectors emit no PLUGIN projections; the
 * CLI side effect never re-runs on copy). These cases pin the command's
 * contract: marketplace regeneration is unconditional and CLI-independent,
 * registration is best-effort and non-fatal, and the per-home marketplace
 * name is what every driver call receives.
 */
public final class HomeRefreshPluginsTest {

    private record Call(String op, String marketplace, String plugin) {}

    private static final class RecordingDriver implements HarnessPluginCli.Driver {
        final List<Call> calls = new ArrayList<>();
        final boolean available;

        RecordingDriver(boolean available) { this.available = available; }

        @Override public String agentId() { return "fake"; }
        @Override public String binary() { return "fake"; }
        @Override public String installHint() { return "install fake"; }
        @Override public boolean available() { return available; }

        @Override
        public HarnessPluginCli.Result ensureMarketplaceAdded(Path root, String name) {
            calls.add(new Call("marketplace-add", name, null));
            return new HarnessPluginCli.Result(0, "added", "");
        }

        @Override
        public HarnessPluginCli.Result refreshMarketplace(Path root, String name) {
            calls.add(new Call("marketplace-update", name, null));
            return new HarnessPluginCli.Result(0, "updated", "");
        }

        @Override
        public HarnessPluginCli.Result reinstallPlugin(String plugin, String name) {
            calls.add(new Call("install", name, plugin));
            return new HarnessPluginCli.Result(0, "installed", "");
        }

        @Override
        public HarnessPluginCli.Result uninstallPlugin(String plugin, String name) {
            calls.add(new Call("uninstall", name, plugin));
            return new HarnessPluginCli.Result(0, "removed", "");
        }
    }

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("HomeRefreshPluginsTest");

        suite.test("regenerates the marketplace and registers each plugin under the per-home name", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("alpha", UnitKind.PLUGIN);
            h.seedUnit("alpha", UnitKind.PLUGIN);
            RecordingDriver driver = new RecordingDriver(true);
            HarnessPluginCli.overrideDriversForTesting(List.of(driver));
            try {
                int rc = new HomeCommand.RefreshPluginsCmd(h.store()).call();
                assertEquals(0, rc, "exit code");
            } finally {
                HarnessPluginCli.clearDriverOverrideForTesting();
            }
            PluginMarketplace mp = new PluginMarketplace(h.store());
            assertTrue(Files.isRegularFile(mp.manifestPath()), "manifest regenerated");
            assertTrue(Files.exists(mp.pluginsLinkDir().resolve("alpha"), LinkOption.NOFOLLOW_LINKS),
                    "plugin symlink present");
            String expected = mp.name();
            assertTrue(driver.calls.stream().allMatch(c -> expected.equals(c.marketplace())),
                    "every driver call used the per-home marketplace name " + expected);
            assertTrue(driver.calls.stream().anyMatch(c ->
                            c.op().equals("install") && "alpha".equals(c.plugin())),
                    "plugin reinstalled through the driver");
        });

        suite.test("missing harness CLIs are non-fatal: marketplace still regenerated, exit 0", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("alpha", UnitKind.PLUGIN);
            h.seedUnit("alpha", UnitKind.PLUGIN);
            RecordingDriver missing = new RecordingDriver(false);
            HarnessPluginCli.overrideDriversForTesting(List.of(missing));
            try {
                int rc = new HomeCommand.RefreshPluginsCmd(h.store()).call();
                assertEquals(0, rc, "non-fatal without any harness CLI");
            } finally {
                HarnessPluginCli.clearDriverOverrideForTesting();
            }
            assertTrue(Files.isRegularFile(new PluginMarketplace(h.store()).manifestPath()),
                    "manifest regenerated regardless");
            assertEquals(0, missing.calls.size(), "unavailable driver never invoked");
        });

        suite.test("no unit content is touched: a plugin's store file survives byte-for-byte", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("alpha", UnitKind.PLUGIN);
            h.seedUnit("alpha", UnitKind.PLUGIN);
            Path marker = h.store().unitDir("alpha", UnitKind.PLUGIN).resolve("local-work.txt");
            Files.writeString(marker, "unsynced local work");
            HarnessPluginCli.overrideDriversForTesting(List.of(new RecordingDriver(true)));
            try {
                assertEquals(0, (int) new HomeCommand.RefreshPluginsCmd(h.store()).call(), "exit");
            } finally {
                HarnessPluginCli.clearDriverOverrideForTesting();
            }
            assertEquals("unsynced local work", Files.readString(marker),
                    "refresh-plugins must never sync unit bytes (issue #50 class)");
        });

        return suite.runAll();
    }

    private HomeRefreshPluginsTest() {}
}
