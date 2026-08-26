package dev.skillmanager.source;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one question: <b>which paths in this store copy are a
 * dereferenced in-unit store link rather than somebody's work?</b>
 *
 * <h2>The drag this exists to end</h2>
 *
 * <p>{@code ChildHomeMaterializer} dereferences an in-unit symlink that points
 * into the parent store into a real directory, so the child home holds its own
 * bytes and never writes through into the store it was materialized from
 * (CHM-5). That is correct, and it is what makes a child home independent.
 *
 * <p>It also happens <em>inside a git working tree</em>. The unit's repository
 * tracks that path at mode {@code 120000}; the working tree now holds a
 * directory. {@code git status} reports {@code " D <path>"} — a deletion — and
 * from that moment every sync of that unit refuses:
 *
 * <pre>
 *   &lt;unit&gt; has extra local changes (working tree edits, or commits ahead of
 *   the installed baseline) — sync would overwrite them.
 *   re-run with: skill-manager sync &lt;unit&gt; --merge
 * </pre>
 *
 * <p>MEASURED on the operator's project home: {@code 2 of 21} change-managed
 * units permanently unsyncable for nine days, inherited by every worktree
 * cloned from that home. The printed remedy does not clear it — {@code --merge}
 * stashes, merges (the merge commit <em>lands</em>), pops, and the pop
 * conflicts on a path the working tree cannot hold as both a directory and a
 * symlink. Reproduced synthetically under
 * {@code results/epic-home-integrity-sync/probes/his-4/}.
 *
 * <h2>Re-derived, never recorded</h2>
 *
 * <p>Every input is read from the live tree at the moment the question is
 * asked: the index's mode for the path, the shape on disk, and the link target
 * git itself holds in the blob. Nothing is persisted and nothing has to be
 * migrated, which is deliberate — this epic's recurring defect is persisted
 * state that is never re-derived, and a {@code dereferencedPaths[]} field in
 * the materialization record would be the next instance of it. A materializer
 * that stops dereferencing a path stops being believed here on the very next
 * command, with no record to correct.
 *
 * <h2>What the predicate actually requires, and why each clause is load-bearing</h2>
 *
 * <p>All three, together:
 *
 * <ol>
 *   <li><b>git tracks the path at mode {@code 120000}.</b> Untracked shapes are
 *       git's business already — it ignores them or reports them as untracked —
 *       and are not what deadlocks.</li>
 *   <li><b>The working tree holds a real directory there</b> ({@code NOFOLLOW}
 *       — a symlink to a directory is still a symlink and still matches what
 *       git tracks, so it is not a divergence at all).</li>
 *   <li><b>The target git holds escapes the unit root.</b> A link resolving
 *       inside the unit is the unit's own content and {@code ChildHomeMaterializer}
 *       deliberately leaves it alone ({@code internalRelative} in its
 *       {@code walk}); only a link reaching OUT of the unit is one it
 *       dereferences.</li>
 * </ol>
 *
 * <p>Clause 3 is the narrow one and it is the reason this is not simply
 * "symlink in HEAD, directory on disk". Git can represent that shape as an
 * ordinary authored change — {@code git rm link && mkdir link && git add
 * link/…} commits fine — so treating every such path as a materialization
 * artifact would silently discard an edit. A link that escapes the unit is
 * different in kind: the bytes underneath it were never this unit's to carry,
 * so a directory standing where one used to be is the materializer's work and
 * not an author's.
 *
 * <h2 id="not-a-licence-to-overwrite">Excluding it is NOT a licence to overwrite it</h2>
 *
 * <p>CHM-24 recorded that "exclude it from the merge algebra" is not
 * automatically safe, and the narrow fix there was itself a data-loss defect.
 * So the rule here is the same one {@link dev.skillmanager.shared.util.Rederivable}
 * and {@code ChildHomeMaterializer.carryOverUnownedTrees} already state, on the
 * git surface rather than the digest surface: a path named here is <b>not
 * counted as a local change, not stashed, and NOT DESTROYED</b>. {@code
 * SyncGitHandler} carries these directories across the stash → merge → pop
 * window and puts them back, because "not mine to compare" and "mine to
 * destroy" cannot both be true of the same bytes.
 *
 * <p><b>A unit whose ONLY divergence is a dereferenced path is therefore not
 * "clean" — it is a unit with nothing an author wrote in it.</b> The distinction
 * matters and is asserted: the sync proceeds and the merge really happens, the
 * baseline really advances, and the directory is still there afterwards. What
 * does <em>not</em> happen is a refusal that protects nothing. A unit that also
 * carries a real edit still has a real edit, still reports it, and is still
 * refused — {@link #worktreeChangesBeyond} subtracts these paths from the
 * status and asks about what is left, rather than answering "clean" whenever
 * one is present.
 */
public final class DereferencedStoreLinks {

    private DereferencedStoreLinks() {}

    /** Git's mode for a symbolic link. */
    private static final String SYMLINK_MODE = "120000";

    /**
     * The unit-relative paths in {@code storeDir} that are a dereferenced
     * in-unit store link, re-derived from the live tree. Empty for a
     * non-repository, an unreadable index, or a tree with none.
     */
    public static Set<String> in(Path storeDir) {
        if (storeDir == null || !GitOps.isGitRepo(storeDir)) return Set.of();
        Path unitRoot = realOrAbsolute(storeDir);
        Set<String> out = new LinkedHashSet<>();
        for (GitOps.IndexEntry entry : GitOps.indexEntries(storeDir)) {
            if (!SYMLINK_MODE.equals(entry.mode())) continue;
            Path onDisk = storeDir.resolve(entry.path());
            if (Files.isSymbolicLink(onDisk)) continue;
            if (!Files.isDirectory(onDisk, LinkOption.NOFOLLOW_LINKS)) continue;
            String target = GitOps.blobText(storeDir, entry.objectId());
            if (target == null || target.isBlank()) continue;
            if (escapesUnit(unitRoot, entry.path(), target.trim())) out.add(entry.path());
        }
        return out;
    }

    /**
     * The porcelain-status lines of {@code storeDir} that name something OTHER
     * than a dereferenced store link — i.e. what an author actually did.
     *
     * <p>Returned as the raw status lines rather than a boolean so a caller
     * that has to explain a refusal can name the paths it is refusing over.
     */
    public static List<String> worktreeChangesBeyond(Path storeDir, Set<String> excluded) {
        String status = GitOps.porcelainStatus(storeDir);
        List<String> out = new ArrayList<>();
        if (status == null || status.isBlank()) return out;
        for (String line : status.split("\n")) {
            if (line.isBlank()) continue;
            String path = statusPath(line);
            if (path != null && excluded.contains(path)) continue;
            out.add(line);
        }
        return out;
    }

    /**
     * Whether {@code storeDir}'s working tree diverges in any way that is not a
     * dereferenced store link. The materialization-aware replacement for
     * {@code GitOps.hasWorktreeChanges} on the sync path.
     */
    public static boolean hasAuthoredWorktreeChanges(Path storeDir) {
        return !worktreeChangesBeyond(storeDir, in(storeDir)).isEmpty();
    }

    /**
     * <b>The one definition of "is there work here a sync would destroy".</b>
     *
     * <p>Both halves of {@code GitOps.isDirty} — uncommitted work, or HEAD past
     * the recorded baseline — with the first half blind to the materializer's
     * own dereferences. The second half is untouched on purpose: a HEAD ahead of
     * the baseline is HIS-4's link 3, and it must still stop an overwrite.
     *
     * <h2>Why it lives here and not in each handler</h2>
     *
     * <p>Because there are two sync paths and there will be a third.
     * {@code SyncGitHandler} syncs a store copy against a git remote;
     * {@code SyncFromLocalDirHandler} syncs it against a local directory
     * ({@code sync --from}). Both refuse with the same sentence — "extra local
     * changes (working tree edits, or commits ahead of the installed baseline)"
     * — and before HIS-4 both asked {@code GitOps.isDirty} directly. Fixing one
     * would have left the other refusing a materialized child copy forever,
     * with the same words, which is <b>two readings of one rule</b>: the shape
     * CHM-15 was, the shape DEF-004 was, and the shape this epic keeps meeting.
     *
     * <p>{@code SyncPathsAgreeAboutDirtyTest} is the oracle that keeps it one
     * definition: it fails when any handler in the effects package asks
     * {@code GitOps.isDirty} or {@code GitOps.hasWorktreeChanges} again.
     */
    public static boolean isAuthoredDirty(Path storeDir, String baselineHash) {
        if (hasAuthoredWorktreeChanges(storeDir)) return true;
        if (baselineHash == null || baselineHash.isBlank()) return false;
        String head = GitOps.headHash(storeDir);
        return head != null && !head.equals(baselineHash);
    }

    /**
     * The path a {@code git status --porcelain} line names, or {@code null}
     * when the line cannot be parsed.
     *
     * <p>Rename lines carry {@code old -> new}; the NEW path is the one on
     * disk, so that is the one compared. Quoted paths (git quotes anything
     * non-ASCII unless {@code core.quotePath=false}) are left as-is rather than
     * half-unquoted: an unparsed path simply fails to match the exclusion set,
     * which errs toward reporting a change rather than hiding one.
     */
    static String statusPath(String line) {
        if (line == null || line.length() < 4) return null;
        String rest = line.substring(3);
        int arrow = rest.indexOf(" -> ");
        if (arrow >= 0) rest = rest.substring(arrow + 4);
        return rest.trim();
    }

    /**
     * Whether the link {@code target}, read from the position {@code rel}
     * occupies, lands outside {@code unitRoot}.
     *
     * <p>Purely textual normalization — {@code Path.normalize()} over the
     * lexical join — because the thing it points at has, by construction, been
     * replaced on disk by the directory that made us ask. Asking the
     * filesystem here would resolve the DIRECTORY that is standing there now
     * and answer "inside the unit" for every one of them, which is the
     * inversion this comment exists to stop somebody re-introducing.
     */
    private static boolean escapesUnit(Path unitRoot, String rel, String target) {
        Path linkDir = unitRoot.resolve(rel.replace('/', File.separatorChar)).getParent();
        if (linkDir == null) return false;
        Path resolved = Path.of(target).isAbsolute()
                ? Path.of(target).normalize()
                : linkDir.resolve(target).normalize();
        return !resolved.startsWith(unitRoot);
    }

    private static Path realOrAbsolute(Path dir) {
        try {
            return dir.toRealPath();
        } catch (Exception e) {
            return dir.toAbsolutePath().normalize();
        }
    }
}
