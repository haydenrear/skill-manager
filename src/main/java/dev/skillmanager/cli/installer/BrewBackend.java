package dev.skillmanager.cli.installer;

import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;
import dev.skillmanager.util.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Install via Homebrew. brew owns its own prefix so we can't relocate the
 * package itself, but after install we symlink every binary from
 * {@code $(brew --prefix <pkg>)/bin/*} into our {@code bin/cli} so PATH stays
 * uniform across backends.
 */
public final class BrewBackend implements InstallerBackend {

    @Override public String id() { return "brew"; }

    @Override
    public boolean available() {
        Platform.Os os = Platform.currentOs();
        return (os == Platform.Os.DARWIN || os == Platform.Os.LINUX) && isOnPath("brew");
    }

    @Override
    public void install(CliDependency dep, SkillStore store, String skillName) throws IOException {
        if (dep.onPath() != null && isOnPath(dep.onPath())) {
            Log.ok("cli: %s already on PATH", dep.onPath());
            return;
        }
        String pkg = dep.packageRef();
        if (pkg == null || pkg.isBlank()) throw new IOException("brew: spec missing package name (brew:<package>)");

        Shell.must(List.of("brew", "install", pkg));

        String prefix = Shell.capture(List.of("brew", "--prefix", pkg));
        if (prefix == null || prefix.isBlank()) {
            Log.warn("cli: brew install %s succeeded but --prefix returned empty", pkg);
            return;
        }
        Path brewBin = Path.of(prefix.trim()).resolve("bin");
        if (!Files.isDirectory(brewBin)) {
            Log.warn("cli: no bin/ under %s", brewBin);
            return;
        }
        Fs.ensureDir(store.cliBinDir());
        try (Stream<Path> entries = Files.list(brewBin)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (!Files.isExecutable(entry)) continue;
                Path link = store.cliBinDir().resolve(entry.getFileName().toString());
                if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(link)) {
                    Files.delete(link);
                }
                try {
                    Files.createSymbolicLink(link, entry);
                } catch (UnsupportedOperationException | IOException e) {
                    // Fall back to copy on filesystems that reject symlinks.
                    Files.copy(entry, link);
                    Fs.makeExecutable(link);
                }
            }
        }
        Log.ok("cli: installed brew %s; linked bins into %s", pkg, store.cliBinDir());
    }
}
