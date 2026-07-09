package dev.skillmanager.commands;

import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager store} — the content-addressed skill store.
 *
 * <p>A unit's slot is {@code skills/<name>/}. The working copy lives under
 * {@code latest/} — that is what install and sync write, and what agent homes
 * link to. Alongside it sit immutable snapshots, one directory per stored sha.
 *
 * <p>The store is a cache, not a lifecycle: {@code remove} takes the working
 * copy away and leaves every snapshot behind.
 */
@Command(name = "store",
        description = "Inspect and populate the content-addressed skill store.",
        subcommands = { StoreCommand.Add.class })
public final class StoreCommand implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }

    /**
     * {@code skill-manager store add <unit> --sha <sha>} — snapshot the
     * installed working copy under {@code skills/<unit>/<sha>/} and move the
     * latest pointer onto that sha.
     *
     * <p>Exits 0 on success, 1 when the unit is not installed, 2 on I/O error.
     * Re-storing a sha already present refreshes the snapshot, so running this
     * again after a sync that did not move HEAD is not an error.
     */
    @Command(name = "add",
            description = "Store the installed unit's content under skills/<name>/<sha>/.")
    public static final class Add implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<unit>", description = "Installed unit to snapshot.")
        String unit;

        @Option(names = "--sha", required = true, paramLabel = "<sha>",
                description = "Content address to store the unit under. Defaults to the unit's resolved git hash when omitted at the call site.")
        String sha;

        @Option(names = "--yes", description = "Assume yes for confirmation prompts.")
        boolean yes;

        @Override
        public Integer call() {
            SkillStore store = SkillStore.defaultStore();
            if (!store.containsUnit(unit)) {
                Log.error("not installed: %s", unit);
                return 1;
            }
            if (!store.contains(unit)) {
                // Plugins, doc-repos, and harnesses keep their flat layout
                // until the pin/venv tickets extend the store to them.
                Log.error("%s is not a skill: the content-addressed store covers skills only", unit);
                return 1;
            }
            String resolved = sha == null || sha.isBlank() ? recordedSha(store, unit) : sha.trim();
            if (resolved == null || resolved.isBlank()) {
                Log.error("no sha for %s: pass --sha, or reinstall from a git source", unit);
                return 1;
            }
            try {
                store.storeUnitVersion(unit, resolved);
                List<String> stored = store.storedVersions(unit);
                Log.ok("stored %s@%s (%d version(s) cached)", unit, resolved, stored.size());
                return 0;
            } catch (IOException e) {
                Log.error("could not store %s@%s: %s", unit, resolved, e.getMessage());
                return 2;
            }
        }

        private static String recordedSha(SkillStore store, String unit) {
            Optional<InstalledUnit> record = new UnitStore(store).read(unit);
            return record.map(InstalledUnit::gitHash).orElse(null);
        }
    }
}
