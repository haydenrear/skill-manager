package dev.skillmanager.registry;

import dev.skillmanager.model.SkillParser;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/** Builds a .tar.gz of a skill directory suitable for uploading to the registry. */
public final class SkillPackager {

    private SkillPackager() {}

    public static Path pack(Path skillDir, Path outputDir) throws IOException {
        if (!Files.isRegularFile(skillDir.resolve(SkillParser.SKILL_FILENAME))) {
            throw new IOException("missing " + SkillParser.SKILL_FILENAME + " in " + skillDir);
        }
        Files.createDirectories(outputDir);
        Path out = outputDir.resolve(skillDir.getFileName() + ".tar.gz");

        try (OutputStream fout = new BufferedOutputStream(Files.newOutputStream(out));
             GZIPOutputStream gzip = new GZIPOutputStream(fout)) {
            writeTar(skillDir, gzip);
        }
        return out;
    }

    private static void writeTar(Path root, OutputStream out) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
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
