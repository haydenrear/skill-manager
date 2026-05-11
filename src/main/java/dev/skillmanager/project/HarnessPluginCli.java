package dev.skillmanager.project;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Per-harness CLI driver for the
 * {@link dev.skillmanager.effects.SkillEffect.RefreshHarnessPlugins} effect.
 *
 * <p>Each harness ({@code claude}, {@code codex}) gets one
 * {@link Driver} that:
 * <ol>
 *   <li>Detects whether its CLI is on PATH ({@link Driver#available()}).</li>
 *   <li>Adds the skill-manager marketplace if not already added
 *       ({@link Driver#ensureMarketplaceAdded(Path)}).</li>
 *   <li>Refreshes the marketplace catalog
 *       ({@link Driver#refreshMarketplace()}).</li>
 *   <li>Uninstalls + reinstalls plugins so hooks/commands picked from a
 *       new bytes set ({@link Driver#reinstallPlugin(String)}); or
 *       cleanly uninstalls a plugin no longer in the catalog
 *       ({@link Driver#uninstallPlugin(String)}).</li>
 * </ol>
 *
 * <p>{@link Claude} fully drives the lifecycle; Codex's CLI does not
 * expose a non-interactive {@code plugin install} verb, so its driver
 * stops at marketplace add/upgrade — the user finishes the install
 * through {@code codex /plugins} once. {@link Codex#reinstallPlugin}
 * and {@link Codex#uninstallPlugin} fall through as no-ops with an
 * informational note.
 *
 * <p>Subprocess invocations route through a {@link Runner}, which
 * defaults to {@link #liveRunner()} (real {@link ProcessBuilder}). Tests
 * inject a fake to capture commands and script outputs without spawning
 * processes.
 */
public final class HarnessPluginCli {

    private HarnessPluginCli() {}

    /** Outcome of a single CLI subprocess run. */
    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() { return exitCode == 0; }
    }

    /** Strategy for invoking subprocesses. */
    @FunctionalInterface
    public interface Runner {
        Result run(List<String> command, Map<String, String> envOverrides) throws IOException;
    }

    public static Runner liveRunner() {
        return (cmd, env) -> {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            if (env != null) pb.environment().putAll(env);
            Process p;
            try {
                p = pb.start();
            } catch (IOException io) {
                // Treat ENOENT (binary missing) as a non-zero exit so
                // callers don't have to wrap every invocation — the
                // available()-gate above is the primary defense, but a
                // race between probe and call could land us here.
                return new Result(127, "", io.getMessage() == null ? "" : io.getMessage());
            }
            String out = drain(p.getInputStream());
            String err = drain(p.getErrorStream());
            try {
                if (!p.waitFor(60, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return new Result(124, out, err + "\n[timeout]");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
                return new Result(130, out, err + "\n[interrupted]");
            }
            return new Result(p.exitValue(), out, err);
        };
    }

    private static String drain(java.io.InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** Per-harness CLI driver. */
    public interface Driver {
        /** Stable id matching the {@code Agent.id()} of the corresponding harness. */
        String agentId();

        /** Binary name probed on PATH. */
        String binary();

        /** Suggested install command surfaced when {@link #available} is false. */
        String installHint();

        /** Whether the binary is currently reachable on PATH. */
        boolean available();

        /**
         * Add the skill-manager marketplace at {@code marketplaceRoot} to
         * the harness's configured marketplaces if it isn't already.
         * Idempotent: a second call after success is a no-op or a
         * harness-side warning that callers ignore.
         */
        Result ensureMarketplaceAdded(Path marketplaceRoot) throws IOException;

        /**
         * Tell the harness to re-read the marketplace catalog. Run after
         * the on-disk {@code marketplace.json} changes.
         *
         * @param marketplaceRoot supplied so drivers backed by a CLI verb
         *     that only operates on git sources (Codex) can re-issue
         *     {@code marketplace add <root>} — which is idempotent for
         *     local paths and serves the same "harness, re-read your
         *     copy of this catalog" purpose.
         */
        Result refreshMarketplace(Path marketplaceRoot) throws IOException;

        /**
         * Uninstall + reinstall the plugin so newly-bundled hooks /
         * commands / agents pick up. The user explicitly asked for this
         * over update-in-place — local-path sources don't always rebuild
         * cleanly through {@code update}. Codex's driver no-ops since
         * its CLI doesn't support non-interactive install.
         */
        Result reinstallPlugin(String pluginName) throws IOException;

        /**
         * Drop the plugin from the harness's installed set. Codex's
         * driver no-ops as above.
         */
        Result uninstallPlugin(String pluginName) throws IOException;
    }

    /** Drivers shipped with skill-manager (Claude + Codex). */
    public static List<Driver> defaultDrivers(Runner runner) {
        return List.of(new Claude(runner), new Codex(runner));
    }

    /**
     * Convenience: live drivers using the real runner.
     *
     * <p>Tests set {@code skill-manager.harness-cli.disabled=true}
     * (system property) to short-circuit every driver to the
     * "unavailable" branch — this prevents accidental
     * {@code claude}/{@code codex} subprocess invocations that would
     * mutate the developer's real harness config when the CLIs are
     * installed locally. The handler treats unavailable drivers as
     * "record HARNESS_CLI_UNAVAILABLE", which is the contract the
     * unit-test sandbox wants to exercise.
     *
     * <p>Tests that need a specific driver set (e.g. one available, one
     * missing — to assert the handler's mixed-availability behavior) can
     * call {@link #overrideDriversForTesting(List)} with the list to use,
     * and {@link #clearDriverOverrideForTesting()} when done. The
     * override takes precedence over the system property.
     */
    public static List<Driver> defaultDrivers() {
        List<Driver> override = TEST_OVERRIDE.get();
        if (override != null) return override;
        if (Boolean.getBoolean("skill-manager.harness-cli.disabled")) {
            return List.of(new ForcedMissing("claude", "brew install claude"),
                    new ForcedMissing("codex", "brew install codex"));
        }
        return defaultDrivers(liveRunner());
    }

    /**
     * Test hook: substitute the driver list returned by
     * {@link #defaultDrivers()}. Thread-local so concurrent test runs
     * don't bleed.
     */
    public static void overrideDriversForTesting(List<Driver> drivers) {
        TEST_OVERRIDE.set(drivers);
    }

    /** Test hook: drop the override set by {@link #overrideDriversForTesting}. */
    public static void clearDriverOverrideForTesting() {
        TEST_OVERRIDE.remove();
    }

    private static final ThreadLocal<List<Driver>> TEST_OVERRIDE = new ThreadLocal<>();

    /**
     * Driver that always reports unavailable + throws on any subprocess
     * verb. Used when the system property
     * {@code skill-manager.harness-cli.disabled=true} is set so unit
     * tests never spawn real harness CLIs even when the binaries are
     * on PATH.
     */
    private static final class ForcedMissing implements Driver {
        private final String agentId;
        private final String hint;
        ForcedMissing(String agentId, String hint) {
            this.agentId = agentId;
            this.hint = hint;
        }
        @Override public String agentId() { return agentId; }
        @Override public String binary() { return agentId; }
        @Override public String installHint() { return hint; }
        @Override public boolean available() { return false; }
        @Override public Result ensureMarketplaceAdded(Path root) throws IOException {
            throw new IOException(agentId + " disabled (test sandbox)");
        }
        @Override public Result refreshMarketplace(Path root) throws IOException {
            throw new IOException(agentId + " disabled (test sandbox)");
        }
        @Override public Result reinstallPlugin(String name) throws IOException {
            throw new IOException(agentId + " disabled (test sandbox)");
        }
        @Override public Result uninstallPlugin(String name) throws IOException {
            throw new IOException(agentId + " disabled (test sandbox)");
        }
    }

    /** Probe PATH for {@code bin} (no environment lookups beyond {@code PATH}). */
    public static boolean onPath(String bin) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return false;
        for (String part : path.split(java.io.File.pathSeparator)) {
            if (part.isBlank()) continue;
            Path candidate = Path.of(part, bin);
            if (Files.isExecutable(candidate)) return true;
        }
        return false;
    }

    // ----------------------------------------------------------- Claude

    /**
     * Drives {@code claude plugin marketplace add}, {@code update},
     * {@code install}, and {@code uninstall} against the
     * {@code skill-manager} marketplace.
     *
     * <p>Subprocess env: forwards the parent {@code PATH}; sets
     * {@code CLAUDE_CONFIG_DIR=$CLAUDE_HOME/.claude} so installs land
     * inside the harness home the rest of skill-manager is writing to
     * (vital for test runs that override {@code CLAUDE_HOME}).
     */
    public static final class Claude implements Driver {

        private final Runner runner;

        public Claude(Runner runner) { this.runner = runner; }

        @Override public String agentId() { return "claude"; }
        @Override public String binary() { return "claude"; }
        @Override public String installHint() { return "brew install claude"; }
        @Override public boolean available() { return onPath(binary()); }

        @Override
        public Result ensureMarketplaceAdded(Path marketplaceRoot) throws IOException {
            // `claude plugin marketplace add` is idempotent in practice
            // — a second add of the same name surfaces a warning the
            // caller can ignore. We always try add; a non-zero exit
            // bubbles up so the effect handler can record an error.
            Result list = runner.run(
                    List.of(binary(), "plugin", "marketplace", "list"),
                    claudeEnv());
            if (list.ok() && list.stdout().contains(PluginMarketplace.NAME)) {
                return new Result(0, "already-added", "");
            }
            return runner.run(
                    List.of(binary(), "plugin", "marketplace", "add",
                            marketplaceRoot.toString(), "--scope", "user"),
                    claudeEnv());
        }

        @Override
        public Result refreshMarketplace(Path marketplaceRoot) throws IOException {
            return runner.run(
                    List.of(binary(), "plugin", "marketplace", "update", PluginMarketplace.NAME),
                    claudeEnv());
        }

        @Override
        public Result reinstallPlugin(String pluginName) throws IOException {
            // Uninstall first so the harness drops cached hook /
            // command / agent state from the previous bytes; ignore
            // failure (the plugin may not be installed yet — that's
            // the install-from-fresh case). Then install.
            runner.run(
                    List.of(binary(), "plugin", "uninstall",
                            pluginName + "@" + PluginMarketplace.NAME),
                    claudeEnv());
            return runner.run(
                    List.of(binary(), "plugin", "install",
                            pluginName + "@" + PluginMarketplace.NAME,
                            "--scope", "user"),
                    claudeEnv());
        }

        @Override
        public Result uninstallPlugin(String pluginName) throws IOException {
            return runner.run(
                    List.of(binary(), "plugin", "uninstall",
                            pluginName + "@" + PluginMarketplace.NAME),
                    claudeEnv());
        }

        /**
         * {@code CLAUDE_CONFIG_DIR} is what the Claude CLI honors; it's
         * derived from skill-manager's {@code CLAUDE_HOME} (parent of
         * {@code .claude/}). Passing it explicitly means a test run that
         * overrides {@code CLAUDE_HOME} keeps the CLI's writes inside
         * the same sandboxed home.
         *
         * <p>An explicit {@link dev.skillmanager.agent.AgentHomes#CLAUDE_CONFIG_DIR}
         * override takes precedence over the {@link dev.skillmanager.agent.AgentHomes#CLAUDE_HOME}
         * + {@code .claude} composition — needed for tests that want to
         * point CLI subprocess writes at a fully bespoke config root,
         * not just at {@code $CLAUDE_HOME/.claude}.
         */
        private static Map<String, String> claudeEnv() {
            Path explicit = dev.skillmanager.agent.AgentHomes
                    .resolve(dev.skillmanager.agent.AgentHomes.CLAUDE_CONFIG_DIR);
            if (explicit != null) {
                return Map.of(dev.skillmanager.agent.AgentHomes.CLAUDE_CONFIG_DIR,
                        explicit.toString());
            }
            Path home = dev.skillmanager.agent.AgentHomes.resolveOrDefault(
                    dev.skillmanager.agent.AgentHomes.CLAUDE_HOME,
                    Path.of(System.getProperty("user.home")));
            return Map.of(dev.skillmanager.agent.AgentHomes.CLAUDE_CONFIG_DIR,
                    home.resolve(".claude").toString());
        }
    }

    // ----------------------------------------------------------- Codex

    /**
     * Codex's CLI exposes only {@code plugin marketplace add} +
     * {@code upgrade} non-interactively. Final plugin install / uninstall
     * happens through Codex's interactive {@code /plugins} UI. This
     * driver therefore manages the marketplace registration and refresh
     * but no-ops on {@link #reinstallPlugin} / {@link #uninstallPlugin}
     * — the marketplace.json regeneration plus a marketplace upgrade is
     * enough for the user's next {@code /plugins} session to pick up
     * the new state.
     *
     * <p>{@code CODEX_HOME} is honored by both skill-manager's
     * {@code CodexAgent} and the Codex CLI itself, so no extra env
     * plumbing is needed.
     */
    public static final class Codex implements Driver {

        private final Runner runner;
        private final Path configPathOverride;

        public Codex(Runner runner) { this(runner, null); }

        /**
         * Constructor used by tests to point at a fixture
         * {@code config.toml} instead of {@code $CODEX_HOME/config.toml}
         * — so per-driver behavior can be exercised without depending
         * on whatever's in the developer's real codex state.
         */
        public Codex(Runner runner, Path configPathOverride) {
            this.runner = runner;
            this.configPathOverride = configPathOverride;
        }

        @Override public String agentId() { return "codex"; }
        @Override public String binary() { return "codex"; }
        @Override public String installHint() { return "brew install codex"; }
        @Override public boolean available() { return onPath(binary()); }

        @Override
        public Result ensureMarketplaceAdded(Path marketplaceRoot) throws IOException {
            return ensureRegisteredAt(marketplaceRoot);
        }

        @Override
        public Result refreshMarketplace(Path marketplaceRoot) throws IOException {
            // codex's `marketplace upgrade <name>` only works for
            // git-backed sources — local-path marketplaces error with
            // "not configured as a Git marketplace". Re-running
            // `marketplace add` is idempotent for the same source path
            // (codex prints "already added" and exits 0), but it errors
            // with "already added from a different source" if the path
            // changed (e.g. a new $SKILL_MANAGER_HOME, or a stale
            // registration left behind by a prior install). We resolve
            // both shapes via the shared {@link #ensureRegisteredAt}
            // path: same path → no-op, mismatched path → remove +
            // re-add (skill-manager owns this marketplace name), absent
            // → add.
            return ensureRegisteredAt(marketplaceRoot);
        }

        /**
         * Reconcile codex's recorded source for the {@code skill-manager}
         * marketplace with {@code marketplaceRoot}:
         *
         * <ul>
         *   <li>Already registered with the same source path → no-op.</li>
         *   <li>Registered with a different source path → run
         *       {@code marketplace remove skill-manager} (skill-manager
         *       owns the marketplace name; a different path here means
         *       a stale registration we should fix) followed by
         *       {@code add}. Both subprocess outcomes flow into the
         *       returned {@link Result}.</li>
         *   <li>Not registered → run {@code add}.</li>
         * </ul>
         */
        private Result ensureRegisteredAt(Path marketplaceRoot) throws IOException {
            String desired = marketplaceRoot.toAbsolutePath().toString();
            Optional<String> existing = readMarketplaceSource(
                    configPathOverride != null ? configPathOverride : codexConfigPath(),
                    PluginMarketplace.NAME);
            if (existing.isPresent()) {
                String existingPath = existing.get();
                if (sameSource(existingPath, desired)) {
                    return new Result(0,
                            "already added at " + existingPath,
                            "");
                }
                // Stale registration at a different path. Remove first;
                // ignore non-zero exits (the registration may have been
                // partially torn down already). Then re-add.
                Result removed = runner.run(
                        List.of(binary(), "plugin", "marketplace", "remove", PluginMarketplace.NAME),
                        Map.of());
                Result added = runner.run(
                        List.of(binary(), "plugin", "marketplace", "add", desired),
                        Map.of());
                String msg = "stale registration at " + existingPath
                        + " replaced with " + desired
                        + (removed.ok() ? "" : " (remove rc=" + removed.exitCode() + ")");
                return new Result(added.exitCode(), msg, added.stderr());
            }
            return runner.run(
                    List.of(binary(), "plugin", "marketplace", "add", desired),
                    Map.of());
        }

        @Override
        public Result reinstallPlugin(String pluginName) {
            return new Result(0, "[codex: install via /plugins UI]", "");
        }

        @Override
        public Result uninstallPlugin(String pluginName) {
            return new Result(0, "[codex: uninstall via /plugins UI]", "");
        }

        /**
         * Resolve {@code $CODEX_HOME/config.toml} (or {@code ~/.codex/config.toml}
         * when the env var isn't set) — the file codex's CLI reads/writes
         * when managing marketplaces. Routed through {@link
         * dev.skillmanager.agent.AgentHomes} so a test-harness override
         * pins this to a sandbox dir instead of the real {@code ~/.codex}.
         *
         * <p>Public so {@code AgentHomesTest} can assert the override
         * plumbing without driving an entire subprocess scenario — the
         * resolution rule is the contract, not an internal detail.
         */
        public static Path codexConfigPath() {
            Path root = dev.skillmanager.agent.AgentHomes.resolveOrDefault(
                    dev.skillmanager.agent.AgentHomes.CODEX_HOME,
                    Path.of(System.getProperty("user.home"), ".codex"));
            return root.resolve("config.toml");
        }

        /**
         * Read the {@code [marketplaces.&lt;name&gt;].source} value out of
         * codex's {@code config.toml}, or {@link Optional#empty()} when
         * the file doesn't exist, isn't parseable, or doesn't list this
         * marketplace. Package-private so tests can supply a fixture
         * config without involving the CLI.
         */
        static Optional<String> readMarketplaceSource(Path configPath, String marketplaceName) {
            if (!Files.isRegularFile(configPath)) return Optional.empty();
            try {
                TomlParseResult parsed = Toml.parse(Files.readString(configPath));
                String key = "marketplaces." + marketplaceName + ".source";
                String value = parsed.getString(key);
                return Optional.ofNullable(value);
            } catch (IOException io) {
                return Optional.empty();
            }
        }

        /**
         * True if {@code a} and {@code b} resolve to the same on-disk
         * location. Handles symlink + relative-vs-absolute differences
         * codex's recorded path may have ({@code /private/tmp/...} vs
         * {@code /tmp/...} on macOS, etc.). Falls back to string equality
         * when either path doesn't exist (e.g. a stale registration
         * pointing at a deleted dir).
         */
        private static boolean sameSource(String a, String b) {
            if (a.equals(b)) return true;
            try {
                Path pa = Path.of(a).toAbsolutePath().normalize();
                Path pb = Path.of(b).toAbsolutePath().normalize();
                if (pa.equals(pb)) return true;
                if (Files.exists(pa) && Files.exists(pb)) {
                    return pa.toRealPath().equals(pb.toRealPath());
                }
                return false;
            } catch (IOException io) {
                return false;
            }
        }
    }

    /**
     * Build the {@code HARNESS_CLI_UNAVAILABLE} message body listing
     * which harness CLIs the user is missing and how to install each.
     * Returns {@code null} when every driver has its CLI on PATH —
     * callers treat that as "clear the error".
     */
    public static String missingHint(List<Driver> drivers) {
        List<String> missing = new ArrayList<>();
        for (Driver d : drivers) if (!d.available()) missing.add(d.binary() + " (try: " + d.installHint() + ")");
        if (missing.isEmpty()) return null;
        return "missing harness CLI on PATH: " + String.join(", ", missing);
    }

}
