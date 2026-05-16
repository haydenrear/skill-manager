package dev.skillmanager.store;

import dev.skillmanager.model.AgentUnit;

import java.util.List;

/** Result of scanning all installed unit kinds, including unreadable entries. */
public record InstalledUnitsResult(
        List<AgentUnit> units,
        List<UnitReadProblem> problems
) {
    public InstalledUnitsResult {
        units = units == null ? List.of() : List.copyOf(units);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }
}
