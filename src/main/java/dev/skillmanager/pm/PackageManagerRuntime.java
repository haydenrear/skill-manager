package dev.skillmanager.pm;

import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Archives;
import dev.skillmanager.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Manages bundled, version-pinned copies of third-party package managers.
 *
 * <p>Layout under {@link SkillStore#root()}:
 * <pre>
 *   pm/
 *     uv/
 *       0.4.18/bin/uv
 *       current -> 0.4.18
 *     node/
 *       22.9.0/bin/{node,npm,npx}
 *       current -> 22.9.0
 * </pre>
 *
 * <p>Callers that need {@code uv} / {@code npm} call {@link #executablePath(String)}
 * and get back the bundled binary if installed, else fall back to system PATH.
 */
public final class PackageManagerRuntime {

    private final SkillStore store;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

    public PackageManagerRuntime(SkillStore store) { this.store = store; }

    public Path pmDir() { return store.root().resolve("pm"); }

    public Path toolDir(PackageManager pm) { return pmDir().resolve(pm.id); }

    public Path versionDir(PackageManager pm, String version) { return toolDir(pm).resolve(version); }

    public Path currentLink(PackageManager pm) { return toolDir(pm).resolve("current"); }

    /** Path to the bundled {@code tool} (if any), else system PATH, else null. */
    public String executablePath(String tool) {
        String bundled = bundledPath(tool);
        if (bundled != null) return bundled;
        return onPath(tool) ? tool : null;
    }

    /** Path to the bundled {@code tool} only. Returns null if not installed under {@code pm/}. */
    public String bundledPath(String tool) {
        for (PackageManager pm : PackageManager.values()) {
            if (!pm.providedTools().contains(tool)) continue;
            Path bundled = resolveCurrentBinary(pm, tool);
            if (bundled != null) return bundled.toString();
        }
        return null;
    }

    /** Return the bundled {@code tool} path, installing the pinned default version if missing. */
    public String ensureBundled(String tool) throws IOException {
        String existing = bundledPath(tool);
        if (existing != null) return existing;
        for (PackageManager pm : PackageManager.values()) {
            if (pm.providedTools().contains(tool)) {
                install(pm, null);
                String after = bundledPath(tool);
                if (after != null) return after;
            }
        }
        throw new IOException("no bundled package manager provides tool: " + tool);
    }

    public Path resolveCurrentBinary(PackageManager pm, String tool) {
        Path current = currentLink(pm);
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return null;
        Path vdir;
        if (Files.isSymbolicLink(current)) {
            try {
                Path target = Files.readSymbolicLink(current);
                vdir = target.isAbsolute() ? target : currentLink(pm).getParent().resolve(target);
            } catch (IOException e) {
                return null;
            }
        } else {
            // Fallback pointer file (filesystems without symlinks).
            try {
                vdir = toolDir(pm).resolve(Files.readString(current).trim());
            } catch (IOException e) {
                return null;
            }
        }
        Path bin = vdir.resolve("bin").resolve(tool);
        return Files.isExecutable(bin) ? bin : null;
    }

    public List<Installed> list() throws IOException {
        List<Installed> out = new ArrayList<>();
        if (!Files.isDirectory(pmDir())) return out;
        for (PackageManager pm : PackageManager.values()) {
            Path td = toolDir(pm);
            if (!Files.isDirectory(td)) continue;
            String current = null;
            Path link = currentLink(pm);
            if (Files.isSymbolicLink(link)) current = Files.readSymbolicLink(link).getFileName().toString();
            List<String> versions = new ArrayList<>();
            try (Stream<Path> s = Files.list(td)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (Files.isSymbolicLink(p)) continue; // skip the `current` pointer
                    if (!Files.isDirectory(p)) continue;
                    versions.add(p.getFileName().toString());
                }
            }
            versions.sort(String::compareTo);
            out.add(new Installed(pm, versions, current));
        }
        return out;
    }

    public Path install(PackageManager pm, String version) throws IOException {
        if (version == null || version.isBlank()) version = pm.defaultVersion;

        Path vdir = versionDir(pm, version);
        if (Files.isDirectory(vdir) && Files.isExecutable(vdir.resolve("bin").resolve(pm.binaryName()))) {
            Log.ok("pm: %s@%s already installed", pm.id, version);
            setCurrent(pm, version);
            return vdir;
        }

        Path cache = store.cacheDir().resolve("pm-" + pm.id + "-" + version + ".tar.gz");
        Fs.ensureDir(cache.getParent());

        String url = pm.downloadUrl(version);
        Log.step("pm: downloading %s@%s from %s", pm.id, version, url);
        download(url, cache);

        Path workdir = store.cacheDir().resolve("pm-extract-" + pm.id);
        if (Files.exists(workdir)) Fs.deleteRecursive(workdir);
        Fs.ensureDir(workdir);
        Archives.extractTarGz(cache, workdir);

        pm.installFromExtracted(workdir, vdir, version);

        setCurrent(pm, version);
        Log.ok("pm: installed %s@%s → %s", pm.id, version, vdir);
        Files.deleteIfExists(cache);
        Fs.deleteRecursive(workdir);
        return vdir;
    }

    private void setCurrent(PackageManager pm, String version) throws IOException {
        Path link = currentLink(pm);
        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(link)) {
            Files.delete(link);
        }
        Path target = Path.of(version); // relative so pm/ is relocatable
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            // Filesystem without symlinks: write a small pointer file.
            Files.writeString(link, version);
        }
    }

    private void download(String url, Path dst) throws IOException {
        try {
            HttpResponse<InputStream> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            }
            try (InputStream in = resp.body()) {
                Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", e);
        }
    }

    private static boolean onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String part : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(part, tool))) return true;
        }
        return false;
    }

    public record Installed(PackageManager pm, List<String> versions, String current) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tool", pm.id);
            m.put("default_version", pm.defaultVersion);
            m.put("installed_versions", versions);
            m.put("current", current);
            return m;
        }
    }
}
