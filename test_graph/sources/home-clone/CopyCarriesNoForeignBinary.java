///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeCloneSupport.java
//SOURCES ../../../src/main/java/dev/skillmanager/pm/PmPlatform.java
//SOURCES ../../../src/main/java/dev/skillmanager/util/Platform.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import dev.skillmanager.pm.PmPlatform;
import dev.skillmanager.util.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * DEF-285 / OUN-12: {@code pm/} is the one directory a home copy carries whose
 * BYTES belong to the machine that made it.
 *
 * <h2>The shape this is standing in for</h2>
 *
 * <p>{@code HomeCloner.SKIPPED_DIRS} skips {@code tools/}, {@code venvs/} and
 * {@code npm/} and re-provisions them. {@code pm/} is deliberately not in that
 * set, so a clone carries it — measured on this host, 203 MB of Mach-O arm64
 * across {@code node} and {@code uv}. On Linux those bytes are executable by
 * permission bits and unrunnable in fact, and the failure surfaces as
 * {@code Exec format error} from whatever needed npm, long after the copy that
 * caused it. A macOS home could not be baked into a Linux image at all.
 *
 * <h2>Two answers, because they cover different copies</h2>
 *
 * <ul>
 *   <li><b>The stamp</b> holds for a copy made by anything —
 *       {@code cp -R}, a {@code COPY} in a Dockerfile, a tarball. The home
 *       reads {@code pm/<tool>/<version>/.platform} and treats a foreign
 *       toolchain as NOT INSTALLED, so it is re-provisioned rather than
 *       executed. This is the one that matters for an image build, because an
 *       image build does not call {@code home clone}.</li>
 *   <li><b>{@code home clone --portable}</b> is the cheap path for a copy
 *       skill-manager itself makes: the bytes never travel at all.</li>
 * </ul>
 *
 * <h2>Controls</h2>
 *
 * <ul>
 *   <li><b>an ordinary clone still carries pm/</b> — otherwise "no pm in the
 *       copy" is also what a clone that started skipping it unconditionally
 *       would produce, and the worktree tier would silently start
 *       re-downloading 203 MB per ticket.</li>
 *   <li><b>a NATIVE toolchain still resolves</b> — otherwise "the foreign one
 *       is refused" is also what a resolver that had stopped finding anything
 *       would produce. This is the control that makes the refusal specific to
 *       the platform rather than to the mechanism.</li>
 * </ul>
 */
public class CopyCarriesNoForeignBinary {

    static final NodeSpec SPEC = NodeSpec.of("home.copy.carries.no.foreign.binary")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.clone.no.agent.home.leak")
            .tags("home-clone", "platform", "def-285")
            .timeout("300s");

    /** Mach-O 64-bit, little-endian: the first four bytes of the real node here. */
    private static final int[] MACH_O_64 = {0xcf, 0xfa, 0xed, 0xfe};
    /** ELF: the first four bytes of a Linux node. */
    private static final int[] ELF = {0x7f, 'E', 'L', 'F'};

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            try {
                return check(ctx);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }
        });
    }

    private static NodeResult check(NodeContext ctx) throws IOException {
        String fixture = ctx.get("home.clone.fixture.built", "fixtureHome").orElse(null);
        if (fixture == null) {
            return NodeResult.fail(SPEC.id(), "UNPROVEN: no fixture home in context");
        }
        Path source = Path.of(fixture);
        Path pmBinary = source.resolve("pm/node/22.9.0/bin/node");

        boolean portableLeftItBehind;
        boolean ordinaryCarriedIt;
        boolean unitsStillTravelled;
        ProcessRecord portable;
        ProcessRecord ordinary;
        try {
            // A toolchain shaped like the real one, stamped for THIS machine —
            // the state of a home that provisioned node normally.
            Files.createDirectories(pmBinary.getParent());
            writeMagic(pmBinary, nativeMagic());
            makeExecutable(pmBinary);
            PmPlatform.stamp(pmBinary.getParent().getParent());

            Path portableDest = Files.createTempDirectory("home-portable-").resolve("copy");
            portable = HomeCloneSupport.sm(ctx, "portable-clone", source.toString(),
                    "home", "clone", "--from", source.toString(),
                    "--to", portableDest.toString(), "--portable");
            portableLeftItBehind = !Files.exists(portableDest.resolve("pm"));
            unitsStillTravelled = Files.isDirectory(portableDest.resolve("skills"));

            Path ordinaryDest = Files.createTempDirectory("home-ordinary-").resolve("copy");
            ordinary = HomeCloneSupport.sm(ctx, "ordinary-clone", source.toString(),
                    "home", "clone", "--from", source.toString(),
                    "--to", ordinaryDest.toString());
            ordinaryCarriedIt = Files.isRegularFile(
                    ordinaryDest.resolve("pm/node/22.9.0/bin/node"));
        } finally {
            // The fixture is shared with later nodes; take the planted
            // toolchain back out whatever happened.
            deleteRecursive(source.resolve("pm"));
        }

        // ---- the stamp, in a home of this node's own -----------------
        // Made separately from the fixture because this half is about a home
        // that ARRIVED from another machine, which is not a state the shared
        // fixture should be left in.
        Path arrived = Files.createTempDirectory("home-arrived-").resolve("home");
        Path arrivedBinary = arrived.resolve("pm/node/22.9.0/bin/node");
        Files.createDirectories(arrivedBinary.getParent());
        writeMagic(arrivedBinary, foreignMagic());
        makeExecutable(arrivedBinary);
        Files.createSymbolicLink(arrived.resolve("pm/node/current"), Path.of("22.9.0"));
        Files.writeString(arrivedBinary.getParent().getParent().resolve(PmPlatform.STAMP),
                foreignKey() + "\n");

        ProcessRecord foreign = HomeCloneSupport.sm(ctx, "which-foreign", arrived.toString(),
                "pm", "which", "node", "--bundled-only");
        boolean theForeignToolchainIsNotOffered = foreign.exitCode() != 0;

        // CONTROL: same home, same layout, restamped for this machine.
        Files.writeString(arrivedBinary.getParent().getParent().resolve(PmPlatform.STAMP),
                Platform.currentKey() + "\n");
        writeMagic(arrivedBinary, nativeMagic());
        makeExecutable(arrivedBinary);
        ProcessRecord native0 = HomeCloneSupport.sm(ctx, "which-native", arrived.toString(),
                "pm", "which", "node", "--bundled-only");
        boolean aNativeToolchainStillResolves = native0.exitCode() == 0;

        boolean pass = portable.exitCode() == 0 && ordinary.exitCode() == 0
                && portableLeftItBehind && ordinaryCarriedIt && unitsStillTravelled
                && theForeignToolchainIsNotOffered && aNativeToolchainStillResolves;

        return (pass ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(),
                        "portableExit=" + portable.exitCode()
                                + " ordinaryExit=" + ordinary.exitCode()
                                + " portableLeftItBehind=" + portableLeftItBehind
                                + " ordinaryCarriedIt=" + ordinaryCarriedIt
                                + " unitsStillTravelled=" + unitsStillTravelled
                                + " foreignRefused=" + theForeignToolchainIsNotOffered
                                + " nativeResolves=" + aNativeToolchainStillResolves))
                .process(portable)
                .process(ordinary)
                .process(foreign)
                .process(native0)
                .assertion("a_portable_copy_leaves_the_machine_specific_bytes_behind",
                        portableLeftItBehind)
                .assertion("a_toolchain_from_another_platform_is_not_offered_as_installed",
                        theForeignToolchainIsNotOffered)
                .assertion("CONTROL_an_ordinary_clone_still_carries_pm",
                        ordinaryCarriedIt)
                .assertion("CONTROL_a_native_toolchain_in_the_same_home_still_resolves",
                        aNativeToolchainStillResolves)
                .assertion("CONTROL_the_portable_copy_is_still_a_home",
                        unitsStillTravelled)
                .log("Measured 2026-09-05 before the fix: pm/ was 203 MB of Mach-O arm64 and "
                        + "a clone carried every byte, with `pm which node` reporting it as "
                        + "installed on a machine that could not execute it.");
    }

    // ---------------------------------------------------------- fixtures

    private static int[] nativeMagic() {
        return Platform.currentOs() == Platform.Os.DARWIN ? MACH_O_64 : ELF;
    }

    private static int[] foreignMagic() {
        return Platform.currentOs() == Platform.Os.DARWIN ? ELF : MACH_O_64;
    }

    /** A platform key that is definitely not this machine's. */
    private static String foreignKey() {
        return Platform.currentOs() == Platform.Os.DARWIN ? "linux-x64" : "darwin-arm64";
    }

    private static void writeMagic(Path file, int[] magic) throws IOException {
        byte[] head = new byte[magic.length + 64];
        for (int i = 0; i < magic.length; i++) head[i] = (byte) magic[i];
        Files.write(file, head);
    }

    private static void makeExecutable(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // a filesystem without POSIX modes does not change the claim
        }
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
