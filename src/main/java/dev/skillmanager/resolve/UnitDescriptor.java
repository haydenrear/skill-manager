package dev.skillmanager.resolve;

import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.model.UnitReference;

import java.util.List;

/**
 * Immutable resolution result. Carries everything the planner needs to
 * emit a Program: the unit's identity, where its bytes live, the
 * resolved sha (for reproducibility), and the parsed dep + reference
 * lists (already unioned for plugins).
 *
 * <p>Produced by {@link CoordResolver}; consumed by the planner
 * (ticket 05+). For ticket 04 the planner doesn't yet read
 * {@code UnitDescriptor}s — the existing {@link dev.skillmanager.resolve.Resolver}
 * keeps fetching skills the legacy way until ticket 05 wires the new
 * pipeline.
 *
 * <p>{@link #sourceId} is hardcoded to {@code "default"} until
 * multi-source / marketplaces land. {@link #discoveryKind} and
 * {@link #transport} are split now (single-value-per-coord-shape today)
 * so adding {@code MARKETPLACE} discovery or {@code ARCHIVE} transport
 * later doesn't ripple through the planner.
 */
public record UnitDescriptor(
        String name,
        UnitKind unitKind,
        String version,
        String sourceId,
        DiscoveryKind discoveryKind,
        Transport transport,
        String origin,
        String resolvedSha,
        List<UnitReference> references,
        List<CliDependency> cliDependencies,
        List<McpDependency> mcpDependencies
) {
    public UnitDescriptor {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (unitKind == null) throw new IllegalArgumentException("unitKind required");
        if (sourceId == null || sourceId.isBlank()) sourceId = "default";
        references = references == null ? List.of() : List.copyOf(references);
        cliDependencies = cliDependencies == null ? List.of() : List.copyOf(cliDependencies);
        mcpDependencies = mcpDependencies == null ? List.of() : List.copyOf(mcpDependencies);
    }
}
