package dev.skillmanager.store;

import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Symlink half of the relocatability rule that {@link HomePaths}
 * implements for metadata.
 *
 * <p>A symlink target cannot hold {@code $SKILL_MANAGER_HOME} — the
 * kernel resolves the stored bytes literally, it does not expand
 * environment variables. So the only way for a link inside a home to
 * survive relocation is for it to be <em>relative</em>. A link from
 * {@code <home>/bin/cli/jinja2} to {@code <home>/venvs/jinja2-cli/bin/jinja2}
 * is written as {@code ../../venvs/jinja2-cli/bin/jinja2} and then
 * resolves correctly under any root.
 *
 * <p>Links that leave the home ({@code /opt/homebrew/...}) stay
 * absolute, for the same reason external metadata paths do: relativizing
 * them would silently repoint them at whatever happens to sit at that
 * offset from the copy.
 */
public final class HomeLinks {

    private HomeLinks() {}

    /**
     * The target to store for a link at {@code link} pointing at
     * {@code target}: relative when both sit inside {@code homeRoot},
     * otherwise {@code target} unchanged (normalized to absolute).
     */
    public static Path storedTarget(Path homeRoot, Path link, Path target) {
        HomePaths paths = HomePaths.of(homeRoot);
        Path absLink = link.toAbsolutePath().normalize();
        Path absTarget = target.toAbsolutePath().normalize();
        if (!paths.isInsideHome(absLink) || !paths.isInsideHome(absTarget)) return absTarget;
        Path linkDir = absLink.getParent();
        if (linkDir == null) return absTarget;
        try {
            return linkDir.relativize(absTarget);
        } catch (IllegalArgumentException e) {
            // Different roots (Windows drives) — nothing relative to say.
            return absTarget;
        }
    }

    /**
     * Rewrite every symlink under {@code dir} whose target is an absolute
     * path into {@code homeRoot} so it becomes relative. Covers links this
     * codebase did not create: {@code uv tool install} and {@code npm -g}
     * both write absolute symlinks into {@code bin/cli/}, and there is no
     * flag that changes that.
     *
     * <p>Recursive. It was not, on the theory that the shim directories are
     * flat — they are not: the real home has
     * {@code bin/cli/.spec-double-compiler/} holding {@code tla2tools.jar},
     * and a backend is free to create more. Recursion is bounded to the
     * directory it is handed, and every caller hands it a shim directory,
     * never unit content.
     *
     * @return how many links were rewritten
     */
    public static int relativizeLinksIn(Path homeRoot, Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        HomePaths paths = HomePaths.of(homeRoot);
        int rewritten = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    rewritten += relativizeOne(homeRoot, paths, entry) ? 1 : 0;
                } else if (Files.isDirectory(entry)) {
                    rewritten += relativizeLinksIn(homeRoot, entry);
                }
            }
        } catch (IOException e) {
            Log.warn("could not scan %s for absolute shims: %s", dir, e.getMessage());
        }
        return rewritten;
    }

    private static boolean relativizeOne(Path homeRoot, HomePaths paths, Path entry) {
        Path target;
        try {
            target = Files.readSymbolicLink(entry);
        } catch (IOException e) {
            return false;
        }
        if (!target.isAbsolute()) return false;
        if (!paths.isInsideHome(target)) return false;
        Path relative = storedTarget(homeRoot, entry, target);
        if (relative.isAbsolute()) return false;
        try {
            Files.delete(entry);
            Files.createSymbolicLink(entry, relative);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            // Best effort: put the absolute link back rather than leaving a
            // hole where a working shim used to be.
            try {
                if (!Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createSymbolicLink(entry, target);
                }
            } catch (IOException | UnsupportedOperationException ignored) {}
            Log.warn("could not relativize shim %s: %s", entry, e.getMessage());
            return false;
        }
    }

    /**
     * Normalize the whole {@code bin/} tree of {@code store}. Called after
     * CLI dependency installation.
     *
     * <p>Scoped to {@code bin/} rather than to {@code bin/cli} and
     * {@code bin/mcp} by name: no writer in this codebase populates
     * {@code bin/mcp} today — the gateway provisions MCP binaries into
     * {@code gateway-data/mcp_binaries/} — but {@link SkillStore#init()}
     * still creates the directory, and naming the parent means a future
     * writer anywhere under {@code bin/} is covered without anyone having
     * to remember this function exists.
     */
    public static int relativizeShims(SkillStore store) {
        return relativizeLinksIn(store.root(), store.binDir());
    }
}
