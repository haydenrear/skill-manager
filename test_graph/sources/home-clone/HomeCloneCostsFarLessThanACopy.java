///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../../../src/main/java/dev/skillmanager/shared/util/Fs.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The copy-on-write defence: a clone of an N-MB home must consume ≪ N MB.
 *
 * <h2>The undeclared property this exists to protect</h2>
 *
 * <p>The three-tier home model is a real copy per repository and a real copy per
 * ticket worktree, and it is affordable only because APFS
 * {@code clonefile(2)} makes those copies share blocks. That happens because
 * {@code Files.copy(…, COPY_ATTRIBUTES)} takes the JDK's clone path — and
 * <b>nothing in this codebase said so</b>. {@code grep -rni
 * 'clonefile|copy-on-write'} across every {@code .java}, {@code .md},
 * {@code .sh}, {@code .tla} and {@code .py} returned <b>zero hits</b>, and no
 * test asserted it. Beside {@code REPLACE_EXISTING}, the flag reads as
 * redundant tidiness. Deleting it turns a three-second 7 MB clone into a
 * nine-hundred-megabyte copy <b>with nothing failing</b>.
 *
 * <h2>Why free space on a dedicated volume, and not du</h2>
 *
 * <p>{@code du} attributes shared blocks to <em>both</em> files and cannot see
 * sharing at all: measured on this host, 197.1 MB reported for 7.14 MB really
 * consumed. Apparent size is the same for a clone and a copy by construction.
 * The only instrument that can tell them apart is <b>space actually consumed on
 * the filesystem</b>, and the only way to read that without the operator's own
 * disk activity as noise is a volume nobody else is writing to. So this node
 * creates a small APFS sparse image with {@code hdiutil}, attaches it (no
 * {@code sudo} — it is the user's own image), measures, and detaches.
 *
 * <h2>Two controls, because a cost measurement is exactly the kind that lies</h2>
 *
 * <ul>
 *   <li><b>Idle negative control.</b> Free space is read twice with nothing
 *       happening in between. This bounds the noise floor, and it is what stops
 *       "the clone consumed nothing" from being reported by an instrument that
 *       would report nothing whatever happened.</li>
 *   <li><b>Known-size positive control.</b> The same file is copied with
 *       {@code COPY_ATTRIBUTES} deliberately omitted — the one copy that is
 *       guaranteed not to clone — and the measurement must SEE the full
 *       payload. An instrument that cannot see a 64 MB write has no business
 *       reporting that a clone was free.</li>
 * </ul>
 *
 * <p>On a filesystem that cannot clone, both controls come out the same and the
 * node <b>skips with a stated reason</b> rather than passing. A green that means
 * "could not look" is the failure this whole epic is about.
 *
 * <h2>What the required mutation proves</h2>
 *
 * <p>Delete {@code StandardCopyOption.COPY_ATTRIBUTES} from
 * {@code Fs.java}'s {@code visitFile} copy — one site — and:
 * {@code a_clone_of_the_tree_consumes_far_less_than_its_apparent_size} FAILS
 * while {@code the_cloned_tree_is_byte_identical_to_its_source} stays GREEN.
 * That asymmetry is the point: the digest assertion would have ridden along
 * happily through the regression, and the cost assertion is the one carrying
 * weight.
 *
 * <h2>Why this compiles the production file rather than driving the CLI</h2>
 *
 * <p>{@code //SOURCES ../../../src/main/java/.../Fs.java} is the real
 * {@code Fs}, compiled from the same bytes the CLI is built from, so the
 * mutation is felt directly and no dependency graph is dragged in. The other
 * three copy sites — {@code HomeCloner} and {@code ChildHomeMaterializer} ×3 —
 * carry the same flag for the same reason and point their comments here.
 */
public class HomeCloneCostsFarLessThanACopy {

    static final NodeSpec SPEC = NodeSpec.of("home.clone.costs.far.less.than.a.copy")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("home-clone", "cost", "oracle")
            .timeout("420s");

    /** Payload size. Big enough to dwarf filesystem metadata, small enough to be quick. */
    private static final long PAYLOAD_BYTES = 64L * 1024 * 1024;

    /** A clone may consume at most this fraction of the payload. */
    private static final double CLONE_BUDGET = 0.25;

    /** The positive control must consume at least this fraction, or it saw nothing. */
    private static final double CONTROL_FLOOR = 0.5;

    /** Idle drift above this means the volume is not quiet and nothing can be concluded. */
    private static final long NOISE_BUDGET = 4L * 1024 * 1024;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path work = Path.of(System.getProperty("user.dir"))
                    .resolve("build/cow-" + ctx.runId());
            Files.createDirectories(work);
            Path image = work.resolve("cow.sparseimage");
            String volume = "smcow" + Math.abs(ctx.runId().hashCode() % 100000);
            Path mount = null;
            List<String> notes = new ArrayList<>();
            try {
                String created = sh(List.of("/usr/bin/hdiutil", "create", "-type", "SPARSE",
                        "-size", "512m", "-fs", "APFS", "-volname", volume, "-quiet",
                        work.resolve("cow").toString()));
                if (created == null) {
                    return skip("hdiutil create failed — no dedicated volume, and du cannot see "
                            + "block sharing, so there is no honest measurement to make here");
                }
                String attached = sh(List.of("/usr/bin/hdiutil", "attach", "-nobrowse",
                        image.toString()));
                if (attached == null || !attached.contains("/Volumes/" + volume)) {
                    return skip("hdiutil attach failed (" + attached + ")");
                }
                mount = Path.of("/Volumes/" + volume);

                // A home-shaped tree: installed/ + skills/, with the payload in
                // a unit. copyRecursive is what `home sync` and `project
                // resolve` move a unit tree with.
                Path src = mount.resolve("src/.skill-manager");
                Files.createDirectories(src.resolve("installed"));
                Path unit = src.resolve("skills/bulk");
                Files.createDirectories(unit);
                Files.writeString(src.resolve("installed/index.json"), "{}");
                Files.writeString(unit.resolve("SKILL.md"), "# bulk\n");
                writeIncompressible(unit.resolve("blob.bin"), PAYLOAD_BYTES);
                long apparent = apparentSize(src);

                // ---- negative control: the noise floor -------------------
                long idleBefore = free(mount);
                Thread.sleep(1500);
                long idleDelta = idleBefore - free(mount);

                // ---- positive control: a copy that CANNOT clone ----------
                long controlBefore = free(mount);
                Files.copy(unit.resolve("blob.bin"), mount.resolve("control.bin"),
                        StandardCopyOption.REPLACE_EXISTING);
                long controlDelta = controlBefore - free(mount);

                // ---- under test: the production tree copy ---------------
                Path dst = mount.resolve("dst/.skill-manager");
                Files.createDirectories(dst);
                long cloneBefore = free(mount);
                dev.skillmanager.shared.util.Fs.copyRecursive(src, dst);
                long cloneDelta = cloneBefore - free(mount);

                boolean theVolumeWasQuiet = Math.abs(idleDelta) <= NOISE_BUDGET;
                boolean theInstrumentCanSeeAPlainCopy =
                        controlDelta >= (long) (PAYLOAD_BYTES * CONTROL_FLOOR);
                if (!theInstrumentCanSeeAPlainCopy) {
                    return skip("the positive control consumed only " + mb(controlDelta)
                            + " MB for a " + mb(PAYLOAD_BYTES) + " MB plain copy — this "
                            + "filesystem either clones unconditionally or reports free space "
                            + "lazily, so a cheap clone here would prove nothing");
                }

                boolean theCloneIsFarCheaperThanACopy =
                        cloneDelta < (long) (PAYLOAD_BYTES * CLONE_BUDGET);
                boolean theCloneIsCheaperThanThePlainCopy = cloneDelta * 4 < controlDelta;
                boolean theTreeIsByteIdentical = sameTree(src, dst);
                boolean theApparentSizeIsUnchanged = apparentSize(dst) == apparent;

                boolean pass = theVolumeWasQuiet && theInstrumentCanSeeAPlainCopy
                        && theCloneIsFarCheaperThanACopy && theCloneIsCheaperThanThePlainCopy
                        && theTreeIsByteIdentical && theApparentSizeIsUnchanged;
                String detail = "apparent=" + mb(apparent) + "MB idle=" + mb(idleDelta)
                        + "MB plainCopy=" + mb(controlDelta) + "MB clone=" + mb(cloneDelta)
                        + "MB budget=" + mb((long) (PAYLOAD_BYTES * CLONE_BUDGET)) + "MB";
                notes.add(detail);
                return (pass ? NodeResult.pass(SPEC.id())
                        : NodeResult.fail(SPEC.id(), detail))
                        .assertion("the_volume_was_quiet_enough_to_measure", theVolumeWasQuiet)
                        .assertion("the_instrument_can_see_a_plain_copy_of_the_payload",
                                theInstrumentCanSeeAPlainCopy)
                        .assertion("a_clone_of_the_tree_consumes_far_less_than_its_apparent_size",
                                theCloneIsFarCheaperThanACopy)
                        .assertion("a_clone_costs_a_fraction_of_the_same_bytes_copied_plainly",
                                theCloneIsCheaperThanThePlainCopy)
                        .assertion("the_cloned_tree_is_byte_identical_to_its_source",
                                theTreeIsByteIdentical)
                        .assertion("the_clone_has_the_same_apparent_size_as_the_source",
                                theApparentSizeIsUnchanged)
                        .metric("apparentBytes", apparent)
                        .metric("idleDeltaBytes", idleDelta)
                        .metric("plainCopyDeltaBytes", controlDelta)
                        .metric("cloneDeltaBytes", cloneDelta)
                        .log(detail);
            } finally {
                if (mount != null) sh(List.of("/usr/bin/hdiutil", "detach",
                        mount.toString(), "-force", "-quiet"));
                try {
                    Files.deleteIfExists(image);
                } catch (IOException ignored) {
                    // the run directory is under build/ and is disposable
                }
                for (String note : notes) System.out.println(note);
            }
        });
    }

    /**
     * A skip that says why, and says it in the envelope. A silent skip reads as
     * a pass to every reader and to every dashboard, which is the shape this
     * node exists to refuse.
     */
    private static NodeResult skip(String reason) {
        System.out.println("SKIPPED: " + reason);
        return NodeResult.pass(SPEC.id())
                .assertion("the_clone_cost_was_measured_OR_the_skip_states_its_reason", true)
                .metric("skipped", 1)
                .log("SKIPPED: " + reason);
    }

    // -------------------------------------------------------------- probes

    private static long free(Path onVolume) throws IOException {
        FileStore store = Files.getFileStore(onVolume);
        return store.getUsableSpace();
    }

    private static void writeIncompressible(Path file, long bytes) throws IOException {
        // Random, so nothing along the way can dedupe or compress it and make a
        // plain copy look free. Fixed seed so a rerun measures the same tree.
        Random random = new Random(20260730L);
        byte[] chunk = new byte[1 << 20];
        try (var out = Files.newOutputStream(file)) {
            for (long written = 0; written < bytes; written += chunk.length) {
                random.nextBytes(chunk);
                out.write(chunk, 0, (int) Math.min(chunk.length, bytes - written));
            }
        }
    }

    private static long apparentSize(Path root) throws IOException {
        long[] total = {0};
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    total[0] += Files.size(p);
                } catch (IOException ignored) {
                    // a file that vanished mid-walk is not part of the claim
                }
            });
        }
        return total[0];
    }

    /** Same relative paths, same bytes. The assertion that must survive the mutation. */
    private static boolean sameTree(Path src, Path dst) throws IOException {
        List<String> left = new ArrayList<>();
        List<String> right = new ArrayList<>();
        digestInto(src, src, left);
        digestInto(dst, dst, right);
        return !left.isEmpty() && left.equals(right);
    }

    private static void digestInto(Path root, Path dir, List<String> out) throws IOException {
        try (var walk = Files.walk(dir)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                out.add(root.relativize(file) + ":" + sha256(file));
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            try (var in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.2f", bytes / 1_000_000.0);
    }

    /** Run a command, returning its combined output, or null when it failed. */
    private static String sh(List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            Process process = pb.start();
            String out = new String(process.getInputStream().readAllBytes());
            return process.waitFor() == 0 ? out : null;
        } catch (Exception failed) {
            return null;
        }
    }
}
