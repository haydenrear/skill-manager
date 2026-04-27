package dev.skillmanager.tools;

import dev.skillmanager.pm.PackageManager;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Catalog of every tool id skill-manager knows how to ensure available.
 * Add an entry here when a new CLI backend or MCP load type needs a
 * runtime tool ({@code uv}, {@code npx}, {@code docker}, …).
 *
 * <p>Lookup is by id, not by class — both {@code CliDependency} and
 * {@code McpDependency.LoadSpec} declare their needs as {@code Set<String>}
 * so the same tool surfaced by either side merges into one
 * {@link ToolDependency}.
 */
public final class ToolRegistry {

    private ToolRegistry() {}

    /**
     * Resolve a tool id to its {@link ToolDependency}, with no requesters
     * attached. Caller composes via
     * {@link ToolDependency#withRequester(String)} when collecting per-skill
     * requests.
     *
     * @throws IllegalArgumentException if {@code id} isn't in the catalog.
     */
    public static ToolDependency byId(String id) {
        return switch (id) {
            case "uv" -> new ToolDependency.Bundled(
                    "uv", "uv (Python package manager)",
                    PackageManager.UV, Set.of());
            case "node", "npm", "npx" -> new ToolDependency.Bundled(
                    id, "Node.js (provides node, npm, npx)",
                    PackageManager.NODE, Set.of());
            case "docker" -> new ToolDependency.External(
                    "docker", "Docker",
                    PackageManager.DOCKER,
                    PackageManager.DOCKER.installHint(),
                    Set.of());
            case "brew" -> new ToolDependency.External(
                    "brew", "Homebrew",
                    PackageManager.BREW,
                    PackageManager.BREW.installHint(),
                    Set.of());
            default -> throw new IllegalArgumentException(
                    "ToolRegistry: unknown tool id: " + id);
        };
    }

    /**
     * Merge a stream of (skillName, toolId) pairs into a deduplicated map
     * of {@link ToolDependency} entries with accumulated requesters.
     *
     * <p>Used by {@code PlanBuilder} to fold together tool needs from every
     * CLI and MCP dependency in the install graph.
     */
    public static Map<String, ToolDependency> collect(
            Iterable<Map.Entry<String, String>> skillTools) {
        Map<String, ToolDependency> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : skillTools) {
            String skill = e.getKey();
            String toolId = e.getValue();
            ToolDependency existing = out.get(toolId);
            if (existing == null) {
                out.put(toolId, byId(toolId).withRequester(skill));
            } else {
                out.put(toolId, existing.withRequester(skill));
            }
        }
        return out;
    }

    /** Convenience: collect from raw (skill -> toolIds) pairs. */
    public static Map<String, ToolDependency> collectFlat(
            Map<String, Set<String>> skillToToolIds) {
        Set<Map.Entry<String, String>> flat = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> e : skillToToolIds.entrySet()) {
            for (String tid : e.getValue()) {
                flat.add(Map.entry(e.getKey(), tid));
            }
        }
        return collect(flat);
    }
}
