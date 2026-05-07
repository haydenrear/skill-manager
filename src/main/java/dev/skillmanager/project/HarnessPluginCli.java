package dev.skillmanager.project;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
     */
    public static List<Driver> defaultDrivers() {
        if (Boolean.getBoolean("skill-manager.harness-cli.disabled")) {
            return List.of(new ForcedMissing("claude", "brew install claude"),
                    new ForcedMissing("codex", "brew install codex"));
        }
        return defaultDrivers(liveRunner());
    }

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
         */
        private static Map<String, String> claudeEnv() {
            String claudeHome = System.getenv("CLAUDE_HOME");
            String home = claudeHome != null && !claudeHome.isBlank()
                    ? claudeHome
                    : System.getProperty("user.home");
            return Map.of("CLAUDE_CONFIG_DIR", Path.of(home, ".claude").toString());
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

        public Codex(Runner runner) { this.runner = runner; }

        @Override public String agentId() { return "codex"; }
        @Override public String binary() { return "codex"; }
        @Override public String installHint() { return "brew install codex"; }
        @Override public boolean available() { return onPath(binary()); }

        @Override
        public Result ensureMarketplaceAdded(Path marketplaceRoot) throws IOException {
            return runner.run(
                    List.of(binary(), "plugin", "marketplace", "add", marketplaceRoot.toString()),
                    Map.of());
        }

        @Override
        public Result refreshMarketplace(Path marketplaceRoot) throws IOException {
            // codex's `marketplace upgrade <name>` only works for
            // git-backed sources — local-path marketplaces error with
            // "not configured as a Git marketplace". Re-running
            // `marketplace add` is idempotent (codex prints
            // "already added" and exits 0), so we use that as the
            // refresh verb. The act of re-adding makes codex re-read
            // the local marketplace.json on next access.
            return runner.run(
                    List.of(binary(), "plugin", "marketplace", "add", marketplaceRoot.toString()),
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
