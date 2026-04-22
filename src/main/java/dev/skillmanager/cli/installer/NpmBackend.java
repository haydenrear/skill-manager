package dev.skillmanager.cli.installer;

import dev.skillmanager.model.CliDependency;
import dev.skillmanager.pm.PackageManager;
import dev.skillmanager.pm.PackageManagerRuntime;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Install npm packages into a per-skill prefix, using a bundled Node if
 * present, else bootstrapping one. Entry points from {@code <prefix>/bin/}
 * are symlinked into {@code bin/cli/}.
 */
public final class NpmBackend implements InstallerBackend {

    @Override public String id() { return "npm"; }

    @Override public boolean available() { return true; }

    @Override
    public void install(CliDependency dep, SkillStore store, String skillName) throws IOException {
        if (dep.onPath() != null && isOnPath(dep.onPath())) {
            Log.ok("cli: %s already on PATH", dep.onPath());
            return;
        }
        String pkg = dep.packageRef();
        if (pkg == null || pkg.isBlank()) throw new IOException("npm: spec missing package name (npm:<package>)");

        PackageManagerRuntime pm = new PackageManagerRuntime(store);
        if (pm.bundledPath("npm") == null) {
            Log.step("npm: bootstrapping bundled node@%s", PackageManager.NODE.defaultVersion);
        }
        String node = pm.ensureBundled("node");
        String npm = pm.bundledPath("npm");
        if (npm == null) throw new IOException("npm: could not locate bundled npm after node install");

        Path prefix = store.npmDir().resolve(skillName);
        Fs.ensureDir(prefix);

        // Ensure node is on PATH for the npm subprocess (npm's shebang assumes it).
        Path nodeDir = Path.of(node).getParent();
        Map<String, String> env = Map.of("PATH", nodeDir + java.io.File.pathSeparator + System.getenv("PATH"));
        Shell.mustWithEnv(List.of(npm, "install", "-g", "--prefix", prefix.toString(), pkg), env);

        Path srcBin = prefix.resolve("bin");
        if (!Files.isDirectory(srcBin)) {
            Log.warn("cli: npm install produced no bin dir at %s", srcBin);
            return;
        }
        Fs.ensureDir(store.cliBinDir());
        try (Stream<Path> entries = Files.list(srcBin)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Path link = store.cliBinDir().resolve(entry.getFileName().toString());
                if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(link)) {
                    Files.delete(link);
                }
                try {
                    Files.createSymbolicLink(link, entry);
                } catch (UnsupportedOperationException | IOException e) {
                    Files.copy(entry, link);
                    Fs.makeExecutable(link);
                }
            }
        }
        Log.ok("cli: installed npm %s → %s (linked into %s)", pkg, prefix, store.cliBinDir());
    }
}
