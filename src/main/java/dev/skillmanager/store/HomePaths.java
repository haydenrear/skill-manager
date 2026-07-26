package dev.skillmanager.store;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Encodes and decodes paths stored inside a Skill Manager home so the
 * home stays a <em>pure function of {@code SKILL_MANAGER_HOME}</em>:
 * nothing written into it anchors to the absolute location it happens
 * to sit at today, and therefore a plain copy of the home works
 * unchanged under a different root.
 *
 * <h2>The rule: only self-references are encoded</h2>
 *
 * A path that points <em>into</em> the home is a self-reference and is
 * stored as {@code $SKILL_MANAGER_HOME/<relative>}. A path that points
 * anywhere else is genuinely external — a project checkout, a brew
 * prefix, the user's {@code ~/.claude} — and is stored verbatim,
 * because rewriting it would break the thing it points at.
 *
 * <p>Concretely, in a child-home record {@code parentHome} points into
 * the home and is encoded; {@code childHome} points at a project
 * checkout and stays absolute. In a projection ledger row
 * {@code sourcePath} points at {@code <home>/skills/<unit>} and is
 * encoded; {@code destPath} points at {@code ~/.claude/skills/<unit>}
 * and stays absolute.
 *
 * <h2>Compatibility</h2>
 *
 * {@link #decode(String)} accepts <em>both</em> forms — a token-prefixed
 * path and a plain absolute path — so a home written by an older
 * skill-manager keeps working with no migration step. Encoding happens
 * only at write time; reads never rewrite what they read (see
 * {@link #decode(String)} for why).
 *
 * <p>An instance is bound to one home root and is immutable and
 * thread-safe.
 */
public final class HomePaths {

    /** The token stored in place of the home root. */
    public static final String TOKEN = "$SKILL_MANAGER_HOME";

    /** Braced spelling, accepted on read for hand-edited files. */
    public static final String BRACED_TOKEN = "${SKILL_MANAGER_HOME}";

    private final Path homeRoot;
    /**
     * {@link #homeRoot} with symlinks resolved, when it exists. On macOS a
     * temp dir handed out as {@code /var/folders/...} really lives at
     * {@code /private/var/folders/...}; callers legitimately hold either
     * spelling, and both must encode.
     */
    private final Path homeRootReal;

    private HomePaths(Path homeRoot, Path homeRootReal) {
        this.homeRoot = homeRoot;
        this.homeRootReal = homeRootReal;
    }

    public static HomePaths of(Path homeRoot) {
        Objects.requireNonNull(homeRoot, "homeRoot");
        Path normalized = homeRoot.toAbsolutePath().normalize();
        return new HomePaths(normalized, realOrSame(normalized));
    }

    public static HomePaths of(SkillStore store) {
        return of(store.root());
    }

    public Path homeRoot() { return homeRoot; }

    /**
     * Storage form of {@code path}: {@code $SKILL_MANAGER_HOME/<rel>} when
     * it points into this home, otherwise the path's own string, unchanged.
     * Null in, null out.
     */
    public String encode(Path path) {
        if (path == null) return null;
        Path abs = path.toAbsolutePath().normalize();
        Path rel = relativize(abs);
        if (rel == null) return path.toString();
        String suffix = rel.toString().replace(java.io.File.separatorChar, '/');
        return suffix.isEmpty() ? TOKEN : TOKEN + "/" + suffix;
    }

    /** String overload for path-shaped values held as {@code String}. */
    public String encode(String path) {
        if (path == null || path.isBlank()) return path;
        if (isEncoded(path)) return path;
        return encode(Path.of(path));
    }

    /**
     * Runtime form of a stored path. A token-prefixed value resolves
     * against this home root; anything else is taken verbatim, which is
     * what makes records written before this encoding existed readable.
     *
     * <p>Reads deliberately do not migrate: {@code decode} returns a path
     * and writes nothing. Rewriting from inside the read path would race
     * other processes and dirty homes the caller only meant to inspect.
     * Old records are re-encoded the next time their writer rewrites them,
     * and wholesale by {@link HomeCloner}, which re-anchors the state
     * surface as part of producing the copy.
     *
     * <p>Note this is not the same as "only explicit writes re-encode".
     * Commands such as {@code list} reconcile default-agent bindings and
     * write the ledger back as part of their normal work, so records do
     * get re-encoded by commands a user thinks of as read-only. That is
     * the ordinary writer path doing its job, and it is exactly why
     * {@link #encode} must never widen what it treats as a
     * self-reference — a wrong encoding would propagate on the next
     * reconcile.
     */
    public Path decode(String stored) {
        if (stored == null) return null;
        String suffix = tokenSuffix(stored);
        if (suffix == null) return Path.of(stored);
        return suffix.isEmpty() ? homeRoot : homeRoot.resolve(suffix);
    }

    /** Decode to a string, preserving null. */
    public String decodeToString(String stored) {
        Path p = decode(stored);
        return p == null ? null : p.toString();
    }

    /** True when {@code stored} is written in the token form. */
    public static boolean isEncoded(String stored) {
        return stored != null && tokenSuffix(stored) != null;
    }

    /** True when {@code path} points at or into this home. */
    public boolean isInsideHome(Path path) {
        return path != null && relativize(path.toAbsolutePath().normalize()) != null;
    }

    /**
     * The portion of {@code abs} below this home, or null when {@code abs}
     * is outside it. Compared lexically against both spellings of the home
     * root; {@code abs} itself is never resolved.
     *
     * <h2>Why {@code abs} is never passed through {@code toRealPath()}</h2>
     *
     * <p>Resolving the candidate would follow its own symlinks, and the
     * paths this is asked about are frequently symlinks <em>into</em> the
     * home: a projection's {@code destPath} is {@code ~/.claude/skills/<unit>},
     * which is a link to {@code <home>/skills/<unit>}, and the ledger is
     * written after that link exists. Resolving it would classify an
     * external destination as a self-reference and store it as
     * {@code $SKILL_MANAGER_HOME/skills/<unit>} — after which {@code unbind}
     * ({@code LiveInterpreter.reverseProjection} →
     * {@code Fs.deleteRecursive(destPath)}) deletes the installed unit out
     * of the store and leaves the agent symlink dangling. That is data
     * loss, and it was a real defect here, not a hypothetical.
     *
     * <p>The two failure directions are not symmetric. Failing to
     * tokenize a genuine self-reference costs relocatability for that one
     * record — the absolute path still resolves, and {@link HomeCloner}
     * re-anchors it. Tokenizing a path that is not a self-reference
     * silently repoints it at unrelated content. So this errs toward
     * leaving paths alone, and matches only what it can prove lexically.
     */
    private Path relativize(Path abs) {
        if (abs.startsWith(homeRoot)) return homeRoot.relativize(abs);
        if (abs.startsWith(homeRootReal)) return homeRootReal.relativize(abs);
        return null;
    }

    /**
     * Everything after the token, or null when {@code stored} does not
     * start with it. An empty string means the value is exactly the home
     * root. A value like {@code $SKILL_MANAGER_HOMEX} is not a match —
     * the token must be followed by a separator or end of string.
     */
    private static String tokenSuffix(String stored) {
        for (String token : new String[]{BRACED_TOKEN, TOKEN}) {
            if (!stored.startsWith(token)) continue;
            String rest = stored.substring(token.length());
            if (rest.isEmpty()) return "";
            char c = rest.charAt(0);
            if (c == '/' || c == java.io.File.separatorChar) {
                return rest.substring(1);
            }
        }
        return null;
    }

    private static Path realOrSame(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException | RuntimeException e) {
            return path;
        }
    }
}
