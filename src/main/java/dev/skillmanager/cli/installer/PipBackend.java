package dev.skillmanager.cli.installer;

import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.lock.Fingerprints;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.pm.PackageCaches;
import dev.skillmanager.pm.PackageManager;
import dev.skillmanager.pm.PackageManagerRuntime;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Install pip packages using uv. Auto-installs a bundled uv into the
 * skill-manager {@code pm/} directory on first use so the install is not
 * dependent on whatever pip/python is sitting on the user's PATH.
 */
public final class PipBackend implements InstallerBackend {

    @Override public String id() { return "pip"; }

    @Override public boolean available() { return true; }

    @Override
    public InstallOutcome install(CliDependency dep, SkillStore store, String skillName)
            throws IOException {
        if (alreadyProvided(dep, store)) return InstallOutcome.ALREADY_PRESENT;
        String pkg = dep.packageRef();
        if (pkg == null || pkg.isBlank()) throw new IOException("pip: spec missing package name (pip:<package>)");
        Fs.ensureDir(store.cliBinDir());
        Fs.ensureDir(store.venvsDir());

        PackageManagerRuntime pm = new PackageManagerRuntime(store);
        if (pm.bundledPath("uv") == null) {
            Log.step("pip: bootstrapping bundled uv@%s", PackageManager.UV.defaultVersion);
        }
        String uv = pm.ensureBundled("uv");

        // Shared content-addressed store first, then the two per-home install
        // targets. The order is the classification: everything from
        // PackageCaches is append-only and global; UV_TOOL_DIR / UV_TOOL_BIN_DIR
        // are mutable roots this home owns and must never be shared, so they
        // are set here and deliberately not in PackageCaches.sharedEnv.
        Map<String, String> env = new LinkedHashMap<>(PackageCaches.sharedEnvEnsured(store.venvsDir()));
        env.put("UV_TOOL_BIN_DIR", store.cliBinDir().toString());
        env.put("UV_TOOL_DIR", store.venvsDir().toString());
        Shell.mustWithEnv(List.of(uv, "tool", "install", "--force", pkg), env);
        Log.ok("cli: installed %s via uv tool (bin=%s)", pkg, store.cliBinDir());
        return InstallOutcome.INSTALLED;
    }

    /**
     * {@code pip-v1} over the declared spec and, when this home actually holds
     * the tool, the version uv resolved into {@code venvs/<name>}.
     *
     * <h2>Why the resolved version is read from disk and not from the lock</h2>
     *
     * <p>The obvious source is {@code RequestedVersion.of(dep).version()}, and
     * it is the wrong one. That is the REQUESTED version, and for any range
     * spec it is deliberately null — {@code pip:ruff&gt;=0.6} pins nothing, so
     * there is nothing to record. A fingerprint built on it would be constant
     * across every upgrade the range permits, which is precisely the "reports a
     * stale artifact as current" failure this ticket exists to close. (The
     * epic's backlog carries this as a finding against
     * {@code RequestedVersion.fromPip}, found as a mutant no test killed;
     * {@code RequestedVersionTest} now kills it. Nothing here depends on it
     * either way.)
     *
     * <p>So the resolved half comes from the {@code .dist-info} directory uv
     * wrote into the tool venv, which names the version actually installed. The
     * declared half is the verbatim spec, which carries the extras and the
     * operator. A pinned spec with no venv still fingerprints — its declared
     * input genuinely is the pin — and the {@link Fingerprint#basis} records
     * which of the two halves the digest covers, because they answer different
     * questions: the declared half detects "the manifest moved", the resolved
     * half detects "what I installed moved".
     */
    @Override
    public Fingerprint fingerprint(CliDependency dep, SkillStore store, String unitName) {
        String pkg = dep.packageRef();
        if (pkg == null || pkg.isBlank()) {
            return Fingerprint.gap("pip " + dep.name() + " has no package in its spec "
                    + "(expected pip:<package>), so nothing declares what would be installed");
        }
        String resolved = resolvedVersion(store, pkg);
        String digest = Fingerprints.scheme("pip-v1")
                .field("package", pkg)
                .field("resolved", resolved)
                .hex();
        return Fingerprint.over(digest, resolved != null
                ? "declared spec + version " + resolved + " resolved into venvs/"
                        + distributionName(pkg)
                : "declared spec only — this home holds no venvs/" + distributionName(pkg)
                        + ", so the installed version is not observable here");
    }

    /**
     * The version in {@code venvs/<dist>/lib/*}{@code /site-packages/<dist>-<v>.dist-info},
     * or null when this home does not hold the tool venv — which is the normal
     * state for a dep {@code CliPresence} found already provided by the machine,
     * and for a clone that skipped {@code venvs/}.
     */
    private static String resolvedVersion(SkillStore store, String packageRef) {
        String dist = distributionName(packageRef);
        if (dist.isEmpty()) return null;
        Path lib = store.venvsDir().resolve(dist).resolve("lib");
        if (!Files.isDirectory(lib)) return null;
        // <venv>/lib/python3.13/site-packages on posix, <venv>/Lib/site-packages
        // on Windows: walk shallowly rather than guessing the interpreter dir.
        try (var levels = Files.walk(lib, 2)) {
            for (Path candidate : levels.filter(p -> p.getFileName() != null
                    && "site-packages".equals(p.getFileName().toString())).toList()) {
                String version = versionFromDistInfo(candidate, dist);
                if (version != null) return version;
            }
        } catch (IOException unreadable) {
            return null;
        }
        return null;
    }

    private static String versionFromDistInfo(Path sitePackages, String dist) {
        // PEP 427 escapes the distribution name with underscores in the
        // .dist-info directory name: jinja2-cli -> jinja2_cli-0.8.2.dist-info.
        String prefix = dist.replace('-', '_') + "-";
        try (var entries = Files.list(sitePackages)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!name.endsWith(".dist-info")) continue;
                if (!name.toLowerCase(Locale.ROOT).startsWith(prefix)) continue;
                return name.substring(prefix.length(), name.length() - ".dist-info".length());
            }
        } catch (IOException unreadable) {
            return null;
        }
        return null;
    }

    /**
     * The PEP 503-normalized distribution name in a pip spec: everything before
     * the extras bracket or the first version operator, lowercased with runs of
     * {@code -_.} collapsed to {@code -}. {@code jinja2-cli[yaml]==0.8.2} is
     * {@code jinja2-cli}, which is both the uv tool directory name and the stem
     * of its {@code .dist-info}.
     */
    static String distributionName(String packageRef) {
        if (packageRef == null) return "";
        int end = packageRef.length();
        for (int i = 0; i < packageRef.length(); i++) {
            char c = packageRef.charAt(i);
            if (c == '[' || c == '=' || c == '<' || c == '>' || c == '~' || c == '!'
                    || c == ';' || c == ' ' || c == '@') {
                end = i;
                break;
            }
        }
        String name = packageRef.substring(0, end).trim().toLowerCase(Locale.ROOT);
        return name.replaceAll("[-_.]+", "-");
    }
}
