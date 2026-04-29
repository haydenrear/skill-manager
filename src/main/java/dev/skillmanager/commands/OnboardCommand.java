package dev.skillmanager.commands;

import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.GatewayRuntime;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * One-shot onboarding for a fresh checkout / install: install the two
 * bundled skills the rest of the workflow assumes are present
 * ({@code skill-manager-skill}, {@code skill-publisher-skill}), then ensure
 * the virtual MCP gateway is up.
 *
 * <p>The two skills live as subdirectories of the install root the bash
 * wrapper already exports as {@code SKILL_MANAGER_INSTALL_DIR}. We resolve
 * each path locally and feed it to the existing {@link InstallCommand} —
 * no network round-trip when the skills ship alongside the CLI. If a
 * registry has them seeded (which the {@code SkillBootstrapper} server
 * bean does on startup) the user could equally well install by name; the
 * local path is just the zero-config default.
 *
 * <p>Idempotent: an already-installed skill is reported and skipped, not
 * re-installed (the underlying install path errors on duplicate, which we
 * treat as already-onboarded). Gateway start is also a no-op when the
 * gateway is already healthy.
 */
@Command(
        name = "onboard",
        description = "Install the bundled skills and start the virtual MCP gateway."
)
public final class OnboardCommand implements Callable<Integer> {

    /**
     * Names of the bundled skill subdirectories under the install root.
     * Kept in sync with {@code SkillBootstrapper.BUNDLED_SKILLS} on the
     * server side — both lists have to agree for the install-by-name
     * fallback (against a server with bootstrap seeded) to work.
     */
    private static final List<String> BUNDLED_SKILLS = List.of(
            "skill-manager-skill",
            "skill-publisher-skill"
    );

    @Option(names = "--install-dir",
            description = "Override the install root that contains the bundled skill "
                    + "directories. Defaults to $SKILL_MANAGER_INSTALL_DIR (set by the "
                    + "skill-manager bash wrapper) or the current working directory.")
    Path installDir;

    @Option(names = "--registry",
            description = "Registry URL override (forwarded to install).")
    String registryUrl;

    @Option(names = "--skip-gateway",
            description = "Install skills only, don't ensure the gateway is up.")
    boolean skipGateway;

    @Override
    public Integer call() throws Exception {
        Path root = resolveInstallRoot();
        if (root == null) {
            Log.error("could not locate install root containing %s. "
                    + "Pass --install-dir or set SKILL_MANAGER_INSTALL_DIR.", BUNDLED_SKILLS);
            return 2;
        }
        Log.step("onboarding from %s", root);

        SkillStore store = SkillStore.defaultStore();
        store.init();

        int installed = 0;
        int skipped = 0;
        int failed = 0;
        for (String name : BUNDLED_SKILLS) {
            Path skillDir = root.resolve(name);
            if (!Files.isDirectory(skillDir) || !Files.isRegularFile(skillDir.resolve("SKILL.md"))) {
                Log.error("bundled skill %s not found at %s", name, skillDir);
                failed++;
                continue;
            }
            // Read the manifest's [skill].name (which may differ from the
            // directory name — e.g. skill-manager-skill ships skill name
            // "skill-manager"). Use that for the already-installed check.
            String skillName = readSkillName(skillDir, name);
            if (store.contains(skillName)) {
                Log.info("%s already installed at %s — skipping", skillName, store.skillDir(skillName));
                skipped++;
                continue;
            }
            int rc = invokeInstall(skillDir.toString());
            if (rc == 0) {
                Log.ok("installed %s", skillName);
                installed++;
            } else {
                // installCommand already logged its own diagnostic — we
                // just summarize at the end. Keep going so a failure on
                // skill-manager-skill doesn't hide a separate failure on
                // skill-publisher-skill.
                Log.warn("install %s exited %d", skillName, rc);
                failed++;
            }
        }

        if (!skipGateway) {
            ensureGatewayUp(store);
        }

        System.out.println();
        System.out.println("onboard summary: installed=" + installed
                + " skipped=" + skipped + " failed=" + failed
                + (skipGateway ? "" : " gateway=" + gatewayStatus(store)));
        return failed == 0 ? 0 : 1;
    }

    /**
     * Run the existing {@link InstallCommand} as if the user had typed
     * {@code skill-manager install <skillDir>} — same SkillStore commit,
     * same gateway-bootstrap, same agent-config sync. Picocli runs the
     * sub-call in the same JVM; we forward our verbose flag and registry
     * override so the underlying command sees consistent state.
     */
    private int invokeInstall(String source) throws Exception {
        InstallCommand sub = new InstallCommand();
        sub.source = source;
        sub.registryUrl = registryUrl;
        return sub.call();
    }

    private static void ensureGatewayUp(SkillStore store) throws java.io.IOException {
        GatewayConfig gw = GatewayConfig.resolve(store, null);
        GatewayClient client = new GatewayClient(gw);
        if (client.ping()) {
            Log.ok("gateway already up at %s", gw.baseUrl());
            return;
        }
        URI base = gw.baseUrl();
        String host = base.getHost() == null ? "127.0.0.1" : base.getHost();
        int port = base.getPort() > 0 ? base.getPort() : 51717;
        GatewayRuntime rt = new GatewayRuntime(store);
        try {
            if (!rt.isRunning()) {
                Log.step("starting virtual MCP gateway on %s:%d", host, port);
                rt.start(host, port);
            }
            if (rt.waitForHealthy(gw.baseUrl().toString(), Duration.ofSeconds(20))) {
                Log.ok("gateway up at %s", gw.baseUrl());
                GatewayConfig.persist(store, gw.baseUrl().toString());
            } else {
                Log.error("gateway did not become healthy within 20s; see %s", rt.logFile());
            }
        } catch (Exception e) {
            Log.error("failed to start gateway: %s", e.getMessage());
        }
    }

    private static String gatewayStatus(SkillStore store) {
        try {
            GatewayConfig gw = GatewayConfig.resolve(store, null);
            return new GatewayClient(gw).ping() ? "up" : "down";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private Path resolveInstallRoot() {
        if (installDir != null) {
            Path p = installDir.toAbsolutePath();
            return hasBundledSkills(p) ? p : null;
        }
        String env = System.getenv("SKILL_MANAGER_INSTALL_DIR");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env).toAbsolutePath();
            if (hasBundledSkills(p)) return p;
        }
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        // Walk up the same way SkillBootstrapper does so dev runs from a
        // subdir (e.g. test_graph/) still find the bundled skills.
        Path cur = cwd;
        while (cur != null) {
            if (hasBundledSkills(cur)) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean hasBundledSkills(Path candidate) {
        for (String name : BUNDLED_SKILLS) {
            if (!Files.isRegularFile(candidate.resolve(name).resolve("SKILL.md"))) return false;
        }
        return true;
    }

    /**
     * Pull {@code [skill].name} out of {@code skill-manager.toml} so we
     * use the canonical name for the already-installed check (the
     * directory name doesn't always match: {@code skill-manager-skill/}
     * publishes as {@code skill-manager}).
     */
    private static String readSkillName(Path skillDir, String fallback) {
        Path toml = skillDir.resolve("skill-manager.toml");
        if (!Files.isRegularFile(toml)) return fallback;
        try {
            boolean inSkillTable = false;
            for (String raw : Files.readAllLines(toml)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    inSkillTable = line.equals("[skill]");
                    continue;
                }
                if (!inSkillTable) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                if (!"name".equals(key)) continue;
                String value = line.substring(eq + 1).trim();
                if (value.length() >= 2
                        && (value.startsWith("\"") && value.endsWith("\"")
                            || value.startsWith("'") && value.endsWith("'"))) {
                    return value.substring(1, value.length() - 1);
                }
                return value;
            }
        } catch (Exception ignored) {}
        return fallback;
    }

}
