package dev.skillmanager.lock;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.InstalledUnitsResult;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * #428 R3/R4: who claims a {@code cli-lock.toml} row NOW, and at which pin.
 *
 * <p>{@code requested_by} is a record of who asked in the past. Nothing used to
 * take a unit out of it when the unit stopped declaring the dependency, or was
 * removed by any path other than {@code CliDependencyCleaner}. The stale name
 * then counted as a holdout in {@link CliPin#sharedConflict}, so it blocked
 * every later pin bump until someone edited the file by hand.
 *
 * <p>A requester is <b>live</b> when either:
 * <ol>
 *   <li>it declares this row in the current pass ({@code declaredThisPass}):
 *       its declaration is the pin it has right now; or</li>
 *   <li>it is an installed unit whose manifest still declares a CLI dep with
 *       this row's {@code (backend, tool)}: that manifest's pin is what it
 *       declares, read from the record and not guessed from the row.</li>
 * </ol>
 * Anything else is <b>stale</b>: either the unit is not installed, or it no
 * longer declares the dependency. A stale requester is pruned from the row and
 * never counts as a holdout.
 */
public final class CliClaimants {

    private CliClaimants() {}

    /**
     * {@code unit -> claim} for every live claimant of {@code (backend, tool)}.
     * The current pass wins over the installed manifest. Null when the
     * installed records cannot be read: then nobody can be called stale.
     */
    public static Map<String, CliPin.Claim> livePins(SkillStore store, String backend, String tool,
                                               Map<String, CliPin.Claim> declaredThisPass) {
        Map<String, Map<String, CliPin.Claim>> installed = installedPinsByKey(store);
        // Unreadable installed records: no claimant can be called stale, so
        // the caller falls back to treating every requester as a holdout.
        if (installed == null) return null;
        Map<String, CliPin.Claim> out = new LinkedHashMap<>(
                installed.getOrDefault(backend + ":" + tool, Map.of()));
        if (declaredThisPass != null) out.putAll(declaredThisPass);
        return out;
    }

    /** The requesters of {@code row} that are not live. */
    public static List<String> stale(CliLock.Entry row, Map<String, CliPin.Claim> livePins) {
        List<String> out = new ArrayList<>();
        if (row == null) return out;
        for (String r : row.requestedBy()) {
            if (r != null && !livePins.containsKey(r)) out.add(r);
        }
        return out;
    }

    /**
     * Drop every stale requester from every row of {@code lock}, in memory. The
     * caller saves. The rows themselves are kept, even when no requester is
     * left: removing an artifact is uninstall's job ({@code CliDependencyCleaner}),
     * and a lock row is the only record of where it is.
     *
     * @param passByKey {@code "backend:tool" -> (unit -> claim)} for this pass,
     *        or empty
     * @return one line per pruned requester, for the run log
     */
    public static List<String> prune(CliLock lock, SkillStore store,
                                     Map<String, Map<String, CliPin.Claim>> passByKey) {
        Map<String, Map<String, CliPin.Claim>> installed = installedPinsByKey(store);
        List<String> pruned = new ArrayList<>();
        // Never prune on an unreadable record: "could not read" is not "gone".
        if (installed == null) return pruned;
        for (CliLock.Entry row : lock.all()) {
            String key = row.backend() + ":" + row.tool();
            Map<String, CliPin.Claim> live = new LinkedHashMap<>(installed.getOrDefault(key, Map.of()));
            live.putAll(passByKey.getOrDefault(key, Map.of()));
            CliLock.Entry next = row;
            for (String gone : stale(row, live)) {
                next = next.withoutRequester(gone);
                pruned.add(key + ": " + gone);
            }
            if (next != row) lock.put(next);
        }
        for (String p : pruned) {
            Log.detail("cli: dropped a stale requester from cli-lock.toml (%s): no longer "
                    + "installed, or no longer declares it", p);
        }
        return pruned;
    }

    private static Map<String, Map<String, CliPin.Claim>> installedPinsByKey(SkillStore store) {
        Map<String, Map<String, CliPin.Claim>> out = new LinkedHashMap<>();
        if (store == null) return null;
        List<AgentUnit> units;
        try {
            InstalledUnitsResult listed = store.listInstalledUnits();
            // #434 F5: a unit that failed to read is missing from units(), and
            // it may still claim any row. Absent from the list is not gone.
            if (!listed.problems().isEmpty()) {
                Log.warn("cli: %d installed unit(s) could not be read; not deciding CLI claimants "
                        + "from a partial list", listed.problems().size());
                return null;
            }
            units = listed.units();
        } catch (IOException | RuntimeException e) {
            Log.warn("cli: could not read installed units to decide CLI claimants: %s",
                    e.getMessage());
            return null;
        }
        for (AgentUnit unit : units) {
            for (CliDependency dep : unit.cliDependencies()) {
                String key = dep.backend() + ":" + RequestedVersion.of(dep).tool();
                out.computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .putIfAbsent(unit.name(), CliPin.claim(dep));
            }
        }
        return out;
    }
}
