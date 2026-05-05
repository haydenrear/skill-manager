package dev.skillmanager._lib.fakes;

import dev.skillmanager.resolve.Git;
import dev.skillmanager.shared.util.Fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test double for {@link Git}. Holds an in-memory mapping from
 * {@code (url, ref)} → on-disk source directory; {@link #cloneAt}
 * copies the source into the requested destination, mimicking a
 * real clone but without spawning {@code git}.
 *
 * <p>Records every {@code cloneAt} call for assertions
 * (see {@link #calls()}). A scripted IOException can be queued via
 * {@link #failNextClone(String)} to simulate fetch failures for the
 * resolver's error path.
 */
public final class FakeGit implements Git {

    public record Call(String url, String ref, Path dest) {}

    private final Map<Key, Path> repos = new LinkedHashMap<>();
    private final Map<Path, String> shas = new HashMap<>();
    private final java.util.ArrayList<Call> calls = new java.util.ArrayList<>();
    private String nextCloneFailure;

    public FakeGit register(String url, String ref, Path source) {
        repos.put(new Key(url, ref), source);
        return this;
    }

    public FakeGit registerSha(Path tree, String sha) {
        shas.put(tree.toAbsolutePath(), sha);
        return this;
    }

    public FakeGit failNextClone(String message) {
        this.nextCloneFailure = message;
        return this;
    }

    public List<Call> calls() { return List.copyOf(calls); }

    @Override
    public Path cloneAt(String url, String ref, Path dest) throws IOException {
        calls.add(new Call(url, ref, dest));
        if (nextCloneFailure != null) {
            String msg = nextCloneFailure;
            nextCloneFailure = null;
            throw new IOException(msg);
        }
        Path source = repos.get(new Key(url, ref));
        if (source == null) source = repos.get(new Key(url, null));
        if (source == null) {
            throw new IOException("FakeGit: no registered repo for url=" + url + " ref=" + ref);
        }
        if (!Files.isDirectory(dest)) Files.createDirectories(dest);
        Path target = dest.resolve(source.getFileName());
        Fs.copyRecursive(source, target);
        // Default sha if registered against the source path; otherwise a
        // deterministic stub so determinism tests can pin behaviour.
        String sha = shas.get(source.toAbsolutePath());
        if (sha == null) sha = stableSha(url, ref);
        shas.put(target.toAbsolutePath(), sha);
        return target;
    }

    @Override
    public String headHash(Path dir) {
        return shas.get(dir.toAbsolutePath());
    }

    private static String stableSha(String url, String ref) {
        int h = java.util.Objects.hash(url, ref);
        return String.format("%08x", h) + "f00dface";
    }

    private record Key(String url, String ref) {}
}
