package dev.skillmanager.cli.installer;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a sync scoped to some units owes the units it left alone (#402).
 *
 * <p>Scoping means a CLI dep of another unit can stay unbuilt in this home,
 * which is the normal state of a fresh {@code home clone} because the clone
 * skips {@code cache/}. Skipping it is fine; saying nothing about it is not. So
 * the pass keeps those units' shims in place and names, by tool and unit, what
 * this home cannot run. No state is recorded that would claim otherwise: the
 * entry point left by the clone still refuses at exec time and names its build.
 */
public final class CliReconcileScope {

    private CliReconcileScope() {}

    /** Shims only an out-of-scope unit declares, which this pass must not prune. */
    public static Set<String> shimsToKeep(List<AgentUnit> reconciled, List<AgentUnit> outOfScope) {
        if (outOfScope.isEmpty()) return Set.of();
        Set<String> keep = new LinkedHashSet<>(CliShimPruner.declaredNames(outOfScope));
        keep.removeAll(CliShimPruner.declaredNames(reconciled));
        return keep;
    }

    /**
     * Warn about each out-of-scope CLI dep that will not run from this home. A
     * tool also declared by a reconciled unit is that pass's to report.
     *
     * @return the {@code tool (unit)} entries warned about
     */
    public static List<String> reportUnbuilt(List<AgentUnit> reconciled, List<AgentUnit> outOfScope,
                                             SkillStore store) {
        Set<String> inScope = CliShimPruner.declaredNames(reconciled);
        Set<String> seen = new LinkedHashSet<>();
        Set<String> unbuilt = new LinkedHashSet<>();
        for (AgentUnit unit : outOfScope) {
            for (CliDependency dep : unit.cliDependencies()) {
                String artifact = artifactName(dep);
                if (artifact == null || inScope.contains(artifact) || !seen.add(artifact)) continue;
                if (CliArtifact.usableInHome(store, artifact)) continue;
                if (CliPresence.providedOutsideEveryHome(dep.onPath()) != null) continue;
                unbuilt.add(artifact + " (" + unit.name() + ")");
            }
        }
        if (!unbuilt.isEmpty()) {
            Log.warn("cli: %d CLI dep(s) of other units cannot run from this home, and this "
                            + "sync did not build them: %s. `skill-manager sync <unit>` builds "
                            + "one unit's; `skill-manager sync` builds them all.",
                    unbuilt.size(), String.join(", ", unbuilt));
        }
        return List.copyOf(unbuilt);
    }

    private static String artifactName(CliDependency dep) {
        for (CliDependency.InstallTarget target : dep.install().values()) {
            if (target.binary() != null && !target.binary().isBlank()) return target.binary();
        }
        if (dep.onPath() != null && !dep.onPath().isBlank()) return dep.onPath();
        return "tar".equals(dep.backend()) ? dep.name() : null;
    }
}
