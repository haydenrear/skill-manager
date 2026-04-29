package dev.skillmanager.pm;

import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A package manager / runtime tool skill-manager knows about.
 *
 * <p>Two flavors:
 * <ul>
 *   <li><b>Bundleable</b> ({@link #bundleable()} == true): skill-manager
 *       owns the version, downloads it into
 *       {@code $SKILL_MANAGER_HOME/pm/<id>/<version>/}, maintains a
 *       {@code current} symlink, and can re-install on demand. Examples:
 *       {@link #UV}, {@link #NODE}.</li>
 *   <li><b>External</b> ({@link #bundleable()} == false): system-managed.
 *       skill-manager only checks for presence on PATH and surfaces an
 *       install hint when missing. Examples: {@link #DOCKER}, {@link #BREW}.</li>
 * </ul>
 *
 * <p>Both kinds participate in the unified {@code ToolDependency}
 * collection step in {@code PlanBuilder}, so a CLI dep declaring
 * {@code spec = "pip:..."} and an MCP dep declaring
 * {@code load = { type = "uv", ... }} both end up requesting the same
 * {@link #UV} entry — which is then realized at most once per install.
 */
public enum PackageManager {

    UV("uv", "0.4.18", true) {
        @Override public String downloadUrl(String version) {
            return "https://github.com/astral-sh/uv/releases/download/" + version + "/uv-" + uvPlatform() + ".tar.gz";
        }
        @Override public String binaryName() { return "uv"; }
        @Override public List<String> providedTools() { return List.of("uv"); }

        /** Archive layout: {@code uv-<platform>/uv} — single static binary. */
        @Override
        public void installFromExtracted(Path extractedRoot, Path vdir, String version) throws IOException {
            Path src = extractedRoot.resolve("uv-" + uvPlatform()).resolve("uv");
            if (!Files.isRegularFile(src)) throw new IOException("uv: missing binary at " + src);
            Path dstBin = vdir.resolve("bin");
            Fs.ensureDir(dstBin);
            Files.copy(src, dstBin.resolve("uv"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Fs.makeExecutable(dstBin.resolve("uv"));
        }

        private String uvPlatform() {
            return switch (Platform.currentOs()) {
                case DARWIN -> Platform.currentArch() == Platform.Arch.ARM64 ? "aarch64-apple-darwin" : "x86_64-apple-darwin";
                case LINUX -> Platform.currentArch() == Platform.Arch.ARM64 ? "aarch64-unknown-linux-gnu" : "x86_64-unknown-linux-gnu";
                default -> throw new UnsupportedOperationException("uv: unsupported platform " + Platform.currentKey());
            };
        }
    },

    NODE("node", "22.9.0", true) {
        @Override public String downloadUrl(String version) {
            return "https://nodejs.org/dist/v" + version + "/node-v" + version + "-" + nodePlatform() + ".tar.gz";
        }
        @Override public String binaryName() { return "node"; }
        @Override public List<String> providedTools() { return List.of("node", "npm", "npx"); }

        /** Archive layout: {@code node-v<version>-<platform>/{bin,lib,include,share}} — full tree. */
        @Override
        public void installFromExtracted(Path extractedRoot, Path vdir, String version) throws IOException {
            Path src = extractedRoot.resolve("node-v" + version + "-" + nodePlatform());
            if (!Files.isDirectory(src)) throw new IOException("node: expected tree at " + src);
            if (Files.isDirectory(vdir)) Fs.deleteRecursive(vdir);
            Fs.ensureDir(vdir);
            Fs.copyRecursive(src, vdir);
            for (String tool : List.of("node", "npm", "npx")) {
                Path p = vdir.resolve("bin").resolve(tool);
                if (Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Fs.makeExecutable(p);
            }
        }

        private String nodePlatform() {
            return switch (Platform.currentOs()) {
                case DARWIN -> Platform.currentArch() == Platform.Arch.ARM64 ? "darwin-arm64" : "darwin-x64";
                case LINUX -> Platform.currentArch() == Platform.Arch.ARM64 ? "linux-arm64" : "linux-x64";
                default -> throw new UnsupportedOperationException("node: unsupported platform " + Platform.currentKey());
            };
        }
    },

    /**
     * Docker is required by {@code McpDependency.DockerLoad}. skill-manager
     * does not bundle docker — it's a system-level dependency the user
     * must install themselves. {@link PackageManagerRuntime#ensureAvailable}
     * does a presence check and surfaces the install hint if missing.
     */
    DOCKER("docker", null, false) {
        @Override public String binaryName() { return "docker"; }
        @Override public List<String> providedTools() { return List.of("docker"); }
        @Override public String installHint() {
            return "Install Docker: https://docs.docker.com/get-docker/";
        }
        @Override public String downloadUrl(String version) {
            throw new UnsupportedOperationException("docker is external; not bundleable");
        }
        @Override public void installFromExtracted(Path extractedRoot, Path vdir, String version) {
            throw new UnsupportedOperationException("docker is external; not bundleable");
        }
    },

    /**
     * Homebrew (macOS / Linux) backs the {@code brew:} CLI install backend.
     * Like docker, it's external — skill-manager checks for presence and
     * surfaces an install hint if missing. There's no MCP load type for
     * brew currently; it lives here so {@code BrewBackend} can route its
     * presence check through the unified {@link PackageManagerRuntime}
     * surface instead of duplicating logic.
     */
    BREW("brew", null, false) {
        @Override public String binaryName() { return "brew"; }
        @Override public List<String> providedTools() { return List.of("brew"); }
        @Override public String installHint() {
            return "Install Homebrew: https://brew.sh";
        }
        @Override public String downloadUrl(String version) {
            throw new UnsupportedOperationException("brew is external; not bundleable");
        }
        @Override public void installFromExtracted(Path extractedRoot, Path vdir, String version) {
            throw new UnsupportedOperationException("brew is external; not bundleable");
        }
    };

    public final String id;
    /** Bundleable PMs only: the version skill-manager pins. {@code null} for external PMs. */
    public final String defaultVersion;
    /** True if skill-manager can install this PM under {@code $SKILL_MANAGER_HOME/pm/}. */
    public final boolean bundleable;

    PackageManager(String id, String defaultVersion, boolean bundleable) {
        this.id = id;
        this.defaultVersion = defaultVersion;
        this.bundleable = bundleable;
    }

    public boolean bundleable() { return bundleable; }

    public abstract String binaryName();

    public abstract List<String> providedTools();

    /**
     * URL to download {@code version} from. Overridden by every bundleable
     * PM. External PMs throw {@link UnsupportedOperationException}.
     */
    public abstract String downloadUrl(String version);

    /** Copy the package manager's files from {@code extractedRoot} into
     *  {@code vdir}, normalizing to {@code <vdir>/bin/<binary>}.
     *  External PMs throw {@link UnsupportedOperationException}. */
    public abstract void installFromExtracted(Path extractedRoot, Path vdir, String version) throws IOException;

    /**
     * Human-pointer to the install instructions for an external PM.
     * Returns {@code null} for bundleable PMs (skill-manager handles
     * those itself).
     */
    public String installHint() { return null; }

    /** Find the PM that provides a given tool name, or {@code null}. */
    public static PackageManager providerOf(String tool) {
        for (PackageManager p : values()) {
            if (p.providedTools().contains(tool)) return p;
        }
        return null;
    }

    public static PackageManager byId(String id) {
        for (PackageManager p : values()) if (p.id.equalsIgnoreCase(id)) return p;
        throw new IllegalArgumentException("unknown package manager: " + id);
    }
}
