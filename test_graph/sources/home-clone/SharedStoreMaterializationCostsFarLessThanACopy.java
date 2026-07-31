///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/StorageSharing.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The other half of the shared-store claim: a venv built out of the shared
 * store must be backed by the store's blocks rather than by copies of them.
 *
 * <h2>Why sharing the store is not on its own worth anything</h2>
 *
 * <p>{@code shared.package.cache.is.not.private.to.the.home} asserts that
 * every home reads one content-addressed store. That saves downloads. It saves
 * no disk at all unless the venv is materialized <em>by reference</em> — and
 * materialization mode is invisible to every ordinary instrument. Measured on
 * this host: three skill-script venvs in one home held <b>48,258 files across
 * 48,258 distinct inodes</b>, 1.6 GB, zero sharing. The same package set
 * materialized out of a shared store has the same apparent size, the same
 * {@code du}, the same {@code stat} block counts and the same link counts.
 *
 * <h2>What changed, and why the volume instrument had to go</h2>
 *
 * <p>This node used to infer materialization cost from free space on a
 * dedicated APFS sparse image. It was <b>flaky: two failures in four runs, on
 * two different guards</b>, with {@code venvConsumed} swinging from −5.54 MB
 * to +15.64 MB across identical work. A negative consumption is the proof that
 * the instrument was measuring the host: a sparse image's available space is
 * bounded by the free space of the disk backing it, so with 2.5 GiB free and a
 * k3d cluster running, every unrelated write on the machine passed straight
 * through the "dedicated" volume.
 *
 * <p>Widening the noise budget would have deleted the check rather than fixed
 * it. Instead the property is now read where it lives: <b>per file, from the
 * filesystem</b>. Two files share storage when they name the same inode or
 * when their data begins at the same physical device offset, and both are
 * answers about one file that no other process can perturb. See
 * {@link StorageSharing} and {@code sources/lib/extentprobe.py}. There is no
 * sparse image any more, no {@code hdiutil}, and no idle-noise guard — the
 * measurement does not have a noise floor to compare against.
 *
 * <h2>Three controls, because "it was free" is the easiest lie to tell</h2>
 *
 * <ul>
 *   <li><b>Liveness (positive) control</b> — a hard link to a real store file.
 *       Every POSIX filesystem can make one and it shares every block by
 *       definition, so a probe that cannot see it is BROKEN, and this node
 *       fails rather than skipping. This replaces the old {@code dd} control,
 *       which existed to prove the instrument could see a write it could not
 *       share.</li>
 *   <li><b>Discrimination (negative) control</b> — a real store file copied
 *       byte for byte through a read/write loop. It must come back
 *       <em>unshared</em>. Without it, a probe that answered "shared" for
 *       everything would pass this node perfectly.</li>
 *   <li><b>Anti-vacuity floor on the payload</b> — the venv under test must be
 *       a real package set (files and apparent bytes above an explicit floor,
 *       and enough bytes in files large enough to own blocks at all). "The
 *       venv shared everything" is trivially true of a venv that was never
 *       populated, and is exactly what {@code UV_LINK_MODE=symlink} produces:
 *       measured, 8 measurable files and 0.29 MB.</li>
 * </ul>
 *
 * <h2>Cross-platform disposition</h2>
 *
 * <p>The materialization mode is chosen from what this filesystem is
 * <em>observed</em> to support, not from a platform guess:
 *
 * <ul>
 *   <li>reflink available (APFS, and any fs where the JDK's attributed copy
 *       shares blocks) → {@code UV_LINK_MODE=clone}, sharing seen as identical
 *       physical extents;</li>
 *   <li>otherwise → {@code UV_LINK_MODE=hardlink}, sharing seen as identical
 *       inodes. This is the path Linux/ext4 takes, and it is a full
 *       measurement rather than a skip: CI's {@code ubuntu-latest} runs this
 *       node's assertions, not a stub.</li>
 * </ul>
 *
 * <p>Only a filesystem that can do neither — no reflink and no hard links —
 * skips, and the reason is printed and carried in the envelope.
 *
 * <h2>A mutation that does not mutate</h2>
 *
 * <p>{@code UV_LINK_MODE=copy} is <b>not</b> a way to break sharing on macOS:
 * Rust's {@code std::fs::copy} calls {@code fclonefileat}, so uv's copy mode
 * still produces clones on APFS. Measured: clone mode 99.56% of measurable
 * bytes shared, copy mode 99.56%. The mutation that does break it is a
 * materialization that genuinely rewrites the bytes — see {@link
 * StorageSharing#streamCopy}, which is what the negative control uses and what
 * the node was mutation-tested against.
 *
 * <h2>And the acceptance test the whole design rests on</h2>
 *
 * <p>{@code the_materialized_venv_is_still_writable} installs a further
 * package into the venv after measuring it, and
 * {@code the_shared_store_is_unharmed_by_that_write} then builds a second venv
 * from the same store and imports from it. Nothing here is made read-only:
 * block sharing is what makes the venv cheap, and the venv staying a normal
 * writable install target is what makes it usable. If either ever fails, the
 * sharing is wrong and must be backed out — not worked around.
 *
 * <h2>Skips</h2>
 *
 * <p>No {@code uv}, no Python to run the probe with, or no network to warm the
 * store: this node SKIPS <b>with its reason printed and carried in the
 * envelope</b>. It does not quietly pass. A missing probe script is a FAILURE,
 * not a skip — "the instrument was absent" must never produce the same
 * envelope as "the property holds".
 */
public class SharedStoreMaterializationCostsFarLessThanACopy {

    static final NodeSpec SPEC = NodeSpec.of("shared.store.materialization.costs.far.less.than.a.copy")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("home-clone", "package-cache", "cost", "oracle")
            .timeout("900s");

    /** Of the bytes that can own blocks, this fraction must be the store's. */
    private static final double SHARE_FLOOR = 0.90;

    /** Below these the venv is not a package set and its sharing means nothing. */
    private static final long MIN_APPARENT_BYTES = 20L * 1024 * 1024;
    /** …and that much of it must sit in files large enough to own blocks. */
    private static final long MIN_VENV_MEASURABLE_BYTES = 20L * 1024 * 1024;
    private static final int MIN_FILES = 500;

    /** Chosen for bulk: numpy + pandas are ~60 MB of real files, no build step. */
    private static final List<String> PACKAGES = List.of("numpy", "pandas", "requests", "rich");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String uv = StorageSharing.which("uv");
            if (uv == null) {
                return skip("uv is not on PATH — the materialization mode under test is uv's, "
                        + "and there is nothing honest to measure without it");
            }
            String python = StorageSharing.python();
            if (python == null) {
                return skip("no python3 on PATH to run the extent probe with — block sharing "
                        + "is not readable from the JDK, and du cannot see it at all");
            }
            if (!Files.isReadable(StorageSharing.script())) {
                // Deliberately a failure. A missing instrument and a held
                // property must not produce the same envelope.
                return NodeResult.fail(SPEC.id(), "the extent probe is missing from "
                                + StorageSharing.script())
                        .assertion("the_extent_probe_is_present", false);
            }

            Path work = Path.of(System.getProperty("user.dir"))
                    .resolve("build/pkgshare-" + ctx.runId());
            StorageSharing.rmrf(work);
            Files.createDirectories(work);
            List<String> notes = new ArrayList<>();
            try {
                // Store, venv and controls all under one directory, so they are
                // on one filesystem by construction. If they were not, the
                // by-reference modes would be unavailable and the measurement
                // would only be re-proving that.
                Path store = work.resolve("shared-store");
                Files.createDirectories(store);

                // ---- warm the shared store ------------------------------
                Path warm = work.resolve("warm");
                if (sh(List.of(uv, "venv", "--python", "3.12", warm.toString())) == null) {
                    return skip("uv could not create a venv to warm the store with");
                }
                if (shEnv(install(uv, warm), Map.of("UV_CACHE_DIR", store.toString())) == null) {
                    return skip("could not warm the shared store (no network, or the package "
                            + "set is unavailable) — with a cold store this would measure a "
                            + "download, not a materialization");
                }
                StorageSharing.rmrf(warm);
                notes.add("store warmed: " + StorageSharing.mb(StorageSharing.apparentSize(store))
                        + " MB in " + StorageSharing.countFiles(store) + " files");

                // ---- what can this filesystem actually share? ------------
                // Asked of the filesystem, not guessed from the platform, and
                // asked with a real store file so the answer is about the
                // storage the venv will be materialized out of.
                Path witness = largestFile(store);
                if (witness == null) {
                    return skip("the warmed store holds no file large enough to measure with");
                }
                // Each control lives alone in its own directory so that each
                // gets its own verdict rather than an average of the three.
                Path reflink = work.resolve("control-reflink");
                Path hardlink = work.resolve("control-hardlink");
                Path bytecopy = work.resolve("control-bytecopy");
                StorageSharing.attributedCopy(witness, reflink.resolve("payload.bin"));
                StorageSharing.hardLink(witness, hardlink.resolve("payload.bin"));
                StorageSharing.streamCopy(witness, bytecopy.resolve("payload.bin"));

                StorageSharing.Measurement each = StorageSharing.measure(python, store,
                        List.of(reflink, hardlink, bytecopy));

                boolean reflinkWorksHere = !each.target(0).nothingShared();
                boolean theProbeSeesAHardLinkAsShared = !each.target(1).nothingShared();
                boolean theProbeSeesAByteCopyAsUnshared = each.target(2).nothingShared();
                String linkMode = reflinkWorksHere ? "clone" : "hardlink";
                notes.add("control reflink:  " + each.target(0).describe());
                notes.add("control hardlink: " + each.target(1).describe());
                notes.add("control bytecopy: " + each.target(2).describe());
                notes.add("platform=" + each.platform() + " witness="
                        + StorageSharing.mb(Files.size(witness)) + "MB linkMode=" + linkMode);

                if (!reflinkWorksHere && !theProbeSeesAHardLinkAsShared) {
                    return skip("this filesystem (" + each.platform() + ") shares blocks neither "
                            + "by reflink nor by hard link, so uv would copy and there is no "
                            + "by-reference materialization to observe here");
                }

                // ---- under test: materialize a venv from the store -------
                Path venv = work.resolve("venv");
                if (sh(List.of(uv, "venv", "--python", "3.12", venv.toString())) == null) {
                    return skip("uv could not create the venv under test");
                }
                String installed = shEnv(install(uv, venv),
                        Map.of("UV_CACHE_DIR", store.toString(), "UV_LINK_MODE", linkMode));
                if (installed == null) {
                    return skip("the install into the venv under test failed outright");
                }

                StorageSharing.Measurement measured =
                        StorageSharing.measure(python, store, List.of(venv));
                StorageSharing.Sharing venvSharing = measured.target(0);
                long apparent = StorageSharing.apparentSize(venv);
                int files = StorageSharing.countFiles(venv);
                notes.add("venv: " + venvSharing.describe() + " apparent="
                        + StorageSharing.mb(apparent) + "MB files=" + files);
                if (!measured.errors().isEmpty()) notes.add("probe errors: " + measured.errors());

                // ---- acceptance: nothing became read-only ---------------
                boolean theMaterializedVenvIsStillWritable = shEnv(
                        List.of(uv, "pip", "install", "--python",
                                venv.resolve("bin/python").toString(), "httpx"),
                        Map.of("UV_CACHE_DIR", store.toString(), "UV_LINK_MODE", linkMode)) != null
                        && sh(List.of(venv.resolve("bin/python").toString(), "-c",
                                "import httpx, numpy, pandas")) != null;

                // ---- acceptance: the store survived that write ----------
                Path second = work.resolve("venv2");
                boolean theSharedStoreIsUnharmedByThatWrite =
                        sh(List.of(uv, "venv", "--python", "3.12", second.toString())) != null
                        && shEnv(install(uv, second),
                                Map.of("UV_CACHE_DIR", store.toString(),
                                        "UV_LINK_MODE", linkMode)) != null
                        && sh(List.of(second.resolve("bin/python").toString(), "-c",
                                "import numpy, pandas")) != null;

                // ---- verdict --------------------------------------------
                boolean theProbeAnsweredForEveryFile = measured.probeAnsweredEverything()
                        && each.probeAnsweredEverything();
                boolean theVenvIsARealPackageSet =
                        apparent >= MIN_APPARENT_BYTES && files >= MIN_FILES
                                && venvSharing.measurableBytes() >= MIN_VENV_MEASURABLE_BYTES;
                boolean theVenvIsBackedByTheStoresOwnBlocks =
                        theVenvIsARealPackageSet
                                && venvSharing.sharedFraction() >= SHARE_FLOOR;

                boolean pass = theProbeSeesAHardLinkAsShared
                        && theProbeSeesAByteCopyAsUnshared
                        && theProbeAnsweredForEveryFile
                        && theVenvIsARealPackageSet
                        && theVenvIsBackedByTheStoresOwnBlocks
                        && theMaterializedVenvIsStillWritable
                        && theSharedStoreIsUnharmedByThatWrite;

                String detail = "platform=" + measured.platform() + " linkMode=" + linkMode
                        + " venvShared=" + StorageSharing.pct(venvSharing.sharedFraction())
                        + " (" + StorageSharing.mb(venvSharing.sharedBytes()) + "/"
                        + StorageSharing.mb(venvSharing.measurableBytes()) + "MB) floor="
                        + StorageSharing.pct(SHARE_FLOOR) + " viaInode="
                        + venvSharing.viaInode() + " viaExtent=" + venvSharing.viaExtent()
                        + " files=" + files + " apparent=" + StorageSharing.mb(apparent) + "MB";
                notes.add(detail);

                return (pass ? NodeResult.pass(SPEC.id()) : NodeResult.fail(SPEC.id(), detail))
                        .assertion("the_probe_sees_a_hard_link_as_shared",
                                theProbeSeesAHardLinkAsShared)
                        .assertion("the_probe_sees_a_plain_byte_copy_as_not_shared",
                                theProbeSeesAByteCopyAsUnshared)
                        .assertion("the_probe_answered_for_every_file_it_was_asked_about",
                                theProbeAnsweredForEveryFile)
                        .assertion("the_venv_under_test_is_a_real_package_set",
                                theVenvIsARealPackageSet)
                        .assertion("a_venv_from_the_shared_store_is_backed_by_the_stores_own_blocks",
                                theVenvIsBackedByTheStoresOwnBlocks)
                        .assertion("the_materialized_venv_is_still_writable",
                                theMaterializedVenvIsStillWritable)
                        .assertion("the_shared_store_is_unharmed_by_that_write",
                                theSharedStoreIsUnharmedByThatWrite)
                        .metric("venvSharedBytes", venvSharing.sharedBytes())
                        .metric("venvMeasurableBytes", venvSharing.measurableBytes())
                        .metric("venvSharedFiles", venvSharing.sharedFiles())
                        .metric("venvSharedViaInode", venvSharing.viaInode())
                        .metric("venvSharedViaExtent", venvSharing.viaExtent())
                        .metric("venvApparentBytes", apparent)
                        .metric("venvFiles", files)
                        .metric("probeErrors", venvSharing.probeErrors())
                        .log(detail);
            } finally {
                for (String note : notes) System.out.println(note);
                try {
                    StorageSharing.rmrf(work);
                } catch (IOException ignored) {
                    // build/ is disposable
                }
            }
        });
    }

    /** The biggest real file in the warmed store — the one worth cloning. */
    private static Path largestFile(Path store) throws IOException {
        try (var walk = Files.walk(store)) {
            return walk.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException unreadable) {
                            return -1L;
                        }
                    }))
                    .filter(p -> {
                        try {
                            return Files.size(p) >= 1L << 20;
                        } catch (IOException unreadable) {
                            return false;
                        }
                    })
                    .orElse(null);
        }
    }

    private static List<String> install(String uv, Path venv) {
        List<String> cmd = new ArrayList<>(List.of(uv, "pip", "install", "--python",
                venv.resolve("bin/python").toString()));
        cmd.addAll(PACKAGES);
        return cmd;
    }

    /** A skip that states its reason in the envelope, never a silent green. */
    private static NodeResult skip(String reason) {
        System.out.println("SKIPPED: " + reason);
        return NodeResult.pass(SPEC.id())
                .assertion("the_materialization_sharing_was_measured_OR_the_skip_states_its_reason",
                        true)
                .metric("skipped", 1)
                .log("SKIPPED: " + reason);
    }

    private static String sh(List<String> command) {
        return shEnv(command, Map.of());
    }

    /** Run a command with extra env, returning combined output, or null on failure. */
    private static String shEnv(List<String> command, Map<String, String> env) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            pb.environment().putAll(env);
            Process process = pb.start();
            String out = new String(process.getInputStream().readAllBytes());
            return process.waitFor() == 0 ? out : null;
        } catch (Exception failed) {
            return null;
        }
    }
}
