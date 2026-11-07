package dev.skillmanager.effects;

import dev.skillmanager.source.DereferencedStoreLinks;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.MaterializationEscrow;
import dev.skillmanager.source.RecordVersionRefresh;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * HP-2 (#406, #407): move one git-tracked unit to an IMMUTABLE target, or
 * refuse by name. The one primitive behind {@code sync --lock} (a bumped lock
 * row), {@code sync <unit> --to <sha>} and {@code project resolve} (a declared
 * {@code revision}).
 *
 * <h2>Why this is not a merge</h2>
 *
 * <p>The ordinary sync path fetches the recorded ref and MERGES it. That can
 * only move a unit forward, and only to wherever the ref points now: a pin
 * OLDER than HEAD merges as "already up to date" and leaves HEAD ahead (#407),
 * and a lock row naming B while main has moved to C merges C (#406). A pin is a
 * position, so it is reached by putting HEAD there:
 *
 * <ol>
 *   <li>fetch the pin from the origin; an unavailable sha FAILS CLOSED;</li>
 *   <li>refuse if HEAD carries local commits the pin does not contain — moving
 *       would discard them;</li>
 *   <li>stash worktree edits, {@code reset --hard <pin>}, pop the stash: the pop
 *       is the three-way apply of the edits onto the PIN (never onto trunk);</li>
 *   <li>on a conflicting pop, roll back to exactly where the store was and
 *       refuse naming the unit and the files;</li>
 *   <li>only then rewrite origin / ref / resolved sha TOGETHER in the installed
 *       record, which the units lock is regenerated from.</li>
 * </ol>
 *
 * <p>A refusal is a PARTIAL receipt carrying {@link ContextFact.SyncGitFailed}
 * — exit non-zero, the unit named, no rollback of other units' work — rather
 * than {@link ContextFact.SyncGitRefused}, whose rendered remedy is
 * {@code --merge}, the exact command that merged trunk in #407.
 */
public final class SyncGitPin {

    private SyncGitPin() {}

    public static EffectReceipt run(SkillEffect.SyncGit e, EffectContext ctx,
                                    InstalledUnit src, Path storeDir) throws IOException {
        String name = e.unitName();
        SkillEffect.SyncGit.Pin pin = e.pin();
        if (!GitOps.isGitRepo(storeDir)) {
            return refuse(e, name, "cannot move to pin " + pin.label()
                    + ": the installed unit is not git-tracked");
        }
        String origin = notBlank(pin.origin()) ? pin.origin()
                : src != null && notBlank(src.origin()) ? src.origin()
                : GitOps.originUrl(storeDir);
        if (!notBlank(origin)) {
            return refuse(e, name, "cannot move to pin " + pin.label() + ": no origin to fetch it from");
        }
        String want = notBlank(pin.sha()) ? pin.sha() : pin.ref();
        if (!notBlank(want)) {
            return refuse(e, name, "pin names neither a sha nor a ref");
        }

        String fetched = GitOps.fetchRef(storeDir, origin, want);
        if (fetched == null || (notBlank(pin.sha()) && !fetched.startsWith(pin.sha())
                && !pin.sha().startsWith(fetched))) {
            return refuse(e, name, "pin " + pin.label() + " is not available from " + origin
                    + " — nothing was changed");
        }
        String sha = fetched;
        String recordedRef = notBlank(pin.ref()) ? pin.ref() : (src != null ? src.gitRef() : null);
        String head = GitOps.headHash(storeDir);
        boolean worktreeEdits = DereferencedStoreLinks.hasAuthoredWorktreeChanges(storeDir);

        if (sha.equals(head)) {
            // Already there. Restate the record only if it disagrees, so a
            // converged home is not rewritten on every run.
            restateRecord(ctx, name, origin, recordedRef, sha, src, false);
            return EffectReceipt.ok(e, new ContextFact.SyncGitUpToDate(name, pin.label()));
        }

        // Local COMMITS. A HEAD that is still the recorded baseline holds only
        // upstream's commits, and leaving them costs nothing — they are
        // upstream's to give back. A HEAD past the baseline is authored work;
        // it moves only when the pin already contains it (a fast-forward).
        String baseline = src != null ? src.gitHash() : null;
        boolean authoredCommits = baseline != null && !baseline.equals(head);
        if (authoredCommits && !GitOps.isAncestor(storeDir, head, sha)) {
            return refuse(e, name, "HEAD " + shortSha(head) + " carries commits past the installed baseline "
                    + shortSha(baseline) + " that pin " + shortSha(sha)
                    + " does not contain; moving would discard them — push or drop them, then re-run");
        }

        SyncGitHandler.BaselineWatch watch = SyncGitHandler.BaselineWatch.before(ctx.store(), name, e.kind());
        MaterializationEscrow escrow = MaterializationEscrow.lift(storeDir, homeRootOf(ctx), true);
        String failure;
        try {
            failure = moveTo(storeDir, head, sha);
        } finally {
            escrow.restore();
        }
        if (failure != null) return refuse(e, name, failure);

        restateRecord(ctx, name, origin, recordedRef, sha, src, true);
        watch.afterUpstreamMove();
        Log.info("%s: moved %s → pin %s%s", name, shortSha(head), shortSha(sha),
                worktreeEdits ? " (local edits carried onto the pin)" : "");
        return EffectReceipt.ok(e, new ContextFact.SyncGitMerged(name, sha));
    }

    /** @return null on success, else why the store was left exactly as it was. */
    static String moveTo(Path storeDir, String preHead, String sha) {
        boolean stashed = GitOps.stashAll(storeDir, "skill-manager-pin");
        if (!GitOps.resetHard(storeDir, sha)) {
            GitOps.resetHard(storeDir, preHead);
            if (stashed) GitOps.stashPop(storeDir);
            return "could not check out pin " + shortSha(sha) + " — nothing was changed";
        }
        if (stashed && !GitOps.stashPop(storeDir)) {
            List<String> conflicted = GitOps.unmergedFiles(storeDir);
            GitOps.resetHard(storeDir, preHead);
            boolean recovered = GitOps.stashPop(storeDir);
            return "local edits conflict with pin " + shortSha(sha) + " in " + conflicted.size()
                    + " file(s) " + conflicted + "; left at " + shortSha(preHead)
                    + (recovered ? " with the edits intact" : " — the edits are at stash@{0}");
        }
        return null;
    }

    private static void restateRecord(EffectContext ctx, String name, String origin, String ref,
                                      String sha, InstalledUnit src, boolean moved) {
        if (src == null) return;
        boolean same = sha.equals(src.gitHash()) && java.util.Objects.equals(origin, src.origin())
                && java.util.Objects.equals(ref, src.gitRef());
        if (same && !moved) return;
        try {
            InstalledUnit next = new InstalledUnit(src.name(), src.version(), src.kind(), src.installSource(),
                    origin, sha, ref, UnitStore.nowIso(), src.errors(), src.unitKind());
            ctx.writeSource(RecordVersionRefresh.orSame(ctx.store(), next));
            ctx.clearError(name, InstalledUnit.ErrorKind.MERGE_CONFLICT);
        } catch (IOException ex) {
            Log.warn("could not record pin %s for %s: %s", shortSha(sha), name, ex.getMessage());
        }
    }

    private static EffectReceipt refuse(SkillEffect.SyncGit e, String name, String reason) {
        return EffectReceipt.partial(e, reason, new ContextFact.SyncGitFailed(name, reason));
    }

    private static Path homeRootOf(EffectContext ctx) {
        try { return ctx.store() != null ? ctx.store().root() : null; }
        catch (Exception ex) { return null; }
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    static String shortSha(String s) {
        if (s == null) return "<none>";
        return s.length() > 10 ? s.substring(0, 10) : s;
    }
}
