package dev.skillmanager.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A CLI tool a skill needs on PATH.
 *
 * <p>The {@link #spec()} chooses the installer backend via a {@code prefix:body}
 * form. Supported prefixes:
 * <ul>
 *   <li>{@code pip:package[==version]} — pip install (platform-independent)</li>
 *   <li>{@code npm:package[@version]}  — npm -g install (platform-independent)</li>
 *   <li>{@code brew:package}            — Homebrew (macOS/Linux)</li>
 *   <li>{@code tar[:name]}              — download+extract using {@link #install()} targets</li>
 * </ul>
 *
 * <p>{@link #platformIndependent()} is informational — pip/npm are implicitly
 * platform-independent; for {@code tar:} it marks an {@code install.any} target.
 */
public record CliDependency(
        String name,
        String spec,
        String minVersion,
        String versionCheck,
        String onPath,
        boolean platformIndependent,
        Map<String, InstallTarget> install
) {

    public CliDependency {
        install = install == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(install));
    }

    public String backend() {
        if (spec == null || spec.isBlank()) return "tar";
        int colon = spec.indexOf(':');
        if (colon < 0) return spec;
        return spec.substring(0, colon);
    }

    public String packageRef() {
        if (spec == null) return null;
        int colon = spec.indexOf(':');
        return colon < 0 ? "" : spec.substring(colon + 1);
    }

    /**
     * Tool ids the install plan must guarantee are available before this
     * CLI dep can be installed. Mirrors the same surface on
     * {@code McpDependency.LoadSpec.requiredToolIds()} so {@code PlanBuilder}
     * can collect both into one deduplicated {@code EnsureTool} group.
     *
     * <ul>
     *   <li>{@code pip:} → uv (skill-manager bundles)</li>
     *   <li>{@code npm:} → npm  (skill-manager bundles via Node)</li>
     *   <li>{@code brew:} → brew (external; presence-check only)</li>
     *   <li>{@code tar:} → none (the backend downloads the binary itself)</li>
     * </ul>
     */
    public java.util.Set<String> requiredToolIds() {
        return switch (backend()) {
            case "pip" -> java.util.Set.of("uv");
            case "npm" -> java.util.Set.of("npm");
            case "brew" -> java.util.Set.of("brew");
            default -> java.util.Set.of();
        };
    }

    public record InstallTarget(
            String url,
            String archive,
            String binary,
            List<String> extract,
            String sha256
    ) {
        public InstallTarget {
            extract = extract == null ? List.of() : List.copyOf(extract);
        }
    }
}
