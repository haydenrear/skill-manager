package dev.skillmanager.source;

import dev.skillmanager.resolve.Git;
import dev.skillmanager.shared.util.Fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Small {@link Git} adapter for project-local development checkouts.
 */
public final class SubprocessGit implements Git {

    @Override
    public Path cloneAt(String url, String ref, Path dest) throws IOException {
        Fs.ensureDir(dest);
        Path target = dest.resolve(repoName(url));
        if (Files.exists(target)) {
            throw new IOException("clone destination already exists: " + target);
        }
        run(null, List.of("git", "clone", "--", url, target.toString()));
        if (ref != null && !ref.isBlank()) {
            run(target, List.of("git", "checkout", "--quiet", ref));
        }
        return target;
    }

    @Override
    public String headHash(Path dir) {
        return GitOps.headHash(dir);
    }

    private static void run(Path cwd, List<String> argv) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        if (cwd != null) pb.directory(cwd.toFile());
        Process p = pb.start();
        String output;
        try {
            output = new String(p.getInputStream().readAllBytes());
            int rc = p.waitFor();
            if (rc != 0) {
                throw new IOException(String.join(" ", argv) + " failed with " + rc + ":\n" + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroy();
            throw new IOException("interrupted running " + argv.get(0), e);
        }
    }

    private static String repoName(String url) {
        String normalized = url == null ? "repo" : url;
        int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf(':'));
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (name.endsWith(".git")) name = name.substring(0, name.length() - ".git".length());
        name = name.replaceAll("[^A-Za-z0-9._-]", "-");
        return name.isBlank() ? "repo" : name;
    }
}
