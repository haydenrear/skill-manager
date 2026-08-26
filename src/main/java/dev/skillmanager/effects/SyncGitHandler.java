package dev.skillmanager.effects;

import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.lifecycle.BundledSkills;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.registry.AuthenticationRequiredException;
import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.registry.RegistryUnavailableException;
import dev.skillmanager.source.DereferencedStoreLinks;
import dev.skillmanager.source.MaterializationEscrow;
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
        //
        // "Uncommitted changes" is asked of what an AUTHOR did, not of what the
        // materializer did. A child home holds a real directory where the unit
        // tracks a symlink into the parent store, because that is what makes it
        // independent (CHM-5) -- and git reads it as a deletion. Counting it as
        // local work is what left 2 of 21 units in the operator's project home
        // permanently unsyncable: the refusal protected nothing, and its own
        // remedy could not clear it. See DereferencedStoreLinks, including why
        // this is not licence to overwrite those paths -- runMerge carries them
        // across the merge window and puts them back.
        String baseline = src != null ? src.gitHash() : null;
        boolean dirty = isAuthoredDirty(storeDir, baseline);

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
        BaselineWatch watch = BaselineWatch.before(store, skillName, e.kind());
        EffectReceipt receipt = runGitMerge(ctx, storeDir, upstream, target.ref, skillName, e,
                allowUnrelatedHistories(src, skillName));
        if (receipt.status() == EffectStatus.OK) watch.afterUpstreamMove();
        return receipt;
    }

    /**
     * The materialization record's half of an upstream sync.
     *
     * <p>A sync moves the store copy to what upstream holds and refreshes
     * {@code installed/<unit>.json}. The per-unit materialization record --
     * the baseline every reconcile, prune and close-out reads -- was left
     * naming the tree the home was cloned with (#210). This pairs the two
     * moments a sync has to ask about it: {@link #before} establishes, while
     * it is still true, that the copy stood on its own record with nothing
     * local in it; {@link #afterUpstreamMove} then restates the record against
     * the tree upstream just wrote. A copy that carried an edit or a commit
     * before the sync fails the first question and keeps its record, so the
     * edit remains an edit afterwards.
     *
     * <p>Failures here are logged and swallowed: the sync itself succeeded and
     * a record that could not be rewritten is exactly as stale as it was
     * before, which every reader already tolerates.
     */
    public static final class BaselineWatch {
        private final SkillStore home;
        private final String name;
        private final UnitKind kind;
        private final boolean pristine;

        private BaselineWatch(SkillStore home, String name, UnitKind kind, boolean pristine) {
            this.home = home;
            this.name = name;
            this.kind = kind;
            this.pristine = pristine;
        }

        public static BaselineWatch before(SkillStore home, String name, UnitKind kind) {
            boolean pristine = false;
            if (home != null && name != null && kind != null) {
                try {
                    pristine = ChildHomeMaterializer.standsOnItsCopyRecord(home, name, kind);
                } catch (IOException | RuntimeException ex) {
                    Log.warn("could not read the materialization record for %s before sync: %s",
                            name, ex.getMessage());
                }
            }
            return new BaselineWatch(home, name, kind, pristine);
        }

        /** Was the copy a pristine, record-described tree when {@link #before} looked? */
        public boolean wasPristine() { return pristine; }

        /** Restate the record against the tree upstream wrote, if the copy was pristine before. */
        public void afterUpstreamMove() {
            if (!pristine) return;
            try {
                ChildHomeMaterializer.restateBaseline(home, name, kind);
            } catch (IOException | RuntimeException ex) {
                Log.warn("could not restate the materialization baseline for %s after sync: %s",
                        name, ex.getMessage());
            }
        }
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
        // Lift the dereferenced store links OUT of the working tree before git
        // is allowed to look at it, and put them back at the end. Not an
        // optimization: while they are there, `git stash` and `git merge` both
        // see a directory where the repository holds mode 120000, and neither
        // can express that. Measured outcomes with them left in place, on the
        // synthetic project-tier fixture in
        // results/epic-home-integrity-sync/probes/his-4/:
        //   * the stash pop conflicts -> MERGE_CONFLICT, stash@{0} abandoned,
        //     no MERGE_HEAD, and a printed remedy (`git add` + `git commit`)
        //     with nothing to act on;
        //   * or the merge "succeeds" and SILENTLY REVERTS the materialization,
        //     leaving the child home pointing at a symlink again (run1) or with
        //     the tree deleted outright (run2).
        // Both are the exclusion rule from carryOverUnownedTrees, broken on the
        // git surface: not compared, not copied, and NOT DESTROYED.
        MaterializationEscrow escrow = MaterializationEscrow.lift(storeDir, homeRootOf(ctx), true);
        try {
            return mergeWithoutMaterializedTrees(ctx, storeDir, upstream, ref, skillName,
                    allowUnrelatedHistories, preHead);
        } finally {
            escrow.restore();
        }
    }

    private static MergeResult mergeWithoutMaterializedTrees(
            EffectContext ctx, Path storeDir, String upstream, String ref, String skillName,
            boolean allowUnrelatedHistories, String preHead) {
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
            // LINK 3, AND THE ONLY ONE THAT MAKES THE STATE PERMANENT.
            //
            // The merge above has already COMMITTED. Returning here without
            // undoing it leaves HEAD ahead of installed/<unit>.json forever,
            // and every later sync then reports "commits ahead of the installed
            // baseline" and refuses -- measured on the operator's home as
            // record c72d03a6 (2026-07-25) against store HEAD eab28837, nine
            // days later, on a unit nobody had edited.
            //
            // So the merge is ROLLED BACK rather than left standing, and the
            // stash re-popped onto the pre-merge HEAD it was taken from, where
            // it applies. The plan offered the alternative -- write the
            // advanced baseline together with the recorded conflict -- and this
            // is the safer of the two: it leaves the store exactly where the
            // operator left it, so a conflict costs a retry rather than a
            // repository state nobody asked for. A failed sync now changes
            // nothing at all.
            //
            // THE CONFLICT IS READ BEFORE THE ROLLBACK, and that ordering is
            // the whole of review finding HIGH-2. Reading it after means
            // reading it from a tree the rollback has just made clean: the
            // recovery pop succeeds, no stages remain, no MERGE_HEAD exists,
            // and the caller is handed an EMPTY conflict set. Measured, with
            // the read in the wrong place:
            //
            //   ✗ <unit>: merge conflict in 0 file(s):
            //   ✗   already clear ... the record has not caught up
            //   errors after ONE later command: []
            //
            // -- a genuinely divergent unit told nothing is wrong, by a remedy
            // that names no files, over a record that then erases itself on the
            // next command. That is DEF-015's shape manufactured rather than
            // deferred, and it fails this ticket's own acceptance item that the
            // remedy for a divergent unit changes the verdict.
            List<String> conflicted = GitOps.unmergedFiles(storeDir);
            GitOps.resetHard(storeDir, preHead);
            boolean recovered = GitOps.stashPop(storeDir);
            if (recovered) {
                // NO MERGE_CONFLICT IS RECORDED, and that is the second half of
                // review finding HIGH-2. The rollback succeeded, so the store is
                // byte-for-byte where it started: not mid-merge, no unmerged
                // paths, nothing for anyone to resolve IN GIT. An error recorded
                // here would be one whose own probe -- unmergedFiles().isEmpty()
                // -- reports "resolved" on the very next command, so it would
                // erase itself while the divergence it named was untouched.
                // That is precisely DEF-015's shape, and manufacturing it here
                // to look thorough would be worse than not recording it.
                //
                // The condition is not lost: "local work that conflicts with
                // upstream" is re-derived by the dirty gate on every single
                // sync, which is what makes it safe to leave unrecorded. The
                // caller still gets rc=8 and the file list, so the exit code and
                // the printed remedy are unaffected.
                tryClearError(ctx, skillName, InstalledUnit.ErrorKind.MERGE_CONFLICT);
            } else {
                // The rollback could not restore the local work, so there IS a
                // durable condition -- the stash -- and it must be recorded.
                tryAddError(ctx, skillName, InstalledUnit.ErrorKind.MERGE_CONFLICT,
                        "local changes conflict with " + upstream + " " + ref + " in "
                                + conflicted.size() + " file(s); the merge was rolled back but "
                                + "the local work could not be reapplied — it is at stash@{0}");
            }
            return new MergeResult(8, null, conflicted);
        }

        refreshSourceRecord(ctx, skillName, storeDir);
        return new MergeResult(0, fetchedHash);
    }

    public record MergeResult(int rc, String fetchedHash, List<String> conflictedFiles) {
        public MergeResult(int rc, String fetchedHash) { this(rc, fetchedHash, List.of()); }
    }

    private static void tryClearError(EffectContext ctx, String skillName,
                                      InstalledUnit.ErrorKind kind) {
        try { ctx.clearError(skillName, kind); }
        catch (IOException e) { Log.warn("could not clear %s on %s: %s", kind, skillName, e.getMessage()); }
    }

    private static void tryAddError(EffectContext ctx, String skillName,
                                    InstalledUnit.ErrorKind kind, String message) {
        try { ctx.addError(skillName, kind, message); }
        catch (IOException e) { Log.warn("could not record error for %s: %s", skillName, e.getMessage()); }
    }

    /**
     * {@code GitOps.isDirty}, asked about what an AUTHOR did.
     *
     * <p>Same two halves — uncommitted work, or HEAD past the recorded baseline
     * — with the first half blind to {@linkplain DereferencedStoreLinks
     * dereferenced store links}, which the materializer wrote and no author
     * did. The second half is untouched on purpose: a HEAD ahead of the
     * baseline is HIS-4's link 3 and it must still stop an overwrite. What
     * releases a home already in that state is
     * {@link #alreadyContainsTarget} below, which now gets a chance to run
     * because the first half no longer answers "dirty" first.
     */
    private static boolean isAuthoredDirty(Path storeDir, String baselineHash) {
        return DereferencedStoreLinks.isAuthoredDirty(storeDir, baselineHash);
    }

    /**
     * <p>THE RELEASE PATH for a home stranded behind a merge a previous sync
     * committed without writing its baseline. HEAD already contains the target,
     * so there is nothing to merge and nothing to overwrite: the record is
     * restated against the tree and the unit is syncable again, on an ORDINARY
     * {@code sync}. Before HIS-4 this could not be reached from a materialized
     * child home, because the dereferenced tree made the guard on the first
     * line answer "there is local work here" and the caller refused first. The
     * operator's two units were recovered by hand instead — {@code git reset},
     * deleting the scaffold trees, then {@code sync --merge} — which is not a
     * remedy anything can print.
     */
    /**
     * {@code GitOps.isDirty}, asked about what an AUTHOR did.
     *
     * <p>Same two halves — uncommitted work, or HEAD past the recorded baseline
     * — with the first half blind to {@linkplain DereferencedStoreLinks
     * dereferenced store links}, which the materializer wrote and no author
     * did. The second half is untouched on purpose: a HEAD ahead of the
     * baseline is HIS-4's link 3 and it must still stop an overwrite. What
     * releases a home already in that state is
     * {@link #alreadyContainsTarget} below, which now gets a chance to run
     * because the first half no longer answers "dirty" first.
     */
    /** The home whose {@code cache/} the escrow parks bytes in; null if unavailable. */
    private static Path homeRootOf(EffectContext ctx) {
        try { return ctx.store() != null ? ctx.store().root() : null; }
        catch (Exception e) { return null; }
    }

    /**
     * <p>THE RELEASE PATH for a home stranded behind a merge a previous sync
     * committed without writing its baseline. HEAD already contains the target,
     * so there is nothing to merge and nothing to overwrite: the record is
     * restated against the tree and the unit is syncable again, on an ORDINARY
     * {@code sync}. Before HIS-4 this could not be reached from a materialized
     * child home, because the dereferenced tree made the guard on the first
     * line answer "there is local work here" and the caller refused first. The
     * operator's two units were recovered by hand instead — {@code git reset},
     * deleting the scaffold trees, then {@code sync --merge} — which is not a
     * remedy anything can print.
     */
    private static boolean alreadyContainsTarget(Path storeDir, String upstream, TargetRef target) {
        if (DereferencedStoreLinks.hasAuthoredWorktreeChanges(storeDir)) return false;

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
