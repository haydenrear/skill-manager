package dev.skillmanager.util;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Archive extraction via Apache Commons Compress.
 *
 * <p>Replaces a hand-rolled tar parser. Handles PAX headers, long filenames,
 * sparse files, and symlinks via a well-tested library. Guards against path
 * traversal (zip/tar slip) for every entry.
 */
public final class Archives {

    private Archives() {}

    public enum Kind { TAR_GZ, ZIP, RAW }

    public static Kind detect(String urlOrFilename) {
        String lower = urlOrFilename.toLowerCase();
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) return Kind.TAR_GZ;
        if (lower.endsWith(".zip")) return Kind.ZIP;
        return Kind.RAW;
    }

    public static void extractTarGz(Path archive, Path dst) throws IOException {
        try (InputStream fin = new BufferedInputStream(Files.newInputStream(archive));
             GzipCompressorInputStream gin = new GzipCompressorInputStream(fin);
             TarArchiveInputStream tin = new TarArchiveInputStream(gin)) {
            extractTar(tin, dst);
        }
    }

    public static void extractTarGz(InputStream stream, Path dst) throws IOException {
        try (InputStream gin = new GzipCompressorInputStream(stream);
             TarArchiveInputStream tin = new TarArchiveInputStream(gin)) {
            extractTar(tin, dst);
        }
    }

    public static void extractZip(Path archive, Path dst) throws IOException {
        try (InputStream fin = new BufferedInputStream(Files.newInputStream(archive));
             ZipArchiveInputStream zin = new ZipArchiveInputStream(fin)) {
            ArchiveEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!zin.canReadEntryData(entry)) continue;
                Path out = safeResolve(dst, entry.getName());
                if (entry.isDirectory()) {
                    Fs.ensureDir(out);
                    continue;
                }
                Fs.ensureDir(out.getParent());
                Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                if (entry instanceof ZipArchiveEntry z && (z.getUnixMode() & 0111) != 0) {
                    Fs.makeExecutable(out);
                }
            }
        }
    }

    private static void extractTar(TarArchiveInputStream tin, Path dst) throws IOException {
        TarArchiveEntry entry;
        while ((entry = tin.getNextEntry()) != null) {
            if (!tin.canReadEntryData(entry)) continue;
            Path out = safeResolve(dst, entry.getName());
            if (entry.isDirectory()) {
                Fs.ensureDir(out);
                continue;
            }
            if (entry.isSymbolicLink()) {
                Fs.ensureDir(out.getParent());
                Path link = Path.of(entry.getLinkName());
                if (Files.exists(out, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(out)) {
                    Files.delete(out);
                }
                try {
                    Files.createSymbolicLink(out, link);
                } catch (UnsupportedOperationException | IOException e) {
                    // Skip symlinks on systems that don't support them.
                }
                continue;
            }
            if (entry.isLink()) continue; // hard links — skip for simplicity
            Fs.ensureDir(out.getParent());
            Files.copy(tin, out, StandardCopyOption.REPLACE_EXISTING);
            if ((entry.getMode() & 0111) != 0) {
                Fs.makeExecutable(out);
            }
        }
    }

    private static Path safeResolve(Path root, String name) throws IOException {
        Path resolved = root.resolve(name).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IOException("archive slip detected: " + name);
        }
        return resolved;
    }
}
