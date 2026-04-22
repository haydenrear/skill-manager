package dev.skillmanager.store;

import dev.skillmanager.util.Archives;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.util.Fs;
import dev.skillmanager.util.Log;
import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Resolves a skill source to a local directory containing a {@code SKILL.md}.
 *
 * <p>Returns a {@link FetchResult} with staging path + size/digest when available
 * so the planner can show a download map before anything is committed.
 */
public final class Fetcher {

    private Fetcher() {}

    public record FetchResult(
            Path dir,
            ResolvedGraph.SourceKind kind,
            long bytesDownloaded,
            String sha256
    ) {}

    public static FetchResult fetch(String source, String version, Path workspace, SkillStore store) throws IOException {
        String normalized = source == null ? "" : source.trim();
        if (normalized.isEmpty()) throw new IOException("empty skill source");

        if (normalized.startsWith("file:")) normalized = normalized.substring("file:".length());

        if (normalized.startsWith("github:")) {
            return new FetchResult(
                    gitClone("https://github.com/" + normalized.substring("github:".length()) + ".git", version, workspace),
                    ResolvedGraph.SourceKind.GIT, 0, null);
        }
        if (normalized.startsWith("git+")) {
            return new FetchResult(gitClone(normalized.substring("git+".length()), version, workspace),
                    ResolvedGraph.SourceKind.GIT, 0, null);
        }
        if (normalized.endsWith(".git") || normalized.startsWith("ssh://") || normalized.startsWith("git@")) {
            return new FetchResult(gitClone(normalized, version, workspace), ResolvedGraph.SourceKind.GIT, 0, null);
        }

        Path localCandidate = Path.of(normalized);
        if (Files.isDirectory(localCandidate)) {
            Path staged = workspace.resolve("staged");
            if (Files.exists(staged)) Fs.deleteRecursive(staged);
            Fs.ensureDir(staged);
            Fs.copyRecursive(localCandidate, staged);
            return new FetchResult(locateSkillRoot(staged), ResolvedGraph.SourceKind.LOCAL, 0, null);
        }

        return fetchFromRegistry(normalized, version, workspace, store);
    }

    private static FetchResult fetchFromRegistry(String coord, String version, Path workspace, SkillStore store) throws IOException {
        String name = coord;
        String resolvedVersion = version;
        int at = coord.indexOf('@');
        if (at >= 0) {
            name = coord.substring(0, at);
            String ver = coord.substring(at + 1);
            if (resolvedVersion == null || resolvedVersion.isBlank()) resolvedVersion = ver;
        }

        RegistryConfig cfg = RegistryConfig.resolve(store, null);
        RegistryClient client = new RegistryClient(cfg);
        Log.step("registry: fetching %s%s from %s", name,
                resolvedVersion == null ? "" : "@" + resolvedVersion, cfg.baseUrl());
        Path tar = workspace.resolve(name + ".tar.gz");
        long bytes = client.download(name, resolvedVersion, tar);
        String sha = sha256(tar);

        Path extract = workspace.resolve("extracted");
        Fs.ensureDir(extract);
        Archives.extractTarGz(tar, extract);
        return new FetchResult(locateSkillRoot(extract), ResolvedGraph.SourceKind.REGISTRY, bytes, sha);
    }

    private static Path gitClone(String url, String ref, Path workspace) throws IOException {
        Path target = workspace.resolve("clone");
        if (Files.exists(target)) Fs.deleteRecursive(target);
        Fs.ensureDir(workspace);
        Log.step("cloning %s", url);
        try (Git git = Git.cloneRepository().setURI(url).setDirectory(target.toFile()).call()) {
            if (ref != null && !ref.isBlank()) {
                git.checkout().setName(ref).call();
            }
        } catch (Exception e) {
            throw new IOException("git clone failed: " + url, e);
        }
        return locateSkillRoot(target);
    }

    private static Path locateSkillRoot(Path dir) throws IOException {
        if (Files.isRegularFile(dir.resolve(SkillParser.SKILL_FILENAME))) return dir;
        try (var s = Files.walk(dir, 3)) {
            return s.filter(p -> p.getFileName() != null
                            && p.getFileName().toString().equals(SkillParser.SKILL_FILENAME)
                            && Files.isRegularFile(p))
                    .map(Path::getParent)
                    .findFirst()
                    .orElse(dir);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }
}
