package dev.skillmanager.pm;

import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.util.Platform;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one place that decides which package-manager directories a home
 * <em>shares</em> with every other home and which ones it must own.
 *
 * <h2>Two categories, and only one of them is safe to share</h2>
 *
 * <p>Every directory a package manager writes to falls into exactly one of
 * these, and conflating them is what this class exists to stop:
 *
 * <ul>
 *   <li><b>Content-addressed store</b> — {@code ~/.cache/uv}, {@code ~/.npm},
 *       the pip wheel cache. An entry is named by the hash/version of what it
 *       holds, so writing is <em>append-only by construction</em>: two homes
 *       racing to populate the same key write the same bytes. Nothing a home
 *       does here can change what another home reads. <b>Share it.</b></li>
 *   <li><b>Install target</b> — a venv, a {@code uv tool} root, an npm
 *       {@code --prefix}, an extracted toolchain. These are mutable: the agent
 *       is expected to {@code uv pip install} into them, upgrade them, and
 *       break them. Two homes pointed at one of these are one
 *       {@code --force} away from pulling the interpreter out from under each
 *       other. <b>Keep it per-home.</b></li>
 * </ul>
 *
 * <p>The distinction is not "read-only vs writable". Nothing here is made
 * read-only — a read-only store would break the very first {@code uv pip
 * install} an agent runs, and confinement is not this codebase's mechanism.
 * The store stays writable and stays shared <em>because</em> the only writes
 * it accepts are new content under new keys.
 *
 * <h2>Sharing a store is worthless unless materialization is cheap</h2>
 *
 * <p>A shared store that every venv copies out of saves download time and
 * nothing else: three skill-script venvs in one measured home held 48,258
 * files across 48,258 distinct inodes — 1.6 GB with zero sharing. The store
 * pays off only when the install target is materialized by reference, so
 * {@link #linkMode(Path)} picks the cheapest mechanism the filesystem
 * actually supports:
 *
 * <ol>
 *   <li>{@code clone} — a copy-on-write reflink. Blocks are shared until
 *       written, so the venv is fully writable and costs nothing until it is
 *       changed. Needs a reflink-capable filesystem (APFS, Btrfs, XFS with
 *       {@code reflink=1}, ZFS, bcachefs) and needs the store and the target
 *       on the <em>same</em> filesystem.</li>
 *   <li>{@code hardlink} — same inode, so the same block-sharing, but an
 *       in-place edit of the installed file would reach the store entry. This
 *       is uv's own default on Linux: uv caches post-build, ready-to-install
 *       artifacts precisely so that installing never rewrites them. Requires
 *       the same filesystem.</li>
 *   <li>{@code copy} — the last resort, and the current status quo. Correct
 *       everywhere, cheap nowhere. Taken when the store is on a different
 *       filesystem from the home, which is the normal case for a CI container
 *       whose cache is a mounted volume.</li>
 * </ol>
 *
 * <p>Guessing wrong degrades rather than fails: uv falls back to copy at
 * runtime when the mode it was handed cannot be used, and emits a warning.
 *
 * <h2>Why the resolution goes through {@link AgentHomes#userHome()}</h2>
 *
 * <p>"The operator's global cache" means the one under {@code $HOME}, read
 * the same way every other home-relative path in this codebase is read. A
 * fixture that redirects {@code HOME} to isolate itself gets an isolated
 * cache too, which is what keeps the home tripwire honest — reading
 * {@code user.home} here would punch the same hole through the fixture that
 * issue #18 punched through the agent homes.
 */
public final class PackageCaches {

    /** Escape hatch: force a link mode when the operator knows better. */
    public static final String LINK_MODE_OVERRIDE = "SKILL_MANAGER_UV_LINK_MODE";

    /** Filesystem types on which a reflink ({@code clone}) actually works. */
    private static final Set<String> REFLINK_FILESYSTEMS =
            Set.of("apfs", "btrfs", "xfs", "zfs", "bcachefs");

    private PackageCaches() {}

    // ------------------------------------------------- shared stores

    /**
     * The operator's uv cache. {@code UV_CACHE_DIR} wins when the operator
     * already set one, then {@code XDG_CACHE_HOME/uv}, then
     * {@code $HOME/.cache/uv} — uv's own resolution order, so pointing a
     * subprocess at this path is a no-op for a correctly configured host and
     * a repair for a home that would otherwise have carved out its own.
     */
    public static Path uvCacheDir() {
        Path explicit = fromEnv("UV_CACHE_DIR");
        if (explicit != null) return explicit;
        Path xdg = fromEnv("XDG_CACHE_HOME");
        if (xdg != null) return xdg.resolve("uv");
        return AgentHomes.userHome().resolve(".cache").resolve("uv");
    }

    /** The operator's npm cache — {@code npm_config_cache}, else {@code $HOME/.npm}. */
    public static Path npmCacheDir() {
        Path explicit = fromEnv("npm_config_cache");
        if (explicit != null) return explicit;
        return AgentHomes.userHome().resolve(".npm");
    }

    /**
     * The operator's pip cache. Not uv's — a skill-script that builds its venv
     * with {@code python -m venv} + {@code pip install} (which several do)
     * never touches {@code UV_CACHE_DIR}, and its wheel cache is just as
     * content-addressed and just as shareable.
     */
    public static Path pipCacheDir() {
        Path explicit = fromEnv("PIP_CACHE_DIR");
        if (explicit != null) return explicit;
        if (Platform.currentOs() == Platform.Os.DARWIN) {
            return AgentHomes.userHome().resolve("Library").resolve("Caches").resolve("pip");
        }
        Path xdg = fromEnv("XDG_CACHE_HOME");
        if (xdg != null) return xdg.resolve("pip");
        return AgentHomes.userHome().resolve(".cache").resolve("pip");
    }

    // ------------------------------------------------- materialization

    /**
     * The cheapest link mode that will actually work for materializing
     * {@code target} out of {@link #uvCacheDir()}.
     *
     * <p>Never throws and never returns null: an unreadable filesystem, a
     * missing cache, a target whose parents do not exist yet — every one of
     * them degrades to {@code copy}, which is correct everywhere.
     */
    public static String linkMode(Path target) {
        String override = System.getenv(LINK_MODE_OVERRIDE);
        if (override != null && !override.isBlank()) {
            return override.trim().toLowerCase(Locale.ROOT);
        }
        FileStore cacheStore = storeOf(uvCacheDir());
        FileStore targetStore = storeOf(target);
        if (cacheStore == null || targetStore == null) return "copy";
        if (!cacheStore.equals(targetStore)) return "copy";
        String type = cacheStore.type() == null ? "" : cacheStore.type().toLowerCase(Locale.ROOT);
        for (String reflinkable : REFLINK_FILESYSTEMS) {
            if (type.contains(reflinkable)) return "clone";
        }
        return "hardlink";
    }

    // ------------------------------------------------- the env block

    /**
     * The environment every uv/npm/pip subprocess a home spawns should carry:
     * the shared stores, plus the link mode that makes reading from them
     * cheap.
     *
     * <p>Deliberately absent: {@code UV_TOOL_DIR}, {@code UV_TOOL_BIN_DIR} and
     * the npm {@code --prefix}. Those are install targets and stay per-home;
     * their callers set them alongside this block, and
     * {@code shared.package.cache.is.not.private.to.the.home} asserts both
     * directions so that "share the cache" is never widened into "share the
     * venv".
     *
     * <p>Takes the materialization target rather than a {@code SkillStore} on
     * purpose: this class must stay compilable on its own so
     * {@code shared.package.cache.is.not.private.to.the.home} can
     * {@code //SOURCES} the production file and feel a mutation directly,
     * instead of asserting against a re-implementation of the rule.
     *
     * @param materializationTarget the directory venvs will be built in —
     *        {@code <home>/venvs} for a real home. Only its filesystem is
     *        read, and it need not exist yet.
     */
    public static Map<String, String> sharedEnv(Path materializationTarget) {
        Map<String, String> env = new LinkedHashMap<>();
        Path uvCache = uvCacheDir();
        env.put("UV_CACHE_DIR", uvCache.toString());
        env.put("npm_config_cache", npmCacheDir().toString());
        env.put("PIP_CACHE_DIR", pipCacheDir().toString());
        env.put("UV_LINK_MODE",
                linkMode(materializationTarget == null ? uvCache : materializationTarget));
        return env;
    }

    /**
     * {@link #sharedEnv} with the store directories created, so a first-ever
     * install on a host that has never run uv does not hand a subprocess a
     * path it then has to invent. Creation failure is not fatal — the package
     * manager creates its own cache and will say so if it cannot.
     */
    public static Map<String, String> sharedEnvEnsured(Path materializationTarget) {
        Map<String, String> env = sharedEnv(materializationTarget);
        for (String key : new String[]{"UV_CACHE_DIR", "npm_config_cache", "PIP_CACHE_DIR"}) {
            try {
                Files.createDirectories(Path.of(env.get(key)));
            } catch (IOException | RuntimeException tolerated) {
                // The package manager will create it, or fail with a better
                // message than anything this class could produce.
            }
        }
        return env;
    }

    // ------------------------------------------------- helpers

    private static Path fromEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value.trim());
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * The {@link FileStore} holding {@code path}, walking up to the nearest
     * existing ancestor when the path itself has not been created yet.
     */
    private static FileStore storeOf(Path path) {
        Path probe = path == null ? null : path.toAbsolutePath().normalize();
        while (probe != null) {
            if (Files.exists(probe)) {
                try {
                    return Files.getFileStore(probe);
                } catch (IOException unreadable) {
                    return null;
                }
            }
            probe = probe.getParent();
        }
        return null;
    }
}
