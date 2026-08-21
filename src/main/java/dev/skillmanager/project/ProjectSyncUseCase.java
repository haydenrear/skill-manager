package dev.skillmanager.project;

import dev.skillmanager.bindings.ChildHomeHarnessInstaller;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.store.DriftGate;
import dev.skillmanager.store.DriftReport;
import dev.skillmanager.store.HomeDigest;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Brings a project's realization up to date with its units' trunks.
 *
 * <h2>What it used to be</h2>
 *
 * <p>A placeholder: it tore the realization down and re-resolved it from the same
 * local store, which produced the content it already had. It never asked a unit's
 * repository whether the unit had moved, so there was no way to say "get the
 * current version of these skills" — and it paid for the illusion with a
 * whole-tree rollback snapshot and a full teardown on every call.
 *
 * <h2>What it is</h2>
 *
 * <p>Two phases, both of which can be skipped, neither of which destroys:
 *
 * <ol>
 *   <li><b>Pull</b> ({@link UnitTrunkPull}) — fetch each unit's trunk and
 *       three-way merge it into wherever that unit's history lives. Units with
 *       uncommitted local changes are held back unless {@code --merge}.</li>
 *   <li><b>Reconcile</b> — re-resolve. This is already an incremental,
 *       idempotent, hold-back-respecting operation: {@code ProjectDependencyResolver}
 *       reconciles bindings against the previous lock and
 *       {@code ProjectChildHomeScaffolder} refuses to overwrite or prune a
 *       locally-modified child unit. Re-resolving is therefore all a sync needs,
 *       and it is what removes the teardown.</li>
 * </ol>
 *
 * <h2>Why the teardown is now opt-in</h2>
 *
 * <p>{@code --rebuild} keeps the old behaviour for a realization that is actually
 * broken (a half-written binding, a projection pointing somewhere stale). It is
 * not the default because a teardown is the most destructive thing this code can
 * do: it is the only path that removes child-home units at all, and it needs a
 * full snapshot of the tree to be able to undo itself. Making the common case the
 * one that touches nothing means the common case cannot lose anything.
 */
public final class ProjectSyncUseCase {

    private final SkillStore store;
    private final GatewayConfig gateway;

    public ProjectSyncUseCase(SkillStore store, GatewayConfig gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    /**
     * @param pull fetch and merge each unit's trunk before reconciling
     * @param rebuild tear the realization down and rebuild it, instead of
     *        reconciling in place
     */
    public record Options(boolean pull, boolean rebuild, UnitTrunkPull.Options pullOptions) {
        public Options {
            pullOptions = pullOptions == null ? UnitTrunkPull.Options.defaults() : pullOptions;
        }

        /** Pull the trunk, reconcile in place, hold back local edits. */
        public static Options defaults() {
            return new Options(true, false, UnitTrunkPull.Options.defaults());
        }

        /** Reconcile from the parent store's already-selected bytes without pulling a trunk. */
        public static Options reconcileOnly() {
            return new Options(false, false, UnitTrunkPull.Options.defaults());
        }

        /** The pre-#8 behaviour, for a realization that needs rebuilding. */
        public static Options rebuildOnly() {
            return new Options(false, true, UnitTrunkPull.Options.defaults());
        }
    }

    public record Result(
            ProjectDependencyResolver.Result resolved,
            int bindingsRemoved,
            List<Path> clearedPaths,
            UnitTrunkPull.Report pull,
            boolean rebuilt,
            DriftGate drift
    ) {
        public Result {
            clearedPaths = clearedPaths == null ? List.of() : List.copyOf(clearedPaths);
            pull = pull == null ? new UnitTrunkPull.Report(List.of()) : pull;
        }

        public Result(ProjectDependencyResolver.Result resolved, int bindingsRemoved,
                      List<Path> clearedPaths, UnitTrunkPull.Report pull, boolean rebuilt) {
            this(resolved, bindingsRemoved, clearedPaths, pull, rebuilt, null);
        }

        public String mode() { return rebuilt ? "rebuild" : "pull-reconcile"; }

        /** The drift this sync produced, which a launch will now insist is read. */
        public DriftReport driftReport() {
            return drift == null ? new DriftReport(null, null, List.of()) : drift.report();
        }
    }

    public Result sync(SkillProject project, ProjectDependencyResolver.Options options)
            throws IOException {
        return sync(project, options, Options.defaults());
    }

    public Result sync(SkillProject project, ProjectDependencyResolver.Options options,
                       Options syncOptions) throws IOException {
        if (project == null) throw new IllegalArgumentException("project must not be null");
        // A sync mutates the home in place, so a frozen home refuses it before
        // anything is fetched, merged, torn down, or written.
        dev.skillmanager.policy.HomePolicy.requireLive(store, "project sync");
        store.init();
        ProjectDependencyResolver.Options opts = options == null
                ? ProjectDependencyResolver.Options.defaults()
                : options;
        Options sync = syncOptions == null ? Options.defaults() : syncOptions;

        SkillProjectLock previousLock = new SkillProjectLockStore(store)
                .read(project.registryName())
                .orElse(null);

        // Captured before anything moves, and compared afterwards. The baseline is
        // whatever was last recorded when one exists, so drift is measured from the
        // state somebody last acknowledged rather than from the start of this call
        // — a change made by a previous unacknowledged sync must not be forgotten
        // just because this one is being measured fresh.
        HomeDigest before = HomeDigest.read(store).orElseGet(() -> {
            try {
                return HomeDigest.compute(store);
            } catch (IOException uncomputable) {
                return null;
            }
        });

        UnitTrunkPull.Report pull = sync.pull()
                ? pullTrunks(project, previousLock, sync.pullOptions())
                : new UnitTrunkPull.Report(List.of());

        Result result = sync.rebuild()
                ? rebuild(project, previousLock, opts, pull)
                : reconcile(project, opts, pull);

        DriftGate gate = DriftGate.recordSince(store, before, "project sync").orElse(null);
        if (gate != null) {
            dev.skillmanager.store.HomeDescriptor.CliSpelling spelling =
                    dev.skillmanager.store.HomeDescriptor.cliSpelling(store.root());
            String cli = spelling.command();
            Log.warn("this sync changed %d unit(s) in %s — a launch will refuse until the change "
                            + "is read (`%s home drift`, then `%s home drift --ack`)",
                    gate.report().units().size(), store.root(), cli, cli);
            for (String line : gate.report().render()) Log.warn("  %s", line);
            if (spelling.caveat() != null) Log.warn("  note: %s", spelling.caveat());
        }
        return new Result(result.resolved(), result.bindingsRemoved(), result.clearedPaths(),
                result.pull(), result.rebuilt(), gate);
    }

    /**
     * Reconcile in place: no teardown, no snapshot, no rollback window.
     *
     * <p>{@code resolve} is idempotent and already refuses to overwrite or prune a
     * child unit the agent edited, so running it is the reconciliation. Nothing
     * here needs to be undone if it fails part way, which is why there is no
     * snapshot: the failure mode of a partially reconciled realization is
     * "re-run it", not "restore it".
     */
    private Result reconcile(SkillProject project, ProjectDependencyResolver.Options opts,
                            UnitTrunkPull.Report pull) throws IOException {
        // The one thing a failed reconcile must still undo. `resolve` writes the
        // registration snapshot — the record of the project's declared intent —
        // before it can discover that the manifest names something uninstallable,
        // so a failure would otherwise leave a broken manifest recorded as this
        // project's intent and every later command reading it. Restoring one small
        // file is not the whole-tree snapshot the rebuild path needs; that cost is
        // exactly what reconciling in place exists to avoid.
        RegistrationSnapshot registration = RegistrationSnapshot.capture(store, project);
        try {
            ProjectDependencyResolver.Result resolved =
                    new ProjectDependencyResolver(store, gateway).resolve(project, opts);
            return new Result(resolved, 0, List.of(), pull, false);
        } catch (IOException | RuntimeException ex) {
            registration.restore(ex);
            throw ex;
        }
    }

    /** The bytes of one project's registration snapshot, and how to put them back. */
    private record RegistrationSnapshot(Path file, byte[] before, boolean existed) {

        static RegistrationSnapshot capture(SkillStore store, SkillProject project) {
            Path file = store.projectsDir()
                    .resolve(project.registryName())
                    .resolve(project.manifestPath().getFileName().toString());
            try {
                return java.nio.file.Files.isRegularFile(file)
                        ? new RegistrationSnapshot(file, java.nio.file.Files.readAllBytes(file), true)
                        : new RegistrationSnapshot(file, null, false);
            } catch (IOException unreadable) {
                return new RegistrationSnapshot(file, null, false);
            }
        }

        void restore(Exception failure) {
            try {
                if (existed) {
                    java.nio.file.Files.write(file, before);
                } else if (java.nio.file.Files.exists(file)) {
                    java.nio.file.Files.delete(file);
                }
            } catch (IOException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
        }
    }

    /** The pre-#8 teardown-and-rebuild, behind {@code --rebuild}. */
    private Result rebuild(SkillProject project, SkillProjectLock previousLock,
                           ProjectDependencyResolver.Options opts, UnitTrunkPull.Report pull)
            throws IOException {
        ProjectRealizationSnapshot snapshot =
                ProjectRealizationSnapshot.capture(store, project, previousLock);
        try {
            ProjectRemoveUseCase.Result removed = new ProjectRemoveUseCase(store, gateway)
                    .removeRealization(project, previousLock, true, true);
            ProjectDependencyResolver.Result resolved = new ProjectDependencyResolver(store, gateway)
                    .resolve(project, opts);
            return new Result(resolved, removed.bindingsRemoved(), removed.clearedPaths(), pull, true);
        } catch (IOException | RuntimeException ex) {
            restoreAndRethrow(snapshot, ex);
            throw ex;
        } finally {
            closeQuietly(snapshot);
        }
    }

    /**
     * Pull the trunk for every unit the previous lock resolved.
     *
     * <p>Driven off the previous lock rather than the manifest because the lock is
     * the closure that is actually installed, transitive dependencies included —
     * and a transitively-pulled-in skill is exactly the kind of thing that moves
     * without anyone in this project noticing. A project with no lock yet has
     * nothing to pull; the resolve that follows installs it at its current trunk
     * anyway.
     */
    private UnitTrunkPull.Report pullTrunks(SkillProject project, SkillProjectLock previousLock,
                                            UnitTrunkPull.Options pullOptions) throws IOException {
        if (previousLock == null || previousLock.resolvedUnits().isEmpty()) {
            return new UnitTrunkPull.Report(List.of());
        }
        ChildHomeHarnessInstaller.Layout layout = ProjectChildHomeScaffolder.layoutFor(project);
        SkillStore childStore = new SkillStore(layout.childSkillManagerHome());
        UnitTrunkPull.Report report = new UnitTrunkPull(store, gateway)
                .pull(previousLock.resolvedUnits(), childStore, pullOptions);
        for (UnitTrunkPull.UnitPull unit : report.changed()) {
            Log.ok("pulled %s — %s", unit.label(), unit.detail());
        }
        return report;
    }

    private void restoreAndRethrow(
            ProjectRealizationSnapshot snapshot,
            Exception failure
    ) throws IOException {
        try {
            snapshot.restore();
        } catch (IOException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new IOException(failure);
    }

    private static void closeQuietly(ProjectRealizationSnapshot snapshot) {
        try {
            snapshot.close();
        } catch (IOException ignored) {
            // Rollback snapshots live in a temp directory; cleanup must not hide
            // the project sync failure or success that preceded it.
        }
    }
}
