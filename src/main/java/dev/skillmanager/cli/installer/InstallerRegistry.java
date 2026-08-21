package dev.skillmanager.cli.installer;

import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.store.HomeLinks;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.store.WriteConfinement;
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

    /**
     * Install one dep, with the producer boundary confined to this home.
     *
     * <h2>Why the confinement is HERE and not inside the producer</h2>
     *
     * <p>{@code backend.install} forks. A {@code skill-script} is an arbitrary
     * script this codebase did not write, handed {@code $SKILL_MANAGER_BIN_DIR}
     * and {@code $SKILL_MANAGER_HOME}, and no in-JVM hook can see a single byte
     * it writes. So confinement is expressed as the only two things this side of
     * the fork can actually assert:
     *
     * <ol>
     *   <li><b>Before.</b> The destination handed to the producer must resolve
     *       inside this home. {@code $SKILL_MANAGER_BIN_DIR} pointing at another
     *       home's {@code bin/cli} is DEF-007's shape on the write path, and a
     *       producer given it will write there faithfully and successfully.</li>
     *   <li><b>After.</b> The artifact the producer claims to have made must
     *       resolve inside this home. This is the HIS-7 escape stated as a
     *       postcondition rather than as one call site's fix: the producer's
     *       {@code cat > "$SKILL_MANAGER_BIN_DIR/<n>"} FOLLOWS an inherited
     *       symlink and lands in the parent store, and the install then reports
     *       success about a file this home does not hold. Measured twice on the
     *       operator's real root home.</li>
     * </ol>
     *
     * <p>The after-check cannot un-write bytes that are already written, and
     * this file does not pretend otherwise. What it does is refuse the install
     * that escaped, name the path and the home, and keep the lock from recording
     * an artifact that lives somewhere else — instead of the previous behaviour,
     * which was to report the install green.
     *
     * <h2>The scope an outer caller declared WIDENS this, it does not replace it</h2>
     *
     * <p>When an effect has declared a
     * {@link dev.skillmanager.store.WriteConfinement.Scope} — see
     * {@code SkillEffect.writeConfinement} — that scope is used, so an effect
     * that legitimately writes under a second root can say so and be reviewed
     * for it. When nothing declared one, this method declares this home, so
     * calling the registry directly is confined too.
     */
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
        WriteConfinement.Scope outer = WriteConfinement.declared();
        WriteConfinement.Scope scope = outer.unconfined()
                ? WriteConfinement.forHome(store.root(), "cli install of " + dep.name())
                : outer;
        WriteConfinement.Scope previous = WriteConfinement.declare(scope);
        InstallOutcome outcome;
        Path inherited = null;
        try {
            // The destination, before the fork. Unconditional, because this is
            // the check that keeps bytes from EVER reaching the wrong home
            // rather than noticing afterwards that they did.
            WriteConfinement.requireContainerInside(store.cliBinDir(), store.root(),
                    "the bin/cli handed to the " + id + " backend");
            refuseAForeignDestination(dep, store, id);
            inherited = force ? takeOwnershipOfShim(dep, store) : null;
            try {
                outcome = backend.install(dep, store, skillName, force);
            } finally {
                if (inherited != null) restoreForeignShim(dep, store, inherited);
            }
            // Backends we do not control write absolute symlinks into bin/cli:
            // `uv tool install` and `npm -g` both do, and neither has a flag for
            // it. An absolute link into the home is the one thing a copy of the
            // home cannot survive, so normalize after every install rather than
            // patching each backend and hoping the next one remembers.
            HomeLinks.relativizeShims(store);
            requireProducedInThisHome(dep, store, id);
        } finally {
            WriteConfinement.restore(previous);
        }
        return outcome == null ? InstallOutcome.INSTALLED : outcome;
    }

    /**
     * Refuse before the fork when the slot a producer is about to write is a
     * link into a home this one has no sanction to share with.
     *
     * <h2>Refused, not repaired, and the asymmetry is deliberate</h2>
     *
     * <p>Two different shapes end up in the same slot, and treating them alike
     * is how either the damage or the sharing comes back:
     *
     * <ul>
     *   <li>A <b>sanctioned mirror</b> — a child home's link at its parent's
     *       {@code bin/cli/<name>}, which {@code HomeCloner} recognises through
     *       a live claim or a descent record. That is not a leak, it is the
     *       point of a child home. It is left exactly alone here; on a routine
     *       pass the backend reports it already present and writes nothing.</li>
     *   <li>An <b>unsanctioned</b> link into some other home. A producer handed
     *       that slot writes the OTHER home's bytes, because {@code cat >} and
     *       {@code Files.writeString} both follow the link. That is the HIS-7
     *       escape, measured twice on the operator's real root home. This is a
     *       corrupt home, and the thing that repairs it is {@code sync}'s prune
     *       — not an install quietly rewriting somebody else's file first. So
     *       it is refused with the path and the home named.</li>
     * </ul>
     *
     * <p>Under {@code force} the caller has said this home has a reason to own
     * the artifact, which is the same signal a rebuild carries, so the slot is
     * taken ownership of instead (and restored if the producer fails) — HIS-7's
     * mechanism, reused rather than copied. That covers the one case this method
     * deliberately walks past: a FORCED run over a sanctioned mirror, which
     * would otherwise write through into the parent store.
     */
    private static void refuseAForeignDestination(CliDependency dep, SkillStore store, String id) {
        for (String spelling : producedSpellings(dep)) {
            Path slot = store.cliBinDir().resolve(spelling);
            if (!Files.isSymbolicLink(slot)) continue;
            if (dev.skillmanager.store.HomeCloner.unsanctionedForeignHome(
                    "bin/cli/" + spelling, slot, store.root()) == null) continue;
            WriteConfinement.checkWrite(slot,
                    "the destination handed to the " + id + " backend for " + dep.name());
        }
    }

    /**
     * Refuse when the artifact this install claims to have produced resolves
     * outside the declared roots.
     *
     * <p>Every spelling the dep could have landed as is checked — the declared
     * {@code on_path}, and each backend's declared {@code binary} — because a
     * dep's manifest name and the file it lands as differ often enough that
     * reading only one of them would check the wrong path and pass. That is the
     * same four-spelling problem {@code CliShimPruner.declaredArtifactNames} and
     * {@code CliDependencyCleaner} both solve, and it is solved the same way
     * here on purpose.
     *
     * <p>Uses the WRITE rule ({@link WriteConfinement#checkWrite}), which
     * follows the final component, because that is what the producer's
     * redirection did.
     */
    private static void requireProducedInThisHome(CliDependency dep, SkillStore store, String id) {
        for (String spelling : producedSpellings(dep)) {
            Path produced = store.cliBinDir().resolve(spelling);
            if (!Files.exists(produced, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            // THE TWO EXEMPTIONS, AND THEY ARE NOT NEW ONES. Asking
            // HomeCloner.unsanctionedForeignHome rather than spelling a second
            // rule means this refuses exactly what `home verify` refuses:
            //
            //   * a SANCTIONED MIRROR is exempt. It still points into the parent
            //     store after this pass because nothing wrote it -- the backend
            //     found it present and declined. Refusing here would refuse the
            //     sharing arrangement itself, on every sync of every child home.
            //   * a path OUTSIDE EVERY HOME is exempt: brew links its cellar
            //     into bin/cli, and CliPresence.providedOutsideEveryHome already
            //     owns the "this is a system tool" case. A guard that refused
            //     those would break every brew-backed dep in every home.
            //
            // What is left is the one thing this ticket is about: an artifact
            // that reaches into ANOTHER Skill Manager home with nothing
            // sanctioning it.
            if (dev.skillmanager.store.HomeCloner.unsanctionedForeignHome(
                    "bin/cli/" + spelling, produced, store.root()) == null) continue;
            WriteConfinement.checkWrite(produced,
                    "the " + id + " backend's artifact for " + dep.name());
        }
    }

    /** The bin/cli basenames one dep could legitimately have landed as. */
    private static java.util.Set<String> producedSpellings(CliDependency dep) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        addSpelling(out, dep.onPath());
        for (CliDependency.InstallTarget target : dep.install().values()) {
            addSpelling(out, target.binary());
        }
        return out;
    }

    private static void addSpelling(java.util.Set<String> out, String value) {
        if (value == null || value.isBlank() || value.contains("/")) return;
        out.add(value);
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
        // DEF-007's SECOND CALL SITE, and HIS-10 is what made it reachable.
        //
        // `Files.delete(at)` below removes the LINK rather than its target, so
        // this method looks safe — and is, as long as `at` is spelled through
        // directories that are really this home's. Where bin/cli is ITSELF a
        // link at another home's bin/cli, `cliBinDir().resolve(spelling)`
        // resolves into that home and this deletes the other home's entry.
        //
        // Before HIS-10 an inherited link was pruned on a clone's first sync, so
        // it never survived long enough to reach a rebuild. It now survives
        // every sync — which is the point of that ticket — and therefore reaches
        // here. The confinement is asked BEFORE the isSymbolicLink probe because
        // that probe follows bin/cli too, and a question asked through the wrong
        // home is answered about the wrong home.
        WriteConfinement.requireInside(at, store.root(),
                "taking ownership of bin/cli/" + spelling);
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
