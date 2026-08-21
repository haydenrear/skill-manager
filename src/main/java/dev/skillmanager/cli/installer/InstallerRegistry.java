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
     * Take ownership of {@code dep}'s bin/cli slot when THIS home is rebuilding
     * the artifact, so the producer writes a real file here instead of through
     * an inherited symlink into another home.
     *
     * <h2>Called from the REBUILD path only, and that is the whole design</h2>
     *
     * <p>A cloned home inherits {@code bin/cli/<name>} as an absolute symlink
     * into its parent. That is deliberate: they are the parent's artifacts, the
     * agent needs them on PATH, and building artifacts nobody changed is waste.
     * A routine {@code sync} must therefore leave them exactly alone.
     *
     * <p>A REBUILD is the opposite signal. It happens because the agent changed
     * the unit, so this home now has a reason to own the artifact, and
     * replacing the inherited link is the point rather than a side effect.
     * {@link dev.skillmanager.effects.LiveInterpreter#rebuildCliArtifact} is
     * the one caller.
     *
     * <p>An earlier version of this ran from {@code installOne}, which every
     * sync reaches: {@code PlanBuilder.addCli} emits a {@code RunCliInstall}
     * for every declared dep on every pass, so one {@code sync} in a child home
     * would have deleted all five inherited shims and re-provisioned five local
     * venvs — defeating {@code ChildHomeMaterializer.mirrorExistingShim}, whose
     * documented purpose is that the child SHARES the toolchain its parent
     * provisioned. It would also have disagreed with {@code CliShimPruner},
     * which asks {@code HomeCloner.unsanctionedForeignHome} precisely so it does
     * not touch this shape. Two readers of one rule, again.
     *
     * <h2>Restored if the producer fails</h2>
     *
     * <p>Deleting and then failing leaves the home strictly worse than before —
     * the tool is gone where a working parent-owned one stood, and the failure
     * is swallowed by the bulk install path. So the link is recreated when the
     * producer does not replace it.
     *
     * @return what was removed, for {@link #restoreForeignShim} to put back
     */
    public static Path takeOwnershipOfShim(CliDependency dep, SkillStore store) {
        if (dep == null || store == null) return null;
        // dep.onPath() only, and NOT dep.name(): CliPresence.providedByThisHome
        // consults the name for `tar` alone, and CliPresence's own javadoc
        // spends 25 lines on the measurement showing why widening that fallback
        // to the other backends was harmful. Producers write on_path.
        String spelling = "tar".equals(dep.backend()) && dep.onPath() == null
                ? dep.name() : dep.onPath();
        if (spelling == null || spelling.isBlank() || spelling.contains("/")) return null;
        Path at = store.cliBinDir().resolve(spelling);
        if (!Files.isSymbolicLink(at)) return null;
        Path foreign = CliArtifact.foreignHomeReachedBy(at, store.root());
        if (foreign == null) return null;
        Path target;
        try {
            target = Files.readSymbolicLink(at);
            Files.delete(at);
        } catch (IOException notOurs) {
            Log.warn("cli: could not take ownership of the shim at %s, which resolves into the "
                    + "home at %s; the producer may write through it into that home (%s)",
                    at, foreign, notOurs.getMessage());
            return null;
        }
        // Log.info, not Log.detail: CliShimPruner logs its equivalent deletion
        // at info, and a deletion nobody sees at default verbosity is how this
        // becomes invisible damage.
        Log.info("cli: %s was this home's link into %s; rebuilding it here, so this home owns it",
                spelling, foreign);
        return target;
    }

    /** Put back what {@link #takeOwnershipOfShim} removed, if nothing replaced it. */
    public static void restoreForeignShim(CliDependency dep, SkillStore store, Path removed) {
        if (removed == null || dep == null || store == null) return;
        String spelling = "tar".equals(dep.backend()) && dep.onPath() == null
                ? dep.name() : dep.onPath();
        if (spelling == null || spelling.isBlank() || spelling.contains("/")) return;
        Path at = store.cliBinDir().resolve(spelling);
        if (Files.exists(at, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;   // producer wrote
        try {
            Files.createSymbolicLink(at, removed);
            Log.warn("cli: the rebuild of %s produced nothing, so its link into the parent store "
                    + "was put back — this home still does not own that artifact", spelling);
        } catch (IOException cannot) {
            Log.warn("cli: the rebuild of %s produced nothing and its link could not be restored "
                    + "(%s); the tool is missing from this home", spelling, cannot.getMessage());
        }
    }
}
