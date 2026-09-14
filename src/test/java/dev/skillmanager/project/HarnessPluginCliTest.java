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

        suite.test("Claude: ensureMarketplaceAdded skips when THIS identity is listed at THIS path", () -> {
            CapturingRunner runner = new CapturingRunner();
            runner.script.add(ok(listed("skill-manager", "/tmp/mp")));
            HarnessPluginCli.Claude driver = new HarnessPluginCli.Claude(runner);

            HarnessPluginCli.Result r = driver.ensureMarketplaceAdded(Path.of("/tmp/mp"), PluginMarketplace.NAME);

            assertTrue(r.ok(), "treated as success");
            assertEquals("already registered: skill-manager at /tmp/mp", r.stdout(), "names identity and path");
            assertEquals(1, runner.calls.size(), "only the list call ran");
            List<String> firstCmd = runner.calls.get(0).cmd();
            assertEquals(List.of("claude", "plugin", "marketplace", "list", "--json"), firstCmd, "list --json");
        });

        suite.test("Claude: ensureMarketplaceAdded runs add when list is empty, and confirms it", () -> {
            CapturingRunner runner = new CapturingRunner();
            runner.script.add(ok("[]"));
            runner.script.add(ok("marketplace added"));
            runner.script.add(ok(listed("skill-manager", "/tmp/mp")));
            HarnessPluginCli.Claude driver = new HarnessPluginCli.Claude(runner);

            HarnessPluginCli.Result r = driver.ensureMarketplaceAdded(Path.of("/tmp/mp"), PluginMarketplace.NAME);

            assertTrue(r.ok(), "ok: " + r);
            assertEquals(3, runner.calls.size(), "list + add + list");
            List<String> add = runner.calls.get(1).cmd();
            assertEquals("add", add.get(3), "add verb");
            assertEquals("/tmp/mp", add.get(4), "passed marketplace root");
            assertTrue(add.contains("--scope") && add.contains("user"), "user scope");
        });

        suite.test("#352 shape 2: a listed name that CONTAINS the identity does not skip the add", () -> {
            CapturingRunner runner = new CapturingRunner();
            // The root's bare `skill-manager` is a substring of this name, and of
            // its path. The old `stdout.contains(name)` returned "already-added".
            runner.script.add(ok(listed("skill-manager-919db26e", "/cdc/.skill-manager/plugin-marketplace")));
            runner.script.add(ok("added"));
            runner.script.add(ok("[" + entry("skill-manager-919db26e", "/cdc/.skill-manager/plugin-marketplace")
                    + "," + entry("skill-manager", "/root/.skill-manager/plugin-marketplace") + "]"));
            HarnessPluginCli.Claude driver = new HarnessPluginCli.Claude(runner);

            HarnessPluginCli.Result r = driver.ensureMarketplaceAdded(
                    Path.of("/root/.skill-manager/plugin-marketplace"), PluginMarketplace.NAME);

            assertTrue(r.ok(), "ok: " + r);
            assertEquals("add", runner.calls.get(1).cmd().get(3), "the add ran");
            assertTrue(runner.calls.stream().noneMatch(c -> c.cmd().contains("remove")),
                    "and another home's registration was not removed");
        });

        suite.test("#352 shape 1: this path under another name is removed, re-added, and its plugins migrate", () -> {
            CapturingRunner runner = new CapturingRunner();
            String mp = "/p/.skill-manager/plugin-marketplace";
            runner.script.add(ok(listed("skill-manager", mp)));                      // list
            runner.script.add(ok("[{\"id\":\"skt@skill-manager\",\"enabled\":true},"
                    + "{\"id\":\"x@claude-plugins-official\",\"enabled\":true}]"));   // plugin list
            runner.script.add(ok("removed"));                                         // remove
            runner.script.add(ok("added"));                                           // add
            runner.script.add(ok(listed("skill-manager-0fd46eec", mp)));             // list again
            runner.script.add(ok("installed"));                                       // install
            HarnessPluginCli.Claude driver = new HarnessPluginCli.Claude(runner);

            HarnessPluginCli.Result r = driver.ensureMarketplaceAdded(Path.of(mp), "skill-manager-0fd46eec");

            assertTrue(r.ok(), "ok: " + r);
            assertEquals(List.of("claude", "plugin", "marketplace", "remove", "skill-manager"),
                    runner.calls.get(2).cmd(), "the stale name is removed");
            assertEquals(List.of("claude", "plugin", "install", "skt@skill-manager-0fd46eec", "--scope", "user"),
                    runner.calls.get(5).cmd(), "its plugin is installed under the identity");
            assertEquals(6, runner.calls.size(), "and no other plugin is touched");
            assertTrue(r.stdout().contains("expected skill-manager-0fd46eec at " + mp + ", found skill-manager"),
                    "the outcome names expected and found: " + r.stdout());
        });

        suite.test("#352 shape 1: add that keeps the old name is a FAILURE naming expected and found", () -> {
            CapturingRunner runner = new CapturingRunner();
            String mp = "/p/.skill-manager/plugin-marketplace";
            runner.script.add(ok("[]"));
            runner.script.add(ok("Marketplace 'skill-manager' already on disk"));
            runner.script.add(ok(listed("skill-manager", mp)));
            HarnessPluginCli.Claude driver = new HarnessPluginCli.Claude(runner);

            HarnessPluginCli.Result r = driver.ensureMarketplaceAdded(Path.of(mp), "skill-manager-0fd46eec");

            assertFalse(r.ok(), "not ok: " + r);
            assertTrue(r.stderr().contains("expected marketplace skill-manager-0fd46eec at " + mp)
                            && r.stderr().contains("found skill-manager at " + mp),
                    "names both: " + r.stderr());
        });

        suite.test("Claude: the text listing parses by name and path when --json is not understood", () -> {
            List<HarnessPluginCli.Registered> found = HarnessPluginCli.parseMarketplaceList(
                    "Configured marketplaces:\n\n  ❯ skill-manager\n    Source: Directory (/a/b)\n\n"
                            + "  ❯ official\n    Source: GitHub (anthropics/x)\n");
            assertEquals(List.of(new HarnessPluginCli.Registered("skill-manager", "/a/b"),
                    new HarnessPluginCli.Registered("official", "anthropics/x")), found, "parsed");
        });

        suite.test("Claude: refreshMarketplace runs `marketplace update <name>`", () -> {
            CapturingRunner runner = new CapturingRunner();
            runner.script.add(new HarnessPluginCli.Result(0, "updated", ""));
            HarnessPluginCli.Claude driver = new HarnessPluginCli.Claude(runner);

            driver.refreshMarketplace(Path.of("/tmp/mp"), PluginMarketplace.NAME);

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

            HarnessPluginCli.Result r = driver.reinstallPlugin("repo-intel", PluginMarketplace.NAME);

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

            driver.ensureMarketplaceAdded(Path.of("/tmp/mp"), PluginMarketplace.NAME);

            Map<String, String> env = runner.calls.get(0).env();
            String configDir = env.get("CLAUDE_CONFIG_DIR");
            assertTrue(configDir != null && configDir.endsWith(".claude"),
                    "CLAUDE_CONFIG_DIR ends with .claude (was: " + configDir + ")");
        });

        suite.test("Codex: ensureMarketplaceAdded runs `marketplace add <path>` when not yet registered", () -> {
            CapturingRunner runner = new CapturingRunner();
            runner.script.add(new HarnessPluginCli.Result(0, "added", ""));
            // No config file → "absent" branch, runs add unconditionally.
            HarnessPluginCli.Codex driver = new HarnessPluginCli.Codex(runner,
                    java.nio.file.Files.createTempDirectory("codex-cfg-")
                            .resolve("does-not-exist.toml"));

            driver.ensureMarketplaceAdded(Path.of("/tmp/mp"), PluginMarketplace.NAME);

            List<String> cmd = runner.calls.get(0).cmd();
            assertEquals("codex", cmd.get(0), "codex binary");
            assertEquals("add", cmd.get(3), "add verb");
            assertTrue(cmd.get(4).endsWith("/tmp/mp"), "marketplace root passed in (was: " + cmd.get(4) + ")");
        });

        suite.test("Codex: refreshMarketplace re-runs `marketplace add <root>` (idempotent local-path refresh)", () -> {
            CapturingRunner runner = new CapturingRunner();
            runner.script.add(new HarnessPluginCli.Result(0, "Marketplace already added", ""));
            HarnessPluginCli.Codex driver = new HarnessPluginCli.Codex(runner,
                    java.nio.file.Files.createTempDirectory("codex-cfg-")
                            .resolve("does-not-exist.toml"));

            driver.refreshMarketplace(Path.of("/tmp/mp"), PluginMarketplace.NAME);

            List<String> cmd = runner.calls.get(0).cmd();
            // codex's `marketplace upgrade` only handles git-backed
            // sources, so the local-path refresh path re-issues `add`.
            assertEquals("add", cmd.get(3), "add verb (idempotent local refresh)");
            assertTrue(cmd.get(4).endsWith("/tmp/mp"), "passed marketplace root (was: " + cmd.get(4) + ")");
        });

        suite.test("Codex: skips re-add when config already lists our marketplace at the same path", () -> {
            // Existing codex config registers skill-manager at the
            // exact path we're about to ensure → driver returns ok
            // without invoking the CLI at all.
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("codex-cfg-");
            java.nio.file.Path cfg = tmp.resolve("config.toml");
            java.nio.file.Path desired = java.nio.file.Files.createTempDirectory("mp-");
            java.nio.file.Files.writeString(cfg, """
                    [marketplaces.skill-manager]
                    last_updated = "2026-05-07T00:00:00Z"
                    source_type = "local"
                    source = "%s"
                    """.formatted(desired.toAbsolutePath()));

            CapturingRunner runner = new CapturingRunner();
            HarnessPluginCli.Codex driver = new HarnessPluginCli.Codex(runner, cfg);

            HarnessPluginCli.Result r = driver.ensureMarketplaceAdded(desired, PluginMarketplace.NAME);

            assertTrue(r.ok(), "driver reports ok");
            assertEquals(0, runner.calls.size(),
                    "no CLI invocation when registration matches");
            assertTrue(r.stdout().contains("already added"),
                    "result stdout signals the no-op path (was: " + r.stdout() + ")");
        });

        // Regression: this is the user-reported codex error
        // ("marketplace 'skill-manager' is already added from a different
        // source; remove it before adding this source"). Stale
        // registration at a different path used to bubble up as
        // marketplace-add failed for the whole sync.
        suite.test("Codex: replaces stale registration when path mismatches (remove + re-add)", () -> {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("codex-cfg-");
            java.nio.file.Path cfg = tmp.resolve("config.toml");
            java.nio.file.Files.writeString(cfg, """
                    [marketplaces.skill-manager]
                    last_updated = "2026-05-07T00:00:00Z"
                    source_type = "local"
                    source = "/old/skill-manager-home/plugin-marketplace"
                    """);

            CapturingRunner runner = new CapturingRunner();
            // Scripted: remove succeeds, add succeeds.
            runner.script.add(new HarnessPluginCli.Result(0, "removed", ""));
            runner.script.add(new HarnessPluginCli.Result(0, "added", ""));
            HarnessPluginCli.Codex driver = new HarnessPluginCli.Codex(runner, cfg);
            java.nio.file.Path desired = java.nio.file.Files.createTempDirectory("new-mp-");
            runner.onAdd = () -> writeCodex(cfg, "skill-manager", desired);

            HarnessPluginCli.Result r = driver.ensureMarketplaceAdded(desired, PluginMarketplace.NAME);

            assertTrue(r.ok(), "ok overall (add succeeded after remove cleared the stale entry)");
            assertEquals(2, runner.calls.size(),
                    "exactly two CLI calls (remove + add), got " + runner.calls.size());
            assertEquals("remove", runner.calls.get(0).cmd().get(3),
                    "first call removes the stale registration");
            assertEquals("skill-manager", runner.calls.get(0).cmd().get(4),
                    "remove targets our marketplace name");
            assertEquals("add", runner.calls.get(1).cmd().get(3),
                    "second call adds the desired path");
            assertTrue(r.stdout().contains("stale registration"),
                    "diagnostic message names the stale path (was: " + r.stdout() + ")");
        });

        suite.test("Codex: stale-registration replacement still ok when remove returns non-zero", () -> {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("codex-cfg-");
            java.nio.file.Path cfg = tmp.resolve("config.toml");
            java.nio.file.Files.writeString(cfg, """
                    [marketplaces.skill-manager]
                    source_type = "local"
                    source = "/old/path"
                    """);

            CapturingRunner runner = new CapturingRunner();
            // Remove fails (already partially torn down maybe), but we
            // tolerate it and still run add. Final result follows the
            // add outcome.
            runner.script.add(new HarnessPluginCli.Result(1, "", "no such marketplace"));
            runner.script.add(new HarnessPluginCli.Result(0, "added", ""));
            HarnessPluginCli.Codex driver = new HarnessPluginCli.Codex(runner, cfg);
            java.nio.file.Path desired = java.nio.file.Files.createTempDirectory("new-mp-");
            runner.onAdd = () -> writeCodex(cfg, "skill-manager", desired);

            HarnessPluginCli.Result r = driver.ensureMarketplaceAdded(desired, PluginMarketplace.NAME);

            assertTrue(r.ok(), "ok when add succeeds — remove failure tolerated");
            assertEquals(2, runner.calls.size(), "still tries both remove and add");
            assertTrue(r.stdout().contains("remove rc="),
                    "diagnostic captures the non-zero remove rc (was: " + r.stdout() + ")");
        });

        suite.test("#352 shape 1, Codex: this path under another name is removed and its plugins migrate", () -> {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("codex-cfg-");
            java.nio.file.Path cfg = tmp.resolve("config.toml");
            java.nio.file.Path desired = java.nio.file.Files.createTempDirectory("mp-");
            java.nio.file.Files.writeString(cfg, """
                    [marketplaces.skill-manager]
                    source_type = "local"
                    source = "%s"

                    [plugins."skt@skill-manager"]
                    enabled = true
                    """.formatted(desired.toAbsolutePath()));
            CapturingRunner runner = new CapturingRunner();
            runner.onAdd = () -> writeCodex(cfg, "skill-manager-0fd46eec", desired);
            HarnessPluginCli.Codex driver = new HarnessPluginCli.Codex(runner, cfg);

            HarnessPluginCli.Result r = driver.ensureMarketplaceAdded(desired, "skill-manager-0fd46eec");

            assertTrue(r.ok(), "ok: " + r);
            List<List<String>> cmds = runner.calls.stream().map(CapturingRunner.Call::cmd).toList();
            assertEquals(List.of(
                    List.of("codex", "plugin", "remove", "skt@skill-manager"),
                    List.of("codex", "plugin", "marketplace", "remove", "skill-manager"),
                    List.of("codex", "plugin", "marketplace", "add", desired.toAbsolutePath().toString()),
                    List.of("codex", "plugin", "add", "skt@skill-manager-0fd46eec")), cmds,
                    "remove the stale plugin and name, add the identity, re-add the plugin under it");
            assertTrue(r.stdout().contains("expected skill-manager-0fd46eec at " + desired.toAbsolutePath()
                    + ", found skill-manager"), "names expected and found: " + r.stdout());
        });

        suite.test("Codex: readMarketplaceSource returns empty for missing/unparseable config", () -> {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("codex-cfg-");
            java.util.Optional<String> missing =
                    HarnessPluginCli.Codex.readMarketplaceSource(tmp.resolve("nope"), "skill-manager");
            assertTrue(missing.isEmpty(), "no file → empty");

            java.nio.file.Path empty = tmp.resolve("empty.toml");
            java.nio.file.Files.writeString(empty, "");
            assertTrue(HarnessPluginCli.Codex.readMarketplaceSource(empty, "skill-manager").isEmpty(),
                    "no marketplaces table → empty");

            java.nio.file.Path unrelated = tmp.resolve("other.toml");
            java.nio.file.Files.writeString(unrelated, """
                    [marketplaces.someone-elses-marketplace]
                    source = "/elsewhere"
                    """);
            assertTrue(HarnessPluginCli.Codex.readMarketplaceSource(unrelated, "skill-manager").isEmpty(),
                    "different marketplace name → empty");
        });

        suite.test("Codex: reinstallPlugin runs `plugin add <name>@skill-manager`", () -> {
            CapturingRunner runner = new CapturingRunner();
            runner.script.add(new HarnessPluginCli.Result(0, "added", ""));
            HarnessPluginCli.Codex driver = new HarnessPluginCli.Codex(runner);

            HarnessPluginCli.Result r1 = driver.reinstallPlugin("anything", PluginMarketplace.NAME);

            assertTrue(r1.ok(), "plugin add reports ok");
            assertEquals(1, runner.calls.size(), "one subprocess invocation");
            List<String> cmd = runner.calls.get(0).cmd();
            assertEquals("codex", cmd.get(0), "codex binary");
            assertEquals("plugin", cmd.get(1), "plugin subcommand");
            assertEquals("add", cmd.get(2), "add verb");
            assertEquals("anything@" + PluginMarketplace.NAME, cmd.get(3), "plugin coord");
        });

        suite.test("Codex: reinstallPlugin treats already-added output as success", () -> {
            CapturingRunner runner = new CapturingRunner();
            runner.script.add(new HarnessPluginCli.Result(1, "", "Plugin already added"));
            HarnessPluginCli.Codex driver = new HarnessPluginCli.Codex(runner);

            HarnessPluginCli.Result r = driver.reinstallPlugin("anything", PluginMarketplace.NAME);

            assertTrue(r.ok(), "already-added output is idempotent success");
            assertEquals(1, runner.calls.size(), "still invoked codex plugin add");
        });

        suite.test("Codex: uninstallPlugin no-op (CLI doesn't support it)", () -> {
            CapturingRunner runner = new CapturingRunner();
            HarnessPluginCli.Codex driver = new HarnessPluginCli.Codex(runner);

            HarnessPluginCli.Result r = driver.uninstallPlugin("anything", PluginMarketplace.NAME);

            assertTrue(r.ok(), "reports ok");
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

    private static HarnessPluginCli.Result ok(String stdout) {
        return new HarnessPluginCli.Result(0, stdout, "");
    }

    private static String entry(String name, String path) {
        return "{\"name\":\"" + name + "\",\"source\":\"directory\",\"path\":\"" + path + "\"}";
    }

    /** {@code claude plugin marketplace list --json} with one entry. */
    private static String listed(String name, String path) {
        return "[" + entry(name, path) + "]";
    }

    /** What {@code codex plugin marketplace add} leaves: the table under {@code name}, and nothing stale. */
    private static void writeCodex(Path cfg, String name, Path source) {
        try {
            java.nio.file.Files.writeString(cfg, "[marketplaces." + name + "]\nsource_type = \"local\"\n"
                    + "source = \"" + source.toAbsolutePath() + "\"\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records every invocation; returns scripted Results in order. */
    private static final class CapturingRunner implements HarnessPluginCli.Runner {
        record Call(List<String> cmd, Map<String, String> env) {}
        final List<Call> calls = new ArrayList<>();
        final List<HarnessPluginCli.Result> script = new ArrayList<>();
        /** The side effect a real {@code marketplace add} has on the config, when a test needs it. */
        Runnable onAdd;
        @Override
        public HarnessPluginCli.Result run(List<String> command, Map<String, String> envOverrides) {
            calls.add(new Call(List.copyOf(command),
                    envOverrides == null ? Map.of() : Map.copyOf(envOverrides)));
            if (onAdd != null && command.size() > 3 && command.get(2).equals("marketplace")
                    && command.get(3).equals("add")) {
                onAdd.run();
            }
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
        @Override public HarnessPluginCli.Result ensureMarketplaceAdded(Path root, String marketplaceName) { return ok(); }
        @Override public HarnessPluginCli.Result refreshMarketplace(Path root, String marketplaceName) { return ok(); }
        @Override public HarnessPluginCli.Result reinstallPlugin(String name, String marketplaceName) { return ok(); }
        @Override public HarnessPluginCli.Result uninstallPlugin(String name, String marketplaceName) { return ok(); }
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
        @Override public HarnessPluginCli.Result ensureMarketplaceAdded(Path root, String marketplaceName) throws IOException {
            throw new IOException("not on path");
        }
        @Override public HarnessPluginCli.Result refreshMarketplace(Path root, String marketplaceName) throws IOException {
            throw new IOException("not on path");
        }
        @Override public HarnessPluginCli.Result reinstallPlugin(String name, String marketplaceName) throws IOException {
            throw new IOException("not on path");
        }
        @Override public HarnessPluginCli.Result uninstallPlugin(String name, String marketplaceName) throws IOException {
            throw new IOException("not on path");
        }
    }
}
