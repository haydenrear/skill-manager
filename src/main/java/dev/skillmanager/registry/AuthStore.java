package dev.skillmanager.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Persists the bearer + refresh token issued to {@code skill-manager login}.
 *
 * <p>Backing file is {@code $SKILL_MANAGER_HOME/auth.token} with POSIX
 * owner-only perms where the filesystem supports them. Contents are a
 * small JSON blob:
 *
 * <pre>{@code
 * { "access_token": "…", "refresh_token": "…", "expires_at": "2026-..." }
 * }</pre>
 *
 * <p>Anything else on disk is treated as a corrupt/legacy file and
 * surfaced as "not logged in" — forcing a re-login is safer than silently
 * accepting a mystery string as a bearer.
 *
 * <p>{@code SKILL_MANAGER_AUTH_TOKEN} env var always wins (the test graph +
 * CI use it). It carries an access token only — no refresh, no expiry.
 *
 * <p>The {@link Tokens} record is the only thing {@link RegistryClient}
 * consumes, so swapping the on-disk format for a keychain-backed store is
 * a local change.
 */
public final class AuthStore {

    public static final String FILENAME = "auth.token";

    private final SkillStore store;
    private final ObjectMapper json = new ObjectMapper();

    public AuthStore(SkillStore store) { this.store = store; }

    public record Tokens(String accessToken, String refreshToken, Instant expiresAt) {
        public boolean hasRefresh() { return refreshToken != null && !refreshToken.isBlank(); }
    }

    /** Returns the cached tokens, or null if the user hasn't logged in. */
    public Tokens load() {
        String env = System.getenv("SKILL_MANAGER_AUTH_TOKEN");
        if (env != null && !env.isBlank()) {
            return new Tokens(env.trim(), null, null);
        }
        Path f = file();
        if (!Files.isRegularFile(f)) return null;
        String body;
        try {
            body = Files.readString(f).trim();
        } catch (IOException e) {
            return null;
        }
        if (body.isEmpty() || !body.startsWith("{")) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(body, Map.class);
            String access = asString(parsed.get("access_token"));
            if (access == null || access.isBlank()) return null;
            String refresh = asString(parsed.get("refresh_token"));
            Instant exp = null;
            Object expRaw = parsed.get("expires_at");
            if (expRaw instanceof String s && !s.isBlank()) {
                try { exp = Instant.parse(s); } catch (Exception ignored) {}
            }
            return new Tokens(access, refresh, exp);
        } catch (IOException ignored) {
            return null;
        }
    }

    /** Short-circuit when callers only need the bearer to attach to a request. */
    public String loadAccessToken() {
        Tokens t = load();
        return t == null ? null : t.accessToken();
    }

    public void save(Tokens tokens) throws IOException {
        Fs.ensureDir(store.root());
        Path f = file();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", tokens.accessToken());
        if (tokens.refreshToken() != null) out.put("refresh_token", tokens.refreshToken());
        if (tokens.expiresAt() != null) out.put("expires_at", tokens.expiresAt().toString());
        Files.writeString(f, json.writeValueAsString(out));
        try {
            Files.setPosixFilePermissions(f,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {}
    }

    public boolean clear() throws IOException {
        return Files.deleteIfExists(file());
    }

    public Path file() { return store.root().resolve(FILENAME); }

    private static String asString(Object o) { return o == null ? null : o.toString(); }
}
