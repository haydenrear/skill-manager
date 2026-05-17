package dev.skillmanager.effects;

import dev.skillmanager.registry.AuthenticationRequiredException;
import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.registry.RegistryUnavailableException;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
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
        String skillName = e.unitName();
        Path storeDir = store.unitDir(skillName, e.kind());
        InstalledUnit src = ctx.source(skillName).orElse(null);

        if (!GitOps.isAvailable() || !GitOps.isGitRepo(storeDir)) {
            // Bundled skills (skill-manager / skill-publisher) ship in-tree
            // with the CLI and have no .git/ on purpose — see issue #44.
            // Suppress the persistent NEEDS_GIT_MIGRATION error for them.
            if (!dev.skillmanager.lifecycle.BundledSkills.isBundled(skillName)) {
                ctx.addError(skillName, InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION,
                        "not git-tracked — reinstall from a git source to enable sync/upgrade");
            }
            return EffectReceipt.partial(e, "not git-tracked",
                    new ContextFact.SyncGitNotGitTracked(skillName));
        }
        ctx.clearError(skillName, InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION);

        String upstream = src != null && src.origin() != null && !src.origin().isBlank()
                ? src.origin()
                : GitOps.originUrl(storeDir);
        if (upstream == null || upstream.isBlank()) {
            ctx.addError(skillName, InstalledUnit.ErrorKind.NO_GIT_REMOTE,
                    "git-tracked but no origin remote configured");
            return EffectReceipt.partial(e, "no origin remote",
                    new ContextFact.SyncGitNoOrigin(skillName));
        }
        ctx.clearError(skillName, InstalledUnit.ErrorKind.NO_GIT_REMOTE);

        // Dirty means either uncommitted changes or HEAD moved past the
        // source-record baseline. We still resolve the target before refusing:
        // a user may have already merged the upstream commit manually, leaving
        // only the source record stale. That case should refresh the record
        // instead of printing a no-op `sync --merge` recipe.
        String baseline = src != null ? src.gitHash() : null;
        boolean dirty = GitOps.isDirty(storeDir, baseline);

        TargetResolution tr = resolveTarget(store, ctx, e, src, skillName, storeDir, upstream, dirty);
        if (tr.fact != null) {
            return EffectReceipt.ok(e, tr.fact);
        }
        TargetRef target = tr.ref;

        if (dirty && !e.merge()) {
            if (alreadyContainsTarget(storeDir, upstream, target)) {
                refreshSourceRecord(ctx, skillName, storeDir);
                return EffectReceipt.ok(e, new ContextFact.SyncGitUpToDate(skillName, target.displayLabel()));
            }
            return EffectReceipt.partial(e, "extra local changes — re-run with --merge",
                    new ContextFact.SyncGitRefused(skillName, upstream, e.gitLatest()));
        }

        if (!dirty && target.sha != null && target.sha.equals(baseline)) {
            return EffectReceipt.ok(e, new ContextFact.SyncGitUpToDate(skillName, target.displayLabel()));
        }
        return runGitMerge(ctx, storeDir, upstream, target.ref, skillName, e);
    }

    private static TargetResolution resolveTarget(SkillStore store, EffectContext ctx,
                                                  SkillEffect.SyncGit e, InstalledUnit src,
                                                  String skillName, Path storeDir,
                                                  String upstream, boolean dirty) throws IOException {
        if (e.gitLatest()) {
            String tracked = src != null ? src.gitRef() : null;
            if (tracked != null && !tracked.isBlank()) {
                return TargetResolution.ref(new TargetRef(tracked, null, null));
            }
            return TargetResolution.ref(new TargetRef("HEAD", null, null));
        }

        InstalledUnit.InstallSource installSource = src != null && src.installSource() != null
                ? src.installSource()
                : InstalledUnit.InstallSource.UNKNOWN;

        if (installSource == InstalledUnit.InstallSource.REGISTRY) {
            VersionLookup lookup = lookupServerVersion(store, skillName);
            // Exhaustive switch over the sealed VersionLookup so adding
            // a new failure mode (or success variant) lights up a
            // compile error in this exact arm. The four current
            // outcomes each map to a distinct ContextFact + ErrorKind
            // pair so the closing report can point the user at the
            // right remediation.
            return switch (lookup) {
                case VersionLookup.Found(ServerVersion sv) -> {
                    ctx.clearError(skillName, InstalledUnit.ErrorKind.REGISTRY_UNAVAILABLE);
                    ctx.clearError(skillName, InstalledUnit.ErrorKind.AUTHENTICATION_NEEDED);
                    String localVer = src != null ? src.version() : null;
                    // Only short-circuit on "no upgrade needed" when the working tree
                    // is clean. If dirty + --merge, the user explicitly wants to fold
                    // local changes against upstream even though versions match —
                    // fall through to the merge against the recorded git_sha.
                    if (!dirty && localVer != null && compareVersions(localVer, sv.version) >= 0) {
                        yield TargetResolution.fact(new ContextFact.SyncGitNoUpgradeNeeded(skillName, localVer));
                    }
                    yield TargetResolution.ref(new TargetRef(sv.gitSha, sv.gitSha, "v" + sv.version));
                }
                case VersionLookup.AuthRequired(String message) -> {
                    ctx.addError(skillName, InstalledUnit.ErrorKind.AUTHENTICATION_NEEDED, message);
                    yield TargetResolution.fact(new ContextFact.SyncGitAuthRequired(skillName, message));
                }
                case VersionLookup.Unreachable(String message) -> {
                    ctx.addError(skillName, InstalledUnit.ErrorKind.REGISTRY_UNAVAILABLE, message);
                    yield TargetResolution.fact(new ContextFact.SyncGitRegistryUnavailable(skillName));
                }
                case VersionLookup.Empty ignored -> {
                    ctx.addError(skillName, InstalledUnit.ErrorKind.REGISTRY_UNAVAILABLE,
                            "registry didn't return a git_sha for latest " + skillName);
                    yield TargetResolution.fact(new ContextFact.SyncGitRegistryUnavailable(skillName));
                }
            };
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
                    new ContextFact.SyncGitConflicted(skillName, result.conflictedFiles));
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

        String fetchedHash = GitOps.fetchRef(storeDir, upstream, ref);
        if (fetchedHash == null) {
            if (stashed) GitOps.stashPop(storeDir);
            return new MergeResult(1, null);
        }

        GitOps.MergeOutcome outcome = GitOps.mergeFetchHead(storeDir);
        if (!outcome.ok()) {
            if (!outcome.conflictedFiles().isEmpty()) {
                tryAddError(ctx, skillName, InstalledUnit.ErrorKind.MERGE_CONFLICT,
                        "merge conflict against " + upstream + " " + ref);
                return new MergeResult(8, null, outcome.conflictedFiles());
            }
            GitOps.mergeAbort(storeDir);
            GitOps.resetHard(storeDir, preHead);
            if (stashed) GitOps.stashPop(storeDir);
            return new MergeResult(1, null);
        }

        if (stashed && !GitOps.stashPop(storeDir)) {
            List<String> conflicted = GitOps.unmergedFiles(storeDir);
            tryAddError(ctx, skillName, InstalledUnit.ErrorKind.MERGE_CONFLICT,
                    "stash pop conflict after merging " + upstream + " " + ref
                            + " — local changes preserved at stash@{0}");
            return new MergeResult(8, null, conflicted);
        }

        refreshSourceRecord(ctx, skillName, storeDir);
        return new MergeResult(0, fetchedHash);
    }

    public record MergeResult(int rc, String fetchedHash, List<String> conflictedFiles) {
        public MergeResult(int rc, String fetchedHash) { this(rc, fetchedHash, List.of()); }
    }

    private static void tryAddError(EffectContext ctx, String skillName,
                                    InstalledUnit.ErrorKind kind, String message) {
        try { ctx.addError(skillName, kind, message); }
        catch (IOException e) { Log.warn("could not record error for %s: %s", skillName, e.getMessage()); }
    }

    private static boolean alreadyContainsTarget(Path storeDir, String upstream, TargetRef target) {
        if (GitOps.hasWorktreeChanges(storeDir)) return false;

        String targetHash = target.sha();
        if (targetHash == null || !GitOps.isAncestor(storeDir, targetHash, "HEAD")) {
            String fetchedHash = GitOps.fetchRef(storeDir, upstream, target.ref());
            if (fetchedHash == null) return false;
            targetHash = fetchedHash;
        }
        return GitOps.isAncestor(storeDir, targetHash, "HEAD");
    }

    private static void refreshSourceRecord(EffectContext ctx, String skillName, Path storeDir) {
        try {
            ctx.source(skillName).ifPresent(old -> {
                try {
                    ctx.writeSource(old.withGitMoved(GitOps.headHash(storeDir), UnitStore.nowIso()));
                } catch (IOException ex) {
                    Log.warn("could not refresh source record for %s: %s", skillName, ex.getMessage());
                }
            });
            ctx.clearError(skillName, InstalledUnit.ErrorKind.MERGE_CONFLICT);
        } catch (Exception ex) {
            Log.warn("could not refresh source record for %s: %s", skillName, ex.getMessage());
        }
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

    /**
     * Sealed result for the registry version lookup. Lets the caller
     * pattern-match exhaustively on the four possible outcomes —
     * {@code Found}, {@code AuthRequired}, {@code Unreachable},
     * {@code Empty} — each of which translates to a distinct
     * {@link InstalledUnit.ErrorKind} and {@link ContextFact}. The
     * older "return null on any failure" shape collapsed all three
     * failure modes into "registry unavailable" and lost the
     * auth-needed signal — users got "start the registry" guidance
     * when their refresh token had expired.
     */
    sealed interface VersionLookup {
        record Found(ServerVersion sv) implements VersionLookup {}
        record AuthRequired(String message) implements VersionLookup {}
        record Unreachable(String message) implements VersionLookup {}
        record Empty() implements VersionLookup {}
    }

    static VersionLookup lookupServerVersion(SkillStore store, String skillName) {
        try {
            RegistryClient registry = RegistryClient.authenticated(store, RegistryConfig.resolve(store, null));
            Map<String, Object> meta = registry.describeVersion(skillName, "latest");
            String gitSha = (String) meta.get("git_sha");
            if (gitSha == null || gitSha.isBlank()) return new VersionLookup.Empty();
            return new VersionLookup.Found(new ServerVersion(
                    (String) meta.get("version"), gitSha, (String) meta.get("github_url")));
        } catch (AuthenticationRequiredException auth) {
            // Refresh token also expired or never set — the structured
            // error gets surfaced through the closing report's banner so
            // the user sees a `skill-manager login` hint per affected
            // unit, instead of the auth-required exception bubbling out
            // of the effect (where runOne would just record it as a
            // FAILED-receipt string and lose the actionable signal).
            return new VersionLookup.AuthRequired(
                    auth.getMessage() == null
                            ? "registry refused cached credentials"
                            : auth.getMessage());
        } catch (RegistryUnavailableException down) {
            return new VersionLookup.Unreachable(
                    down.getMessage() == null
                            ? "registry at " + down.baseUrl() + " is not reachable"
                            : down.getMessage());
        } catch (IOException io) {
            // Non-2xx status, malformed body, mid-response TCP reset.
            // Surfacing as REGISTRY_UNAVAILABLE keeps the pre-existing
            // user-visible behavior for these less-common shapes.
            Log.warn("registry: lookup of %s failed — %s", skillName, io.getMessage());
            return new VersionLookup.Unreachable(
                    "registry lookup failed: " + io.getMessage());
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
