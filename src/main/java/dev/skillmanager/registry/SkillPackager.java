package dev.skillmanager.registry;

import dev.skillmanager.model.PluginParser;
import dev.skillmanager.model.SkillParser;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Builds a {@code .tar.gz} of a unit directory suitable for uploading to
 * the registry. Auto-detects plugin vs bare skill via
 * {@link #detectKind}: plugins have {@code .claude-plugin/plugin.json};
 * skills have {@code SKILL.md} at the root.
 *
 * <p>Bundle inclusion list per kind:
 * <ul>
 *   <li><b>SKILL</b>: every regular file under the dir, excluding hidden
 *       entries (anything path-segmented under {@code .*} like
 *       {@code .git/}, {@code .DS_Store}). Existing behavior, unchanged.</li>
 *   <li><b>PLUGIN</b>: same exclusion logic, plus an explicit allow for
 *       {@code .claude-plugin/} (the manifest dir) and {@code .mcp.json}
 *       (Claude's runtime MCP config). Other dotfiles still excluded.</li>
 * </ul>
 *
 * <p>The {@code .tar.gz} format is unchanged across kinds — only the file
 * inclusion list differs. Server-side, the published bundle's
 * {@code unit_kind} column drives kind-aware UI; that work lives in
 * {@code server-java/} and is out of scope here.
 */
public final class SkillPackager {

    public enum Kind { SKILL, PLUGIN }

    private SkillPackager() {}

    /**
     * Detect what kind of unit lives at {@code dir}. Plugin presence is
     * checked first (via {@link PluginParser#looksLikePlugin}); bare
     * skill is the fallback when {@code SKILL.md} sits at the root.
     */
    public static Kind detectKind(Path dir) throws IOException {
        if (PluginParser.looksLikePlugin(dir)) return Kind.PLUGIN;
        if (Files.isRegularFile(dir.resolve(SkillParser.SKILL_FILENAME))) return Kind.SKILL;
        throw new IOException("not a recognizable unit dir (no .claude-plugin/plugin.json, no "
                + SkillParser.SKILL_FILENAME + "): " + dir);
    }

    public static Path pack(Path unitDir, Path outputDir) throws IOException {
        Kind kind = detectKind(unitDir);
        Files.createDirectories(outputDir);
        Path out = outputDir.resolve(unitDir.getFileName() + ".tar.gz");

        try (OutputStream fout = new BufferedOutputStream(Files.newOutputStream(out));
             GZIPOutputStream gzip = new GZIPOutputStream(fout)) {
            writeTar(unitDir, gzip, kind);
        }
        return out;
    }

    private static void writeTar(Path root, OutputStream out, Kind kind) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> includeInBundle(root, p, kind))
                    .forEach(files::add);
        }
        for (Path p : files) {
            String name = root.relativize(p).toString().replace('\\', '/');
            byte[] data = Files.readAllBytes(p);
            writeTarEntry(out, name, data, Files.isExecutable(p));
        }
        // End-of-archive: two 512-byte zero blocks.
        out.write(new byte[1024]);
    }

    /**
     * Decide whether to include {@code file} in a bundle of {@code kind}.
     * Skill bundles exclude every dotfile (legacy behavior). Plugin
     * bundles allow {@code .claude-plugin/...} and {@code .mcp.json}
     * — those are the manifest + runtime config the registry needs to
     * recreate the plugin — but still exclude {@code .git}, IDE
     * scratches, etc.
     */
    private static boolean includeInBundle(Path root, Path file, Kind kind) {
        Path rel = root.relativize(file);
        for (Path seg : rel) {
            String s = seg.toString();
            if (s.startsWith(".")) {
                // Plugin bundle's allowlist: .claude-plugin/<anything> and the
                // top-level .mcp.json file. Anything else .-prefixed stays out.
                if (kind == Kind.PLUGIN) {
                    if (s.equals(".claude-plugin")) return true;  // dir gate — children OK
                    if (s.equals(".mcp.json") && seg.equals(rel.getFileName())) return true;
                }
                return false;
            }
        }
        return true;
    }

    private static void writeTarEntry(OutputStream out, String name, byte[] data, boolean executable) throws IOException {
        byte[] header = new byte[512];
        writeName(header, 0, 100, name);
        writeOctal(header, 100, 8, executable ? 0755 : 0644);
        writeOctal(header, 108, 8, 0);           // uid
        writeOctal(header, 116, 8, 0);           // gid
        writeOctal(header, 124, 12, data.length);
        writeOctal(header, 136, 12, System.currentTimeMillis() / 1000);
        // checksum: initially spaces
        for (int i = 148; i < 156; i++) header[i] = ' ';
        header[156] = '0'; // normal file
        writeName(header, 257, 6, "ustar");
        writeName(header, 263, 2, "00");
        long cksum = 0;
        for (byte b : header) cksum += (b & 0xff);
        writeOctal(header, 148, 7, cksum);
        header[155] = 0;

        out.write(header);
        out.write(data);
        int rem = data.length % 512;
        if (rem > 0) out.write(new byte[512 - rem]);
    }

    private static void writeName(byte[] buf, int off, int len, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int n = Math.min(bytes.length, len);
        System.arraycopy(bytes, 0, buf, off, n);
    }

    private static void writeOctal(byte[] buf, int off, int len, long value) {
        String oct = Long.toOctalString(value);
        String padded = "0".repeat(Math.max(0, len - 1 - oct.length())) + oct;
        byte[] bytes = padded.getBytes(StandardCharsets.US_ASCII);
        int n = Math.min(bytes.length, len - 1);
        System.arraycopy(bytes, 0, buf, off, n);
        buf[off + len - 1] = 0;
    }
}
