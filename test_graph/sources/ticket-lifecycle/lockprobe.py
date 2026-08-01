#!/usr/bin/env python3
"""Observe, hold, and contend for the POSIX record lock behind ``HomeLock``.

Why this exists
---------------
``HomeLock`` is the exclusion that makes several ticket worktrees safe to close
out into one project home at the same time. The obvious way to test it — start
two ``home sync`` runs and compare their process windows — measures the wrong
thing and reports the wrong answer: a skill-manager process spends 2-3 seconds
in jbang/JVM startup *before* ``HomeLock.acquire`` is ever called, so two runs
whose locked sections are strictly ordered still show overlapping process
lifetimes. Measured on the development machine: the lock file reads FREE at
t=1-2s, HELD at t=3-7s, FREE after. A wall-clock oracle over those two runs
says "OVERLAPPED" and means nothing by it.

So this probe reads the lock itself.

``java.nio.channels.FileLock`` is implemented on Unix as an ``fcntl`` record
lock, which is the same primitive ``fcntl.lockf`` and ``F_GETLK`` use here.
That is what makes a Python observer sound rather than merely adjacent: an
``F_GETLK`` query genuinely reports the JVM's lock, and a ``LOCK_EX`` taken
here genuinely blocks it.

Three modes, each doing one job
-------------------------------
``sample``  Poll ``F_GETLK`` and print ``<millis> <holder-pid>`` per line.
            ``0`` means free, ``-1`` means the file does not exist yet,
            ``-2`` means the query itself failed. Those are distinct values on
            purpose: "could not look" must never be readable as "looked and
            found nothing free", which is the defect class this epic hit four
            times.

``hold``    Take the lock and keep it until a release file appears. This turns
            a race into an experiment: both syncs are started, both reach
            ``HomeLock.acquire`` while the lock is held elsewhere, and both are
            then released at one instant. Without it the two runs may simply
            not overlap, and a green result would mean "they did not contend"
            rather than "they contended and were ordered".

``write``   The negative control: write into a home WITHOUT taking the lock.
            Two of these are what an unserialised pair looks like, and the
            oracle in ``TicketLifecycleConcurrentCloseOut`` must report them as
            unserialised or it is not measuring anything.

The struct layout
-----------------
``struct flock`` is not the same shape on every platform, so the format is
selected per platform rather than assumed, and ``self-test`` verifies the
choice at run time by holding the lock in a child and asking ``F_GETLK`` for
that child's pid. A probe whose struct unpacking is wrong reports pid 0 —
indistinguishable from "free" — which would make every assertion built on it
vacuous.
"""
import errno
import fcntl
import os
import struct
import subprocess
import sys
import time

if sys.platform == "darwin":
    # struct flock { off_t l_start; off_t l_len; pid_t l_pid; short l_type; short l_whence; }
    FLOCK_FMT = "qqihh"
    F_GETLK = 7
else:
    # Linux x86_64: struct flock { short l_type; short l_whence; off_t l_start;
    #                              off_t l_len; pid_t l_pid; }
    FLOCK_FMT = "hhqqi"
    F_GETLK = 5

F_RDLCK, F_UNLCK, F_WRLCK = (1, 2, 3) if sys.platform == "darwin" else (0, 2, 1)

FREE = 0
MISSING = -1
UNREADABLE = -2


def _pack_query():
    if sys.platform == "darwin":
        return struct.pack(FLOCK_FMT, 0, 0, 0, F_WRLCK, 0)
    return struct.pack(FLOCK_FMT, F_WRLCK, 0, 0, 0, 0)


def _unpack(res):
    values = struct.unpack(FLOCK_FMT, res)
    if sys.platform == "darwin":
        _l_start, _l_len, l_pid, l_type, _l_whence = values
    else:
        l_type, _l_whence, _l_start, _l_len, l_pid = values
    return l_type, l_pid


def holder(path):
    """The pid holding an exclusive lock on ``path``, or one of the sentinels."""
    if not os.path.exists(path):
        return MISSING
    try:
        fd = os.open(path, os.O_RDWR)
    except OSError:
        return UNREADABLE
    try:
        l_type, l_pid = _unpack(fcntl.fcntl(fd, F_GETLK, _pack_query()))
        return FREE if l_type == F_UNLCK else l_pid
    except OSError:
        return UNREADABLE
    finally:
        os.close(fd)


def mode_sample(path, seconds, stop_file, out_file, interval=0.015):
    start = time.time()
    rows = []
    while time.time() - start < seconds:
        rows.append("%d %d" % (int((time.time() - start) * 1000), holder(path)))
        if os.path.exists(stop_file):
            break
        time.sleep(interval)
    with open(out_file, "w") as fh:
        fh.write("\n".join(rows) + "\n")


def mode_hold(path, ready_file, release_file, max_seconds):
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    fcntl.lockf(fd, fcntl.LOCK_EX)
    with open(ready_file, "w") as fh:
        fh.write("%d\n" % os.getpid())
    start = time.time()
    while not os.path.exists(release_file) and time.time() - start < max_seconds:
        time.sleep(0.02)
    fcntl.lockf(fd, fcntl.LOCK_UN)
    os.close(fd)


def mode_lockedwrite(path, target, seconds):
    """A well-behaved writer: takes the lock, writes, releases. Positive control."""
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    fcntl.lockf(fd, fcntl.LOCK_EX)
    with open(target, "a") as fh:
        fh.write("locked writer %d\n" % os.getpid())
    time.sleep(seconds)
    fcntl.lockf(fd, fcntl.LOCK_UN)
    os.close(fd)


def mode_write(path, target, seconds):
    """An unserialised writer: writes without ever taking the lock. Negative control."""
    with open(target, "a") as fh:
        fh.write("unlocked writer %d\n" % os.getpid())
    time.sleep(seconds)


def mode_selftest(path):
    """Prove the struct layout is right before anything is asserted on it.

    Holds the lock in a child process and asks ``F_GETLK`` for the holder. A
    wrong ``struct flock`` layout answers 0 here — which reads exactly like
    "free" — so without this the sampler could report an empty timeline on a
    perfectly serialised pair and every assertion above it would pass or fail
    for reasons unrelated to the lock.
    """
    ready = path + ".selftest.ready"
    release = path + ".selftest.release"
    for stale in (ready, release):
        if os.path.exists(stale):
            os.remove(stale)
    child = subprocess.Popen(
        [sys.executable, os.path.abspath(__file__), "hold", path, ready, release, "20"])
    deadline = time.time() + 20
    while not os.path.exists(ready) and time.time() < deadline:
        time.sleep(0.02)
    with open(ready) as fh:
        child_pid = int(fh.read().strip())
    observed_while_held = holder(path)
    with open(release, "w") as fh:
        fh.write("go\n")
    child.wait(timeout=20)
    observed_after = holder(path)
    for stale in (ready, release):
        if os.path.exists(stale):
            os.remove(stale)
    print("childPid=%d whileHeld=%d afterRelease=%d" %
          (child_pid, observed_while_held, observed_after))
    return 0 if (observed_while_held == child_pid and observed_after == FREE) else 1


def main(argv):
    mode = argv[1]
    if mode == "sample":
        mode_sample(argv[2], float(argv[3]), argv[4], argv[5])
        return 0
    if mode == "hold":
        mode_hold(argv[2], argv[3], argv[4], float(argv[5]))
        return 0
    if mode == "lockedwrite":
        mode_lockedwrite(argv[2], argv[3], float(argv[4]))
        return 0
    if mode == "write":
        mode_write(argv[2], argv[3], float(argv[4]))
        return 0
    if mode == "selftest":
        return mode_selftest(argv[2])
    sys.stderr.write("unknown mode: %s\n" % mode)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
