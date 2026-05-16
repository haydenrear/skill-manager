package dev.skillmanager.commands;

import dev.skillmanager.effects.UnitReadProblemReporter;
import dev.skillmanager.lock.LockDiff;
import dev.skillmanager.lock.LockedUnit;
import dev.skillmanager.lock.UnitsLock;
import dev.skillmanager.lock.UnitsLockReader;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.store.UnitReadProblem;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager lock} — group for lock-related read-only commands.
 * The mutating side (sync --lock / sync --refresh) lives on
 * {@link SyncCommand} so install-state-changing flags stay together.
 */
@Command(name = "lock",
        description = "Inspect units.lock.toml.",
        subcommands = { LockCommand.Status.class })
public final class LockCommand implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        // No subcommand → print help.
        spec.commandLine().usage(System.out);
        return 0;
    }

    /**
     * {@code skill-manager lock status} — diff units.lock.toml against the
     * live install set inferred from {@code installed/<name>.json}.
     *
     * <p>Drift buckets:
     * <ul>
     *   <li>"added in live": installed on disk but not in lock — somebody
     *       skipped {@code UpdateUnitsLock} (legacy install pre-ticket-10
     *       or a manual {@code skills/} edit).</li>
     *   <li>"removed from live": in lock but not on disk — store was
     *       trimmed without going through uninstall.</li>
     *   <li>"bumped": same name in both, fields differ — typically a sync
     *       advanced {@code resolved_sha} but the lock is stale.</li>
     * </ul>
     *
     * <p>Exits 0 when in sync, 1 on any drift, 2 on read errors.
     */
    @Command(name = "status",
            description = "Show drift between units.lock.toml and the live install set.")
    public static final class Status implements Callable<Integer> {

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() throws IOException {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            java.nio.file.Path lockPath = UnitsLockReader.defaultPath(store);
            UnitsLock lockOnDisk;
            try {
                lockOnDisk = UnitsLockReader.read(lockPath);
            } catch (IOException io) {
                Log.error("could not read %s: %s", lockPath, io.getMessage());
                return 2;
            }

            LiveStateResult live = readLiveState(store);
            UnitReadProblemReporter.render(store, live.problems(), false);
            UnitsLock liveState = live.lock();
            // Diff "from lock to live" — the lock is the recorded promise,
            // live is what's actually on disk. "added in live but not in
            // lock" surfaces as LockDiff.added(); "removed from live"
            // surfaces as LockDiff.removed(); etc.
            LockDiff drift = LockDiff.between(lockOnDisk, liveState);
            if (json) {
                if (!JsonOutput.print(new StatusResult(lockPath.toString(), liveState.units().size(),
                        drift.isEmpty(), drift))) return 2;
                return drift.isEmpty() ? 0 : 1;
            }
            if (drift.isEmpty()) {
                Log.ok("units.lock.toml is in sync with %s installed unit(s)",
                        liveState.units().size());
                return 0;
            }

            System.out.println("units.lock.toml drift:");
            if (!drift.added().isEmpty()) {
                System.out.println("  installed but not in lock:");
                for (LockedUnit u : drift.added()) {
                    System.out.printf("    + %s (%s%s)%n",
                            u.name(), u.kind().name().toLowerCase(),
                            u.version() == null ? "" : "@" + u.version());
                }
            }
            if (!drift.removed().isEmpty()) {
                System.out.println("  in lock but not installed:");
                for (LockedUnit u : drift.removed()) {
                    System.out.printf("    - %s (%s%s)%n",
                            u.name(), u.kind().name().toLowerCase(),
                            u.version() == null ? "" : "@" + u.version());
                }
            }
            if (!drift.bumped().isEmpty()) {
                System.out.println("  fields drifted (lock stale):");
                for (LockDiff.Bump b : drift.bumped()) {
                    System.out.printf("    * %s: %s → %s%n",
                            b.before().name(),
                            describe(b.before()), describe(b.after()));
                }
            }
            System.out.println();
            System.out.println("  fix: run `skill-manager sync --refresh` to write the lock from live state");
            return 1;
        }

        private static String describe(LockedUnit u) {
            String v = u.version() == null ? "?" : u.version();
            String sha = u.resolvedSha() == null ? "" :
                    "@" + (u.resolvedSha().length() > 8 ? u.resolvedSha().substring(0, 8) : u.resolvedSha());
            return v + sha;
        }

        public record StatusResult(
                String lockPath,
                int liveUnitCount,
                boolean inSync,
                LockDiff drift) {}
    }

    /** Build a {@link UnitsLock} from {@code installed/<name>.json} records. */
    public record LiveStateResult(UnitsLock lock, List<UnitReadProblem> problems) {}

    public static LiveStateResult readLiveState(SkillStore store) throws IOException {
        UnitStore sources = new UnitStore(store);
        List<LockedUnit> rows = new ArrayList<>();
        var listed = store.listInstalledUnits();
        for (var unit : listed.units()) {
            InstalledUnit rec = sources.read(unit.name()).orElse(null);
            if (rec == null) continue;
            rows.add(LockedUnit.fromInstalled(rec));
        }
        return new LiveStateResult(new UnitsLock(UnitsLock.CURRENT_SCHEMA, rows), listed.problems());
    }
}
