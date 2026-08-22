package dev.skillmanager.source;

import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bytes of one store copy, moved aside for the duration of an operation that
 * would destroy them, and afterwards either moved back or dropped.
 *
 * <h2>Two callers, one mechanism</h2>
 *
 * <p>Which bytes are lifted and when they come back is the caller's decision;
 * park-restore-clean is not, so it is one class:
 *
 * <ul>
 *   <li>{@link #lift} — the {@linkplain DereferencedStoreLinks dereferenced
 *       store links} of a unit, lifted over a sync (git merge, or the wholesale
 *       replace of {@code sync --from}) and {@linkplain #restore restored
 *       unconditionally} at the end of it. HIS-4 / #231.</li>
 *   <li>{@link #liftPaths} — the pre-image of a unit directory that
 *       {@code CommitUnitsToStore} is about to delete and overwrite, lifted
 *       before the commit and {@linkplain #restore restored only if the program
 *       walks back}; {@linkplain #discard dropped} when it commits.
 *       HIS-11 / #186.</li>
 * </ul>
 *
 * <p>The second caller is the whole reason {@link #discard} exists. HIS-4's
 * escrow always ends in a restore, so it never had a "the operation succeeded,
 * let the held bytes go" edge. An escrow that only ever restores would leave
 * one held tree per resolve under {@code <home>/cache/} — the same
 * nothing-ever-cleans-it residue the review of #231 found in the first version
 * of this class, one directory further down.
 *
 * <h2>Why this is its own class and not a helper inside one handler</h2>
 *
 * <p>It WAS a private record inside {@code SyncGitHandler}, and an adversarial
 * review of #231 found the hole that shape guarantees: the escrow protected the
 * git merge path and <b>nothing protected the second sync path</b>.
 * {@code sync --from <dir>} applies by
 * {@code Fs.deleteRecursive(storeDir)} then {@code Fs.copyRecursive(src, storeDir)}
 * — a wholesale replace with no carry-over at all. Before HIS-4 a materialized
 * child copy never reached it, because the dereference made the unit read dirty
 * and the handler refused; HIS-4 taught that path the dereference is not an
 * author's work, and so walked it straight into the delete. MEASURED, exit 0:
 *
 * <pre>
 *   build-logic -&gt; ../../../outside     # a symlink again, pointing out of the unit
 *   child-only.txt: No such file or directory   # the child home's bytes: gone
 * </pre>
 *
 * <p>That is a data-loss defect this ticket <em>introduced</em>, in exactly the
 * shape it had deferred as DEF-014 — which is the argument for one class both
 * paths use rather than one guarded path and one that looks fine.
 *
 * <h2>The rule, which is not "exclude"</h2>
 *
 * <p>Same rule {@code ChildHomeMaterializer.carryOverUnownedTrees} states for
 * the digest surface, here for the filesystem surface: a path that is not
 * compared is also <b>not copied and NOT DESTROYED</b>. "Not mine to compare"
 * and "mine to destroy" cannot both be true of the same bytes. CHM-24 is the
 * standing reminder that the narrow version of this — just exclude it — was
 * itself a data-loss defect.
 *
 * <h2>Where the bytes are parked, and why not next to the unit</h2>
 *
 * <p>Under {@code <home>/cache/}, never {@code <home>/skills/}. The first
 * version used the store's own parent, which puts a directory in the <b>units
 * namespace</b>: the same review found it stranding
 * {@code <home>/skills/.sm-materialized-*} on every failure — exit 0, invisible
 * to {@code list}, to {@code home verify} and to prune, one per sync, cleaned up
 * by nothing. The cache directory is the home's own scratch space, it is on the
 * same filesystem (so {@link Files#move} stays a rename rather than a
 * cross-device copy), and nothing enumerates units from it.
 */
public final class MaterializationEscrow {

    private static final String PREFIX = ".materialization-escrow-";

    private final Path storeDir;
    private final Path holding;
    private final Map<String, Path> held;

    private MaterializationEscrow(Path storeDir, Path holding, Map<String, Path> held) {
        this.storeDir = storeDir;
        this.holding = holding;
        this.held = held;
    }

    /** An escrow holding nothing, for the paths where there is nothing to protect. */
    public static MaterializationEscrow empty(Path storeDir) {
        return new MaterializationEscrow(storeDir, null, Map.of());
    }

    /**
     * Move every dereferenced store link in {@code storeDir} aside.
     *
     * @param homeRoot the home whose {@code cache/} holds the bytes meanwhile;
     *                 when {@code null} nothing is lifted, because parking them
     *                 somewhere unknown is worse than not lifting them
     * @param restoreTrackedShape whether to put the symlink the repository
     *                 tracks back at each path once the directory is out of the
     *                 way. TRUE for a git operation — otherwise the tree still
     *                 reads as a deletion, which stashes and then collides on
     *                 the pop. FALSE for a wholesale replace, which is about to
     *                 delete the whole tree anyway and where a checkout would
     *                 be a pointless write.
     */
    public static MaterializationEscrow lift(Path storeDir, Path homeRoot,
                                             boolean restoreTrackedShape) {
        Set<String> paths = DereferencedStoreLinks.in(storeDir);
        return liftPaths(storeDir, homeRoot, paths, restoreTrackedShape);
    }

    /**
     * Move each of {@code relPaths} — relative to {@code storeDir} — aside.
     *
     * <p>Same park-and-hold as {@link #lift}; the caller names the paths
     * instead of {@link DereferencedStoreLinks} deriving them. A path that is
     * not there is skipped rather than failing the lift: "there was nothing to
     * protect" and "the protection broke" are different, and only the second
     * deserves a warning.
     *
     * <p>The move is a rename, not a copy — {@code <home>/cache/} and
     * {@code <home>/skills/} are the same filesystem. Between the move and
     * whatever the caller does next the bytes are only in the holding
     * directory. That window is not a regression over the alternative: the
     * caller in {@code Executor} is about to run a delete-then-copy that would
     * have destroyed those bytes outright, so a crash inside the window leaves
     * them recoverable under {@code cache/} where before it left them gone.
     */
    public static MaterializationEscrow liftPaths(Path storeDir, Path homeRoot,
                                                  Collection<String> relPaths,
                                                  boolean restoreTrackedShape) {
        Collection<String> paths = relPaths == null ? Set.of() : relPaths;
        if (paths.isEmpty() || homeRoot == null) return empty(storeDir);

        Map<String, Path> held = new LinkedHashMap<>();
        Path holding = null;
        try {
            Path cache = homeRoot.resolve("cache");
            Files.createDirectories(cache);
            holding = Files.createTempDirectory(cache, PREFIX);
            int i = 0;
            for (String rel : paths) {
                Path from = storeDir.resolve(rel);
                if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(from)) {
                    continue;
                }
                Path to = holding.resolve(String.valueOf(i++));
                Files.move(from, to);
                held.put(rel, to);
            }
            if (restoreTrackedShape) GitOps.checkoutPaths(storeDir, held.keySet());
        } catch (IOException io) {
            Log.warn("could not set aside the tree(s) of %s (%s) — proceeding with "
                    + "them in place", storeDir, io.getMessage());
        }
        if (held.isEmpty() && holding != null) {
            // Nothing was lifted after all — every named path was absent, or
            // the very first move failed. Leaving the temp directory behind
            // would be the unbounded-cache-residue defect with an empty
            // directory instead of a full one.
            try { Fs.deleteRecursive(holding); } catch (IOException ignored) { /* cache scratch */ }
            holding = null;
        }
        return new MaterializationEscrow(storeDir, holding, held);
    }

    /**
     * Put every escrowed tree back, replacing whatever now stands at its path.
     *
     * <p>Whatever now stands there is usually the symlink the repository tracks
     * — restored by the merge, or copied in by the replace — and that is exactly
     * what a child home must not hold, so it is removed. It can also be a real
     * DIRECTORY: upstream is entitled to convert a tracked symlink into real
     * content, which is precisely what the managed-bindings migration did. The
     * first version deleted with {@code deleteIfExists}, which throws
     * {@code DirectoryNotEmptyException} on that case and stranded the bytes;
     * this one removes recursively.
     *
     * <p>Best-effort, in the same sense {@code carryOverUnownedTrees} is: a
     * failure logs the path the bytes are at rather than throwing away an
     * operation that has already succeeded.
     */
    public void restore() {
        for (Map.Entry<String, Path> e : held.entrySet()) {
            Path back = storeDir.resolve(e.getKey());
            try {
                if (Files.exists(back, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(back)) {
                    Fs.deleteRecursive(back);
                }
                Path parent = back.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.move(e.getValue(), back);
            } catch (IOException io) {
                Log.warn("could not restore the materialized tree %s of %s (%s) — its bytes are "
                        + "at %s; `skill-manager project resolve` writes it again",
                        e.getKey(), storeDir, io.getMessage(), e.getValue());
            }
        }
        if (holding != null) {
            try { Fs.deleteRecursive(holding); } catch (IOException ignored) { /* cache scratch */ }
        }
    }

    /**
     * Let the held bytes go without putting them back — the success edge.
     *
     * <p>For {@link #liftPaths}'s caller the destination now holds the bytes
     * the operation meant to write, and the pre-image is exactly what was
     * meant to be superseded. Keeping it would mean one held tree per resolve
     * accumulating under {@code cache/} forever, which is the failure mode the
     * review of #231 named: invisible to {@code list}, to {@code home verify}
     * and to prune, cleaned up by nothing.
     *
     * <p>Idempotent, and safe to call after {@link #restore} — an escrow that
     * has already given its bytes back is holding nothing.
     */
    public void discard() {
        if (!held.isEmpty()) held.clear();
        if (holding != null) {
            try {
                Fs.deleteRecursive(holding);
            } catch (IOException io) {
                Log.warn("could not clean up the escrowed pre-image at %s (%s) — it is scratch "
                        + "under the home's cache and safe to delete by hand", holding, io.getMessage());
            }
        }
    }

    /** Whether anything is actually being held — for callers that log. */
    public boolean isEmpty() { return held.isEmpty(); }
}
