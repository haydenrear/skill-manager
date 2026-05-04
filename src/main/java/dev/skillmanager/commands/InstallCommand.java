package dev.skillmanager.commands;

import dev.skillmanager.lifecycle.SkillSideEffects;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.GatewayRuntime;
import dev.skillmanager.plan.AuditLog;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanBuilder;
import dev.skillmanager.plan.PlanPrinter;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.resolve.Resolver;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.SkillSource;
import dev.skillmanager.source.SkillSourceStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager install <source>} is the one-shot skill install. It
 * always:
 *
 * <ul>
 *   <li>resolves the full transitive skill dep tree and commits to the store,</li>
 *   <li>installs the CLI deps every resolved skill declares,</li>
 *   <li>registers each skill's MCP deps with the virtual MCP gateway,</li>
 *   <li>copies the new skills into every known agent's skills dir,</li>
 *   <li>ensures every known agent's MCP config has the single
 *       {@code virtual-mcp-gateway} entry (idempotent — no-op if present).</li>
 * </ul>
 *
 * No flags for opting out of steps. No confirmation prompts. Fails fast if
 * the requested top-level skill is already installed — remove it first.
 */
@Command(
        name = "install",
        description = "Install a skill and everything it depends on. Sources can be a registry name "
                + "(`name[@version]`), a github coordinate (`github:user/repo`), a git URL "
                + "(`git+https://...`), or a local directory (`./path`, `/abs/path`, or `file:<path>`). "
                + "Local-directory installs do not contact the registry — useful for iterating on "
                + "a skill from a working tree without publishing first. Use `skill-manager sync "
                + "<name> --from <dir>` to refresh an already-installed skill from the same dir."
)
public final class InstallCommand implements Callable<Integer> {

    @Parameters(index = "0",
            description = "Source: name[@version] (registry), github:user/repo, git+https://..., "
                    + "or a local directory (./path, /abs/path, file:<path>) — local sources do not "
                    + "contact the registry.")
    public String source;

    @Option(names = {"--version", "--ref"}, description = "Registry version / git ref")
    public String version;

    @Option(names = "--registry",
            description = "Registry URL override for this invocation (persisted so `search` and "
                    + "`install` stay consistent). Can also be set via SKILL_MANAGER_REGISTRY_URL.")
    public String registryUrl;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        Policy.writeDefaultIfMissing(store);
        Policy policy = Policy.load(store);
        AuditLog audit = new AuditLog(store);

        if (registryUrl != null && !registryUrl.isBlank()) {
            dev.skillmanager.registry.RegistryConfig.resolve(store, registryUrl);
        }

        Log.step("resolving %s", source);
        Resolver resolver = new Resolver(store);
        ResolvedGraph graph = resolver.resolve(source, version);
        try {
            // Reject if the top-level skill is already installed.
            var resolvedList = graph.resolved();
            String top = resolvedList.isEmpty() ? null : resolvedList.get(0).name();
            if (top != null && store.contains(top)) {
                Log.error("skill '%s' is already installed at %s — remove it first (skill-manager remove %s)",
                        top, store.skillDir(top), top);
                return 3;
            }

            CliLock lock = CliLock.load(store);
            // Pass the runtime so the planner can presence-check external
            // tools (docker, brew) and emit accurate EnsureTool entries.
            dev.skillmanager.pm.PackageManagerRuntime pmRuntime =
                    new dev.skillmanager.pm.PackageManagerRuntime(store);
            InstallPlan plan = new PlanBuilder(policy, lock, pmRuntime)
                    .plan(graph, true, true, store.cliBinDir());
            PlanPrinter.print(plan);
            if (plan.blocked()) {
                Log.error("plan has blocked items — see policy at %s", store.root().resolve("policy.toml"));
                return 2;
            }

            // Ensure the gateway is reachable BEFORE committing the skill
            // to the store. If we commit first and the gateway check then
            // fails, `store.contains(top)` is permanently true and the
            // user's only recovery is `remove` + retry — contradicting the
            // "rerun" hint we'd print.
            GatewayConfig gw = GatewayConfig.resolve(store, null);
            if (!ensureGatewayRunning(store, gw)) {
                Log.error("gateway at %s is unreachable and could not be started — "
                        + "start it manually (`skill-manager gateway up`) and rerun",
                        gw.baseUrl());
                return 4;
            }

            resolver.commit(graph);
            audit.recordPlan(plan, "install");
            recordSourceProvenance(store, graph);
            for (var r : graph.resolved()) {
                System.out.println("INSTALLED: " + r.name()
                        + (r.version() == null ? "" : "@" + r.version())
                        + " -> " + store.skillDir(r.name()));
            }

            SkillSideEffects.Result result = SkillSideEffects.runPostUpdate(
                    store, gw, java.util.Map.of(), true, true);
            SkillSideEffects.printAgentConfigSummary(result, gw.mcpEndpoint().toString());
            return 0;
        } finally {
            graph.cleanup();
        }
    }

    /**
     * Ensure the virtual MCP gateway referenced by {@code gw} is reachable.
     * If its host is local and nothing is listening, start it and wait for
     * {@code /health}. Returns {@code true} when the gateway is alive by
     * the time we return.
     */
    static boolean ensureGatewayRunning(SkillStore store, GatewayConfig gw) {
        GatewayClient ping = new GatewayClient(gw);
        if (ping.ping()) return true;

        URI base = gw.baseUrl();
        String host = base.getHost();
        boolean isLocal = "127.0.0.1".equals(host) || "localhost".equals(host) || "0.0.0.0".equals(host);
        if (!isLocal) {
            Log.warn("gateway at %s is unreachable and not local — not attempting to start", gw.baseUrl());
            return false;
        }

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
                return true;
            }
            Log.error("gateway did not become healthy within 20s; see %s", rt.logFile());
            return false;
        } catch (Exception e) {
            Log.error("failed to start gateway: %s", e.getMessage());
            return false;
        }
    }

    /** Best-effort: write {@code <store>/sources/<name>.json} for each committed skill. */
    private static void recordSourceProvenance(SkillStore store, ResolvedGraph graph) {
        SkillSourceStore sources = new SkillSourceStore(store);
        String now = SkillSourceStore.nowIso();
        for (ResolvedGraph.Resolved r : graph.resolved()) {
            try {
                java.nio.file.Path skillDir = store.skillDir(r.name());
                SkillSource.Kind kind;
                String origin;
                String hash = null;
                String gitRef = null;
                if (GitOps.isGitRepo(skillDir)) {
                    kind = SkillSource.Kind.GIT;
                    String resolvedUrl = gitUrlFromSource(r.source());
                    if (resolvedUrl != null) {
                        GitOps.setOrigin(skillDir, resolvedUrl);
                        origin = resolvedUrl;
                    } else {
                        String filePath = filePathFromSource(r.source());
                        if (filePath != null
                                && GitOps.isGitRepo(java.nio.file.Path.of(filePath))) {
                            GitOps.setOrigin(skillDir, filePath);
                            origin = filePath;
                        } else {
                            origin = GitOps.originUrl(skillDir);
                        }
                    }
                    hash = GitOps.headHash(skillDir);
                    gitRef = GitOps.detectInstallRef(skillDir);
                } else {
                    kind = SkillSource.Kind.LOCAL_DIR;
                    origin = r.source();
                }
                SkillSource.InstallSource installSource = mapInstallSource(r.sourceKind());
                sources.write(new SkillSource(
                        r.name(), r.version(), kind, installSource,
                        origin, hash, gitRef, now, null));
            } catch (Exception e) {
                Log.warn("could not record source provenance for %s: %s", r.name(), e.getMessage());
            }
        }
    }

    private static SkillSource.InstallSource mapInstallSource(ResolvedGraph.SourceKind sk) {
        if (sk == null) return SkillSource.InstallSource.UNKNOWN;
        return switch (sk) {
            case REGISTRY -> SkillSource.InstallSource.REGISTRY;
            case GIT -> SkillSource.InstallSource.GIT;
            case LOCAL -> SkillSource.InstallSource.LOCAL_FILE;
        };
    }

    private static String gitUrlFromSource(String source) {
        if (source == null) return null;
        String s = source.trim();
        if (s.startsWith("github:")) {
            return "https://github.com/" + s.substring("github:".length()) + ".git";
        }
        if (s.startsWith("git+")) return s.substring("git+".length());
        if (s.startsWith("ssh://") || s.startsWith("git@") || s.endsWith(".git")) return s;
        return null;
    }

    private static String filePathFromSource(String source) {
        if (source == null) return null;
        String s = source.trim();
        if (s.startsWith("file:")) s = s.substring("file:".length());
        else if (!s.startsWith("/") && !s.startsWith("./") && !s.startsWith("../")) return null;
        try {
            return java.nio.file.Path.of(s).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return null;
        }
    }

}
