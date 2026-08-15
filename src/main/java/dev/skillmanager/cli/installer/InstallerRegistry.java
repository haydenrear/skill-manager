package dev.skillmanager.cli.installer;

import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.store.HomeLinks;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InstallerRegistry {

    private final Map<String, InstallerBackend> backends = new LinkedHashMap<>();

    public InstallerRegistry() {
        register(new TarBackend());
        register(new PipBackend());
        register(new NpmBackend());
        register(new BrewBackend());
        register(new SkillScriptBackend());
    }

    public void register(InstallerBackend backend) {
        backends.put(backend.id(), backend);
    }

    public InstallerBackend get(String id) {
        return backends.get(id);
    }

    /**
     * Every registered backend id, in registration order. The set of backends
     * is decided here and nowhere else, so a test that must cover "all of them"
     * asks rather than repeating the list — a repeated list is what let one
     * backend keep a fingerprint scheme the other four did not have.
     */
    public java.util.Set<String> registeredIds() {
        return java.util.Collections.unmodifiableSet(backends.keySet());
    }

    /**
     * Ask {@code dep}'s backend to fingerprint its declared inputs.
     *
     * <p>This exists so that no caller has to hold a backend id in order to get
     * a fingerprint. It replaces
     * {@code "skill-script".equals(dep.backend()) ? SkillScriptBackend.fingerprintFor(…) : null},
     * which lived in {@link dev.skillmanager.lock.CliInstallRecorder} and — as a
     * second, separately maintained copy of the same branch — in
     * {@code LiveInterpreter.runCliInstall}. Both are now this one call, and the
     * set of backends that produce a fingerprint is exactly the set that
     * implements {@link InstallerBackend}, which is the set this registry
     * already decides.
     *
     * <p>Never returns null and never throws: an unknown backend and a backend
     * that threw are both {@link Fingerprint#gap}s that name themselves. A
     * failure to describe an install must not fail an install that succeeded.
     */
    public Fingerprint fingerprintFor(CliDependency dep, SkillStore store, String unitName) {
        InstallerBackend backend = backends.get(dep.backend());
        if (backend == null) {
            return Fingerprint.gap("no installer backend registered for '" + dep.backend()
                    + "' (supported: " + backends.keySet() + ")");
        }
        try {
            Fingerprint fingerprint = backend.fingerprint(dep, store, unitName);
            return fingerprint != null ? fingerprint
                    : Fingerprint.gap("backend " + backend.id() + " returned no fingerprint for "
                            + dep.name());
        } catch (RuntimeException e) {
            return Fingerprint.gap("backend " + backend.id() + " could not fingerprint "
                    + dep.name() + ": " + e);
        }
    }

    /** Install every CLI dep declared by every skill, preserving skill-name context for per-skill isolation. */
    public void installFor(List<Skill> skills, SkillStore store) throws IOException {
        Fs.ensureDir(store.cliBinDir());
        for (Skill s : skills) {
            for (CliDependency dep : s.cliDependencies()) {
                installOne(dep, store, s.name());
            }
        }
    }

    public InstallOutcome installOne(CliDependency dep, SkillStore store, String skillName)
            throws IOException {
        return installOne(dep, store, skillName, false);
    }

    public InstallOutcome installOne(CliDependency dep, SkillStore store, String skillName,
                                     boolean force) throws IOException {
        String id = dep.backend();
        InstallerBackend backend = backends.get(id);
        if (backend == null) {
            Log.warn("cli: unknown backend '%s' for %s (supported: %s)", id, dep.name(), backends.keySet());
            return InstallOutcome.SKIPPED;
        }
        if (!backend.available()) {
            Log.warn("cli: backend %s not available on this host; skipping %s", id, dep.name());
            return InstallOutcome.SKIPPED;
        }
        InstallOutcome outcome = backend.install(dep, store, skillName, force);
        // Backends we do not control write absolute symlinks into bin/cli:
        // `uv tool install` and `npm -g` both do, and neither has a flag for
        // it. An absolute link into the home is the one thing a copy of the
        // home cannot survive, so normalize after every install rather than
        // patching each backend and hoping the next one remembers.
        HomeLinks.relativizeShims(store);
        return outcome == null ? InstallOutcome.INSTALLED : outcome;
    }
}
