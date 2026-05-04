package dev.skillmanager.lifecycle;

import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.SkillSource;
import dev.skillmanager.source.SkillSourceStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs at the start of every skill-manager command. Two jobs:
 *
 * <ol>
 *   <li>Onboard skills that have a store dir but no {@code sources/<name>.json}.
 *       Older installs predate source-tracking; downstream commands depend
 *       on the record for sync / upgrade decisions.</li>
 *   <li>For each {@link SkillSource.SkillError} on each skill, attempt the
 *       corrective action and validate via a fresh probe. If validation
 *       passes, drop the error from the list. Anything that doesn't
 *       validate stays for the next command to retry.</li>
 * </ol>
 *
 * <p>Failure-tolerant: never throws past the calling command. Worst case
 * an error stays on the skill and gets retried again.
 */
public final class SkillReconciler {

    private SkillReconciler() {}

    public static void reconcile(SkillStore store, GatewayConfig gw) {
        SkillSourceStore sources = new SkillSourceStore(store);
        List<Skill> installed;
        try { installed = store.listInstalled(); }
        catch (IOException e) {
            Log.warn("reconcile: could not list installed skills — %s", e.getMessage());
            return;
        }

        for (Skill skill : installed) {
            try { onboardIfMissing(store, sources, skill); }
            catch (Exception e) { Log.warn("reconcile onboard %s: %s", skill.name(), e.getMessage()); }
        }

        for (Skill skill : installed) {
            sources.read(skill.name()).ifPresent(src -> {
                if (!src.hasErrors()) return;
                for (SkillSource.SkillError err : List.copyOf(src.errors())) {
                    try { tryFix(store, gw, sources, skill, err); }
                    catch (Exception e) {
                        Log.warn("reconcile %s [%s]: %s", skill.name(), err.kind(), e.getMessage());
                    }
                }
            });
        }
    }

    private static void onboardIfMissing(SkillStore store, SkillSourceStore sources, Skill skill) throws IOException {
        if (sources.read(skill.name()).isPresent()) return;
        Path skillDir = store.skillDir(skill.name());
        SkillSource.Kind kind;
        String origin = null, hash = null, gitRef = null;
        if (GitOps.isGitRepo(skillDir)) {
            kind = SkillSource.Kind.GIT;
            origin = GitOps.originUrl(skillDir);
            hash = GitOps.headHash(skillDir);
            gitRef = GitOps.detectInstallRef(skillDir);
        } else {
            kind = SkillSource.Kind.LOCAL_DIR;
        }
        // Pre-tracking installs lose their original install-source signal.
        // UNKNOWN is the safe default — sync routes it like LOCAL_FILE
        // (git-only, no server lookup).
        SkillSource onboarded = new SkillSource(
                skill.name(), skill.version(), kind, SkillSource.InstallSource.UNKNOWN,
                origin, hash, gitRef, SkillSourceStore.nowIso(), null);
        if (kind == SkillSource.Kind.LOCAL_DIR) {
            onboarded = onboarded.withErrorAdded(new SkillSource.SkillError(
                    SkillSource.ErrorKind.NEEDS_GIT_MIGRATION,
                    "skill is not git-tracked — sync/upgrade unavailable until reinstalled from a git source",
                    SkillSourceStore.nowIso()));
        } else if (origin == null || origin.isBlank()) {
            onboarded = onboarded.withErrorAdded(new SkillSource.SkillError(
                    SkillSource.ErrorKind.NO_GIT_REMOTE,
                    "git-tracked but no origin remote configured",
                    SkillSourceStore.nowIso()));
        }
        sources.write(onboarded);
        Log.info("reconcile: onboarded %s (kind=%s)", skill.name(), kind);
    }

    private static void tryFix(SkillStore store, GatewayConfig gw, SkillSourceStore sources,
                               Skill skill, SkillSource.SkillError err) throws IOException {
        Path dir = store.skillDir(skill.name());
        switch (err.kind()) {
            case MERGE_CONFLICT -> validateMergeConflictCleared(store, sources, skill);
            case GATEWAY_UNAVAILABLE -> retryGateway(gw, sources, skill);
            case MCP_REGISTRATION_FAILED -> retryMcpRegister(gw, sources, skill);
            case NO_GIT_REMOTE -> {
                if (GitOps.isGitRepo(dir) && GitOps.originUrl(dir) != null) {
                    sources.clearError(skill.name(), SkillSource.ErrorKind.NO_GIT_REMOTE);
                    Log.ok("reconcile: %s git origin is now configured", skill.name());
                }
            }
            case NEEDS_GIT_MIGRATION -> {
                if (GitOps.isGitRepo(dir)) {
                    sources.clearError(skill.name(), SkillSource.ErrorKind.NEEDS_GIT_MIGRATION);
                    Log.ok("reconcile: %s is now git-tracked", skill.name());
                }
            }
            case REGISTRY_UNAVAILABLE -> {
                // No cheap probe — leave it for the next sync/upgrade to clear.
            }
        }
    }

    /**
     * Walk every installed skill, log a summary banner of any outstanding
     * errors with a per-error fix hint. Called at the end of every command.
     */
    public static void printOutstandingErrors(SkillStore store) {
        SkillSourceStore sources = new SkillSourceStore(store);
        List<Skill> installed;
        try { installed = store.listInstalled(); }
        catch (IOException e) { return; }

        java.util.LinkedHashMap<String, SkillSource> errored = new java.util.LinkedHashMap<>();
        for (Skill s : installed) {
            sources.read(s.name()).ifPresent(src -> {
                if (src.hasErrors()) errored.put(s.name(), src);
            });
        }
        if (errored.isEmpty()) return;

        System.err.println();
        System.err.println("⚠ skills with outstanding errors (" + errored.size() + ") — re-run after fixing:");
        for (var entry : errored.entrySet()) {
            String skillName = entry.getKey();
            SkillSource src = entry.getValue();
            Path dir = store.skillDir(skillName);
            System.err.println();
            System.err.println("  " + skillName + ":");
            for (SkillSource.SkillError err : src.errors()) {
                System.err.println("    - " + err.kind() + ": " + err.message());
                System.err.println("      → " + hint(err.kind(), skillName, dir));
            }
        }
        System.err.println();
    }

    private static String hint(SkillSource.ErrorKind kind, String skillName, Path storeDir) {
        return switch (kind) {
            case GATEWAY_UNAVAILABLE -> "start the gateway: skill-manager gateway up";
            case MCP_REGISTRATION_FAILED -> "retry: skill-manager sync " + skillName;
            case MERGE_CONFLICT -> "resolve in " + storeDir + ", then `git add` + `git commit`";
            case NO_GIT_REMOTE -> "set origin: cd " + storeDir + " && git remote add origin <url>";
            case NEEDS_GIT_MIGRATION -> "reinstall from a git source: skill-manager uninstall "
                    + skillName + " && skill-manager install github:<owner>/<repo>";
            case REGISTRY_UNAVAILABLE -> "ensure the registry is reachable, then re-run sync/upgrade "
                    + "(or use --git-latest to bypass the registry for git-tracked skills)";
        };
    }

    private static void validateMergeConflictCleared(SkillStore store, SkillSourceStore sources, Skill skill) throws IOException {
        Path dir = store.skillDir(skill.name());
        if (!GitOps.isGitRepo(dir)) return;
        if (GitOps.unmergedFiles(dir).isEmpty()) {
            sources.clearError(skill.name(), SkillSource.ErrorKind.MERGE_CONFLICT);
            Log.ok("reconcile: %s merge conflict resolved", skill.name());
        } else {
            Log.warn("reconcile: %s still has unmerged files", skill.name());
        }
    }

    private static void retryGateway(GatewayConfig gw, SkillSourceStore sources, Skill skill) throws IOException {
        if (gw == null) return;
        if (new GatewayClient(gw).ping()) {
            sources.clearError(skill.name(), SkillSource.ErrorKind.GATEWAY_UNAVAILABLE);
            Log.ok("reconcile: %s gateway-unavailable cleared", skill.name());
        }
    }

    private static void retryMcpRegister(GatewayConfig gw, SkillSourceStore sources, Skill skill) throws IOException {
        if (gw == null) return;
        GatewayClient client = new GatewayClient(gw);
        if (!client.ping()) return;
        new McpWriter(gw).registerAll(List.of(skill));
        if (allMcpDepsRegistered(client, skill)) {
            sources.clearError(skill.name(), SkillSource.ErrorKind.MCP_REGISTRATION_FAILED);
            Log.ok("reconcile: %s mcp registration recovered", skill.name());
        }
    }

    private static boolean allMcpDepsRegistered(GatewayClient client, Skill skill) {
        for (McpDependency d : skill.mcpDependencies()) {
            try { if (client.describe(d.name()).isEmpty()) return false; }
            catch (IOException e) { return false; }
        }
        return true;
    }
}
