package dev.skillmanager.effects;

import dev.skillmanager.lifecycle.BundledSkills;
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

        if (!GitOps.isGitRepo(storeDir)) {
            // A unit the operator DELIBERATELY installed from a local path is
            // in exactly the state they asked for. It has nothing upstream, so
            // there is nothing for sync to do and nothing for anyone to fix.
            //
            // This used to record NEEDS_GIT_MIGRATION here regardless, which
            // made `file:` sources incoherent end to end: `skill-project.toml`
            // accepts `source = "file:///abs/path"`, `project resolve` installs
            // it and exits 0 printing `✓ installed <unit>` — and from then on
            // EVERY invocation (`list`, `--help`, `exec`, `home describe`,
            // `bindings list`) appended
            //
            //   ⚠ skills with outstanding errors (1) … NEEDS_GIT_MIGRATION
            //
            // with a remedy ("reinstall from a git source") that undoes the
            // thing the operator asked for. Accept, install, celebrate, then
            // error forever is not a contract either way round: either the
            // manifest schema should refuse `file:`, or a `file:` install
            // should be a state the tool is at peace with. It is the second —
            // local installs are how a unit is developed before it has a
            // remote, and skill-dev depends on them.
            //
            // The fact is still REPORTED on every sync, so "this will not
            // update" stays visible; it is a note about a choice, not an
            // outstanding error against the unit. Clearing here is also what
            // heals homes that already carry the record.
            if (src != null && src.installSource() == InstalledUnit.InstallSource.LOCAL_FILE) {
                ctx.clearError(skillName, InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION);
                return EffectReceipt.ok(e,
                        new ContextFact.SyncGitLocalInstall(skillName, src.origin()));
            }
            // Everything else that is not git-tracked genuinely cannot sync
            // and nobody chose it: a REGISTRY or GIT install whose .git is
            // gone, or a directory the reconciler found in the store with no
            // provenance at all. That is still an error.
            ctx.addError(skillName, InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION,
                    "not git-tracked; file/local installs do not sync — reinstall from github: "
                            + "or git+ source, or add a git remote");
            return EffectReceipt.partial(e, "not git-tracked",
                    new ContextFact.SyncGitNotGitTracked(skillName));
        }
        // The old condition was `!GitOps.isAvailable() || !GitOps.isGitRepo(dir)`.
        // The first half is subsumed: isGitRepo shells out to `git rev-parse`
        // and reads false when git is not on PATH, so a machine with no git
        // takes the branch above — and takes the LOCAL_FILE arm of it, which is
        // right: a local install has nothing upstream whether or not git exists.
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
        // A unit that HAS a git remote, but a local one. It syncs (from that
        // local path) and it will never see anyone else's work.
        //
        // This used to be a Log.warn per unit per sync — twenty identical
        // sentences on a twenty-unit home, every run, forever, which is how a
        // true warning becomes wallpaper. It is now a typed fact: the sentence
        // goes to the run log and the CONSOLE gets the count, in the same
        // rollup line as the units that synced from a remote. A count is also
        // the form in which it is checkable — "3 of 20 will never update" is
        // actionable in a way that three sentences among twenty are not.
        boolean localOrigin = src != null
                && src.installSource() == InstalledUnit.InstallSource.LOCAL_FILE
                && !looksLikeRemoteUrl(upstream);
        EffectReceipt receipt = syncTracked(store, ctx, e, src, skillName, storeDir, upstream);
        return localOrigin ? alsoLocalOrigin(receipt, skillName, upstream) : receipt;
    }

    /**
     * Append a {@link ContextFact.SyncGitLocalInstall} to a receipt that
     * already carries this unit's sync outcome, so the renderer can count the
     * provenance without losing what the sync actually did.
     */
    private static EffectReceipt alsoLocalOrigin(EffectReceipt r, String skillName, String origin) {
        List<ContextFact> facts = new java.util.ArrayList<>(r.facts());
        facts.add(new ContextFact.SyncGitLocalInstall(skillName, origin));
        return new EffectReceipt(r.effect(), r.status(), r.continuation(),
                facts, r.errorMessage(), r.at());
    }

    private static EffectReceipt syncTracked(SkillStore store, EffectContext ctx,
                                             SkillEffect.SyncGit e, InstalledUnit src,
                                             String skillName, Path storeDir, String upstream)
            throws IOException {
        // Dirty means either uncommitted changes or HEAD moved past the
        // source-record baseline. We still resolve the target before refusing:
        // a user may have already merged the upstream commit manually, leaving
        // only the source record stale. That case should refresh the record
        // instead of printing a no-op `sync --merge` recipe.
        String baseline = src != null ? src.gitHash() : null;
        boolean dirty = GitOps.isDirty(storeDir, baseline);

        TargetResolution tr = resolveTarget(store, ctx, e, src, skillName, storeDir, upstream, dirty);
        if (tr.fact != null) {
            if (dirty && !e.merge() && isRegistryLookupFailure(tr.fact)) {
                return EffectReceipt.partial(e, "extra local changes — re-run with --merge",
                        new ContextFact.SyncGitRefused(skillName, upstream, e.gitLatest(), false));
            }
            return EffectReceipt.ok(e, tr.fact);
        }
        TargetRef target = tr.ref;

        if (dirty && !e.merge()) {
            if (alreadyContainsTarget(storeDir, upstream, target)) {
                refreshSourceRecord(ctx, skillName, storeDir);
                return EffectReceipt.ok(e, new ContextFact.SyncGitUpToDate(skillName, target.displayLabel()));
            }
            return EffectReceipt.partial(e, "extra local changes — re-run with --merge",
                    new ContextFact.SyncGitRefused(skillName, upstream, e.gitLatest(), false));
        }

        if (!dirty && target.sha != null && target.sha.equals(baseline)) {
            return EffectReceipt.ok(e, new ContextFact.SyncGitUpToDate(skillName, target.displayLabel()));
        }
        return runGitMerge(ctx, storeDir, upstream, target.ref, skillName, e,
                allowUnrelatedHistories(src, skillName));
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
                                             SkillEffect.SyncGit effect,
                                             boolean allowUnrelatedHistories) {
        MergeResult result = runMerge(ctx, storeDir, upstream, ref, skillName, allowUnrelatedHistories);
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

    private static boolean isRegistryLookupFailure(ContextFact fact) {
        return fact instanceof ContextFact.SyncGitRegistryUnavailable
                || fact instanceof ContextFact.SyncGitAuthRequired;
    }

    private static boolean looksLikeRemoteUrl(String upstream) {
        if (upstream == null || upstream.isBlank()) return false;
        String s = upstream.trim();
        return s.startsWith("http://")
                || s.startsWith("https://")
                || s.startsWith("ssh://")
                || s.startsWith("git@");
    }

    private static boolean allowUnrelatedHistories(InstalledUnit src, String skillName) {
        return src != null
                && src.installSource() == InstalledUnit.InstallSource.LOCAL_FILE
                && BundledSkills.isBundled(skillName);
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
        return runMerge(ctx, storeDir, upstream, ref, skillName, false);
    }

    public static MergeResult runMerge(EffectContext ctx, Path storeDir, String upstream,
                                       String ref, String skillName,
                                       boolean allowUnrelatedHistories) {
        String preHead = GitOps.headHash(storeDir);
        boolean stashed = GitOps.stashAll(storeDir, "skill-manager-sync");

        String fetchedHash = GitOps.fetchRef(storeDir, upstream, ref);
        if (fetchedHash == null) {
            if (stashed) GitOps.stashPop(storeDir);
            return new MergeResult(1, null);
        }

        GitOps.MergeOutcome outcome = GitOps.mergeFetchHead(storeDir, allowUnrelatedHistories);
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
