package dev.skillmanager.project;

import dev.skillmanager._lib.test.Tests;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Behavior of the per-harness CLI drivers. Subprocess invocations are
 * captured via a fake {@link HarnessPluginCli.Runner} so the tests
 * never spawn {@code claude} / {@code codex} — they verify command
 * shape, env wiring, and the marketplace-add idempotence path.
 *
 * <p>The {@link HarnessPluginCli#defaultDrivers()} surface is covered
 * indirectly via the live runner constructor (no point exercising
 * {@link ProcessBuilder} in a unit test) — the focused tests sit
 * against {@link HarnessPluginCli.Claude} and {@link HarnessPluginCli.Codex}.
 */
public final class HarnessPluginCliTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("HarnessPluginCliTest");

        suite.test("Claude: ensureMarketplaceAdded skips when list already shows it", () -> {
            CapturingRunner runner = new CapturingRunner();
            runner.script.add(new HarnessPluginCli.Result(0,
                    "Configured marketplaces:\n  ❯ skill-manager\n", ""));
            HarnessPluginCli.Claude driver = new HarnessPluginCli.Claude(runner);

            HarnessPluginCli.Result r = driver.ensureMarketplaceAdded(Path.of("/tmp/mp"));

            assertTrue(r.ok(), "treated as success");
            assertEquals("already-added", r.stdout(), "marker stdout");
            assertEquals(1, runner.calls.size(), "only the list call ran");
            List<String> firstCmd = runner.calls.get(0).cmd();
            assertEquals("claude", firstCmd.get(0), "claude binary");
            assertEquals("plugin", firstCmd.get(1), "plugin subcommand");
            assertEquals("marketplace", firstCmd.get(2), "marketplace subcommand");
            assertEquals("list", firstCmd.get(3), "list verb");
        });

        suite.test("Claude: ensureMarketplaceAdded runs add when list is empty", () -> {
            CapturingRunner runner = new CapturingRunner();
            runner.script.add(new HarnessPluginCli.Result(0,
                    "Configured marketplaces:\n  (none)\n", ""));
            runner.script.add(new HarnessPluginCli.Result(0, "marketplace added", ""));
            HarnessPluginCli.Claude driver = new HarnessPluginCli.Claude(runner);

            HarnessPluginCli.Result r = driver.ensureMarketplaceAdded(Path.of("/tmp/mp"));

            assertTrue(r.ok(), "ok");
            assertEquals(2, runner.calls.size(), "list + add");
            List<String> add = runner.calls.get(1).cmd();
            assertEquals("add", add.get(3), "add verb");
            assertEquals("/tmp/mp", add.get(4), "passed marketplace root");
            assertTrue(add.contains("--scope") && add.contains("user"), "user scope");
        });

        suite.test("Claude: refreshMarketplace runs `marketplace update <name>`", () -> {
            CapturingRunner runner = new CapturingRunner();
            runner.script.add(new HarnessPluginCli.Result(0, "updated", ""));
            HarnessPluginCli.Claude driver = new HarnessPluginCli.Claude(runner);

            driver.refreshMarketplace(Path.of("/tmp/mp"));

            List<String> cmd = runner.calls.get(0).cmd();
            assertEquals("update", cmd.get(3), "update verb");
            assertEquals(PluginMarketplace.NAME, cmd.get(4), "marketplace name");
        });

        suite.test("Claude: reinstallPlugin runs uninstall then install (uninstall+reinstall semantics)", () -> {
            CapturingRunner runner = new CapturingRunner();
            // uninstall (may fail because plugin not yet there — fine)
            runner.script.add(new HarnessPluginCli.Result(1, "", "not installed"));
            // install
            runner.script.add(new HarnessPluginCli.Result(0, "installed", ""));
            HarnessPluginCli.Claude driver = new HarnessPluginCli.Claude(runner);

            HarnessPluginCli.Result r = driver.reinstallPlugin("repo-intel");

            assertTrue(r.ok(), "install succeeded");
            assertEquals(2, runner.calls.size(), "uninstall + install");
            assertEquals("uninstall", runner.calls.get(0).cmd().get(2), "first call uninstall");
            assertEquals("install", runner.calls.get(1).cmd().get(2), "second call install");
            // Plugin coord includes @marketplace
            String installCoord = runner.calls.get(1).cmd().get(3);
            assertEquals("repo-intel@" + PluginMarketplace.NAME, installCoord, "coord = name@marketplace");
        });

        suite.test("Claude: env exports CLAUDE_CONFIG_DIR pointing at $CLAUDE_HOME/.claude", () -> {
            CapturingRunner runner = new CapturingRunner();
            runner.script.add(new HarnessPluginCli.Result(0, "skill-manager", ""));
            HarnessPluginCli.Claude driver = new HarnessPluginCli.Claude(runner);

            driver.ensureMarketplaceAdded(Path.of("/tmp/mp"));

            Map<String, String> env = runner.calls.get(0).env();
            String configDir = env.get("CLAUDE_CONFIG_DIR");
            assertTrue(configDir != null && configDir.endsWith(".claude"),
                    "CLAUDE_CONFIG_DIR ends with .claude (was: " + configDir + ")");
        });

        suite.test("Codex: ensureMarketplaceAdded runs `marketplace add <path>`", () -> {
            CapturingRunner runner = new CapturingRunner();
            runner.script.add(new HarnessPluginCli.Result(0, "added", ""));
            HarnessPluginCli.Codex driver = new HarnessPluginCli.Codex(runner);

            driver.ensureMarketplaceAdded(Path.of("/tmp/mp"));

            List<String> cmd = runner.calls.get(0).cmd();
            assertEquals("codex", cmd.get(0), "codex binary");
            assertEquals("add", cmd.get(3), "add verb");
            assertEquals("/tmp/mp", cmd.get(4), "marketplace root passed in");
        });

        suite.test("Codex: refreshMarketplace re-runs `marketplace add <root>` (idempotent local-path refresh)", () -> {
            CapturingRunner runner = new CapturingRunner();
            runner.script.add(new HarnessPluginCli.Result(0, "Marketplace already added", ""));
            HarnessPluginCli.Codex driver = new HarnessPluginCli.Codex(runner);

            driver.refreshMarketplace(Path.of("/tmp/mp"));

            List<String> cmd = runner.calls.get(0).cmd();
            // codex's `marketplace upgrade` only handles git-backed
            // sources, so the local-path refresh path re-issues `add`.
            assertEquals("add", cmd.get(3), "add verb (idempotent local refresh)");
            assertEquals("/tmp/mp", cmd.get(4), "passed marketplace root");
        });

        suite.test("Codex: reinstallPlugin / uninstallPlugin no-op (CLI doesn't support them)", () -> {
            CapturingRunner runner = new CapturingRunner();
            HarnessPluginCli.Codex driver = new HarnessPluginCli.Codex(runner);

            HarnessPluginCli.Result r1 = driver.reinstallPlugin("anything");
            HarnessPluginCli.Result r2 = driver.uninstallPlugin("anything");

            assertTrue(r1.ok() && r2.ok(), "both report ok");
            assertEquals(0, runner.calls.size(), "no subprocess invocations");
        });

        suite.test("missingHint returns null when every driver is available", () -> {
            HarnessPluginCli.Driver always = new AlwaysAvailableDriver("claude");
            String hint = HarnessPluginCli.missingHint(List.of(always));
            assertEquals(null, hint, "all available → no hint");
        });

        suite.test("missingHint lists every missing binary with its install command", () -> {
            HarnessPluginCli.Driver missing = new MissingDriver("codex", "brew install codex");
            String hint = HarnessPluginCli.missingHint(List.of(missing));
            assertTrue(hint != null && hint.contains("codex") && hint.contains("brew install codex"),
                    "hint surfaces missing binary + install command (was: " + hint + ")");
        });

        suite.test("onPath false for a nonsense binary", () -> {
            assertFalse(HarnessPluginCli.onPath("definitely-not-a-real-binary-xyz"),
                    "garbage names not on PATH");
        });

        return suite.runAll();
    }

    /** Records every invocation; returns scripted Results in order. */
    private static final class CapturingRunner implements HarnessPluginCli.Runner {
        record Call(List<String> cmd, Map<String, String> env) {}
        final List<Call> calls = new ArrayList<>();
        final List<HarnessPluginCli.Result> script = new ArrayList<>();
        @Override
        public HarnessPluginCli.Result run(List<String> command, Map<String, String> envOverrides) {
            calls.add(new Call(List.copyOf(command),
                    envOverrides == null ? Map.of() : Map.copyOf(envOverrides)));
            if (script.isEmpty()) return new HarnessPluginCli.Result(0, "", "");
            return script.remove(0);
        }
    }

    private static final class AlwaysAvailableDriver implements HarnessPluginCli.Driver {
        private final String agentId;
        AlwaysAvailableDriver(String agentId) { this.agentId = agentId; }
        @Override public String agentId() { return agentId; }
        @Override public String binary() { return agentId; }
        @Override public String installHint() { return "n/a"; }
        @Override public boolean available() { return true; }
        @Override public HarnessPluginCli.Result ensureMarketplaceAdded(Path root) { return ok(); }
        @Override public HarnessPluginCli.Result refreshMarketplace(Path root) { return ok(); }
        @Override public HarnessPluginCli.Result reinstallPlugin(String name) { return ok(); }
        @Override public HarnessPluginCli.Result uninstallPlugin(String name) { return ok(); }
        private static HarnessPluginCli.Result ok() { return new HarnessPluginCli.Result(0, "", ""); }
    }

    private static final class MissingDriver implements HarnessPluginCli.Driver {
        private final String agentId;
        private final String hint;
        MissingDriver(String agentId, String hint) { this.agentId = agentId; this.hint = hint; }
        @Override public String agentId() { return agentId; }
        @Override public String binary() { return agentId; }
        @Override public String installHint() { return hint; }
        @Override public boolean available() { return false; }
        @Override public HarnessPluginCli.Result ensureMarketplaceAdded(Path root) throws IOException {
            throw new IOException("not on path");
        }
        @Override public HarnessPluginCli.Result refreshMarketplace(Path root) throws IOException {
            throw new IOException("not on path");
        }
        @Override public HarnessPluginCli.Result reinstallPlugin(String name) throws IOException {
            throw new IOException("not on path");
        }
        @Override public HarnessPluginCli.Result uninstallPlugin(String name) throws IOException {
            throw new IOException("not on path");
        }
    }
}
