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
