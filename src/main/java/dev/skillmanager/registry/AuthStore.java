package dev.skillmanager.registry;

import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Persists the bearer token we attach on every registry request.
 *
 * <p>One-file MVP at {@code ~/.skill-manager/auth.token} with owner-only
 * perms on POSIX filesystems. A keychain-backed store is a plausible
 * future swap-out — the interface is deliberately narrow so that swap
 * doesn't leak into callers.
 *
 * <p>{@code SKILL_MANAGER_AUTH_TOKEN} environment variable always wins
 * (useful for CI / test graphs). If neither env var nor file is present,
 * {@link #load()} returns null and callers hit the server unauthenticated.
 */
public final class AuthStore {

    public static final String FILENAME = "auth.token";

    private final SkillStore store;

    public AuthStore(SkillStore store) { this.store = store; }

    public String load() {
        String env = System.getenv("SKILL_MANAGER_AUTH_TOKEN");
        if (env != null && !env.isBlank()) return env.trim();
        Path f = file();
        if (!Files.isRegularFile(f)) return null;
        try {
            String t = Files.readString(f).trim();
            return t.isEmpty() ? null : t;
        } catch (IOException e) {
            return null;
        }
    }

    public void save(String token) throws IOException {
        Fs.ensureDir(store.root());
        Path f = file();
        Files.writeString(f, token);
        // Owner-read-only where the filesystem supports it.
        try {
            Files.setPosixFilePermissions(f, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {}
    }

    public boolean clear() throws IOException {
        return Files.deleteIfExists(file());
    }

    public Path file() { return store.root().resolve(FILENAME); }
}
