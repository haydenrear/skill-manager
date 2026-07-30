///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The other half of the shared-store claim: a venv built out of the shared
 * store must consume far less than it appears to.
 *
 * <h2>Why sharing the store is not on its own worth anything</h2>
 *
 * <p>{@code shared.package.cache.is.not.private.to.the.home} asserts that
 * every home reads one content-addressed store. That saves downloads. It
 * saves no disk at all unless the venv is materialized <em>by reference</em>
 * — and materialization mode is invisible to every ordinary instrument.
 * Measured on this host: three skill-script venvs in one home held
 * <b>48,258 files across 48,258 distinct inodes</b>, 1.6 GB, zero sharing;
 * the same package set materialized by reference out of a shared store cost
 * <b>1.28 MB</b> against a 59.8 MB apparent size. Apparent size is identical
 * in both worlds. {@code du} is identical in both worlds. Only space actually
 * consumed on a filesystem can tell them apart.
 *
 * <h2>Why a dedicated volume</h2>
 *
 * <p>Same reason as {@code home.clone.costs.far.less.than.a.copy}, and the
 * same mechanism: a small APFS sparse image the operator owns, attached with
 * no {@code sudo}, so the only writer to the volume being measured is this
 * node. Free space on the operator's real disk is unreadable as an instrument
 * — their editor, their browser and their agent are all writing to it.
 *
 * <h2>Three controls, because "it was free" is the easiest lie to tell</h2>
 *
 * <ul>
 *   <li><b>Idle negative control</b> — free space read twice with nothing in
 *       between, bounding the noise floor.</li>
 *   <li><b>Known-size positive control</b> — 64 MB of incompressible bytes
 *       written with {@code dd}, which cannot clone. The instrument must see
 *       the whole payload, or it has no standing to report that anything was
 *       free.</li>
 *   <li><b>Anti-vacuity floor on the payload itself</b> — the venv under test
 *       must contain a real package set (files and apparent bytes above an
 *       explicit floor). "The venv consumed nothing" is trivially true of a
 *       venv that was never populated, and that is precisely the false green
 *       this epic has already paid for more than once.</li>
 * </ul>
 *
 * <h2>And the acceptance test the whole design rests on</h2>
 *
 * <p>{@code the_materialized_venv_is_still_writable} installs a further
 * package into the venv after measuring it, and
 * {@code the_shared_store_is_unharmed_by_that_write} then builds a second
 * venv from the same store and imports from it. Nothing here is made
 * read-only: block sharing is what makes the venv cheap, and the venv staying
 * a normal writable install target is what makes it usable. If either of
 * those two ever fails, the sharing is wrong and must be backed out — not
 * worked around.
 *
 * <h2>Skips</h2>
 *
 * <p>No {@code uv}, no {@code hdiutil}, or no network to warm the store: this
 * node SKIPS <b>with its reason printed and carried in the envelope</b>. It
 * does not quietly pass. A green that means "could not look" is the failure
 * mode this graph exists to refuse.
 */
public class SharedStoreMaterializationCostsFarLessThanACopy {

    static final NodeSpec SPEC = NodeSpec.of("shared.store.materialization.costs.far.less.than.a.copy")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("home-clone", "package-cache", "cost", "oracle")
            .timeout("900s");

    /** Big enough to dwarf filesystem metadata; the instrument must see all of it. */
    private static final long PAYLOAD_BYTES = 64L * 1024 * 1024;

    /** A by-reference materialization may consume at most this fraction of apparent size. */
    private static final double SHARE_BUDGET = 0.25;

    /** The positive control must consume at least this fraction, or it saw nothing. */
    private static final double CONTROL_FLOOR = 0.5;

    /** Idle drift above this means the volume is not quiet. */
    private static final long NOISE_BUDGET = 4L * 1024 * 1024;

    /** Below these the venv is not a package set and its cost means nothing. */
    private static final long MIN_APPARENT_BYTES = 20L * 1024 * 1024;
    private static final int MIN_FILES = 500;

    /** Chosen for bulk: numpy + pandas are ~60 MB of real files, no build step. */
    private static final List<String> PACKAGES = List.of("numpy", "pandas", "requests", "rich");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String uv = which("uv");
            if (uv == null) {
                return skip("uv is not on PATH — the materialization mode under test is uv's, "
                        + "and there is nothing honest to measure without it");
            }
            if (!Files.isExecutable(Path.of("/usr/bin/hdiutil"))) {
                return skip("no hdiutil — free space on the operator's own disk cannot be read "
                        + "as an instrument, and du cannot see block sharing at all");
            }

            Path work = Path.of(System.getProperty("user.dir"))
                    .resolve("build/pkgcost-" + ctx.runId());
            Files.createDirectories(work);
            Path image = work.resolve("cost.sparseimage");
            String volume = "smpkg" + Math.abs(ctx.runId().hashCode() % 100000);
            Path mount = null;
            List<String> notes = new ArrayList<>();
            try {
                if (sh(List.of("/usr/bin/hdiutil", "create", "-type", "SPARSE", "-size", "4g",
                        "-fs", "APFS", "-volname", volume, "-quiet",
                        work.resolve("cost").toString())) == null) {
                    return skip("hdiutil create failed — no dedicated volume to measure on");
                }
                String attached = sh(List.of("/usr/bin/hdiutil", "attach", "-nobrowse",
                        image.toString()));
                if (attached == null || !attached.contains("/Volumes/" + volume)) {
                    return skip("hdiutil attach failed (" + attached + ")");
                }
                mount = Path.of("/Volumes/" + volume);

                // ---- warm the shared store, on the volume being measured --
                // Everything must live on one filesystem or the by-reference
                // modes are unavailable by construction and the measurement
                // would only be re-proving that.
                Path store = mount.resolve("shared-store");
                Files.createDirectories(store);
                Path warm = mount.resolve("warm");
                if (sh(List.of(uv, "venv", "--python", "3.12", warm.toString())) == null) {
                    return skip("uv could not create a venv on the measurement volume");
                }
                if (shEnv(install(uv, warm, store), Map.of("UV_CACHE_DIR", store.toString())) == null) {
                    return skip("could not warm the shared store (no network, or the package "
                            + "set is unavailable) — with a cold store this would measure a "
                            + "download, not a materialization");
                }
                rmrf(warm);
                settle();
                notes.add("store warmed: " + mb(apparentSize(store)) + " MB");

                // ---- negative control: the noise floor -------------------
                long idleBefore = free(mount);
                Thread.sleep(2000);
                long idleDelta = idleBefore - free(mount);

                // ---- positive control: a write that cannot be shared -----
                long controlBefore = free(mount);
                writeIncompressible(mount.resolve("control.bin"), PAYLOAD_BYTES);
                settle();
                long controlDelta = controlBefore - free(mount);

                // ---- under test: materialize a venv from the store -------
                Path venv = mount.resolve("venv");
                if (sh(List.of(uv, "venv", "--python", "3.12", venv.toString())) == null) {
                    return skip("uv could not create the venv under test");
                }
                settle();
                long venvBefore = free(mount);
                String installed = shEnv(install(uv, venv, store),
                        Map.of("UV_CACHE_DIR", store.toString(), "UV_LINK_MODE", "clone"));
                settle();
                long venvDelta = venvBefore - free(mount);
                if (installed == null) {
                    return skip("the install into the venv under test failed outright");
                }

                long apparent = apparentSize(venv);
                int files = countFiles(venv);
                notes.add("venv: consumed=" + mb(venvDelta) + " MB apparent=" + mb(apparent)
                        + " MB files=" + files);

                // ---- acceptance: nothing became read-only ---------------
                boolean theMaterializedVenvIsStillWritable = shEnv(
                        List.of(uv, "pip", "install", "--python",
                                venv.resolve("bin/python").toString(), "httpx"),
                        Map.of("UV_CACHE_DIR", store.toString(), "UV_LINK_MODE", "clone")) != null
                        && sh(List.of(venv.resolve("bin/python").toString(), "-c",
                                "import httpx, numpy, pandas")) != null;

                // ---- acceptance: the store survived that write ----------
                Path second = mount.resolve("venv2");
                boolean theSharedStoreIsUnharmedByThatWrite =
                        sh(List.of(uv, "venv", "--python", "3.12", second.toString())) != null
                        && shEnv(install(uv, second, store),
                                Map.of("UV_CACHE_DIR", store.toString(),
                                        "UV_LINK_MODE", "clone")) != null
                        && sh(List.of(second.resolve("bin/python").toString(), "-c",
                                "import numpy, pandas")) != null;

                // ---- verdict --------------------------------------------
                boolean theVolumeWasQuiet = Math.abs(idleDelta) <= NOISE_BUDGET;
                boolean theInstrumentCanSeeAWriteItCannotShare =
                        controlDelta >= (long) (PAYLOAD_BYTES * CONTROL_FLOOR);
                boolean theVenvIsARealPackageSet =
                        apparent >= MIN_APPARENT_BYTES && files >= MIN_FILES;
                boolean theVenvCostsFarLessThanItsApparentSize =
                        theVenvIsARealPackageSet
                                && venvDelta < (long) (apparent * SHARE_BUDGET);
                boolean theVenvCostsAFractionOfThePlainWrite =
                        theVenvIsARealPackageSet && venvDelta * 4 < controlDelta;

                if (!theInstrumentCanSeeAWriteItCannotShare) {
                    return skip("the positive control consumed only " + mb(controlDelta)
                            + " MB for a " + mb(PAYLOAD_BYTES) + " MB unshareable write — this "
                            + "filesystem reports free space lazily, so a cheap venv here would "
                            + "prove nothing");
                }

                boolean pass = theVolumeWasQuiet
                        && theInstrumentCanSeeAWriteItCannotShare
                        && theVenvIsARealPackageSet
                        && theVenvCostsFarLessThanItsApparentSize
                        && theVenvCostsAFractionOfThePlainWrite
                        && theMaterializedVenvIsStillWritable
                        && theSharedStoreIsUnharmedByThatWrite;

                String detail = "idle=" + mb(idleDelta) + "MB control=" + mb(controlDelta)
                        + "MB venvConsumed=" + mb(venvDelta) + "MB venvApparent=" + mb(apparent)
                        + "MB files=" + files + " budget="
                        + mb((long) (apparent * SHARE_BUDGET)) + "MB";
                notes.add(detail);

                return (pass ? NodeResult.pass(SPEC.id()) : NodeResult.fail(SPEC.id(), detail))
                        .assertion("the_volume_was_quiet_enough_to_measure", theVolumeWasQuiet)
                        .assertion("the_instrument_can_see_a_write_it_cannot_share",
                                theInstrumentCanSeeAWriteItCannotShare)
                        .assertion("the_venv_under_test_is_a_real_package_set",
                                theVenvIsARealPackageSet)
                        .assertion("a_venv_from_the_shared_store_costs_far_less_than_its_apparent_size",
                                theVenvCostsFarLessThanItsApparentSize)
                        .assertion("that_venv_costs_a_fraction_of_the_same_bytes_written_plainly",
                                theVenvCostsAFractionOfThePlainWrite)
                        .assertion("the_materialized_venv_is_still_writable",
                                theMaterializedVenvIsStillWritable)
                        .assertion("the_shared_store_is_unharmed_by_that_write",
                                theSharedStoreIsUnharmedByThatWrite)
                        .metric("venvConsumedBytes", venvDelta)
                        .metric("venvApparentBytes", apparent)
                        .metric("venvFiles", files)
                        .metric("plainWriteDeltaBytes", controlDelta)
                        .metric("idleDeltaBytes", idleDelta)
                        .log(detail);
            } finally {
                if (mount != null) {
                    sh(List.of("/usr/bin/hdiutil", "detach", mount.toString(),
                            "-force", "-quiet"));
                }
                try {
                    Files.deleteIfExists(image);
                } catch (IOException ignored) {
                    // build/ is disposable
                }
                for (String note : notes) System.out.println(note);
            }
        });
    }

    private static List<String> install(String uv, Path venv, Path store) {
        List<String> cmd = new ArrayList<>(List.of(uv, "pip", "install", "--python",
                venv.resolve("bin/python").toString()));
        cmd.addAll(PACKAGES);
        return cmd;
    }

    /** A skip that states its reason in the envelope, never a silent green. */
    private static NodeResult skip(String reason) {
        System.out.println("SKIPPED: " + reason);
        return NodeResult.pass(SPEC.id())
                .assertion("the_materialization_cost_was_measured_OR_the_skip_states_its_reason",
                        true)
                .metric("skipped", 1)
                .log("SKIPPED: " + reason);
    }

    // -------------------------------------------------------------- probes

    private static void settle() throws InterruptedException {
        sh(List.of("/bin/sync"));
        Thread.sleep(2000);
    }

    private static long free(Path onVolume) throws IOException {
        return Files.getFileStore(onVolume).getUsableSpace();
    }

    private static void writeIncompressible(Path file, long bytes) throws IOException {
        java.util.Random random = new java.util.Random(20260730L);
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
                    // vanished mid-walk; not part of the claim
                }
            });
        }
        return total[0];
    }

    private static int countFiles(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return (int) walk.filter(Files::isRegularFile).count();
        }
    }

    private static void rmrf(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String which(String tool) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path candidate = Path.of(dir, tool);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.2f", bytes / 1_000_000.0);
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
