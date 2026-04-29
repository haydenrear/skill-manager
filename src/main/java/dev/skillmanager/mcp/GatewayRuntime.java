package dev.skillmanager.mcp;

import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Manages the lifecycle of the bundled virtual-mcp-gateway Python server. */
public final class GatewayRuntime {

    public static final String PID_FILE = "gateway.pid";
    public static final String LOG_FILE = "gateway.log";
    public static final String CONFIG_FILE = "gateway-config.json";

    private final SkillStore store;

    public GatewayRuntime(SkillStore store) {
        this.store = store;
    }

    public Path pidFile() { return store.root().resolve(PID_FILE); }
    public Path logFile() { return store.root().resolve(LOG_FILE); }
    public Path configFile() { return store.root().resolve(CONFIG_FILE); }

    /** Stable data directory for the gateway — where it writes {@code dynamic-servers.json} + binaries. */
    public Path gatewayDataDir() { return store.root().resolve("gateway-data"); }

    /** Location of the gateway Python package (top-level {@code virtual-mcp-gateway/} dir). */
    public Path gatewaySource() {
        String env = System.getenv("SKILL_MANAGER_GATEWAY_SRC");
        if (env != null && !env.isBlank()) return Path.of(env);
        String install = System.getenv("SKILL_MANAGER_INSTALL_DIR");
        if (install != null && !install.isBlank()) {
            return Path.of(install, "virtual-mcp-gateway");
        }
        return Path.of(System.getProperty("user.dir"), "virtual-mcp-gateway");
    }

    /** Python interpreter to use for both the gateway server and the bundled MCP client. */
    public String pythonExecutable() {
        String env = System.getenv("SKILL_MANAGER_PYTHON");
        if (env != null && !env.isBlank()) return env;
        Path venv = gatewaySource().resolve(".venv/bin/python");
        if (Files.isExecutable(venv)) return venv.toString();
        return "python3";
    }

    /**
     * Bootstrap the gateway venv via {@code uv sync} when it's missing.
     * Brew-installed users get the gateway source tree but no .venv, so the
     * first {@code gateway up} would otherwise fall back to bare python3 and
     * crash with ModuleNotFoundError. Returns silently if the venv already
     * exists or if a custom interpreter is pinned via SKILL_MANAGER_PYTHON.
     */
    public void ensureVenv() throws IOException {
        if (System.getenv("SKILL_MANAGER_PYTHON") != null) return;
        Path src = gatewaySource();
        if (Files.isExecutable(src.resolve(".venv/bin/python"))) return;
        if (!Files.isDirectory(src)) {
            throw new IOException("gateway source not found at " + src);
        }
        if (which("uv") == null) {
            throw new IOException(
                    "virtual-mcp-gateway needs a Python venv but `uv` is not on PATH.\n" +
                    "Install uv (https://docs.astral.sh/uv/) — e.g. `brew install uv` — and re-run `skill-manager gateway up`.\n" +
                    "Alternatively, point SKILL_MANAGER_PYTHON at a Python interpreter with the gateway deps already installed.");
        }
        Log.info("bootstrapping gateway venv via `uv sync` in %s", src);
        ProcessBuilder pb = new ProcessBuilder("uv", "sync", "--all-extras")
                .directory(src.toFile())
                .inheritIO();
        try {
            int rc = pb.start().waitFor();
            if (rc != 0) throw new IOException("`uv sync` exited with status " + rc);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("`uv sync` interrupted", ie);
        }
    }

    private static String which(String tool) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            Path p = Path.of(dir, tool);
            if (Files.isExecutable(p)) return p.toString();
        }
        return null;
    }

    public boolean isRunning() {
        Long pid = readPid();
        if (pid == null) return false;
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    public Long readPid() {
        Path p = pidFile();
        if (!Files.isRegularFile(p)) return null;
        try {
            String s = Files.readString(p).trim();
            return s.isEmpty() ? null : Long.parseLong(s);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    public Process start(String host, int port) throws IOException {
        if (isRunning()) throw new IOException("gateway already running (pid=" + readPid() + ")");

        Path src = gatewaySource();
        if (!Files.isDirectory(src)) {
            throw new IOException("gateway source not found at " + src +
                    " (set SKILL_MANAGER_GATEWAY_SRC or ensure virtual-mcp-gateway/ exists at repo root)");
        }

        Fs.ensureDir(store.root());
        Fs.ensureDir(gatewayDataDir());
        if (!Files.exists(configFile())) {
            Files.writeString(configFile(), "{\n  \"protocol_version\": \"2025-11-05\",\n  \"mcp_servers\": []\n}\n");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(pythonExecutable());
        cmd.add("-m");
        cmd.add("gateway.server");
        cmd.add("--config");
        cmd.add(configFile().toString());
        cmd.add("--host");
        cmd.add(host);
        cmd.add("--port");
        cmd.add(Integer.toString(port));

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(src.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile().toFile());
        // Stable dir: dynamic-servers.json + downloaded MCP binaries live here.
        pb.environment().put("VMG_DATA_DIR", gatewayDataDir().toString());
        // The gateway resolves bundled npx/uv from $SKILL_MANAGER_HOME/pm/...
        // when spawning npm-load and uv-load MCP servers, so make sure the
        // home is on the gateway's environment regardless of how the
        // operator invoked `skill-manager gateway up`.
        pb.environment().put("SKILL_MANAGER_HOME", store.root().toString());
        Process proc = pb.start();
        Files.writeString(pidFile(), Long.toString(proc.pid()));
        Log.info("gateway pid=%d log=%s", proc.pid(), logFile());
        return proc;
    }

    public boolean stop(Duration timeout) throws IOException {
        Long pid = readPid();
        if (pid == null) return false;
        var handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null || !handle.isAlive()) {
            Files.deleteIfExists(pidFile());
            return false;
        }
        handle.destroy();
        try {
            handle.onExit().get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            handle.destroyForcibly();
        }
        Files.deleteIfExists(pidFile());
        return true;
    }

    public boolean waitForHealthy(String baseUrl, Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI url = URI.create(baseUrl.replaceAll("/+$", "") + "/health");
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<Void> resp = http.send(
                        HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() / 100 == 2) return true;
            } catch (Exception ignored) {}
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }
}
