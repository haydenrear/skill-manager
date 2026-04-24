package dev.skillmanager.registry;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Structural check on a bearer string. Catches the easy class of bugs
 * where we'd accept a garbage value as an access token — HTML error page
 * body, "null", trimmed empty string, etc. — without doing anything so
 * heavyweight as full JWS signature verification (that's the server's
 * job on every request).
 *
 * <p>Doesn't prove the token is valid or un-expired; just that the
 * shape matches RFC 7519: three base64url segments separated by dots.
 */
public final class JwtFormat {

    private JwtFormat() {}

    public static boolean looksLikeJwt(String token) {
        if (token == null || token.isBlank()) return false;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return false;
        Base64.Decoder dec = Base64.getUrlDecoder();
        for (int i = 0; i < 3; i++) {
            String part = parts[i];
            if (part.isEmpty() && i < 2) return false; // signature may be empty for alg=none; we still require header + payload
            try {
                byte[] bytes = dec.decode(part);
                if (i < 2) {
                    String decoded = new String(bytes, StandardCharsets.UTF_8);
                    if (!decoded.startsWith("{") || !decoded.endsWith("}")) return false;
                }
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return true;
    }
}
