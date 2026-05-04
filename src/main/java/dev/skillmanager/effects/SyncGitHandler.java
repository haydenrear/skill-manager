package dev.skillmanager.effects;

import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.SkillSource;
import dev.skillmanager.source.SkillSourceStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Per-skill git stash → fetch → merge → pop, plus install-source routing
 * (REGISTRY → registry git_sha; GIT/LOCAL/UNKNOWN → tracked gitRef from origin).
 *
 * <p>Pulled out of {@code SyncCommand} so {@code sync} and {@code upgrade}
 * compose the same effect program. The handler is stateless — every input
 * comes through the {@link SkillEffect.SyncGit} record and the
 * {@link EffectContext} for source-record reads/writes.
 *
 * <p>Receipt facts are typed {@link ContextFact}s — one of
 * {@link ContextFact.SyncGitMerged}, {@link ContextFact.SyncGitConflicted},
 * {@link ContextFact.SyncGitRefused}, {@link ContextFact.SyncGitFailed},
 * {@link ContextFact.SyncGitUpToDate}, {@link ContextFact.SyncGitNotGitTracked},
 * {@link ContextFact.SyncGitNoOrigin},
 * {@link ContextFact.SyncGitRegistryUnavailable}, or
 * {@link ContextFact.SyncGitNoUpgradeNeeded}.
 */
public final class SyncGitHandler {

    private SyncGitHandler() {}

    public static EffectReceipt run(SkillEffect.SyncGit e, EffectContext ctx) throws IOException {
        SkillStore store = ctx.store();
        String skillName = e.skillName();
        Path storeDir = store.skillDir(skillName);
        SkillSource src = ctx.source(skillName).orElse(null);

        if (!GitOps.isAvailable() || !GitOps.isGitRepo(storeDir)) {
            ctx.addError(skillName, SkillSource.ErrorKind.NEEDS_GIT_MIGRATION,
                    "not git-tracked — reinstall from a git source to enable sync/upgrade");
            return EffectReceipt.partial(e, "not git-tracked",
                    new ContextFact.SyncGitNotGitTracked(skillName));
        }
        ctx.clearError(skillName, SkillSource.ErrorKind.NEEDS_GIT_MIGRATION);

        String upstream = src != null && src.origin() != null && !src.origin().isBlank()
                ? src.origin()
                : GitOps.originUrl(storeDir);
        if (upstream == null || upstream.isBlank()) {
            ctx.addError(skillName, SkillSource.ErrorKind.NO_GIT_REMOTE,
                    "git-tracked but no origin remote configured");
            return EffectReceipt.partial(e, "no origin remote",
                    new ContextFact.SyncGitNoOrigin(skillName));
        }
        ctx.clearError(skillName, SkillSource.ErrorKind.NO_GIT_REMOTE);

        TargetResolution tr = resolveTarget(store, ctx, e, src, skillName, storeDir, upstream);
        if (tr.fact != null) {
            return EffectReceipt.ok(e, tr.fact);
        }
        TargetRef target = tr.ref;

        String baseline = src != null ? src.gitHash() : null;
        boolean dirty = GitOps.isDirty(storeDir, baseline);
        if (!dirty && target.sha != null && target.sha.equals(baseline)) {
            Log.ok("%s: already at %s (%s)", skillName, target.displayLabel(),
                    baseline.substring(0, Math.min(7, baseline.length())));
            return EffectReceipt.ok(e, new ContextFact.SyncGitUpToDate(skillName, target.displayLabel()));
        }
        if (dirty && !e.merge()) {
            printMergeInstructions(skillName, storeDir, upstream, e.gitLatest());
            return EffectReceipt.partial(e, "extra local changes — re-run with --merge",
                    new ContextFact.SyncGitRefused(skillName));
        }
        return runGitMerge(ctx, storeDir, upstream, target.ref, skillName, e);
    }

    private static void printMergeInstructions(String skillName, Path storeDir, String upstream,
                                               boolean gitLatest) {
        Log.error("%s has extra local changes (working tree edits or commits ahead of installed baseline).",
                skillName);
        System.err.println();
        System.err.println("Sync would overwrite them. Re-run with --merge:");
        System.err.println();
        String flags = (gitLatest ? " --git-latest" : "") + " --merge";
        System.err.println("    skill-manager sync " + skillName + flags);
        System.err.println();
        System.err.println("Or merge by hand:");
        System.err.println();
        System.err.println("    cd " + storeDir);
        System.err.println("    git fetch " + upstream + " HEAD");
        System.err.println("    git merge FETCH_HEAD");
        System.err.println();
    }

    private static TargetResolution resolveTarget(SkillStore store, EffectContext ctx,
                                                  SkillEffect.SyncGit e, SkillSource src,
                                                  String skillName, Path storeDir,
                                                  String upstream) throws IOException {
        if (e.gitLatest()) {
            String tracked = src != null ? src.gitRef() : null;
            if (tracked != null && !tracked.isBlank()) {
                return TargetResolution.ref(new TargetRef(tracked, null, null));
            }
            Log.warn("%s: install was sha-pinned (no branch/tag tracked); --git-latest fetches remote HEAD",
                    skillName);
            return TargetResolution.ref(new TargetRef("HEAD", null, null));
        }

        SkillSource.InstallSource installSource = src != null && src.installSource() != null
                ? src.installSource()
                : SkillSource.InstallSource.UNKNOWN;

        if (installSource == SkillSource.InstallSource.REGISTRY) {
            ServerVersion sv = lookupServerVersion(store, skillName);
            if (sv == null) {
                ctx.addError(skillName, SkillSource.ErrorKind.REGISTRY_UNAVAILABLE,
                        "registry didn't return a git_sha for latest " + skillName);
                return TargetResolution.fact(new ContextFact.SyncGitRegistryUnavailable(skillName));
            }
            ctx.clearError(skillName, SkillSource.ErrorKind.REGISTRY_UNAVAILABLE);
            String localVer = src != null ? src.version() : null;
            if (localVer != null && compareVersions(localVer, sv.version) >= 0) {
                Log.ok("%s: at %s (>= registry's latest %s) — no upgrade needed",
                        skillName, localVer, sv.version);
                return TargetResolution.fact(new ContextFact.SyncGitNoUpgradeNeeded(skillName, localVer));
            }
            return TargetResolution.ref(new TargetRef(sv.gitSha, sv.gitSha, "v" + sv.version));
        }

        // Non-registry installs: always pull from git remote. No version compare.
        String tracked = src != null ? src.gitRef() : null;
        if (tracked != null && !tracked.isBlank()) {
            return TargetResolution.ref(new TargetRef(tracked, null, tracked));
        }
        String defaultBranch = GitOps.remoteDefaultBranch(storeDir, upstream);
        if (defaultBranch != null) {
            return TargetResolution.ref(new TargetRef(defaultBranch, null, defaultBranch));
        }
        return TargetResolution.ref(new TargetRef("HEAD", null, "HEAD"));
    }

    private static EffectReceipt runGitMerge(EffectContext ctx, Path storeDir,
                                             String upstream, String ref, String skillName,
                                             SkillEffect.SyncGit effect) {
        MergeResult result = runMerge(ctx, storeDir, upstream, ref, skillName);
        return switch (result.rc) {
            case 0 -> EffectReceipt.ok(effect,
                    new ContextFact.SyncGitMerged(skillName, result.fetchedHash));
            case 8 -> EffectReceipt.partial(effect, "merge conflict",
                    new ContextFact.SyncGitConflicted(skillName));
            default -> EffectReceipt.failed(effect,
                    List.of(new ContextFact.SyncGitFailed(skillName, "git fetch/merge rc=" + result.rc)),
                    "git fetch/merge failed (rc=" + result.rc + ")");
        };
    }

    /**
     * Stash → fetch → merge → pop. Public helper so the {@code --from} path in
     * {@code SyncCommand} can run the same merge against a local-dir upstream
     * without duplicating the rollback / conflict / stash-pop bookkeeping.
     *
     * @return rc — 0 ok, 1 fetch/merge failure (rolled back), 8 merge or stash-pop conflict.
     */
    public static MergeResult runMerge(EffectContext ctx, Path storeDir, String upstream,
                                       String ref, String skillName) {
        String preHead = GitOps.headHash(storeDir);
        boolean stashed = GitOps.stashAll(storeDir, "skill-manager-sync");

        Log.step("git fetch %s %s && git merge FETCH_HEAD (in %s)", upstream, ref, storeDir);
        String fetchedHash = GitOps.fetchRef(storeDir, upstream, ref);
        if (fetchedHash == null) {
            Log.error("`git fetch %s %s` failed", upstream, ref);
            if (stashed) GitOps.stashPop(storeDir);
            return new MergeResult(1, null);
        }

        GitOps.MergeOutcome outcome = GitOps.mergeFetchHead(storeDir);
        if (!outcome.ok()) {
            if (!outcome.conflictedFiles().isEmpty()) {
                logConflict(skillName, storeDir, outcome.conflictedFiles());
                tryAddError(ctx, skillName, SkillSource.ErrorKind.MERGE_CONFLICT,
                        "merge conflict against " + upstream + " " + ref);
                return new MergeResult(8, null);
            }
            GitOps.mergeAbort(storeDir);
            GitOps.resetHard(storeDir, preHead);
            if (stashed) GitOps.stashPop(storeDir);
            return new MergeResult(1, null);
        }

        if (stashed && !GitOps.stashPop(storeDir)) {
            List<String> conflicted = GitOps.unmergedFiles(storeDir);
            logConflict(skillName, storeDir, conflicted);
            tryAddError(ctx, skillName, SkillSource.ErrorKind.MERGE_CONFLICT,
                    "stash pop conflict after merging " + upstream + " " + ref
                            + " — local changes preserved at stash@{0}");
            return new MergeResult(8, null);
        }

        Log.ok("%s: merged %s", skillName, fetchedHash.substring(0, Math.min(7, fetchedHash.length())));
        try {
            ctx.source(skillName).ifPresent(old -> {
                try {
                    ctx.writeSource(old.withGitMoved(GitOps.headHash(storeDir), SkillSourceStore.nowIso()));
                } catch (IOException ex) {
                    Log.warn("could not refresh source record for %s: %s", skillName, ex.getMessage());
                }
            });
            ctx.clearError(skillName, SkillSource.ErrorKind.MERGE_CONFLICT);
        } catch (Exception ex) {
            Log.warn("could not refresh source record for %s: %s", skillName, ex.getMessage());
        }
        return new MergeResult(0, fetchedHash);
    }

    public record MergeResult(int rc, String fetchedHash) {}

    private static void logConflict(String skillName, Path storeDir, List<String> conflicted) {
        Log.error("%s: merge conflict in %d file(s):", skillName, conflicted.size());
        for (String f : conflicted) System.err.println("    " + f);
        System.err.println();
        System.err.println("Resolve in " + storeDir + ", then `git add` + `git commit`,");
        System.err.println("or `git merge --abort` (and `git stash drop` if applicable) to back out.");
    }

    private static void tryAddError(EffectContext ctx, String skillName,
                                    SkillSource.ErrorKind kind, String message) {
        try { ctx.addError(skillName, kind, message); }
        catch (IOException e) { Log.warn("could not record error for %s: %s", skillName, e.getMessage()); }
    }

    private record TargetRef(String ref, String sha, String label) {
        String displayLabel() { return label != null ? label : ref; }
    }

    /** Either the resolved target ref to fetch, or a terminal fact (already up-to-date / registry down). */
    private record TargetResolution(TargetRef ref, ContextFact fact) {
        static TargetResolution ref(TargetRef ref) { return new TargetResolution(ref, null); }
        static TargetResolution fact(ContextFact fact) { return new TargetResolution(null, fact); }
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

    /** Naive numeric semver compare (X.Y.Z); pre-release suffixes treated as equal. */
    static int compareVersions(String a, String b) {
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
}
