///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/StorageSharing.java
//SOURCES ../../../src/main/java/dev/skillmanager/shared/util/Fs.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The copy-on-write defence: a clone of an N-MB home must be backed by the
 * source's own blocks rather than by N MB of fresh ones.
 *
 * <h2>The undeclared property this exists to protect</h2>
 *
 * <p>The three-tier home model is a real copy per repository and a real copy
 * per ticket worktree, and it is affordable only because APFS
 * {@code clonefile(2)} makes those copies share blocks. That happens because
 * {@code Files.copy(…, COPY_ATTRIBUTES)} takes the JDK's clone path — and
 * <b>nothing in this codebase said so</b>. {@code grep -rni
 * 'clonefile|copy-on-write'} across every {@code .java}, {@code .md},
 * {@code .sh}, {@code .tla} and {@code .py} returned <b>zero hits</b>, and no
 * test asserted it. Beside {@code REPLACE_EXISTING}, the flag reads as
 * redundant tidiness. Deleting it turns a three-second 7 MB clone into a
 * nine-hundred-megabyte copy <b>with nothing failing</b>.
 *
 * <h2>Why not du, and no longer free space either</h2>
 *
 * <p>{@code du} attributes shared blocks to <em>both</em> files and cannot see
 * a clone at all: measured on this host, 197.1 MB reported for 7.14 MB really
 * consumed. Apparent size is identical for a clone and a copy by construction,
 * {@code stat} reports the clone's full allocation in {@code st_blocks}, and
 * {@code st_nlink} stays 1 because a clone is not a link.
 *
 * <p>This node used to close that gap with free space on a dedicated APFS
 * sparse image. It shared that instrument — and therefore its latent
 * flakiness — with {@code shared.store.materialization.costs.far.less.than.a
 * .copy}, which failed two runs in four on this host with a swing of 21 MB
 * across identical work and a <em>negative</em> measured consumption. A sparse
 * image's available space is bounded by the free space of the disk backing it,
 * so the "dedicated" volume was reporting the operator's editor, browser and
 * agents.
 *
 * <p>So the property is now read where it lives: per file, from the
 * filesystem. Two files share storage when they name the same inode or when
 * their data begins at the same physical device offset. See
 * {@link StorageSharing}. No {@code hdiutil}, no sparse image, no idle-noise
 * guard, and nothing a busy host can perturb.
 *
 * <h2>Three controls, because a cost measurement is exactly the kind that lies</h2>
 *
 * <ul>
 *   <li><b>Liveness (positive) control.</b> A hard link to the payload. Every
 *       POSIX filesystem can make one and it shares every block by definition,
 *       so a probe that cannot see it is broken and this node FAILS rather
 *       than skipping.</li>
 *   <li><b>Discrimination (negative) control.</b> The payload copied byte for
 *       byte through a read/write loop, which must come back unshared. Without
 *       it a probe answering "shared" for everything would pass perfectly.</li>
 *   <li><b>Platform-capability gate.</b> The node makes its own
 *       {@code Files.copy(…, COPY_ATTRIBUTES)} of the payload and asks whether
 *       THAT shared. This is a question about the platform, not about the code
 *       under test, and it is what keeps the node honest on Linux — where the
 *       JDK does not request a reflink and the clone economics genuinely do
 *       not exist. When the gate is shut the node SKIPS with the reason
 *       printed in the envelope; it never reports a pass it did not measure.</li>
 * </ul>
 *
 * <h2>What the required mutation proves</h2>
 *
 * <p>Delete {@code StandardCopyOption.COPY_ATTRIBUTES} from {@code Fs.java}'s
 * {@code visitFile} copy — one site — and
 * {@code the_clone_is_backed_by_the_sources_own_blocks} FAILS while
 * {@code the_cloned_tree_is_byte_identical_to_its_source} stays GREEN. That
 * asymmetry is the point: the digest assertion would have ridden along happily
 * through the regression, and the cost assertion is the one carrying weight.
 * The capability gate is unaffected by the mutation because the gate's copy is
 * the node's own, made with the flag, not {@code Fs}'s.
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

    /** Of the bytes that can own blocks, this fraction of the clone must be the source's. */
    private static final double SHARE_FLOOR = 0.90;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String python = StorageSharing.python();
            if (python == null) {
                return skip("no python3 on PATH to run the extent probe with — block sharing is "
                        + "not readable from the JDK, and du cannot see it at all");
            }
            if (!Files.isReadable(StorageSharing.script())) {
                // Deliberately a failure. A missing instrument and a held
                // property must not produce the same envelope.
                return NodeResult.fail(SPEC.id(),
                                "the extent probe is missing from " + StorageSharing.script())
                        .assertion("the_extent_probe_is_present", false);
            }

            Path work = Path.of(System.getProperty("user.dir"))
                    .resolve("build/cow-" + ctx.runId());
            StorageSharing.rmrf(work);
            Files.createDirectories(work);
            List<String> notes = new ArrayList<>();
            try {
                // A home-shaped tree: installed/ + skills/, with the payload in
                // a unit. copyRecursive is what `home sync` and `project
                // resolve` move a unit tree with.
                Path src = work.resolve("src/.skill-manager");
                Files.createDirectories(src.resolve("installed"));
                Path unit = src.resolve("skills/bulk");
                Files.createDirectories(unit);
                Files.writeString(src.resolve("installed/index.json"), "{}");
                Files.writeString(unit.resolve("SKILL.md"), "# bulk\n");
                StorageSharing.writeIncompressible(unit.resolve("blob.bin"), PAYLOAD_BYTES);
                long apparent = StorageSharing.apparentSize(src);
                Path payload = unit.resolve("blob.bin");

                // ---- controls, and the platform-capability gate ----------
                Path canReflink = work.resolve("control-reflink");
                Path hardlink = work.resolve("control-hardlink");
                Path bytecopy = work.resolve("control-bytecopy");
                StorageSharing.attributedCopy(payload, canReflink.resolve("payload.bin"));
                StorageSharing.hardLink(payload, hardlink.resolve("payload.bin"));
                StorageSharing.streamCopy(payload, bytecopy.resolve("payload.bin"));

                StorageSharing.Measurement controls = StorageSharing.measure(python, src,
                        List.of(canReflink, hardlink, bytecopy));
                boolean theJdkCopyCanReflinkHere = !controls.target(0).nothingShared();
                boolean theProbeSeesAHardLinkAsShared = !controls.target(1).nothingShared();
                boolean theProbeSeesAByteCopyAsUnshared = controls.target(2).nothingShared();
                notes.add("control reflink:  " + controls.target(0).describe());
                notes.add("control hardlink: " + controls.target(1).describe());
                notes.add("control bytecopy: " + controls.target(2).describe());

                // ---- under test: the production tree copy ---------------
                Path dst = work.resolve("dst/.skill-manager");
                Files.createDirectories(dst);
                dev.skillmanager.shared.util.Fs.copyRecursive(src, dst);

                StorageSharing.Measurement measured =
                        StorageSharing.measure(python, src, List.of(dst));
                StorageSharing.Sharing clone = measured.target(0);
                notes.add("clone: " + clone.describe());

                boolean theTreeIsByteIdentical = sameTree(src, dst);
                boolean theApparentSizeIsUnchanged = StorageSharing.apparentSize(dst) == apparent;
                boolean theProbeAnsweredForEveryFile = measured.probeAnsweredEverything()
                        && controls.probeAnsweredEverything();

                // The gate is asked AFTER the controls have proved the probe
                // works, so "this platform cannot reflink" can never be a
                // broken probe wearing a skip as a disguise.
                if (theProbeSeesAHardLinkAsShared && theProbeSeesAByteCopyAsUnshared
                        && !theJdkCopyCanReflinkHere) {
                    return skip("on " + measured.platform() + " the JDK's copy does not request a"
                            + " reflink and this filesystem produced none for it, so the"
                            + " clone economics the home model rests on do not exist here."
                            + " The probe is working: it saw the hard-link control as shared"
                            + " and the byte-copy control as unshared in this same run."
                            + " byteIdentical=" + theTreeIsByteIdentical);
                }

                boolean theCloneIsBackedByTheSourcesOwnBlocks =
                        clone.measurableBytes() >= PAYLOAD_BYTES / 2
                                && clone.sharedFraction() >= SHARE_FLOOR;

                boolean pass = theProbeSeesAHardLinkAsShared
                        && theProbeSeesAByteCopyAsUnshared
                        && theProbeAnsweredForEveryFile
                        && theCloneIsBackedByTheSourcesOwnBlocks
                        && theTreeIsByteIdentical
                        && theApparentSizeIsUnchanged;

                String detail = "platform=" + measured.platform()
                        + " apparent=" + StorageSharing.mb(apparent) + "MB cloneShared="
                        + StorageSharing.pct(clone.sharedFraction()) + " ("
                        + StorageSharing.mb(clone.sharedBytes()) + "/"
                        + StorageSharing.mb(clone.measurableBytes()) + "MB) floor="
                        + StorageSharing.pct(SHARE_FLOOR) + " viaInode=" + clone.viaInode()
                        + " viaExtent=" + clone.viaExtent();
                notes.add(detail);

                return (pass ? NodeResult.pass(SPEC.id()) : NodeResult.fail(SPEC.id(), detail))
                        .assertion("the_probe_sees_a_hard_link_as_shared",
                                theProbeSeesAHardLinkAsShared)
                        .assertion("the_probe_sees_a_plain_byte_copy_as_not_shared",
                                theProbeSeesAByteCopyAsUnshared)
                        .assertion("the_probe_answered_for_every_file_it_was_asked_about",
                                theProbeAnsweredForEveryFile)
                        .assertion("the_clone_is_backed_by_the_sources_own_blocks",
                                theCloneIsBackedByTheSourcesOwnBlocks)
                        .assertion("the_cloned_tree_is_byte_identical_to_its_source",
                                theTreeIsByteIdentical)
                        .assertion("the_clone_has_the_same_apparent_size_as_the_source",
                                theApparentSizeIsUnchanged)
                        .metric("apparentBytes", apparent)
                        .metric("cloneSharedBytes", clone.sharedBytes())
                        .metric("cloneMeasurableBytes", clone.measurableBytes())
                        .metric("cloneSharedViaInode", clone.viaInode())
                        .metric("cloneSharedViaExtent", clone.viaExtent())
                        .metric("probeErrors", clone.probeErrors())
                        .log(detail);
            } finally {
                for (String note : notes) System.out.println(note);
                try {
                    StorageSharing.rmrf(work);
                } catch (IOException ignored) {
                    // the run directory is under build/ and is disposable
                }
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
                .assertion("the_clone_sharing_was_measured_OR_the_skip_states_its_reason", true)
                .metric("skipped", 1)
                .log("SKIPPED: " + reason);
    }

    // -------------------------------------------------------------- probes

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
}
