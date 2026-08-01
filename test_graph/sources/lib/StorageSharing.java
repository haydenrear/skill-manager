import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whether a tree's files share their backing blocks with another tree's, read
 * per file out of the filesystem.
 *
 * <h2>Why the cost nodes stopped measuring free space</h2>
 *
 * <p>Both cost oracles in {@code home-clone} used to infer sharing from the
 * change in free space on a dedicated APFS sparse image. That instrument is
 * statistical, and on a host with 2.5 GiB free and a k3d cluster running it
 * measured the host rather than the workload. Four consecutive runs of
 * {@code shared.store.materialization.costs.far.less.than.a.copy} on identical
 * work:
 *
 * <pre>
 *   run     idle       control    venvConsumed   result
 *   010918  -10.79MB    55.47MB       -0.72MB    FAIL (idle noise)
 *   011144    1.43MB    55.39MB       15.64MB    FAIL (venv/control ratio)
 *   011226    1.83MB    74.08MB       -5.54MB    PASS
 *   011307    3.38MB    70.52MB        8.38MB    PASS
 * </pre>
 *
 * <p>A <em>negative</em> consumption for work that certainly consumed
 * something is the proof: a sparse image's available space is bounded by the
 * free space of the disk backing it, so every write the operator's editor,
 * browser and agents made leaked straight through the "dedicated" volume. The
 * fix is not a wider budget — that is deleting the check. The fix is to stop
 * inferring a per-file property from a volume-wide one.
 *
 * <h2>What is measured instead</h2>
 *
 * <p>Two files share storage when they name the same inode (a hard link) or
 * when their data begins at the same physical device offset (a reflink: APFS
 * {@code clonefile}, btrfs/xfs reflink). Both are answers about one file, from
 * a syscall about that file — {@code F_LOG2PHYS_EXT} on Darwin,
 * {@code FS_IOC_FIEMAP} on Linux — so no other process on the host can move
 * them. See {@code sources/lib/extentprobe.py}, which this class runs.
 *
 * <p>Nothing cheaper works. Measured on this host for a 10 MB file and its
 * clone: apparent size identical by construction; {@code du} identical
 * (10240 KB each) because it attributes shared blocks to both; {@code stat}
 * {@code st_blocks} identical (20480) because a clone is allocated its full
 * length; {@code st_nlink} still 1, because {@code clonefile} is not a link.
 * Only the physical address differs, and it is the thing being asked about.
 *
 * <h2>The two controls every caller must keep</h2>
 *
 * <p>{@link #hardLink} and {@link #streamCopy} exist to be asserted on, not
 * merely used. A hard link MUST come back shared and a byte copy MUST come
 * back unshared, on every filesystem, in the same run as the measurement. The
 * first fails loudly when the probe goes blind; the second fails loudly when
 * it reports sharing indiscriminately. Both failure modes were observed while
 * building this: on Apple silicon an undeclared variadic {@code fcntl}
 * returned EFAULT for every file, and on ext4 a {@code FS_IOC_FIEMAP} without
 * {@code FIEMAP_FLAG_SYNC} answered physical offset 0 for every file, which
 * made a plain byte copy of a 10 MB file report as fully shared with its
 * source. Neither is detectable from the measurement alone.
 */
final class StorageSharing {

    private StorageSharing() {}

    /** One tree's sharing against the reference tree it was measured with. */
    record Sharing(int files, long bytes, int measurableFiles, long measurableBytes,
                   int sharedFiles, long sharedBytes, int viaInode, int viaExtent,
                   int probeErrors) {

        /** Of the bytes that CAN own blocks, the fraction backed by the reference. */
        double sharedFraction() {
            return measurableBytes == 0 ? 0.0 : (double) sharedBytes / measurableBytes;
        }

        boolean nothingShared() {
            return sharedFiles == 0;
        }

        String describe() {
            return "files=" + files + " measurable=" + measurableFiles + "/"
                    + mb(measurableBytes) + "MB shared=" + sharedFiles + "/"
                    + mb(sharedBytes) + "MB (" + pct(sharedFraction()) + ") viaInode="
                    + viaInode + " viaExtent=" + viaExtent + " probeErrors=" + probeErrors;
        }
    }

    /** The whole probe run: the reference tree, then each target in argument order. */
    record Measurement(String platform, Sharing reference, List<Sharing> targets,
                       String errors, String raw) {

        Sharing target(int position) {
            return targets.get(position);
        }

        /** True when every file the probe was asked about answered. */
        boolean probeAnsweredEverything() {
            if (reference.probeErrors() != 0) return false;
            for (Sharing t : targets) if (t.probeErrors() != 0) return false;
            return true;
        }
    }

    // ------------------------------------------------------------- running

    /**
     * The probe script, which lives beside this file. A missing probe is a
     * FAILURE for the caller and never a skip: "the instrument was not there"
     * and "the property holds" must not produce the same envelope.
     */
    static Path script() {
        return Path.of(System.getProperty("user.dir"))
                .resolve("sources/lib/extentprobe.py");
    }

    /** The first usable interpreter, or null when the host has none. */
    static String python() {
        for (String candidate : List.of("python3", "python")) {
            String found = which(candidate);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Measure {@code targets} against {@code reference}.
     *
     * @throws IOException when the probe could not be run or did not answer —
     *         never a quiet zero, which would read as "nothing is shared".
     */
    static Measurement measure(String python, Path reference, List<Path> targets)
            throws IOException {
        List<String> command = new ArrayList<>(
                List.of(python, "-W", "ignore", script().toString(), reference.toString()));
        for (Path target : targets) command.add(target.toString());

        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        String out;
        int exit;
        try {
            Process process = pb.start();
            out = new String(process.getInputStream().readAllBytes());
            exit = process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("extent probe interrupted", interrupted);
        }
        if (exit != 0) {
            throw new IOException("extent probe exited " + exit + ": " + out);
        }

        String platform = null;
        String errors = "";
        Sharing reference0 = null;
        Map<Integer, Sharing> byPosition = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) continue;
            int space = line.indexOf(' ');
            if (space < 0) continue;
            String scope = line.substring(0, space);
            String rest = line.substring(space + 1);
            if (scope.equals("platform")) {
                platform = rest.trim();
            } else if (scope.equals("error")) {
                errors = rest.trim();
            } else if (scope.equals("reference")) {
                reference0 = parse(rest);
            } else if (scope.startsWith("target")) {
                byPosition.put(Integer.parseInt(scope.substring("target".length())), parse(rest));
            }
        }
        if (platform == null || reference0 == null || byPosition.size() != targets.size()) {
            throw new IOException("extent probe produced no usable report: " + out);
        }
        List<Sharing> ordered = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) ordered.add(byPosition.get(i));
        return new Measurement(platform, reference0, ordered, errors, out.strip());
    }

    private static Sharing parse(String fields) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String pair : fields.trim().split("\\s+")) {
            int eq = pair.indexOf('=');
            if (eq > 0) values.put(pair.substring(0, eq), Long.parseLong(pair.substring(eq + 1)));
        }
        return new Sharing(
                (int) get(values, "files"), get(values, "bytes"),
                (int) get(values, "measurableFiles"), get(values, "measurableBytes"),
                (int) get(values, "sharedFiles"), get(values, "sharedBytes"),
                (int) get(values, "viaInode"), (int) get(values, "viaExtent"),
                (int) get(values, "probeErrors"));
    }

    private static long get(Map<String, Long> values, String key) {
        Long value = values.get(key);
        if (value == null) throw new IllegalStateException("probe omitted " + key);
        return value;
    }

    // ------------------------------------------------------------ controls

    /**
     * A hard link: the same inode under a second name, so the two files share
     * every block by definition. The instrument's liveness control — every
     * POSIX filesystem can do this, so a probe that cannot see it is broken
     * rather than merely unlucky, and the caller must FAIL.
     */
    static void hardLink(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.deleteIfExists(to);
        Files.createLink(to, from);
    }

    /**
     * A byte-for-byte copy through a read/write loop, which cannot share
     * anything on any filesystem. The instrument's discrimination control: a
     * probe that reports THIS as shared is reporting sharing for everything.
     *
     * <h2>Why the loop, and not any "copy" the platform offers</h2>
     *
     * <p>Deliberately not {@code Files.copy}, and deliberately not
     * {@code UV_LINK_MODE=copy}. <b>Whether a "copy" copies is a property of
     * the filesystem, not of the platform</b> — an earlier version of this
     * comment called it a macOS/APFS quirk, and that is wrong in the direction
     * that costs something: it invites reintroducing {@code UV_LINK_MODE=copy}
     * as a negative control "because Linux is safe", where it silently stops
     * being one. Measured (issue #131):
     *
     * <pre>
     *   filesystem            UV_LINK_MODE=copy      valid negative control?
     *   APFS (macOS)          clones                 NO
     *   btrfs                 99.83% shared,         NO
     *                         viaExtent=381
     *   xfs with reflink=1    clones                 NO
     *   ext4                  0.00% shared           yes
     * </pre>
     *
     * <p>The mechanism is the same everywhere: the JDK takes {@code clonefile}
     * on APFS when {@code COPY_ATTRIBUTES} is present, and Rust's
     * {@code std::fs::copy} — which is what uv's copy mode is — calls
     * {@code fclonefileat} on APFS and {@code copy_file_range} on Linux, which
     * btrfs and reflink-enabled xfs service by reflinking. On btrfs the copy
     * run was indistinguishable from the hardlink run.
     *
     * <p>Only a read/write loop through userspace is a copy on every
     * filesystem, which is why this method exists and why every caller's
     * negative control must be this and nothing else.
     */
    static void streamCopy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        try (var in = Files.newInputStream(from);
             var out = Files.newOutputStream(to)) {
            in.transferTo(out);
        }
    }

    /**
     * The copy the production code makes: {@code COPY_ATTRIBUTES} is what
     * selects {@code clonefile} on APFS. Used by callers to ask whether this
     * platform can reflink at all, which is a question about the platform and
     * not about the code under test.
     */
    static void attributedCopy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    /** Incompressible, so nothing downstream can dedupe or compress it into looking free. */
    static void writeIncompressible(Path file, long bytes) throws IOException {
        Files.createDirectories(file.getParent());
        java.util.Random random = new java.util.Random(20260730L);
        byte[] chunk = new byte[1 << 20];
        try (var out = Files.newOutputStream(file)) {
            for (long written = 0; written < bytes; written += chunk.length) {
                random.nextBytes(chunk);
                out.write(chunk, 0, (int) Math.min(chunk.length, bytes - written));
            }
        }
    }

    // ------------------------------------------------------------- walking

    static long apparentSize(Path root) throws IOException {
        long[] total = {0};
        try (var walk = Files.walk(root)) {
            walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).forEach(p -> {
                try {
                    total[0] += Files.size(p);
                } catch (IOException ignored) {
                    // vanished mid-walk; not part of the claim
                }
            });
        }
        return total[0];
    }

    static int countFiles(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return (int) walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).count();
        }
    }

    static void rmrf(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    static String which(String tool) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path candidate = Path.of(dir, tool);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }

    static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.2f", bytes / 1_000_000.0);
    }

    static String pct(double fraction) {
        return String.format(Locale.ROOT, "%.2f%%", fraction * 100.0);
    }
}
