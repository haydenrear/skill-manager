package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.pm.PmPlatform;
import dev.skillmanager.util.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * OUN-12: a home copied onto a machine that is not the one it was made on.
 *
 * <p>Two independent halves of DEF-285, and they fail in opposite ways.
 *
 * <ul>
 *   <li><b>Cost.</b> The three-tier model was adopted on a measured 3.8%,
 *       which is an APFS side effect of {@code clonefile(2)}. Elsewhere the
 *       same copy costs full size. Nothing declared that, so the one node
 *       asserting it SKIPPED on Linux — the only platform CI runs.
 *       {@link HomeCopyEconomics} is the declaration, and a declaration is
 *       what makes the measurement falsifiable on every platform.</li>
 *   <li><b>Platform-specific bytes.</b> {@code pm/} is 203 MB of Mach-O arm64
 *       on this host and it is NOT skipped by a clone. On Linux those bytes
 *       are executable by permission bits and answer {@code ENOEXEC} in fact,
 *       so the home reports the toolchain as installed and something else
 *       fails later with {@code Exec format error}.</li>
 * </ul>
 */
public final class CopyCrossesAPlatformTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("CopyCrossesAPlatformTest");

        // ---------------------------------------------------------- economics

        suite.test("the copy cost is declared for the filesystem it lands on", () -> {
            Path here = Files.createTempDirectory("copy-econ-");
            HomeCopyEconomics.Strategy strategy = HomeCopyEconomics.strategyFor(here);
            String fs = HomeCopyEconomics.filesystemOf(here);

            assertTrue(fs != null && !fs.isBlank(),
                    "the filesystem under the destination is readable");
            if (Platform.currentOs() == Platform.Os.DARWIN && "apfs".equals(fs)) {
                assertEquals(HomeCopyEconomics.Strategy.SHARES_BLOCKS, strategy,
                        "macOS on APFS is the platform the 3.8% was measured on");
            } else if (Platform.currentOs() == Platform.Os.LINUX
                    && java.util.Set.of("btrfs", "xfs", "zfs").contains(fs)) {
                assertEquals(HomeCopyEconomics.Strategy.MAY_SHARE_BLOCKS, strategy,
                        "these kernels can reflink; whether the JDK asks is its own business");
            } else {
                assertEquals(HomeCopyEconomics.Strategy.FULL_COPY, strategy,
                        "everywhere else a copy of N bytes costs N bytes, and saying so is "
                                + "the whole point");
            }
        });

        suite.test("the destination need not exist yet to be costed", () -> {
            // The one call site that matters asks BEFORE the copy, about a
            // directory the copy is going to create.
            Path parent = Files.createTempDirectory("copy-econ-");
            Path notYet = parent.resolve("a/b/c/home");

            assertEquals(HomeCopyEconomics.strategyFor(parent),
                    HomeCopyEconomics.strategyFor(notYet),
                    "resolved against the nearest existing ancestor");
        });

        suite.test("the cost line names a real number and the filesystem", () -> {
            Path here = Files.createTempDirectory("copy-econ-");
            String line = HomeCopyEconomics.describe(here, 189L * 1024 * 1024);

            assertContains(line, "189.0 MB", "the size is stated, not implied");
            assertContains(line, Platform.currentKey(), "and the platform it is true of");
        });

        // -------------------------------------------------- platform-specific

        suite.test("a stamped toolchain is usable only on the platform it names", () -> {
            Path version = Files.createTempDirectory("pm-").resolve("22.9.0");
            Files.createDirectories(version.resolve("bin"));
            Path node = version.resolve("bin/node");
            Files.writeString(node, "#!/bin/sh\nexit 0\n");

            PmPlatform.stamp(version);
            assertEquals(Platform.currentKey(), PmPlatform.stampedKey(version),
                    "the stamp records the platform that provisioned it");
            assertTrue(PmPlatform.usableHere(version, node), "and it is usable here");

            Files.writeString(version.resolve(PmPlatform.STAMP), "plan9-pdp11\n");
            assertFalse(PmPlatform.usableHere(version, node),
                    "a foreign stamp is the whole signal — the bytes are not consulted");
            assertContains(PmPlatform.foreignReason(version, node), "plan9-pdp11",
                    "and the reason names what it was built for");
        });

        suite.test("an UNSTAMPED toolchain is judged by the binary's magic number", () -> {
            // Every home provisioned before this change has no stamp, and that
            // is exactly the population the finding is about. Treating
            // unstamped as fine would protect only homes made after the fix.
            Path version = Files.createTempDirectory("pm-").resolve("22.9.0");
            Files.createDirectories(version.resolve("bin"));
            Path node = version.resolve("bin/node");

            writeMagic(node, 0xcf, 0xfa, 0xed, 0xfe);   // Mach-O 64, little-endian
            assertEquals(Platform.Os.DARWIN, PmPlatform.builtFor(node), "Mach-O reads as darwin");

            writeMagic(node, 0x7f, 'E', 'L', 'F');
            assertEquals(Platform.Os.LINUX, PmPlatform.builtFor(node), "ELF reads as linux");

            writeMagic(node, 'M', 'Z', 0x90, 0x00);
            assertEquals(Platform.Os.WINDOWS, PmPlatform.builtFor(node), "PE reads as windows");

            assertEquals(null, PmPlatform.stampedKey(version),
                    "none of this wrote a stamp — the magic number is the fallback, "
                            + "not a second stamp");
        });

        suite.test("a wrapper script is not refused for being unrecognized", () -> {
            Path version = Files.createTempDirectory("pm-").resolve("0.4.18");
            Files.createDirectories(version.resolve("bin"));
            Path uv = version.resolve("bin/uv");
            Files.writeString(uv, "#!/bin/sh\nexec real-uv \"$@\"\n");

            assertEquals(null, PmPlatform.builtFor(uv), "a script has no platform to read");
            assertTrue(PmPlatform.usableHere(version, uv),
                    "and unknown must mean usable — refusing on a guess breaks working homes");
        });

        suite.test("the foreign binary of a real macOS home would be refused on Linux", () -> {
            // The measured shape of DEF-285, reproduced without needing the
            // other platform: a Mach-O binary stamped darwin-arm64, asked
            // about by a machine that is not darwin-arm64.
            Path version = Files.createTempDirectory("pm-").resolve("22.9.0");
            Files.createDirectories(version.resolve("bin"));
            Path node = version.resolve("bin/node");
            writeMagic(node, 0xcf, 0xfa, 0xed, 0xfe);
            Files.writeString(version.resolve(PmPlatform.STAMP), "darwin-arm64\n");

            boolean weAreThatMachine = Platform.matches("darwin-arm64");
            assertEquals(weAreThatMachine, PmPlatform.usableHere(version, node),
                    "usable exactly on the machine it was built for, and nowhere else");
        });

        // ------------------------------------------------------ portable copy

        suite.test("a portable clone leaves the platform-specific bytes behind", () -> {
            Path source = home();
            Path pmBinary = source.resolve("pm/node/22.9.0/bin/node");
            Files.createDirectories(pmBinary.getParent());
            writeMagic(pmBinary, 0xcf, 0xfa, 0xed, 0xfe);

            Path portable = Files.createTempDirectory("portable-").resolve("home");
            HomeCloner.cloneHome(source, portable, false, false, true);

            assertFalse(Files.exists(portable.resolve("pm")),
                    "pm/ does not travel in a copy that says it is leaving this machine");
            assertTrue(Files.exists(portable.resolve("skills/acme/SKILL.md")),
                    "and everything that is not machine-specific still does");

            Path ordinary = Files.createTempDirectory("ordinary-").resolve("home");
            HomeCloner.cloneHome(source, ordinary, false, false, false);
            assertTrue(Files.exists(ordinary.resolve("pm/node/22.9.0/bin/node")),
                    "while an ordinary clone still carries it — the destination is three "
                            + "directories away on the same kernel, and re-downloading 203 MB "
                            + "to get the identical bytes would be absurd");
        });

        suite.test("isPlatformSpecific matches the root and not a lookalike", () -> {
            assertTrue(HomeCloner.isPlatformSpecific("pm"), "the root itself");
            assertTrue(HomeCloner.isPlatformSpecific("pm/node/22.9.0/bin/node"), "and under it");
            assertFalse(HomeCloner.isPlatformSpecific("pmx/thing"),
                    "a prefix is not a root — this is the mistake a startsWith would make");
            assertFalse(HomeCloner.isPlatformSpecific("skills/pm/SKILL.md"),
                    "and a unit called pm is a unit, not a toolchain");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- fixtures

    /** A minimal home a clone will accept: one unit, one installed record. */
    private static Path home() throws Exception {
        Path root = Files.createTempDirectory("platform-home-").resolve(".skill-manager");
        Files.createDirectories(root.resolve("installed"));
        Files.createDirectories(root.resolve("skills/acme"));
        Files.writeString(root.resolve("skills/acme/SKILL.md"), "# acme\n");
        Files.writeString(root.resolve("installed/acme.json"),
                "{\"name\":\"acme\",\"version\":\"0.1.0\",\"kind\":\"skill\"}");
        return root;
    }

    private static void writeMagic(Path file, int... bytes) throws Exception {
        byte[] head = new byte[bytes.length + 64];
        for (int i = 0; i < bytes.length; i++) head[i] = (byte) bytes[i];
        Files.write(file, head, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
