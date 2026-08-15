package dev.skillmanager.cli.installer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.lock.Fingerprints;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.pm.PackageManager;
import dev.skillmanager.pm.PackageManagerRuntime;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
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
    public InstallOutcome install(CliDependency dep, SkillStore store, String skillName)
            throws IOException {
        if (alreadyProvided(dep, store)) return InstallOutcome.ALREADY_PRESENT;
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
        // The prefix above is the install target and stays per-home; the cache
        // npm reads tarballs out of is content-addressed (keyed on integrity
        // hash) and is shared with every other home — see PackageCaches.
        Path nodeDir = Path.of(node).getParent();
        Map<String, String> env = new java.util.LinkedHashMap<>(
                dev.skillmanager.pm.PackageCaches.sharedEnvEnsured(store.venvsDir()));
        env.put("PATH", nodeDir + java.io.File.pathSeparator + System.getenv("PATH"));
        Shell.mustWithEnv(List.of(npm, "install", "-g", "--prefix", prefix.toString(), pkg), env);

        Path srcBin = prefix.resolve("bin");
        if (!Files.isDirectory(srcBin)) {
            Log.warn("cli: npm install produced no bin dir at %s", srcBin);
            return InstallOutcome.INSTALLED;
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
        return InstallOutcome.INSTALLED;
    }

    /**
     * {@code npm-v1} over the declared spec and, when this home holds the
     * per-unit prefix, the {@code version} in the installed package's own
     * {@code package.json}.
     *
     * <p>npm specs are the case where the requested version is least often a
     * pin: {@code npm:typescript@^5.4} and a bare {@code npm:@google/gemini-cli}
     * both resolve to whatever the registry served on install day, and neither
     * the spec nor {@code cli-lock.toml}'s {@code version} column records what
     * that was. So the installed manifest is the only place the resolved
     * identity exists, and it is read from the prefix this backend installed
     * into rather than from anything on PATH — a tool the machine already
     * provided is not this home's artifact and its version is not this home's
     * input.
     *
     * <p>That is also why the prefix is keyed by the requesting unit: npm
     * installs are per-unit here ({@code npm/<unit>}), so the same package
     * requested by two units is two artifacts with two prefixes.
     */
    @Override
    public Fingerprint fingerprint(CliDependency dep, SkillStore store, String unitName) {
        String pkg = dep.packageRef();
        if (pkg == null || pkg.isBlank()) {
            return Fingerprint.gap("npm " + dep.name() + " has no package in its spec "
                    + "(expected npm:<package>), so nothing declares what would be installed");
        }
        String resolved = unitName == null ? null : resolvedVersion(store, unitName, pkg);
        String digest = Fingerprints.scheme("npm-v1")
                .field("package", pkg)
                .field("resolved", resolved)
                .hex();
        return resolved != null
                ? Fingerprint.resolved(digest, "declared spec + version " + resolved
                        + " installed into npm/" + unitName)
                : Fingerprint.declared(digest, "declared spec only — this home holds no npm/"
                        + unitName + " prefix for " + packageName(pkg) + ", so the installed "
                        + "version is not observable here");
    }

    /** The {@code version} in {@code npm/<unit>/lib/node_modules/<pkg>/package.json}. */
    private static String resolvedVersion(SkillStore store, String unitName, String packageRef) {
        Path manifest = store.npmDir().resolve(unitName).resolve("lib").resolve("node_modules")
                .resolve(packageName(packageRef)).resolve("package.json");
        if (!Files.isRegularFile(manifest)) return null;
        try {
            JsonNode root = new ObjectMapper().readTree(manifest.toFile());
            JsonNode version = root.get("version");
            return version == null || !version.isTextual() ? null : version.asText();
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * The package name in an npm spec, with any trailing {@code @<version>}
     * removed and a leading {@code @scope/} kept.
     *
     * <p>Local rather than {@code RequestedVersion.fromNpm} on purpose. That
     * helper's job is to produce the lock KEY, it treats everything after the
     * last {@code @} as a version whether or not it is one — the project home
     * carries {@code ["npm"."google"] version = "gemini-cli"} from exactly that
     * reading — and a fingerprint must not inherit a parse whose failure mode is
     * to invent a version.
     */
    static String packageName(String packageRef) {
        if (packageRef == null) return "";
        String spec = packageRef.trim();
        int at = spec.lastIndexOf('@');
        if (at <= 0) return spec;
        return spec.substring(0, at);
    }
}
