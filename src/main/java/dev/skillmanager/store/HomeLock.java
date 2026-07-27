package dev.skillmanager.store;

import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.shared.util.Fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mutual exclusion over one Skill Manager home, for the length of one
 * whole-home operation.
 *
 * <h2>Why this had to be added</h2>
 *
 * <p>Nothing in this codebase held a mutex before. {@code CliLock},
 * {@code UnitsLock}, {@code SkillProjectLock} and
 * {@code HarnessInstanceLock} are all <em>lock files</em> in the manifest
 * sense — durable records of what is installed — not locks in the exclusion
 * sense. Nothing stopped two processes from reconciling into the same home at
 * the same time, because until several homes could write into one home there
 * was no way for two writers to meet.
 *
 * <p>{@code home sync} creates exactly that: every ticket worktree closing out
 * writes into the one project home they were all cloned from, and several can
 * close out at once. Per-unit staging already makes a single unit's swap
 * atomic, but two syncs interleaved across a whole home produce a destination
 * that is coherent per unit and incoherent as a home — half of it reconciled
 * against one source, half against another, and the materialization records
 * describing a state that never existed. So the unit of exclusion is the home,
 * not the unit.
 *
 * <h2>Two locks, because one is not enough</h2>
 *
 * <p>{@link FileLock} is held by the JVM, not by the thread: two threads in one
 * process locking the same file do not exclude each other, and the second one
 * gets {@link OverlappingFileLockException} rather than waiting. So a
 * process-wide {@link ReentrantLock} keyed by the home path handles writers
 * inside this JVM, and the file lock handles writers in other processes. Both
 * are needed and neither substitutes for the other.
 *
 * <p>Re-entering on one thread takes the file lock only once (the
 * {@code ReentrantLock} hold count says which acquisition is the outer one), so
 * a lock-holding operation may call another without deadlocking on the OS lock.
 *
 * <h2>The lock file lives where nothing scans</h2>
 *
 * <p>Under {@code .materialization/}, beside the per-unit records, rather than
 * at the home root. A new entry at the root of a home shows up in
 * {@code home clone} reports and in anything that enumerates the home; the
 * records directory is already the home's private bookkeeping and is already
 * excluded from every unit scan.
 */
public final class HomeLock implements AutoCloseable {

    public static final String FILENAME = ".home.lock";

    /** How long a caller waits for a peer before giving up rather than hanging. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private static final Map<String, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();

    private static final long POLL_MILLIS = 25;

    private final ReentrantLock jvmLock;
    private final FileChannel channel;
    private final FileLock fileLock;
    private boolean released;

    private HomeLock(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
        this.jvmLock = jvmLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    /** The lock file for {@code homeRoot}, whether or not it exists. */
    public static Path file(Path homeRoot) {
        return homeRoot.resolve(ChildHomeMaterializer.RECORDS_DIR).resolve(FILENAME);
    }

    public static HomeLock acquire(Path homeRoot, String operation) throws IOException {
        return acquire(homeRoot, operation, DEFAULT_TIMEOUT);
    }

    /**
     * Take the home lock, waiting up to {@code timeout}.
     *
     * @throws IOException when the wait runs out. A timeout is reported rather
     *         than waited through: a sync that blocks forever behind a crashed
     *         peer is indistinguishable from a hung command, and the operator
     *         needs to be told which home is contended.
     */
    public static HomeLock acquire(Path homeRoot, String operation, Duration timeout)
            throws IOException {
        Path root = homeRoot.toAbsolutePath().normalize();
        Path lockFile = file(root);
        Fs.ensureDir(lockFile.getParent());
        long waitMillis = timeout == null ? DEFAULT_TIMEOUT.toMillis() : timeout.toMillis();

        ReentrantLock jvmLock = IN_PROCESS.computeIfAbsent(root.toString(), key -> new ReentrantLock());
        boolean held;
        try {
            held = jvmLock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for the home lock on " + root, interrupted);
        }
        if (!held) {
            throw new IOException(operation + ": " + root + " is locked by another "
                    + "operation in this process and did not free up within " + waitMillis + "ms");
        }
        // The outer acquisition owns the OS lock; a nested one is already
        // covered by it and must not ask the OS for it a second time.
        if (jvmLock.getHoldCount() > 1) return new HomeLock(jvmLock, null, null);

        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            long deadline = System.currentTimeMillis() + waitMillis;
            FileLock fileLock = null;
            while (fileLock == null) {
                try {
                    fileLock = channel.tryLock();
                } catch (OverlappingFileLockException overlapping) {
                    fileLock = null;
                }
                if (fileLock != null) break;
                if (System.currentTimeMillis() >= deadline) {
                    throw new IOException(operation + ": " + root + " is locked by another "
                            + "skill-manager process (" + lockFile + ") and did not free up within "
                            + waitMillis + "ms");
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted waiting for the home lock on " + root,
                            interrupted);
                }
            }
            return new HomeLock(jvmLock, channel, fileLock);
        } catch (IOException | RuntimeException failure) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Releasing the JVM lock below matters more than this handle.
                }
            }
            jvmLock.unlock();
            throw failure;
        }
    }

    @Override
    public void close() {
        if (released) return;
        released = true;
        try {
            if (fileLock != null && fileLock.isValid()) fileLock.release();
        } catch (IOException ignored) {
            // A lock whose channel is already gone is already not held.
        }
        try {
            if (channel != null) channel.close();
        } catch (IOException ignored) {
            // Same: the OS releases it with the descriptor either way.
        }
        jvmLock.unlock();
    }
}
