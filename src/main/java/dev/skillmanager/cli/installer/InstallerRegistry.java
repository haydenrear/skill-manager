package dev.skillmanager.cli.installer;

import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.store.HomeLinks;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        // HIS-7. A shim at this dep's path that resolves into ANOTHER home is
        // cleared before the producer runs, and this is a data-integrity guard
        // rather than a tidiness one.
        //
        // MEASURED, and it wrote into the operator's root home twice before it
        // was noticed. A cloned home inherits `bin/cli/<name>` as an absolute
        // symlink into its parent -- by design, they are the parent's artifacts
        // and the agent needs them on PATH. Producers then write with
        // `cat > "$SKILL_MANAGER_BIN_DIR/<name>"`, and `cat >` FOLLOWS A
        // SYMLINK. So a `build` inside the clone did not write the clone's
        // shim; it overwrote the PARENT's, with a wrapper containing the
        // CLONE's absolute paths:
        //
        //   ~/.skill-manager/bin/cli/computeq  ->  exec "/private/tmp/.../clone
        //   -probe/.skill-manager/cache/.../venv/bin/computeq"
        //
        // A child home mutating its parent's bytes is the one thing this
        // epic's baseline rule exists to prevent, and it happened through a
        // path nobody was checking. `tlc2` survived only because its wrapper
        // happens to be BIN_DIR-relative -- luck, not design.
        //
        // Placed HERE, at the single dispatch, for the same reason
        // relativizeShims sits just below: "normalize after every install
        // rather than patching each backend and hoping the next one
        // remembers". Four backends write into bin/cli by four different
        // mechanisms and the guard must not depend on any of them.
        clearForeignShims(dep, store);
        InstallOutcome outcome = backend.install(dep, store, skillName, force);
        // Backends we do not control write absolute symlinks into bin/cli:
        // `uv tool install` and `npm -g` both do, and neither has a flag for
        // it. An absolute link into the home is the one thing a copy of the
        // home cannot survive, so normalize after every install rather than
        // patching each backend and hoping the next one remembers.
        HomeLinks.relativizeShims(store);
        return outcome == null ? InstallOutcome.INSTALLED : outcome;
    }

    /**
     * Remove any entry at {@code dep}'s bin/cli path that resolves into a
     * different Skill Manager home, so the producer writes a real file here.
     *
     * <p>Deletes only a SYMLINK, and only one whose resolved target lies in
     * another home. A regular file is this home's own artifact and is the
     * producer's to overwrite; a link inside this home is fine; a link to a
     * system tool outside every home belongs to
     * {@link CliPresence#providedOutsideEveryHome} and is left alone.
     *
     * <p>Both spellings are cleared, {@code on_path} and {@code name}, because
     * {@link CliPresence#providedByThisHome} consults both and a producer may
     * write either.
     */
    private static void clearForeignShims(CliDependency dep, SkillStore store) {
        if (dep == null || store == null) return;
        for (String spelling : new String[]{dep.onPath(), dep.name()}) {
            if (spelling == null || spelling.isBlank() || spelling.contains("/")) continue;
            Path at = store.cliBinDir().resolve(spelling);
            if (!Files.isSymbolicLink(at)) continue;
            Path foreign = CliArtifact.foreignHomeReachedBy(at, store.root());
            if (foreign == null) continue;
            try {
                Files.delete(at);
                Log.detail("cli: cleared %s, which resolved into the home at %s — "
                        + "this home builds its own", spelling, foreign);
            } catch (IOException notOurs) {
                Log.warn("cli: could not clear the shim at %s, which resolves into the home "
                        + "at %s; the producer may write through it into that home (%s)",
                        at, foreign, notOurs.getMessage());
            }
        }
    }
}
