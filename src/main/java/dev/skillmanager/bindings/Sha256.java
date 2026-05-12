package dev.skillmanager.bindings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Tiny SHA-256 helper for {@link ProjectionKind#MANAGED_COPY} drift
 * detection. Hashes raw bytes — no normalization (no line-ending,
 * BOM, or trailing-newline conversions). False-positive "edited"
 * warnings from a {@code git checkout} that flipped LF→CRLF are
 * easier to handle (re-bind, or {@code --force}) than divergent
 * normalization policies per team. See #48 for the decision.
 */
public final class Sha256 {

    private Sha256() {}

    /** Hex-encoded SHA-256 of a file's contents. Returns {@code null} when the file is missing. */
    public static String hashFile(Path p) throws IOException {
        if (!Files.exists(p) || !Files.isRegularFile(p)) return null;
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = newDigest();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return toHex(md.digest());
        }
    }

    /** Hex-encoded SHA-256 of an in-memory byte array. */
    public static String hashBytes(byte[] bytes) {
        MessageDigest md = newDigest();
        md.update(bytes);
        return toHex(md.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
