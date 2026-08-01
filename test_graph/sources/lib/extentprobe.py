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

Soundness
---------

Every gap in this probe is closed in the FALSE-NEGATIVE direction: a file the
probe cannot answer about is counted in `probeErrors` and contributes to
nothing, and the caller fails on a non-zero `probeErrors` rather than reading
it as "not shared". That asymmetry is deliberate. This instrument exists to
prove that storage IS shared, so a false positive is the failure that would
make it worthless, and the two ways it could produce one are both refused
rather than tolerated:

* `fe_physical` is only a device address for some extents. See
  `unaddressable`: for an inline, unknown, delayed-allocation, encoded or
  tail-packed extent the field means something else entirely, and two
  tail-packed files genuinely do share the block their tails sit in without
  sharing their data.
* `st_dev` is only a backing-store identity on a filesystem that has one. See
  `device_scope_hazard`: overlayfs reports a single synthetic `st_dev` for
  every file it serves regardless of which layer backs it, so on an overlay
  whose layers sit on different physical devices two unrelated files can
  collide on both key spaces this probe uses.

Both checks are pure functions with a table-driven `--self-test`, because a
soundness check that only runs when the hazard is present is a check nobody
ever sees run.
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

# linux/fiemap.h. Each of these says something about the extent that makes
# `fe_physical` NOT the device address of the file's data, so keying on it
# would compare two numbers that are not the same kind of thing:
#
#   UNKNOWN      the location is not known and the field is meaningless
#   DELALLOC     allocation is delayed; there is no block yet to have an
#                address, and UNKNOWN is set with it
#   ENCODED      the data is compressed/encrypted on disk, so the extent does
#                not map to the file's bytes
#   DATA_INLINE  the data lives in the inode's own metadata and owns no data
#                block at all
#   DATA_TAIL    the tail is packed into a block SHARED WITH OTHER FILES —
#                the one flag here that is an outright false-positive
#                generator, because two unrelated files packed into the same
#                block report the same address while sharing nothing
#
# MIN_MEASURABLE_BYTES screens most inline and tail cases out before the
# ioctl, and FIEMAP_FLAG_SYNC forces delayed allocation to resolve, so this
# gate has not been observed to fire. It is here because "has not fired" is
# not the same claim as "cannot fire", and this is the direction where being
# wrong is silent.
FIEMAP_EXTENT_UNKNOWN = 0x00000002
FIEMAP_EXTENT_DELALLOC = 0x00000004
FIEMAP_EXTENT_ENCODED = 0x00000008
FIEMAP_EXTENT_DATA_INLINE = 0x00000200
FIEMAP_EXTENT_DATA_TAIL = 0x00000400

# Deliberately NOT in the set: LAST (0x1), NOT_ALIGNED (0x100), UNWRITTEN
# (0x800), MERGED (0x1000) and SHARED (0x2000). For all of those `fe_physical`
# is a real device address; SHARED is in fact positive evidence, and refusing
# it would blind the probe to exactly what it measures.
_UNADDRESSABLE_FLAGS = (
    (FIEMAP_EXTENT_UNKNOWN, "FIEMAP_EXTENT_UNKNOWN"),
    (FIEMAP_EXTENT_DELALLOC, "FIEMAP_EXTENT_DELALLOC"),
    (FIEMAP_EXTENT_ENCODED, "FIEMAP_EXTENT_ENCODED"),
    (FIEMAP_EXTENT_DATA_INLINE, "FIEMAP_EXTENT_DATA_INLINE"),
    (FIEMAP_EXTENT_DATA_TAIL, "FIEMAP_EXTENT_DATA_TAIL"),
)


def unaddressable(fe_flags):
    """The flag names on this extent that make `fe_physical` not an address.

    Empty when the extent's physical offset may be compared with another
    file's. Pure, so `--self-test` can drive every flag without a filesystem
    that produces it.
    """
    return [name for bit, name in _UNADDRESSABLE_FLAGS if fe_flags & bit]


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
    return fiemap_offset(int(fm.fm_mapped_extents), int(fm.fe_flags), int(fm.fe_physical))


def fiemap_offset(mapped_extents, fe_flags, fe_physical):
    """The device address FIEMAP's first extent reports, or `Unsupported`.

    Split out of the ioctl so `--self-test` can drive every answer the kernel
    can give -- including the flag words no healthy filesystem produces, which
    is the whole point of the gate.
    """
    if mapped_extents < 1:
        raise Unsupported("FS_IOC_FIEMAP mapped no extents")
    blocking = unaddressable(fe_flags)
    if blocking:
        raise Unsupported("FS_IOC_FIEMAP first extent is %s, so fe_physical is not a "
                          "device address" % "|".join(blocking))
    return int(fe_physical)


_physical = _physical_darwin if sys.platform == "darwin" else _physical_linux


# ------------------------------------------------- device-scope soundness
#
# Both key spaces this probe uses -- "dev:inode" and "dev:offset" -- assume
# `st_dev` names the store the address came from. Overlayfs breaks that
# assumption: it reports ONE synthetic `st_dev` for every file it serves,
# whichever layer actually holds it, while FIEMAP passes straight through to
# the real backing file and answers with that device's addresses. Confirmed on
# a live overlay: a lower-layer file and an upper-layer file both reported
# `st_dev=62` while FIEMAP returned addresses from their real backing stores.
#
# It is only a hazard when the layers sit on DIFFERENT physical devices. Then
# two unrelated files can land on the same offset of two different disks and
# be reported as sharing storage -- a false positive, in an instrument whose
# job is to prove sharing. When every layer is on one device, as it is in the
# ordinary single-disk container, the synthetic `st_dev` collapses to exactly
# the scoping the key wanted and nothing can alias.
#
# So the answer is not "refuse overlayfs" -- that would delete real coverage
# in every container -- but "refuse an overlay whose layers do not agree on a
# device". The mount table already says which they are.


def _unescape_mountinfo(field):
    """mountinfo octal-escapes space, tab, newline and backslash."""
    out, i = [], 0
    while i < len(field):
        if field[i] == "\\" and field[i + 1:i + 4].isdigit():
            out.append(chr(int(field[i + 1:i + 4], 8)))
            i += 4
        else:
            out.append(field[i])
            i += 1
    return "".join(out)


def parse_mountinfo(text):
    """`(mount_point, fstype, super_options)` for each line of /proc mountinfo.

    Fields before the ` - ` separator are fixed-width only up to the optional
    tags, which is why the separator is found rather than counted.
    """
    mounts = []
    for line in text.splitlines():
        parts = line.split(" ")
        if "-" not in parts:
            continue
        sep = parts.index("-")
        if sep < 5 or len(parts) < sep + 3:
            continue
        mounts.append((_unescape_mountinfo(parts[4]), parts[sep + 1], parts[sep + 3]))
    return mounts


def mount_for(mounts, path):
    """The most specific mount whose mount point contains `path`.

    Longest matching prefix, on path components rather than characters, so
    `/var/lib` never matches a mount at `/var/libexec`.
    """
    best = None
    for point, fstype, options in mounts:
        if path == point or path.startswith(point.rstrip("/") + "/") or point == "/":
            if best is None or len(point) >= len(best[0]):
                best = (point, fstype, options)
    return best


def overlay_layer_dirs(super_options):
    """Every directory an overlayfs mount is layered out of.

    `lowerdir` is a `:`-separated list; `upperdir` and `workdir` are single
    paths. `workdir` is included because it must live on the upper filesystem,
    so it is a second witness for the upper device when `upperdir` is absent
    from the options for any reason.
    """
    dirs = []
    for option in super_options.split(","):
        key, _, value = option.partition("=")
        if not value:
            continue
        if key == "lowerdir":
            dirs.extend(part for part in value.split(":") if part)
        elif key in ("upperdir", "workdir"):
            dirs.append(value)
    return dirs


def device_scope_hazard(mounts, path, stat=os.stat):
    """Why `st_dev` cannot scope a storage key under `path`, or `None`.

    `None` means the two key spaces are sound here. A string means they are
    not, and the caller must refuse rather than report -- a refusal shows up
    as `probeErrors`, which the caller already fails on, whereas guessing
    shows up as sharing that was never there.
    """
    mount = mount_for(mounts, path)
    if mount is None or mount[1] != "overlay":
        return None
    layers = overlay_layer_dirs(mount[2])
    if not layers:
        return ("overlayfs at %s declares no layer directories, so its synthetic st_dev "
                "cannot be shown to name one backing store" % mount[0])
    devices = set()
    for layer in layers:
        try:
            devices.add(stat(layer).st_dev)
        except OSError as why:
            return ("overlayfs at %s has a layer this process cannot stat (%s: %s), so its "
                    "synthetic st_dev cannot be shown to name one backing store"
                    % (mount[0], layer, why))
    if len(devices) > 1:
        return ("overlayfs at %s is layered across %d devices %s while reporting one "
                "synthetic st_dev, so dev:offset and dev:inode keys can alias files on "
                "different backing stores" % (mount[0], len(devices), sorted(devices)))
    return None


# A test seam, and nothing else. `--self-test` needs to drive a whole run
# against a filesystem the host does not have, and the alternative -- mounting
# a two-device overlay -- needs root and a kernel that is not always there, so
# the gate would go untested on exactly the platforms that can produce the
# hazard. Never set in a measuring run; the file below is the only source then.
MOUNTINFO_ENV = "EXTENTPROBE_MOUNTINFO"


def _mountinfo():
    override = os.environ.get(MOUNTINFO_ENV)
    if override is not None:
        return parse_mountinfo(override)
    try:
        with open("/proc/self/mountinfo", "r") as handle:
            return parse_mountinfo(handle.read())
    except OSError:
        return []


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


def index(root, hazard=None):
    """Every inode and every first-block address the reference tree owns.

    `hazard` is the reason `st_dev` cannot scope a key under `root`, or None.
    A hazard is not a reason to fall back on a weaker answer: every measurable
    file becomes a counted probe error, which the caller fails on.
    """
    inodes, extents = set(), set()
    stats = _blank()
    errors = []
    for path, st in _walk(root):
        stats["files"] += 1
        stats["bytes"] += st.st_size
        if hazard is None:
            inodes.add("%d:%d" % (st.st_dev, st.st_ino))
        if st.st_size < MIN_MEASURABLE_BYTES:
            continue
        stats["measurableFiles"] += 1
        stats["measurableBytes"] += st.st_size
        if hazard is not None:
            stats["probeErrors"] += 1
            errors.append(hazard)
            continue
        try:
            extents.add(storage_key(path, st.st_dev))
        except Unsupported as why:
            stats["probeErrors"] += 1
            errors.append(str(why))
    return inodes, extents, stats, errors


def measure(root, inodes, extents, hazard=None):
    stats = _blank()
    errors = []
    for path, st in _walk(root):
        stats["files"] += 1
        stats["bytes"] += st.st_size
        if st.st_size < MIN_MEASURABLE_BYTES:
            continue
        stats["measurableFiles"] += 1
        stats["measurableBytes"] += st.st_size
        if hazard is not None:
            stats["probeErrors"] += 1
            errors.append(hazard)
            continue
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
    if len(argv) > 1 and argv[1] == "--self-test":
        return self_test()
    reference = argv[1]
    mounts = _mountinfo()
    # Asked once per tree rather than once per file: a hazard is a property of
    # the filesystem serving the tree, and asking per file would reread the
    # mount table tens of thousands of times to get the same answer.
    hazard = device_scope_hazard(mounts, os.path.abspath(reference))
    inodes, extents, refStats, errors = index(reference, hazard)
    sys.stdout.write("platform %s\n" % sys.platform)
    refStats["inodes"] = len(inodes)
    refStats["extents"] = len(extents)
    _emit("reference", refStats)
    for position, target in enumerate(argv[2:]):
        targetHazard = device_scope_hazard(mounts, os.path.abspath(target))
        stats, targetErrors = measure(target, inodes, extents, hazard or targetHazard)
        errors.extend(targetErrors)
        _emit("target%d" % position, stats)
    if errors:
        # One line, deduplicated: a thousand identical errnos say one thing.
        sys.stdout.write("error %s\n" % json.dumps(sorted(set(errors))))
    return 0


# ------------------------------------------------------------- self test
#
# The two soundness gates guard hazards that a healthy host does not produce,
# so nothing in an ordinary run exercises them. Driving them from tables here
# is what stops them being decoration: `--self-test` exits non-zero when a gate
# stops refusing what it was written to refuse, and prints one `selftest`
# line per case so a caller can assert on the cases rather than on the exit
# code alone.


class _Stat(object):
    def __init__(self, dev):
        self.st_dev = dev


_EXT4_MOUNTINFO = """\
25 0 8:1 / / rw,relatime shared:1 - ext4 /dev/vda1 rw
26 25 0:22 / /proc rw,nosuid shared:5 - proc proc rw
"""

# One device behind every layer: the ordinary single-disk container, where the
# synthetic st_dev happens to scope exactly what the key wanted.
_OVERLAY_ONE_DEVICE = """\
25 0 8:1 / / rw,relatime shared:1 - ext4 /dev/vda1 rw
90 25 0:62 / /var/lib/docker/overlay rw,relatime - overlay overlay \
rw,lowerdir=/lower,upperdir=/upper,workdir=/work
"""

# Lower and upper on different disks: the case where two unrelated files can
# report the same dev:offset.
_OVERLAY_TWO_DEVICES = """\
25 0 8:1 / / rw,relatime shared:1 - ext4 /dev/vda1 rw
90 25 0:62 / /var/lib/docker/overlay rw,relatime - overlay overlay \
rw,lowerdir=/lower:/lower2,upperdir=/upper,workdir=/work
"""

_LAYER_DEVICES = {"/lower": 2049, "/lower2": 2049, "/upper": 2049, "/work": 2049}
_SPLIT_DEVICES = {"/lower": 2049, "/lower2": 2049, "/upper": 2065, "/work": 2065}


def _stat_from(table):
    def stat(path):
        if path not in table:
            raise OSError(2, "No such file or directory", path)
        return _Stat(table[path])
    return stat


def _refused(call):
    """(was it refused, why) for something that should raise Unsupported."""
    try:
        call()
        return False, ""
    except Unsupported as why:
        return True, str(why)


def _end_to_end(mountinfo):
    """Run a whole measurement over a real tree with a declared mount table.

    Two 8 KiB files, one a hard link of the other, so an unrefused run MUST
    report sharing -- which is what makes the refusing run mean something. The
    tree is real; only the mount table it is judged against is supplied.
    """
    import contextlib
    import io
    import shutil
    import tempfile

    work = tempfile.mkdtemp(prefix="extentprobe-selftest-")
    previous = os.environ.get(MOUNTINFO_ENV)
    try:
        reference = os.path.join(work, "reference")
        target = os.path.join(work, "target")
        os.makedirs(reference)
        os.makedirs(target)
        payload = os.urandom(2 * MIN_MEASURABLE_BYTES)
        with open(os.path.join(reference, "payload.bin"), "wb") as handle:
            handle.write(payload)
        os.link(os.path.join(reference, "payload.bin"), os.path.join(target, "payload.bin"))

        os.environ[MOUNTINFO_ENV] = mountinfo
        captured = io.StringIO()
        with contextlib.redirect_stdout(captured):
            main(["extentprobe.py", reference, target])
        report = {}
        for line in captured.getvalue().splitlines():
            scope, _, rest = line.partition(" ")
            if scope in ("reference", "target0"):
                report[scope] = dict(
                    (pair.split("=")[0], int(pair.split("=")[1]))
                    for pair in rest.split(" ") if "=" in pair)
        return report
    finally:
        if previous is None:
            os.environ.pop(MOUNTINFO_ENV, None)
        else:
            os.environ[MOUNTINFO_ENV] = previous
        shutil.rmtree(work, ignore_errors=True)


# An overlay whose layers are paths that cannot be stat-ed: the same refusal
# the two-device case takes, reachable with the real os.stat on any host.
_OVERLAY_UNVERIFIABLE = """\
25 0 8:1 / / rw,relatime shared:1 - overlay overlay \
rw,lowerdir=/extentprobe-no-such-lower,upperdir=/extentprobe-no-such-upper,\
workdir=/extentprobe-no-such-work
"""


def _self_test_cases():
    plain = 0x1  # FIEMAP_EXTENT_LAST alone
    real_address = 0x1 | 0x1000 | 0x2000  # LAST | MERGED | SHARED
    inline_refused, inline_why = _refused(
        lambda: fiemap_offset(1, FIEMAP_EXTENT_DATA_INLINE, 4096))
    tail_refused, _ = _refused(lambda: fiemap_offset(1, FIEMAP_EXTENT_DATA_TAIL, 4096))
    no_extent_refused, _ = _refused(lambda: fiemap_offset(0, 0x1, 4096))
    measured = _end_to_end(_EXT4_MOUNTINFO)
    refused_run = _end_to_end(_OVERLAY_UNVERIFIABLE)
    cases = [
        ("a_plain_extent_is_addressable", unaddressable(plain) == []),
        ("a_shared_or_merged_extent_is_still_addressable",
         unaddressable(real_address) == []),
        ("an_unwritten_preallocated_extent_is_still_addressable",
         unaddressable(0x800) == []),
        ("an_unknown_extent_is_refused",
         unaddressable(FIEMAP_EXTENT_UNKNOWN) == ["FIEMAP_EXTENT_UNKNOWN"]),
        ("a_delayed_allocation_extent_is_refused",
         unaddressable(FIEMAP_EXTENT_DELALLOC) == ["FIEMAP_EXTENT_DELALLOC"]),
        ("an_encoded_extent_is_refused",
         unaddressable(FIEMAP_EXTENT_ENCODED) == ["FIEMAP_EXTENT_ENCODED"]),
        ("an_inline_data_extent_is_refused",
         unaddressable(FIEMAP_EXTENT_DATA_INLINE) == ["FIEMAP_EXTENT_DATA_INLINE"]),
        ("a_tail_packed_extent_is_refused",
         unaddressable(FIEMAP_EXTENT_DATA_TAIL) == ["FIEMAP_EXTENT_DATA_TAIL"]),
        ("a_refused_flag_is_still_refused_beside_addressable_ones",
         unaddressable(plain | FIEMAP_EXTENT_DELALLOC) == ["FIEMAP_EXTENT_DELALLOC"]),
        # -------------------------------------------------- device scoping
        ("a_plain_filesystem_has_no_device_scope_hazard",
         device_scope_hazard(parse_mountinfo(_EXT4_MOUNTINFO), "/home/x/store",
                             _stat_from(_LAYER_DEVICES)) is None),
        ("an_overlay_whose_layers_share_a_device_is_sound",
         device_scope_hazard(parse_mountinfo(_OVERLAY_ONE_DEVICE),
                             "/var/lib/docker/overlay/store",
                             _stat_from(_LAYER_DEVICES)) is None),
        ("an_overlay_layered_across_two_devices_is_refused",
         "can alias" in (device_scope_hazard(parse_mountinfo(_OVERLAY_TWO_DEVICES),
                                             "/var/lib/docker/overlay/store",
                                             _stat_from(_SPLIT_DEVICES)) or "")),
        ("an_overlay_layer_that_cannot_be_stat_ed_is_refused",
         "cannot stat" in (device_scope_hazard(parse_mountinfo(_OVERLAY_ONE_DEVICE),
                                               "/var/lib/docker/overlay/store",
                                               _stat_from({})) or "")),
        ("a_path_outside_the_overlay_is_unaffected_by_it",
         device_scope_hazard(parse_mountinfo(_OVERLAY_TWO_DEVICES), "/home/x/store",
                             _stat_from(_SPLIT_DEVICES)) is None),
        ("the_most_specific_mount_wins_over_the_root_mount",
         mount_for(parse_mountinfo(_OVERLAY_ONE_DEVICE),
                   "/var/lib/docker/overlay/store")[1] == "overlay"),
        ("a_sibling_mount_point_is_not_a_prefix_match",
         mount_for(parse_mountinfo(_OVERLAY_ONE_DEVICE),
                   "/var/lib/docker/overlay-other/store")[1] == "ext4"),
        ("every_overlay_layer_directory_is_read_from_the_options",
         overlay_layer_dirs("rw,lowerdir=/a:/b,upperdir=/c,workdir=/d")
         == ["/a", "/b", "/c", "/d"]),
        # ------------------------------------------------- the call sites
        # Above proves the gates decide correctly. These prove they are
        # WIRED: a gate the ioctl path or main() no longer consults decides
        # correctly about nothing.
        ("the_fiemap_reader_refuses_an_unaddressable_extent", inline_refused),
        ("and_says_which_flag_made_it_refuse",
         "FIEMAP_EXTENT_DATA_INLINE" in inline_why),
        ("the_fiemap_reader_refuses_a_tail_packed_extent", tail_refused),
        ("the_fiemap_reader_still_refuses_a_file_with_no_extents", no_extent_refused),
        # The control for the pair below: without it, "refused everything" is
        # indistinguishable from "there was nothing to measure".
        ("a_sound_mount_table_measures_the_tree_and_finds_the_hard_link",
         measured.get("target0", {}).get("sharedFiles") == 1
         and measured.get("target0", {}).get("viaInode") == 1
         and measured.get("target0", {}).get("probeErrors") == 0),
        ("an_unverifiable_overlay_refuses_every_measurable_file",
         refused_run.get("target0", {}).get("probeErrors")
         == refused_run.get("target0", {}).get("measurableFiles")
         and refused_run.get("target0", {}).get("measurableFiles") == 1),
        ("and_reports_nothing_as_shared_rather_than_guessing",
         refused_run.get("target0", {}).get("sharedFiles") == 0
         and refused_run.get("reference", {}).get("inodes") == 0),
    ]
    return cases


def self_test():
    cases = _self_test_cases()
    for name, ok in cases:
        sys.stdout.write("selftest %s ok=%d\n" % (name, 1 if ok else 0))
    sys.stdout.write("selftest-total %d\n" % len(cases))
    return 0 if all(ok for _, ok in cases) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv) or 0)
