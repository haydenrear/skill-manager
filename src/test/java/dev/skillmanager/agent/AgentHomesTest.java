package dev.skillmanager.agent;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.project.HarnessPluginCli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;
import static dev.skillmanager._lib.test.Tests.assertNotNull;

/**
 * Locks in the thread-local override path through {@link AgentHomes}.
 *
 * <p>Specifically, this is the regression test for the issue where a
 * unit test exercising the harness-CLI install flow polluted the
 * developer's real {@code ~/.claude/plugins/known_marketplaces.json}
 * with a {@code skill-manager} entry pointing at a soon-to-be-deleted
 * {@code skill-handler-test-<random>/plugin-marketplace} temp dir. The
 * fix routes every {@code CLAUDE_HOME} / {@code CLAUDE_CONFIG_DIR} /
 * {@code CODEX_HOME} lookup through {@link AgentHomes} so a
 * thread-local override (installed by {@link TestHarness}) redirects
 * the lookup at a sandbox dir before it can reach the real env vars.
 */
public final class AgentHomesTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("AgentHomesTest");

        // ----------------------------------------------------------------
        // AgentHomes — bare resolution semantics

        suite.test("resolveOrDefault returns default when no override + env unset", () -> {
            AgentHomes.clearOverrides();
            // We can't unset env vars from inside a JVM, but the
            // sentinel below uses a key no caller would set — proving
            // the default-fallback path fires.
            String unusedKey = "SKILL_MANAGER_TEST_AGENT_HOMES_UNUSED";
            Path expected = Path.of("/expected-default");
            Path actual = AgentHomes.resolveOrDefault(unusedKey, expected);
            assertEquals(expected, actual, "default returned");
        });

        suite.test("setOverride wins over the env-backed default", () -> {
            AgentHomes.clearOverrides();
            String key = "SKILL_MANAGER_TEST_AGENT_HOMES_OVERRIDE";
            Path override = Path.of("/sandbox/override");
            AgentHomes.setOverride(key, override);
            try {
                assertEquals(override, AgentHomes.resolveOrDefault(key, Path.of("/default")),
                        "override returned");
                assertEquals(override, AgentHomes.resolve(key),
                        "resolve (no default) sees override");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        suite.test("clearOverrides drops the override", () -> {
            String key = "SKILL_MANAGER_TEST_AGENT_HOMES_CLEAR";
            AgentHomes.setOverride(key, Path.of("/sandbox"));
            AgentHomes.clearOverrides();
            assertEquals(null, AgentHomes.resolve(key),
                    "override gone (no env var either, so null)");
        });

        suite.test("setOverride(key, null) removes that key without disturbing others", () -> {
            AgentHomes.clearOverrides();
            try {
                AgentHomes.setOverride("KEEP_ME", Path.of("/keep"));
                AgentHomes.setOverride("DROP_ME", Path.of("/drop"));
                AgentHomes.setOverride("DROP_ME", null);
                assertEquals(Path.of("/keep"), AgentHomes.resolve("KEEP_ME"),
                        "untouched override survives");
                assertEquals(null, AgentHomes.resolve("DROP_ME"),
                        "targeted null clear drops the entry");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        // ----------------------------------------------------------------
        // HarnessPluginCli.Claude — env wiring honors the override

        suite.test("Claude driver: CLAUDE_HOME override redirects CLAUDE_CONFIG_DIR", () -> {
            AgentHomes.clearOverrides();
            Path sandbox = Files.createTempDirectory("agent-homes-claude-");
            AgentHomes.setOverride(AgentHomes.CLAUDE_HOME, sandbox);
            try {
                CapturingRunner runner = new CapturingRunner();
                runner.script.add(new HarnessPluginCli.Result(0, "", ""));
                runner.script.add(new HarnessPluginCli.Result(0, "added", ""));
                HarnessPluginCli.Claude driver = new HarnessPluginCli.Claude(runner);

                driver.ensureMarketplaceAdded(Path.of("/tmp/some-marketplace"));

                Map<String, String> env = runner.calls.get(0).env();
                String configDir = env.get(AgentHomes.CLAUDE_CONFIG_DIR);
                assertNotNull(configDir, "CLAUDE_CONFIG_DIR set");
                assertEquals(sandbox.resolve(".claude").toString(), configDir,
                        "CLAUDE_CONFIG_DIR = $CLAUDE_HOME/.claude");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        suite.test("Claude driver: explicit CLAUDE_CONFIG_DIR override wins over CLAUDE_HOME", () -> {
            AgentHomes.clearOverrides();
            Path bespokeConfigDir = Files.createTempDirectory("agent-homes-claude-cfg-");
            AgentHomes.setOverride(AgentHomes.CLAUDE_HOME, Path.of("/should-not-be-read"));
            AgentHomes.setOverride(AgentHomes.CLAUDE_CONFIG_DIR, bespokeConfigDir);
            try {
                CapturingRunner runner = new CapturingRunner();
                runner.script.add(new HarnessPluginCli.Result(0, "", ""));
                runner.script.add(new HarnessPluginCli.Result(0, "added", ""));
                HarnessPluginCli.Claude driver = new HarnessPluginCli.Claude(runner);

                driver.ensureMarketplaceAdded(Path.of("/tmp/mp"));

                Map<String, String> env = runner.calls.get(0).env();
                assertEquals(bespokeConfigDir.toString(),
                        env.get(AgentHomes.CLAUDE_CONFIG_DIR),
                        "explicit override used verbatim, no .claude suffix appended");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        // ----------------------------------------------------------------
        // HarnessPluginCli.Codex — config path honors the override

        suite.test("Codex driver: CODEX_HOME override redirects codexConfigPath", () -> {
            AgentHomes.clearOverrides();
            Path sandbox = Files.createTempDirectory("agent-homes-codex-");
            AgentHomes.setOverride(AgentHomes.CODEX_HOME, sandbox);
            try {
                Path configPath = HarnessPluginCli.Codex.codexConfigPath();
                assertEquals(sandbox.resolve("config.toml"), configPath,
                        "codex config path under sandbox");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        // ----------------------------------------------------------------
        // TestHarness — proves the harness sandbox installs the overrides

        suite.test("TestHarness installs sandboxed overrides at create()", () -> {
            AgentHomes.clearOverrides();
            try (TestHarness harness = TestHarness.create()) {
                Path claudeHome = AgentHomes.resolve(AgentHomes.CLAUDE_HOME);
                Path claudeConfig = AgentHomes.resolve(AgentHomes.CLAUDE_CONFIG_DIR);
                Path codexHome = AgentHomes.resolve(AgentHomes.CODEX_HOME);
                assertNotNull(claudeHome, "CLAUDE_HOME override installed");
                assertNotNull(claudeConfig, "CLAUDE_CONFIG_DIR override installed");
                assertNotNull(codexHome, "CODEX_HOME override installed");
                // Sanity: they point at directories the harness created
                // under its temp root, not at the developer's real home.
                assertTrue(claudeHome.toString().contains("skill-handler-test-"),
                        "CLAUDE_HOME under harness temp root: " + claudeHome);
                assertTrue(codexHome.toString().contains("skill-handler-test-"),
                        "CODEX_HOME under harness temp root: " + codexHome);
                // The dirs actually exist on disk (harness creates them).
                assertTrue(Files.isDirectory(claudeConfig),
                        "CLAUDE_CONFIG_DIR dir exists");
                assertTrue(Files.isDirectory(codexHome),
                        "CODEX_HOME dir exists");
            }
            // After close() the overrides are gone — next harness
            // starts clean instead of inheriting the last test's state.
            assertEquals(null, AgentHomes.resolve(AgentHomes.CLAUDE_HOME),
                    "override cleared on close()");
        });

        suite.test("ClaudeAgent.pluginsDir reflects the TestHarness sandbox", () -> {
            AgentHomes.clearOverrides();
            try (TestHarness harness = TestHarness.create()) {
                Path pluginsDir = new ClaudeAgent().pluginsDir();
                String pluginsStr = pluginsDir.toString();
                assertTrue(pluginsStr.contains("skill-handler-test-"),
                        "ClaudeAgent.pluginsDir routed through sandbox: " + pluginsStr);
                assertTrue(pluginsStr.endsWith("/.claude/plugins"),
                        "...with the conventional /.claude/plugins suffix");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        return suite.runAll();
    }

    /**
     * Records every {@link HarnessPluginCli.Runner#run} call's command
     * + env so tests can assert on the subprocess wiring without
     * actually spawning anything. Returns a script of canned results,
     * popped in order.
     */
    static final class CapturingRunner implements HarnessPluginCli.Runner {
        final List<Call> calls = new ArrayList<>();
        final List<HarnessPluginCli.Result> script = new ArrayList<>();

        @Override
        public HarnessPluginCli.Result run(List<String> cmd, Map<String, String> env) {
            calls.add(new Call(cmd, env));
            if (script.isEmpty()) return new HarnessPluginCli.Result(0, "", "");
            return script.remove(0);
        }

        record Call(List<String> cmd, Map<String, String> env) {}
    }
}
