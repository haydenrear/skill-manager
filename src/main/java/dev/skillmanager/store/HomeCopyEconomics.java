package dev.skillmanager.store;

import dev.skillmanager.util.Platform;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * What a copy of a home costs on the filesystem it is being made on — declared
 * by the product, so it can be checked rather than assumed.
 *
 * <h2>The assumption this exists to make falsifiable</h2>
 *
 * <p>The three-tier home model — a copy per repository and a copy per ticket
 * worktree — was adopted on a measured 3.8%: a 189 MB home clone consuming
 * 7.22 MB. That number is <b>APFS-only</b>, and it is not a property anyone
 * asked for. It is a side effect of {@code Files.copy(…, COPY_ATTRIBUTES)}
 * taking the JDK's {@code clonefile(2)} path on macOS. On Linux the JDK does
 * not request a reflink, so the same call is a byte copy and the same clone
 * costs 189 MB.
 *
 * <p>Before this class the codebase held no statement of that at all, so the
 * only assertion of the economics — {@code
 * home.clone.costs.far.less.than.a.copy} — had nothing to compare a
 * measurement against on a platform that does not share blocks, and SKIPPED
 * there. CI runs Linux. The property the home model rests on was therefore
 * asserted on developer laptops and nowhere else (DEF-285, OUN-12).
 *
 * <p>With a declaration the node has something to falsify on every platform:
 * measure the sharing, ask this class what it should have been, and fail when
 * they disagree <b>in either direction</b>. A macOS run that stops sharing is
 * the {@code COPY_ATTRIBUTES} regression. A Linux run that starts sharing
 * means a JDK or filesystem now reflinks and this declaration is stale —
 * which is a thing worth being told loudly, because it would mean the home
 * model just got cheaper somewhere it was not before.
 *
 * <h2>Why the filesystem and not just the OS</h2>
 *
 * <p>macOS mounts things that are not APFS — a DMG, an exFAT drive, an SMB
 * share — and a home on one of those does not share blocks. {@code
 * FileStore.type()} answers "apfs" / "ext4" / "overlay" / "btrfs" directly,
 * so the declaration is read from the volume the copy is actually landing on.
 */
public final class HomeCopyEconomics {

    /** What a copy of N bytes onto this filesystem is expected to consume. */
    public enum Strategy {
        /**
         * The copy shares the source's blocks: near-zero marginal cost.
         * macOS + APFS, where the JDK's {@code COPY_ATTRIBUTES} copy is
         * {@code clonefile(2)}.
         */
        SHARES_BLOCKS,

        /**
         * The kernel can reflink but the JDK may not ask it to. Linux with
         * btrfs / XFS / ZFS: either outcome is legitimate and which one you
         * get depends on the JDK version, so nothing is asserted beyond
         * correctness.
         */
        MAY_SHARE_BLOCKS,

        /** A full byte copy: N bytes in, N bytes consumed. */
        FULL_COPY
    }

    /** Filesystems whose kernels support reflinks but whose JDK support varies. */
    private static final Set<String> REFLINK_CAPABLE = Set.of("btrfs", "xfs", "zfs");

    private HomeCopyEconomics() {}

    /**
     * The strategy a copy landing at {@code destination} will get.
     *
     * <p>Resolved against the nearest existing ancestor, because the
     * destination of a clone does not exist yet by construction.
     */
    public static Strategy strategyFor(Path destination) {
        String fs = filesystemOf(destination);
        if (fs == null) return Strategy.FULL_COPY;
        if (Platform.currentOs() == Platform.Os.DARWIN && fs.equals("apfs")) {
            return Strategy.SHARES_BLOCKS;
        }
        if (Platform.currentOs() == Platform.Os.LINUX && REFLINK_CAPABLE.contains(fs)) {
            return Strategy.MAY_SHARE_BLOCKS;
        }
        return Strategy.FULL_COPY;
    }

    /** The filesystem type at {@code path} or its nearest existing ancestor. */
    public static String filesystemOf(Path path) {
        Path at = path == null ? null : path.toAbsolutePath().normalize();
        for (; at != null; at = at.getParent()) {
            if (!Files.exists(at)) continue;
            try {
                FileStore store = Files.getFileStore(at);
                String type = store.type();
                return type == null ? null : type.toLowerCase(Locale.ROOT);
            } catch (IOException unreadable) {
                return null;
            }
        }
        return null;
    }

    /**
     * One line for {@code home clone} naming what this copy costs and why.
     *
     * <p>{@code apparentBytes} is the size of what was copied; on a full-copy
     * filesystem that is also what it consumed, which is the number an
     * operator sizing an image or a disk needs and has never been told.
     */
    public static String describe(Path destination, long apparentBytes) {
        Strategy strategy = strategyFor(destination);
        String fs = filesystemOf(destination);
        String where = fs == null ? Platform.currentKey() : Platform.currentKey() + "/" + fs;
        return switch (strategy) {
            case SHARES_BLOCKS -> "block-sharing copy on " + where
                    + " — the copy is backed by the source's own blocks, so "
                    + mb(apparentBytes) + " apparent costs almost nothing";
            case MAY_SHARE_BLOCKS -> "copy on " + where
                    + " — this filesystem can share blocks but the JDK may not ask it to, so "
                    + mb(apparentBytes) + " may cost up to " + mb(apparentBytes);
            case FULL_COPY -> "full copy on " + where
                    + " — this platform does not share blocks, so " + mb(apparentBytes)
                    + " apparent costs " + mb(apparentBytes) + " of disk";
        };
    }

    /**
     * A size a person can read. Homes range from a few kilobytes (a fixture)
     * to a gigabyte (the operator root), and "0.0 MB" for the first of those
     * is how a real number turns into noise.
     */
    private static String mb(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
