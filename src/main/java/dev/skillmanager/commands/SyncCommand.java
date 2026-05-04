package dev.skillmanager.commands;

import dev.skillmanager.app.PostUpdateUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.lifecycle.SkillReconciler;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.SkillSource;
import dev.skillmanager.source.SkillSourceStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code sync [name] [--from <dir>] [--git-latest] [--merge]} — pull upstream
 * changes for git-tracked installs and re-run the install side-effects
 * pipeline (tools, CLI deps, MCP register, agent symlinks).
 *
 * <p>Resolution order for the merge target ref:
 * <ul>
 *   <li>{@code --from <dir>} given → uses that dir as the upstream.</li>
 *   <li>{@code --git-latest} given → fetches the install-time {@code gitRef}
 *       (branch / tag) from the recorded origin.</li>
 *   <li>Default → asks the registry for the latest published version's
 *       {@code git_sha}; falls back to the install-time {@code gitRef} when
 *       the skill isn't in the registry (github-direct installs).</li>
 * </ul>
 *
 * <p>The merge stashes any working-tree changes (staged + unstaged + untracked)
 * before fetching, then pops the stash on top of the merged HEAD. A conflicting
 * stash-pop sets the skill's status to {@link SkillSource.Status#MERGE_CONFLICT};
 * the next command's reconciler clears it once the user resolves.
 *
 * <p>Pre-flight: gateway is checked before any git mutation. Non-git installs
 * are not supported — convert them to a git source if you want sync.
 */
@Command(name = "sync",
        description = "Pull upstream + re-run install side effects for git-tracked skills.")
public final class SyncCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Skill name to sync (default: all installed)")
    public String name;

    @Option(names = "--from",
            description = "Local directory to pull skill content from (must contain SKILL.md). "
                    + "Without --merge: shows diff and prompts before overwriting. "
                    + "With --merge and a git-backed source: 3-way merge against the source's HEAD. "
                    + "Requires <name>.")
    public Path fromDir;

    @Option(names = {"-y", "--yes"},
            description = "Skip the approval prompt for --from.")
    public boolean yes;

    @Option(names = "--merge",
            description = "Allow a real 3-way merge against the resolved upstream when local edits exist. "
                    + "Conflicts leave the working tree in conflicted state and set the skill's "
                    + "status to MERGE_CONFLICT until resolved.")
    public boolean merge;

    @Option(names = "--git-latest",
            description = "Skip the registry; fetch the install-time gitRef (branch / tag) instead of the "
                    + "server-published version's git_sha.")
    public boolean gitLatest;

    @Option(names = "--registry",
            description = "Registry URL override for this invocation (persisted).")
    public String registryUrl;

    @Option(names = "--skip-agents",
            description = "Don't refresh agent symlinks or MCP-config entries.")
    public boolean skipAgents;

    @Option(names = "--skip-mcp",
            description = "Don't re-register MCP servers with the gateway.")
    public boolean skipMcp;

    @Option(names = "--dry-run",
            description = "Build the post-update Program and print the effects it would run, "
                    + "without mutating the filesystem, gateway, or registry. Git fetch/merge "
                    + "still happens (sync's primary job is the merge); the dry-run only "
                    + "covers the post-update side-effects pipeline.")
    public boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();

        if (fromDir != null && (name == null || name.isBlank())) {
            Log.error("--from requires a skill name");
            return 2;
        }
        if (registryUrl != null && !registryUrl.isBlank()) {
            RegistryConfig.resolve(store, registryUrl);
        }

        // Don't fail-fast on gateway unreachable: the RegisterMcp effect
        // records GATEWAY_UNAVAILABLE per skill and the next command's
        // reconciler retries. Git work still happens — the user can
        // recover the MCP-side later.
        GatewayConfig gw = GatewayConfig.resolve(store, null);
        if (!skipMcp) InstallCommand.ensureGatewayRunning(store, gw);

        SkillReconciler.reconcile(store, gw);

        Map<String, Set<String>> preMcpDeps = PostUpdateUseCase.snapshotMcpDeps(store);

        int gitSyncRc = 0;
        List<Skill> targets;
        if (name != null && !name.isBlank()) {
            if (!store.contains(name)) {
                Log.error("not installed: %s", name);
                return 1;
            }
            int rc = (fromDir != null)
                    ? applyFromLocalDir(store, name, fromDir)
                    : applyFromImplicitOrigin(store, name);
            if (rc != 0 && rc != 7 && rc != 8) return rc;
            gitSyncRc = rc;
            targets = List.of(store.load(name).orElseThrow());
        } else {
            targets = store.listInstalled();
            if (!targets.isEmpty()) {
                gitSyncRc = syncAllGit(store, targets);
                targets = store.listInstalled();
            }
        }
        if (targets.isEmpty()) {
            Log.warn("no skills installed");
            return 0;
        }

        Program<PostUpdateUseCase.Report> program = PostUpdateUseCase.buildProgram(
                store, gw, preMcpDeps, !skipMcp, !skipAgents);
        ProgramInterpreter interpreter = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store);
        PostUpdateUseCase.Report report = interpreter.run(program);
        PostUpdateUseCase.printAgentConfigSummary(report, gw.mcpEndpoint().toString());
        return gitSyncRc;
    }

    private int syncAllGit(SkillStore store, List<Skill> targets) {
        List<String> refused = new ArrayList<>();
        List<String> conflicted = new ArrayList<>();
        int worstRc = 0;
        for (Skill s : targets) {
            int rc;
            try { rc = applyFromImplicitOrigin(store, s.name()); }
            catch (Exception e) {
                Log.warn("%s: git sync failed — %s", s.name(), e.getMessage());
                continue;
            }
            if (rc == 7) refused.add(s.name());
            else if (rc == 8) conflicted.add(s.name());
            if (rc > worstRc) worstRc = rc;
        }
        if (!refused.isEmpty() || !conflicted.isEmpty()) printSyncAllSummary(refused, conflicted);
        return worstRc;
    }

    private static void printSyncAllSummary(List<String> refused, List<String> conflicted) {
        System.err.println();
        System.err.println("sync summary: " + (refused.size() + conflicted.size()) + " skill(s) need attention");
        if (!refused.isEmpty()) {
            System.err.println();
            System.err.println("  Extra local changes — re-run with --merge to bring upstream in:");
            for (String n : refused) System.err.println("    skill-manager sync " + n + " --merge");
        }
        if (!conflicted.isEmpty()) {
            System.err.println();
            System.err.println("  Conflicted — resolve in the store dir, then `git commit` or `git merge --abort`:");
            for (String n : conflicted) System.err.println("    " + n);
        }
        System.err.println();
    }

    private int applyFromImplicitOrigin(SkillStore store, String skillName) throws IOException {
        Path storeDir = store.skillDir(skillName);
        SkillSourceStore sources = new SkillSourceStore(store);
        SkillSource src = sources.read(skillName).orElse(null);

        if (!GitOps.isAvailable() || !GitOps.isGitRepo(storeDir)) {
            sources.addError(skillName, SkillSource.ErrorKind.NEEDS_GIT_MIGRATION,
                    "not git-tracked — reinstall from a git source to enable sync/upgrade");
            return 0;
        }
        sources.clearError(skillName, SkillSource.ErrorKind.NEEDS_GIT_MIGRATION);

        String upstream = src != null && src.origin() != null && !src.origin().isBlank()
                ? src.origin()
                : GitOps.originUrl(storeDir);
        if (upstream == null || upstream.isBlank()) {
            sources.addError(skillName, SkillSource.ErrorKind.NO_GIT_REMOTE,
                    "git-tracked but no origin remote configured");
            return 0;
        }
        sources.clearError(skillName, SkillSource.ErrorKind.NO_GIT_REMOTE);

        TargetRef target = resolveTarget(store, sources, src, skillName, storeDir, upstream);
        if (target == null) return 0;  // resolveTarget already recorded the cause

        String baseline = src != null ? src.gitHash() : null;
        boolean dirty = GitOps.isDirty(storeDir, baseline);
        if (!dirty && target.sha != null && target.sha.equals(baseline)) {
            Log.ok("%s: already at %s (%s)", skillName, target.displayLabel(),
                    baseline.substring(0, Math.min(7, baseline.length())));
            return 0;
        }
        if (dirty && !merge) {
            printMergeInstructions(skillName, storeDir, upstream, true, false);
            return 7;
        }
        return runGitMerge(store, sources, storeDir, upstream, target.ref, skillName);
    }

    /**
     * Routes by {@link SkillSource.InstallSource}:
     * <ul>
     *   <li>REGISTRY: must reach the server. {@link SkillSource.ErrorKind#REGISTRY_UNAVAILABLE}
     *       on failure. Won't downgrade — if local version &gt;= server version, no-op.</li>
     *   <li>GIT / LOCAL_FILE: pull from the recorded gitRef (or remote default branch).</li>
     * </ul>
     * {@code --git-latest} bypasses the routing entirely — always uses the recorded gitRef.
     */
    private TargetRef resolveTarget(SkillStore store, SkillSourceStore sources, SkillSource src,
                                    String skillName, Path storeDir, String upstream) throws IOException {
        if (gitLatest) {
            String tracked = src != null ? src.gitRef() : null;
            if (tracked != null && !tracked.isBlank()) return new TargetRef(tracked, null, null);
            Log.warn("%s: install was sha-pinned (no branch/tag tracked); --git-latest fetches remote HEAD",
                    skillName);
            return new TargetRef("HEAD", null, null);
        }

        SkillSource.InstallSource installSource = src != null && src.installSource() != null
                ? src.installSource()
                : SkillSource.InstallSource.UNKNOWN;

        if (installSource == SkillSource.InstallSource.REGISTRY) {
            ServerVersion sv = lookupServerVersion(store, skillName);
            if (sv == null) {
                sources.addError(skillName, SkillSource.ErrorKind.REGISTRY_UNAVAILABLE,
                        "registry didn't return a git_sha for latest " + skillName);
                return null;
            }
            sources.clearError(skillName, SkillSource.ErrorKind.REGISTRY_UNAVAILABLE);
            String localVer = src != null ? src.version() : null;
            if (localVer != null && compareVersions(localVer, sv.version) >= 0) {
                Log.ok("%s: at %s (>= registry's latest %s) — no upgrade needed",
                        skillName, localVer, sv.version);
                return null;
            }
            return new TargetRef(sv.gitSha, sv.gitSha, "v" + sv.version);
        }

        // Non-registry installs: always pull from git remote. No version compare.
        String tracked = src != null ? src.gitRef() : null;
        if (tracked != null && !tracked.isBlank()) return new TargetRef(tracked, null, tracked);
        String defaultBranch = GitOps.remoteDefaultBranch(storeDir, upstream);
        if (defaultBranch != null) return new TargetRef(defaultBranch, null, defaultBranch);
        return new TargetRef("HEAD", null, "HEAD");
    }

    /** Naive numeric semver compare (X.Y.Z); pre-release suffixes treated as equal. */
    private static int compareVersions(String a, String b) {
        if (a == null || b == null) return 0;
        String[] aParts = a.split("[.\\-]");
        String[] bParts = b.split("[.\\-]");
        int n = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < n; i++) {
            int ai = i < aParts.length ? parseIntSafe(aParts[i]) : 0;
            int bi = i < bParts.length ? parseIntSafe(bParts[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private record TargetRef(String ref, String sha, String label) {
        String displayLabel() { return label != null ? label : ref; }
    }

    private record ServerVersion(String version, String gitSha, String githubUrl) {}

    private static ServerVersion lookupServerVersion(SkillStore store, String skillName) {
        try {
            RegistryClient registry = RegistryClient.authenticated(store, RegistryConfig.resolve(store, null));
            Map<String, Object> meta = registry.describeVersion(skillName, "latest");
            String gitSha = (String) meta.get("git_sha");
            if (gitSha == null || gitSha.isBlank()) return null;
            return new ServerVersion(
                    (String) meta.get("version"), gitSha, (String) meta.get("github_url"));
        } catch (Exception e) {
            return null;
        }
    }

    private int applyFromLocalDir(SkillStore store, String skillName, Path fromDir) throws Exception {
        Path src = fromDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(src)) {
            Log.error("--from is not a directory: %s", src);
            return 1;
        }
        if (!Files.isRegularFile(src.resolve(SkillParser.SKILL_FILENAME))) {
            Log.error("--from %s is not a skill directory (missing %s)", src, SkillParser.SKILL_FILENAME);
            return 1;
        }
        Path storeDir = store.skillDir(skillName);
        SkillSourceStore sources = new SkillSourceStore(store);
        SkillSource src0 = sources.read(skillName).orElse(null);
        boolean storeIsGit = GitOps.isGitRepo(storeDir);
        boolean srcIsGit = GitOps.isGitRepo(src);

        if (storeIsGit && GitOps.isAvailable()) {
            String baseline = src0 != null ? src0.gitHash() : null;
            boolean dirty = GitOps.isDirty(storeDir, baseline);
            if (dirty && !merge) {
                printMergeInstructions(skillName, storeDir, src.toString(), srcIsGit, true);
                return 7;
            }
            if (merge) {
                if (!srcIsGit) {
                    if (dirty) {
                        printMergeInstructions(skillName, storeDir, src.toString(), false, true);
                        return 7;
                    }
                } else {
                    return runGitMerge(store, sources, storeDir, src.toString(), "HEAD", skillName);
                }
            }
        }

        Log.step("git diff --no-index --name-status %s %s", storeDir, src);
        StringBuilder summary = new StringBuilder();
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--no-index", "--name-status",
                "--", storeDir.toString(), src.toString())
                .redirectErrorStream(true);
        Process p;
        try { p = pb.start(); }
        catch (IOException e) {
            Log.error("`git` not available on PATH: %s", e.getMessage());
            return 1;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println(line);
                summary.append(line).append('\n');
            }
        }
        int rc = p.waitFor();
        if (rc == 0 || summary.length() == 0) {
            Log.ok("%s: store and %s are identical — nothing to apply", skillName, src);
            return 0;
        }
        if (rc != 1) {
            Log.error("`git diff --no-index --name-status` exited %d", rc);
            return 1;
        }

        System.out.println();
        System.out.println("To inspect the full diff, run:");
        System.out.println();
        System.out.println("    git diff --no-index " + storeDir + " " + src);
        System.out.println();

        if (!yes) {
            System.out.print("Apply these changes to " + storeDir + "? [y/N] ");
            System.out.flush();
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
            String input = stdin.readLine();
            if (input == null || !input.trim().toLowerCase().startsWith("y")) {
                Log.warn("aborted; no changes applied");
                return 0;
            }
        }
        Fs.deleteRecursive(storeDir);
        Fs.copyRecursive(src, storeDir);
        Log.ok("%s: applied changes from %s", skillName, src);
        return 0;
    }

    private void printMergeInstructions(String skillName, Path storeDir, String upstream,
                                        boolean upstreamIsGit, boolean explicitFrom) {
        Log.error("%s has extra local changes (working tree edits or commits ahead of installed baseline).",
                skillName);
        System.err.println();
        if (upstreamIsGit) {
            System.err.println("Sync would overwrite them. Re-run with --merge:");
            System.err.println();
            String flags = (explicitFrom ? " --from " + upstream : "")
                    + (gitLatest ? " --git-latest" : "")
                    + " --merge";
            System.err.println("    skill-manager sync " + skillName + flags);
            System.err.println();
            System.err.println("Or merge by hand:");
            System.err.println();
            System.err.println("    cd " + storeDir);
            System.err.println("    git fetch " + upstream + " HEAD");
            System.err.println("    git merge FETCH_HEAD");
        } else {
            System.err.println("Source dir is not a git repo — no upstream branch to merge.");
            System.err.println("Inspect the diff and apply changes by hand:");
            System.err.println();
            System.err.println("    git diff --no-index " + storeDir + " " + upstream);
        }
        System.err.println();
    }

    /**
     * Stash → fetch → merge → pop. Stash gives us natural rollback (reset +
     * pop) on any unexpected failure, and pop conflicts surface
     * local-vs-upstream collisions as MERGE_CONFLICT state for the
     * reconciler to validate on subsequent commands.
     */
    private int runGitMerge(SkillStore store, SkillSourceStore sources, Path storeDir,
                            String upstream, String ref, String skillName) {
        String preHead = GitOps.headHash(storeDir);
        boolean stashed = GitOps.stashAll(storeDir, "skill-manager-sync");

        Log.step("git fetch %s %s && git merge FETCH_HEAD (in %s)", upstream, ref, storeDir);
        String fetchedHash = GitOps.fetchRef(storeDir, upstream, ref);
        if (fetchedHash == null) {
            Log.error("`git fetch %s %s` failed", upstream, ref);
            if (stashed) GitOps.stashPop(storeDir);
            return 1;
        }

        GitOps.MergeOutcome outcome = GitOps.mergeFetchHead(storeDir);
        if (!outcome.ok()) {
            if (!outcome.conflictedFiles().isEmpty()) {
                logConflict(skillName, storeDir, outcome.conflictedFiles());
                tryAddError(sources, skillName, SkillSource.ErrorKind.MERGE_CONFLICT,
                        "merge conflict against " + upstream + " " + ref);
                return 8;
            }
            GitOps.mergeAbort(storeDir);
            GitOps.resetHard(storeDir, preHead);
            if (stashed) GitOps.stashPop(storeDir);
            return 1;
        }

        if (stashed && !GitOps.stashPop(storeDir)) {
            // Pop conflict — local edits collided with merged content. Stash entry
            // stays at stash@{0} so the user can finish the merge by hand.
            List<String> conflicted = GitOps.unmergedFiles(storeDir);
            logConflict(skillName, storeDir, conflicted);
            tryAddError(sources, skillName, SkillSource.ErrorKind.MERGE_CONFLICT,
                    "stash pop conflict after merging " + upstream + " " + ref
                            + " — local changes preserved at stash@{0}");
            return 8;
        }

        Log.ok("%s: merged %s", skillName, fetchedHash.substring(0, Math.min(7, fetchedHash.length())));
        try {
            sources.read(skillName).ifPresent(old -> {
                try { sources.write(old.withGitMoved(GitOps.headHash(storeDir), SkillSourceStore.nowIso())); }
                catch (IOException e) { Log.warn("could not refresh source record for %s: %s", skillName, e.getMessage()); }
            });
            sources.clearError(skillName, SkillSource.ErrorKind.MERGE_CONFLICT);
        } catch (Exception e) {
            Log.warn("could not refresh source record for %s: %s", skillName, e.getMessage());
        }
        return 0;
    }

    private static void logConflict(String skillName, Path storeDir, List<String> conflicted) {
        Log.error("%s: merge conflict in %d file(s):", skillName, conflicted.size());
        for (String f : conflicted) System.err.println("    " + f);
        System.err.println();
        System.err.println("Resolve in " + storeDir + ", then `git add` + `git commit`,");
        System.err.println("or `git merge --abort` (and `git stash drop` if applicable) to back out.");
    }

    private static void tryAddError(SkillSourceStore sources, String skillName,
                                    SkillSource.ErrorKind kind, String message) {
        try { sources.addError(skillName, kind, message); }
        catch (IOException e) { Log.warn("could not record error for %s: %s", skillName, e.getMessage()); }
    }
}
