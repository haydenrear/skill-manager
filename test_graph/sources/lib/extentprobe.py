"""Report which files share their backing storage with a reference tree.

Two files share storage when they name the same inode (a hard link) or when
their data begins at the same physical device offset (a reflink -- APFS
`clonefile`, btrfs/xfs reflink). Both facts are read out of the filesystem one
file at a time, with a syscall that answers about *that file*.

This exists because the obvious instruments cannot answer the question:

  apparent size   identical for a clone and a copy, by construction
  du              attributes shared blocks to both files; it de-duplicates
                  hard links within one invocation but is blind to clones
  stat st_blocks  reports the full allocation for a clone (20480 for a
                  10 MB clone of a 10 MB file, measured)
  stat st_nlink   stays 1 for a clone; only hard links move it
  free space      measures the VOLUME, not the workload -- on a busy host it
                  reports whatever else is writing, including negative
                  consumption for a write that certainly consumed something

Nothing here reads free space, so a concurrent writer elsewhere on the host
cannot change the answer.

Output is one record per line, `<scope> key=value ...`, with targets numbered
in the order they were given on the command line, so that no path ever has to
survive a round trip through a parser.
"""
import ctypes
import json
import os
import sys


class Unsupported(Exception):
    """The filesystem would not say where a file's blocks live."""


# A physical offset of zero is what both kernels report for a file whose
# blocks are not allocated yet. Accepting it as an address makes every
# unallocated file collide with every other one, which reads as "everything
# is shared" -- the vacuous pass this probe exists to prevent. Never a key.
UNALLOCATED = 0

# Under one block a file may live inline in its inode and own no blocks at
# all. Such a file cannot share blocks and is not evidence in either
# direction, so it is counted and then excluded from the measurement.
MIN_MEASURABLE_BYTES = 4096

_libc = ctypes.CDLL(None, use_errno=True)
# fcntl and ioctl are variadic. On arm64 the variadic arguments use a
# different convention from the fixed ones, and ctypes only selects it when
# the FIXED arguments are declared. Without these four lines every fcntl call
# on Apple silicon returns EFAULT and the probe sees nothing whatsoever.
_libc.fcntl.argtypes = [ctypes.c_int, ctypes.c_int]
_libc.fcntl.restype = ctypes.c_int
_libc.ioctl.argtypes = [ctypes.c_int, ctypes.c_ulong]
_libc.ioctl.restype = ctypes.c_int


# ----------------------------------------------------------------- darwin

F_LOG2PHYS_EXT = 65


class _Log2Phys(ctypes.Structure):
    # sys/fcntl.h wraps this in `#pragma pack(4)`, so there is no padding
    # after the leading uint32 and the struct is 20 bytes rather than 24.
    _pack_ = 4
    _fields_ = [("l2p_flags", ctypes.c_uint32),
                ("l2p_contigbytes", ctypes.c_int64),
                ("l2p_devoffset", ctypes.c_int64)]


try:  # 3.14 wants the layout named once _pack_ is set; older ones reject it.
    _Log2Phys._layout_ = "ms"
except Exception:  # pragma: no cover
    pass


def _physical_darwin(fd):
    lp = _Log2Phys()
    lp.l2p_contigbytes = 1 << 20
    lp.l2p_devoffset = 0
    ctypes.set_errno(0)
    if _libc.fcntl(fd, F_LOG2PHYS_EXT, ctypes.byref(lp)) < 0:
        raise Unsupported("F_LOG2PHYS_EXT failed, errno %d" % ctypes.get_errno())
    return int(lp.l2p_devoffset)


# ------------------------------------------------------------------ linux

FS_IOC_FIEMAP = 0xC020660B
FIEMAP_FLAG_SYNC = 0x1


class _Fiemap(ctypes.Structure):
    # struct fiemap followed by exactly one struct fiemap_extent, which is how
    # the flexible array member is passed across the ioctl. 32 + 56 = 88.
    _fields_ = [("fm_start", ctypes.c_uint64),
                ("fm_length", ctypes.c_uint64),
                ("fm_flags", ctypes.c_uint32),
                ("fm_mapped_extents", ctypes.c_uint32),
                ("fm_extent_count", ctypes.c_uint32),
                ("fm_reserved", ctypes.c_uint32),
                ("fe_logical", ctypes.c_uint64),
                ("fe_physical", ctypes.c_uint64),
                ("fe_length", ctypes.c_uint64),
                ("fe_reserved64", ctypes.c_uint64 * 2),
                ("fe_flags", ctypes.c_uint32),
                ("fe_reserved", ctypes.c_uint32 * 3)]


def _physical_linux(fd):
    fm = _Fiemap()
    fm.fm_start = 0
    fm.fm_length = ctypes.c_uint64(~0).value
    fm.fm_extent_count = 1
    # FIEMAP_FLAG_SYNC is load-bearing. Without it ext4's delayed allocation
    # answers 0 for every freshly written file, so a plain byte copy and the
    # file it was copied from come back with the same address and the probe
    # reports total sharing. Measured on ext4: NOSYNC gave physical=0 for
    # both, SYNC gave 12583936 and 23069696.
    fm.fm_flags = FIEMAP_FLAG_SYNC
    ctypes.set_errno(0)
    if _libc.ioctl(fd, FS_IOC_FIEMAP, ctypes.byref(fm)) < 0:
        raise Unsupported("FS_IOC_FIEMAP failed, errno %d" % ctypes.get_errno())
    if fm.fm_mapped_extents < 1:
        raise Unsupported("FS_IOC_FIEMAP mapped no extents")
    return int(fm.fe_physical)


_physical = _physical_darwin if sys.platform == "darwin" else _physical_linux


def storage_key(path, dev):
    """Where this file's first block physically lives, scoped by device."""
    fd = os.open(path, os.O_RDONLY)
    try:
        try:
            os.fsync(fd)
        except OSError:
            pass  # advisory; FIEMAP_FLAG_SYNC is the guarantee on Linux
        offset = _physical(fd)
    finally:
        os.close(fd)
    if offset == UNALLOCATED:
        raise Unsupported("filesystem reported physical offset 0")
    return "%d:%d" % (dev, offset)


def _walk(root):
    for dirpath, _dirs, names in os.walk(root):
        for name in names:
            path = os.path.join(dirpath, name)
            if os.path.islink(path):
                continue
            try:
                st = os.stat(path)
            except OSError:
                continue
            yield path, st


def _blank():
    return {"files": 0, "bytes": 0, "measurableFiles": 0, "measurableBytes": 0,
            "sharedFiles": 0, "sharedBytes": 0, "viaInode": 0, "viaExtent": 0,
            "probeErrors": 0}


def index(root):
    """Every inode and every first-block address the reference tree owns."""
    inodes, extents = set(), set()
    stats = _blank()
    errors = []
    for path, st in _walk(root):
        stats["files"] += 1
        stats["bytes"] += st.st_size
        inodes.add("%d:%d" % (st.st_dev, st.st_ino))
        if st.st_size < MIN_MEASURABLE_BYTES:
            continue
        stats["measurableFiles"] += 1
        stats["measurableBytes"] += st.st_size
        try:
            extents.add(storage_key(path, st.st_dev))
        except Unsupported as why:
            stats["probeErrors"] += 1
            errors.append(str(why))
    return inodes, extents, stats, errors


def measure(root, inodes, extents):
    stats = _blank()
    errors = []
    for path, st in _walk(root):
        stats["files"] += 1
        stats["bytes"] += st.st_size
        if st.st_size < MIN_MEASURABLE_BYTES:
            continue
        stats["measurableFiles"] += 1
        stats["measurableBytes"] += st.st_size
        if "%d:%d" % (st.st_dev, st.st_ino) in inodes:
            stats["viaInode"] += 1
            stats["sharedFiles"] += 1
            stats["sharedBytes"] += st.st_size
            continue
        try:
            key = storage_key(path, st.st_dev)
        except Unsupported as why:
            stats["probeErrors"] += 1
            errors.append(str(why))
            continue
        if key in extents:
            stats["viaExtent"] += 1
            stats["sharedFiles"] += 1
            stats["sharedBytes"] += st.st_size
    return stats, errors


def _emit(scope, stats):
    sys.stdout.write(scope + " " + " ".join(
        "%s=%d" % (k, v) for k, v in sorted(stats.items())) + "\n")


def main(argv):
    reference = argv[1]
    inodes, extents, refStats, errors = index(reference)
    sys.stdout.write("platform %s\n" % sys.platform)
    refStats["inodes"] = len(inodes)
    refStats["extents"] = len(extents)
    _emit("reference", refStats)
    for position, target in enumerate(argv[2:]):
        stats, targetErrors = measure(target, inodes, extents)
        errors.extend(targetErrors)
        _emit("target%d" % position, stats)
    if errors:
        # One line, deduplicated: a thousand identical errnos say one thing.
        sys.stdout.write("error %s\n" % json.dumps(sorted(set(errors))))


if __name__ == "__main__":
    main(sys.argv)
