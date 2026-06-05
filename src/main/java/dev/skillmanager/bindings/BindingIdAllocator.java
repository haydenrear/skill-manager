package dev.skillmanager.bindings;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Allocates user-facing binding ids for explicit binds.
 *
 * <p>Harness, project, and default-agent bindings already have stable
 * owner-scoped ids. This allocator handles the ad-hoc {@code bind}
 * command path, where ids should be readable but still unique across
 * the binding ledgers.
 */
public final class BindingIdAllocator {

    private final Set<String> reserved;

    private BindingIdAllocator(Set<String> reserved) {
        this.reserved = new LinkedHashSet<>(reserved);
    }

    public static BindingIdAllocator fromStore(BindingStore store) {
        Set<String> ids = new LinkedHashSet<>();
        for (Binding binding : store.listAll()) {
            if (binding.bindingId() != null && !binding.bindingId().isBlank()) {
                ids.add(binding.bindingId());
            }
        }
        return new BindingIdAllocator(ids);
    }

    public static BindingIdAllocator empty() {
        return new BindingIdAllocator(Set.of());
    }

    public String reserveForExplicitDoc(Path targetRoot, String docSourceId) {
        return reserve(explicitDocBindingIdBase(targetRoot, docSourceId));
    }

    public String reserveForExplicitUnit(Path targetRoot, String unitName) {
        return reserve(explicitUnitBindingIdBase(targetRoot, unitName));
    }

    public String reserve(String base) {
        String normalized = sanitizeBase(base);
        String candidate = normalized;
        int suffix = 0;
        while (reserved.contains(candidate)) {
            candidate = normalized + ":" + suffix;
            suffix++;
        }
        reserved.add(candidate);
        return candidate;
    }

    public static String explicitDocBindingIdBase(Path targetRoot, String docSourceId) {
        return targetScope(targetRoot) + ":" + sanitizeSegment(docSourceId, "doc") + ":bind";
    }

    public static String explicitUnitBindingIdBase(Path targetRoot, String unitName) {
        return targetScope(targetRoot) + ":" + sanitizeSegment(unitName, "unit") + ":bind";
    }

    public static String targetScope(Path targetRoot) {
        Optional<String> github = githubSlug(targetRoot);
        if (github.isPresent()) return github.get();
        if (targetRoot == null) return "target";
        Path normalized = targetRoot.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        return sanitizeSegment(name == null ? null : name.toString(), "target");
    }

    static Optional<String> githubSlug(Path targetRoot) {
        if (targetRoot == null) return Optional.empty();
        try (Repository repo = new FileRepositoryBuilder()
                .findGitDir(targetRoot.toAbsolutePath().normalize().toFile())
                .readEnvironment()
                .setMustExist(true)
                .build()) {
            StoredConfig cfg = repo.getConfig();
            return githubSlugFromRemote(cfg.getString("remote", "origin", "url"));
        } catch (IOException | IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    static Optional<String> githubSlugFromRemote(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        String s = url.trim();
        if (s.startsWith("git+")) {
            s = s.substring("git+".length());
        }

        String body = null;
        if (s.startsWith("git@github.com:")) {
            body = s.substring("git@github.com:".length());
        } else if (s.startsWith("ssh://git@github.com/")) {
            body = s.substring("ssh://git@github.com/".length());
        } else if (s.startsWith("https://github.com/")) {
            body = s.substring("https://github.com/".length());
        } else if (s.startsWith("http://github.com/")) {
            body = s.substring("http://github.com/".length());
        } else if (s.startsWith("github:")) {
            body = s.substring("github:".length());
        }
        if (body == null) return Optional.empty();

        int query = body.indexOf('?');
        if (query >= 0) body = body.substring(0, query);
        int hash = body.indexOf('#');
        if (hash >= 0) body = body.substring(0, hash);
        while (body.startsWith("/")) body = body.substring(1);
        while (body.endsWith("/")) body = body.substring(0, body.length() - 1);
        if (body.endsWith(".git")) body = body.substring(0, body.length() - 4);

        String[] parts = body.split("/");
        if (parts.length < 2) return Optional.empty();
        String owner = sanitizeSegment(parts[0], "");
        String repo = sanitizeSegment(parts[1], "");
        if (owner.isBlank() || repo.isBlank()) return Optional.empty();
        return Optional.of(owner + ":" + repo);
    }

    private static String sanitizeBase(String raw) {
        if (raw == null || raw.isBlank()) return "binding";
        StringBuilder out = new StringBuilder(raw.length());
        boolean lastDash = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (isSegmentChar(ch) || ch == ':') {
                out.append(ch);
                lastDash = false;
            } else if (!lastDash) {
                out.append('-');
                lastDash = true;
            }
        }
        return trimDashes(out.toString(), "binding");
    }

    private static String sanitizeSegment(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        StringBuilder out = new StringBuilder(raw.length());
        boolean lastDash = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (isSegmentChar(ch)) {
                out.append(ch);
                lastDash = false;
            } else if (!lastDash) {
                out.append('-');
                lastDash = true;
            }
        }
        return trimDashes(out.toString(), fallback);
    }

    private static boolean isSegmentChar(char ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '-'
                || ch == '_'
                || ch == '.';
    }

    private static String trimDashes(String value, String fallback) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') start++;
        while (end > start && value.charAt(end - 1) == '-') end--;
        String trimmed = value.substring(start, end);
        return trimmed.isBlank() ? fallback : trimmed;
    }
}
