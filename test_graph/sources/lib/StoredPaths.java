import java.io.File;
import java.nio.file.Path;

/**
 * Asserting that a store file persisted a path, without pinning which of
 * the two legal spellings it used.
 *
 * <p><b>Why this exists.</b> Paths that point back into the home holding
 * the file are written as {@code $SKILL_MANAGER_HOME/<rel>} rather than
 * verbatim, so a home stays valid when it is cloned or moved to another
 * worktree; the readers resolve the token against the home root on the way
 * back in ({@code HomePaths.decode}, {@code BindingJson.mapperFor}). A node
 * that greps a store file for an absolute path therefore reports a failure
 * for a home that is working exactly as designed — which is what happened
 * to {@code harness.instance.materialized} and
 * {@code harness.child.home.materialized}.
 *
 * <p>Both spellings are accepted deliberately. Which one a given writer
 * emits is not uniform today: {@code pip-cli-skill.projections.json}
 * tokenizes, the child {@code .harness-instance.json} does not, and
 * {@code child-home.json} tokenizes {@code parentHome} but not
 * {@code childHome} — all for paths inside the same home. What these nodes
 * are actually asserting is that the path was persisted and will resolve,
 * and that holds for either form. Pinning the spelling would make these
 * nodes fail on a writer's internal choice rather than on a broken home.
 */
final class StoredPaths {

    /** The token the store writes in place of the home root. */
    static final String TOKEN = "$SKILL_MANAGER_HOME";

    private StoredPaths() {}

    /**
     * Whether {@code json} records {@code path} — either verbatim, or
     * tokenized relative to {@code homeRoot}.
     */
    static boolean records(String json, Path homeRoot, Path path) {
        if (json == null || json.isEmpty()) return false;
        if (json.contains(path.toString())) return true;
        String token = tokenized(homeRoot, path);
        return token != null && json.contains(token);
    }

    /** String overload for a home root held as a {@code String}. */
    static boolean records(String json, String homeRoot, Path path) {
        return records(json, Path.of(homeRoot), path);
    }

    /**
     * {@code path} written as {@code $SKILL_MANAGER_HOME/<rel>}, or null
     * when it does not sit under {@code homeRoot} (those stay absolute).
     */
    static String tokenized(Path homeRoot, Path path) {
        Path root = homeRoot.toAbsolutePath().normalize();
        Path abs = path.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) return null;
        String rel = root.relativize(abs).toString().replace(File.separatorChar, '/');
        return rel.isEmpty() ? TOKEN : TOKEN + "/" + rel;
    }
}
