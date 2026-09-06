package dev.skillmanager.pm;

import dev.skillmanager.util.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Which machine a bundled toolchain under {@code pm/} was built for.
 *
 * <h2>Why a home needs this at all</h2>
 *
 * <p>{@code pm/} is the one directory a clone carries that is
 * <b>machine-specific in its bytes</b>. {@link
 * dev.skillmanager.store.HomeCloner#SKIPPED_DIRS} skips {@code tools/},
 * {@code venvs/} and {@code npm/} and re-provisions them; {@code pm/} is
 * deliberately not skipped, so a copy of a macOS home carries
 * {@code pm/node/22.9.0/bin/node} as Mach-O arm64 — measured here at 203 MB
 * across {@code node} and {@code uv}. On Linux those bytes are executable by
 * permission bits and unrunnable in fact: the kernel answers {@code ENOEXEC},
 * which surfaces as {@code Exec format error} from whatever tried to use npm,
 * at first use, far from the copy that caused it.
 *
 * <p>That is DEF-285 / OUN-12's second half. The fix is not to make {@code pm/}
 * unavailable — a home that cannot run {@code node} is a home that cannot
 * install anything — but to make the home <b>know</b> which platform its copy
 * is for, so a foreign one reads as "not provisioned here" and is re-provisioned
 * from the network rather than executed. {@link PackageManagerRuntime#install}
 * needs no {@code node} and no {@code uv} to do that: it downloads over the
 * JDK's own HTTP client and extracts with {@code Archives}, so bootstrapping
 * {@code pm/} from nothing is exactly as possible as bootstrapping it the first
 * time.
 *
 * <h2>Two readings, in this order</h2>
 *
 * <ol>
 *   <li><b>The stamp.</b> {@link #stamp} writes {@value #STAMP} into a version
 *       directory at install time holding {@link Platform#currentKey()}. It
 *       travels with the copy because it is an ordinary file, which is the
 *       whole point: the destination reads the SOURCE's answer.</li>
 *   <li><b>The binary's own magic number</b>, for the homes that already
 *       exist. Every home provisioned before this change has no stamp, and
 *       treating unstamped as "fine" would leave exactly the population the
 *       finding is about unprotected. ELF, Mach-O and PE are four bytes at
 *       offset 0 and they are unambiguous.</li>
 * </ol>
 *
 * <p>Unknown magic — a shell script, a shim, an empty file — reads as USABLE.
 * A wrapper script is portable by construction, and refusing something merely
 * because it is not recognized would break homes over a guess.
 */
public final class PmPlatform {

    /** Written inside a version directory: {@code pm/<tool>/<version>/.platform}. */
    public static final String STAMP = ".platform";

    private PmPlatform() {}

    /** Record the platform {@code versionDir} was provisioned for. */
    public static void stamp(Path versionDir) throws IOException {
        if (!Files.isDirectory(versionDir)) return;
        Files.writeString(versionDir.resolve(STAMP), Platform.currentKey() + "\n");
    }

    /** The platform key {@code versionDir} was stamped with, or null when unstamped. */
    public static String stampedKey(Path versionDir) {
        if (versionDir == null) return null;
        try {
            Path stamp = versionDir.resolve(STAMP);
            if (!Files.isRegularFile(stamp)) return null;
            String key = Files.readString(stamp).trim();
            return key.isEmpty() ? null : key;
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * Whether {@code binary} under {@code versionDir} can run on this machine.
     *
     * <p>The stamp decides when there is one; otherwise the binary's magic
     * number does; otherwise yes.
     */
    public static boolean usableHere(Path versionDir, Path binary) {
        String stamped = stampedKey(versionDir);
        if (stamped != null) return Platform.matches(stamped);
        Platform.Os built = builtFor(binary);
        return built == null || built == Platform.currentOs();
    }

    /**
     * A description of why a version directory is not usable here, for a log
     * line or a report. Null when it is usable.
     */
    public static String foreignReason(Path versionDir, Path binary) {
        if (usableHere(versionDir, binary)) return null;
        String stamped = stampedKey(versionDir);
        if (stamped != null) {
            return "provisioned for " + stamped + ", running on " + Platform.currentKey();
        }
        Platform.Os built = builtFor(binary);
        return "holds a " + Platform.osKey(built) + " binary, running on "
                + Platform.osKey(Platform.currentOs());
    }

    /**
     * The OS an executable was built for, read from its first four bytes, or
     * null when the format is not one of the three that carry a platform.
     */
    public static Platform.Os builtFor(Path binary) {
        if (binary == null || !Files.isRegularFile(binary)) return null;
        byte[] magic = new byte[4];
        try (InputStream in = Files.newInputStream(binary)) {
            int read = in.readNBytes(magic, 0, 4);
            if (read < 4) return null;
        } catch (IOException unreadable) {
            return null;
        }
        long be = ((long) (magic[0] & 0xff) << 24) | ((magic[1] & 0xff) << 16)
                | ((magic[2] & 0xff) << 8) | (magic[3] & 0xff);
        // ELF: 0x7f 'E' 'L' 'F'.
        if (be == 0x7f454c46L) return Platform.Os.LINUX;
        // Mach-O, both endiannesses and both widths, plus the fat/universal
        // archive header that wraps several of them.
        if (be == 0xfeedfaceL || be == 0xfeedfacfL      // big-endian 32 / 64
                || be == 0xcefaedfeL || be == 0xcffaedfeL   // little-endian 32 / 64
                || be == 0xcafebabeL || be == 0xbebafecaL) { // universal binary
            return Platform.Os.DARWIN;
        }
        // PE/COFF starts 'M' 'Z'.
        if ((magic[0] & 0xff) == 'M' && (magic[1] & 0xff) == 'Z') return Platform.Os.WINDOWS;
        return null;
    }

    /**
     * A one-line, human-readable summary of the platform-specific bytes a home
     * is holding under {@code pm/} — what {@code home clone} prints so the
     * operator learns it at copy time rather than at first exec on the other
     * machine.
     *
     * <p>Empty when the home holds no {@code pm/} tree at all.
     */
    public static String describeHome(Path homeRoot) {
        Path pm = homeRoot.resolve("pm");
        if (!Files.isDirectory(pm)) return "";
        StringBuilder out = new StringBuilder();
        try (var tools = Files.list(pm)) {
            for (Path tool : tools.sorted().toList()) {
                if (!Files.isDirectory(tool)) continue;
                try (var versions = Files.list(tool)) {
                    for (Path version : versions.sorted().toList()) {
                        if (!Files.isDirectory(version) || Files.isSymbolicLink(version)) continue;
                        String key = stampedKey(version);
                        if (key == null) key = guessKey(version);
                        if (key == null) continue;
                        if (out.length() > 0) out.append(", ");
                        out.append(tool.getFileName()).append('@')
                                .append(version.getFileName()).append(' ').append(key);
                    }
                }
            }
        } catch (IOException unreadable) {
            return out.toString();
        }
        return out.toString();
    }

    /** Best-effort platform of an unstamped version directory, from its bin/. */
    private static String guessKey(Path versionDir) {
        Path bin = versionDir.resolve("bin");
        if (!Files.isDirectory(bin)) return null;
        try (var entries = Files.list(bin)) {
            for (Path entry : entries.sorted().toList()) {
                Platform.Os os = builtFor(entry);
                if (os != null) {
                    // No stamp: this is read from the binary's magic number,
                    // and saying so is the difference between a fact and a
                    // guess an operator cannot tell apart.
                    return Platform.osKey(os).toLowerCase(Locale.ROOT) + " (unstamped)";
                }
            }
        } catch (IOException unreadable) {
            return null;
        }
        return null;
    }
}
