package dev.skillmanager.cli.installer;

import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Archives;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;
import dev.skillmanager.util.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Download + extract a tarball/zip/raw binary into {@code bin/cli/<name>}. */
public final class TarBackend implements InstallerBackend {

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

    @Override public String id() { return "tar"; }

    @Override public boolean available() { return true; }

    @Override
    public void install(CliDependency dep, SkillStore store, String skillName) throws IOException {
        if (dep.onPath() != null && isOnPath(dep.onPath())) {
            Log.ok("cli: %s already on PATH", dep.onPath());
            return;
        }
        Fs.ensureDir(store.cliBinDir());
        Path link = store.cliBinDir().resolve(dep.name());
        if (Files.exists(link)) {
            Log.ok("cli: %s already installed", dep.name());
            return;
        }
        CliDependency.InstallTarget target = pickTarget(dep);
        if (target == null || target.url() == null) {
            Log.warn("cli: no install target for %s on %s", dep.name(), Platform.currentKey());
            return;
        }

        Log.step("cli: downloading %s from %s", dep.name(), target.url());
        Fs.ensureDir(store.cacheDir());
        Path download = Files.createTempFile(store.cacheDir(), dep.name() + "-", suffix(target));
        try {
            download(target.url(), download);
            if (target.sha256() != null) verifySha256(download, target.sha256());
            Path extractDir = store.cacheDir().resolve("cli-" + dep.name());
            if (Files.exists(extractDir)) Fs.deleteRecursive(extractDir);
            Fs.ensureDir(extractDir);

            Path binary = extractOrCopy(download, extractDir, target);
            if (binary == null) {
                Log.warn("cli: could not locate binary for %s", dep.name());
                return;
            }
            Fs.makeExecutable(binary);
            if (Files.exists(link)) Files.delete(link);
            Files.copy(binary, link, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Fs.makeExecutable(link);
            Log.ok("cli: installed %s -> %s", dep.name(), link);
        } finally {
            Files.deleteIfExists(download);
        }
    }

    private CliDependency.InstallTarget pickTarget(CliDependency dep) {
        if (dep.platformIndependent() && dep.install().containsKey("any")) {
            return dep.install().get("any");
        }
        for (var e : dep.install().entrySet()) {
            if ("any".equals(e.getKey())) continue;
            if (Platform.matches(e.getKey())) return e.getValue();
        }
        return dep.install().get("any");
    }

    private String suffix(CliDependency.InstallTarget t) {
        return switch (Archives.detect(t.url())) {
            case TAR_GZ -> ".tar.gz";
            case ZIP -> ".zip";
            case RAW -> "";
        };
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

    private void verifySha256(Path file, String expected) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            String actual = HexFormat.of().formatHex(md.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("SHA-256 mismatch: expected " + expected + " got " + actual);
            }
        } catch (Exception e) {
            throw new IOException("checksum failed", e);
        }
    }

    private Path extractOrCopy(Path download, Path dir, CliDependency.InstallTarget target) throws IOException {
        Archives.Kind kind = target.archive() != null
                ? switch (target.archive().toLowerCase()) {
                    case "tar.gz", "tgz" -> Archives.Kind.TAR_GZ;
                    case "zip" -> Archives.Kind.ZIP;
                    default -> Archives.Kind.RAW;
                }
                : Archives.detect(target.url());

        switch (kind) {
            case TAR_GZ -> Archives.extractTarGz(download, dir);
            case ZIP -> Archives.extractZip(download, dir);
            case RAW -> {
                Path out = dir.resolve(target.binary() != null ? target.binary() : "bin");
                Fs.ensureDir(out.getParent());
                Files.copy(download, out, StandardCopyOption.REPLACE_EXISTING);
                return out;
            }
        }
        if (target.binary() != null) {
            Path explicit = dir.resolve(target.binary());
            if (Files.isRegularFile(explicit)) return explicit;
        }
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).filter(Files::isExecutable).findFirst().orElse(null);
        }
    }
}
