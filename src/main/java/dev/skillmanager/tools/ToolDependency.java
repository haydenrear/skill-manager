package dev.skillmanager.tools;

import dev.skillmanager.pm.PackageManager;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Unified abstraction over <em>any</em> tool the install plan needs to
 * guarantee available before CLI installs and MCP registrations execute.
 *
 * <p>Both {@code CliDependency} (via {@code spec = "pip:..."} / {@code "npm:..."} /
 * {@code "brew:..."}) and {@code McpDependency.LoadSpec} (via {@code type = "docker"}
 * / {@code "npm"} / {@code "uv"}) declare a {@code Set<String>} of tool ids
 * (e.g. {@code "uv"}, {@code "npx"}, {@code "docker"}). {@link ToolRegistry}
 * resolves each id to a {@code ToolDependency}; {@code PlanBuilder} groups
 * them, deduplicates, and emits one {@code PlanAction.EnsureTool} per
 * unique tool. Execution materializes them through
 * {@link dev.skillmanager.pm.PackageManagerRuntime#ensureAvailable}.
 *
 * <p>The two flavors mirror {@link PackageManager#bundleable()}:
 * <ul>
 *   <li>{@link Bundled} — skill-manager downloads the providing
 *       {@link PackageManager} into {@code $SKILL_MANAGER_HOME/pm/<id>/}
 *       if it isn't already there. Idempotent on subsequent runs.</li>
 *   <li>{@link External} — system-managed tool (docker, brew). The
 *       runtime only does a presence check; the plan reports an install
 *       hint when it isn't found.</li>
 * </ul>
 */
public sealed interface ToolDependency {

    /** Stable identifier for dedup ({@code "uv"}, {@code "npx"},
     *  {@code "docker"}, {@code "brew"}). */
    String id();

    /** Human-readable label used in plan output. */
    String displayName();

    /** Skills that requested this tool — accumulated by {@code PlanBuilder}
     *  so the plan output can attribute each tool to its requesters. */
    Set<String> requestedBy();

    /** The providing {@link PackageManager}. Always non-null — every tool
     *  id we know about is rooted in some entry of the enum. */
    PackageManager pm();

    /** True if {@link dev.skillmanager.pm.PackageManagerRuntime} can install
     *  this tool itself; false if it can only check for presence. */
    default boolean bundleable() { return pm().bundleable(); }

    /** Returns a new instance with {@code skill} appended to {@link #requestedBy()}. */
    ToolDependency withRequester(String skill);

    /** Skill-manager-bundleable tool. Realized by
     *  {@code PackageManagerRuntime.ensureBundled(tool)}. */
    record Bundled(
            String id,
            String displayName,
            PackageManager pm,
            Set<String> requestedBy
    ) implements ToolDependency {
        public Bundled {
            requestedBy = requestedBy == null ? Set.of() : Set.copyOf(requestedBy);
        }
        @Override public ToolDependency withRequester(String skill) {
            Set<String> next = new LinkedHashSet<>(requestedBy);
            if (skill != null) next.add(skill);
            return new Bundled(id, displayName, pm, next);
        }
    }

    /** External (system-managed) tool. Realized by a presence check via
     *  {@code PackageManagerRuntime.systemPath(tool)}. */
    record External(
            String id,
            String displayName,
            PackageManager pm,
            String installHint,
            Set<String> requestedBy
    ) implements ToolDependency {
        public External {
            requestedBy = requestedBy == null ? Set.of() : Set.copyOf(requestedBy);
        }
        @Override public ToolDependency withRequester(String skill) {
            Set<String> next = new LinkedHashSet<>(requestedBy);
            if (skill != null) next.add(skill);
            return new External(id, displayName, pm, installHint, next);
        }
    }
}
