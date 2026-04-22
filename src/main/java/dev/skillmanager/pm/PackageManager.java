package dev.skillmanager.pm;

import dev.skillmanager.util.Fs;
import dev.skillmanager.util.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** A bundleable third-party package manager skill-manager can pin. */
public enum PackageManager {

    UV("uv", "0.4.18") {
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

    NODE("node", "22.9.0") {
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
    };

    public final String id;
    public final String defaultVersion;

    PackageManager(String id, String defaultVersion) {
        this.id = id;
        this.defaultVersion = defaultVersion;
    }

    public abstract String downloadUrl(String version);

    public abstract String binaryName();

    public abstract List<String> providedTools();

    /** Copy the package manager's files from {@code extractedRoot} into {@code vdir}, normalizing to {@code <vdir>/bin/<binary>}. */
    public abstract void installFromExtracted(Path extractedRoot, Path vdir, String version) throws IOException;

    public static PackageManager byId(String id) {
        for (PackageManager p : values()) if (p.id.equalsIgnoreCase(id)) return p;
        throw new IllegalArgumentException("unknown package manager: " + id);
    }
}
