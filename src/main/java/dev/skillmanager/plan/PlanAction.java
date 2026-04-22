package dev.skillmanager.plan;

import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.resolve.ResolvedGraph;
import java.util.List;

public sealed interface PlanAction {

    Severity severity();

    Section section();

    String title();

    default List<String> notes() { return List.of(); }

    enum Severity { INFO, NOTICE, WARN, DANGER }

    enum Section { RESOLVE, STORE, CLI, MCP, NOTES }

    // ---------------------------------------------------------------- actions

    record FetchSkill(ResolvedGraph.Resolved resolved) implements PlanAction {
        @Override public Severity severity() {
            return switch (resolved.sourceKind()) {
                case REGISTRY -> Severity.NOTICE;
                case GIT -> Severity.WARN;      // git clones can include anything
                case LOCAL -> Severity.INFO;
            };
        }
        @Override public Section section() { return Section.RESOLVE; }
        @Override public String title() {
            String kindLabel = switch (resolved.sourceKind()) {
                case REGISTRY -> "registry";
                case GIT -> "git";
                case LOCAL -> "local";
            };
            String versionPart = resolved.skill().version() == null ? "" : "@" + resolved.skill().version();
            String bytesPart = resolved.bytesDownloaded() > 0 ? "  (" + humanBytes(resolved.bytesDownloaded()) + ")" : "";
            String reused = resolved.reusedFromStore() ? "  [already in store — replacing]" : "";
            return kindLabel + "  " + resolved.name() + versionPart + bytesPart + reused;
        }
        @Override public List<String> notes() {
            List<String> notes = new java.util.ArrayList<>();
            notes.add("source: " + resolved.source());
            if (resolved.sha256() != null) notes.add("sha256: " + resolved.sha256());
            if (!resolved.requestedBy().isEmpty()) notes.add("transitive of: " + String.join(", ", resolved.requestedBy()));
            return notes;
        }
    }

    record InstallSkillIntoStore(String name, String version) implements PlanAction {
        @Override public Severity severity() { return Severity.INFO; }
        @Override public Section section() { return Section.STORE; }
        @Override public String title() { return name + (version == null ? "" : "@" + version); }
    }

    record RunCliInstall(String skillName, CliDependency dep) implements PlanAction {
        @Override public Severity severity() {
            String b = dep.backend();
            if ("pip".equals(b) || "npm".equals(b) || "brew".equals(b)) return Severity.WARN;
            if ("tar".equals(b)) {
                boolean anyHash = dep.install().values().stream().anyMatch(t -> t.sha256() != null);
                return anyHash ? Severity.NOTICE : Severity.DANGER;
            }
            return Severity.WARN;
        }
        @Override public Section section() { return Section.CLI; }
        @Override public String title() {
            return "[" + dep.backend() + "] " + dep.name() + "  (" + dep.spec() + ")";
        }
        @Override public List<String> notes() {
            List<String> out = new java.util.ArrayList<>();
            out.add("needed by: " + skillName);
            if ("tar".equals(dep.backend())) {
                boolean anyHash = dep.install().values().stream().anyMatch(t -> t.sha256() != null);
                if (!anyHash) out.add("no sha256 recorded — downloaded bytes are not verified");
            }
            if ("pip".equals(dep.backend())) out.add("isolated install → UV tool or pip --user");
            if ("npm".equals(dep.backend())) out.add("per-skill prefix + will run the package's postinstall hook");
            if ("brew".equals(dep.backend())) out.add("brew formulas execute arbitrary ruby; symlinked into bin/cli");
            return out;
        }
    }

    record RegisterMcpServer(String skillName, McpDependency dep) implements PlanAction {
        @Override public Severity severity() {
            return switch (dep.load()) {
                case McpDependency.DockerLoad d -> Severity.WARN;
                case McpDependency.BinaryLoad b -> b.initScript() != null ? Severity.DANGER : Severity.WARN;
            };
        }
        @Override public Section section() { return Section.MCP; }
        @Override public String title() {
            String kind = switch (dep.load()) {
                case McpDependency.DockerLoad d -> "docker " + d.image();
                case McpDependency.BinaryLoad b -> "binary " + (b.binPath() != null ? b.binPath() : "<none>");
            };
            return dep.name() + "  (" + kind + ")";
        }
        @Override public List<String> notes() {
            List<String> out = new java.util.ArrayList<>();
            out.add("needed by: " + skillName);
            switch (dep.load()) {
                case McpDependency.DockerLoad d -> { if (d.pull()) out.add("will docker pull " + d.image()); }
                case McpDependency.BinaryLoad b -> { if (b.initScript() != null) out.add("init_script will run: " + b.initScript()); }
            }
            return out;
        }
    }

    record BlockedByPolicy(Section section, String action, String reason) implements PlanAction {
        @Override public Severity severity() { return Severity.DANGER; }
        @Override public String title() { return "BLOCKED  " + action; }
        @Override public List<String> notes() { return List.of(reason); }
    }

    /**
     * Two skills disagree on the version of a CLI tool already locked in
     * {@code cli-lock.toml}. Blocks the install — user must bump the losing
     * skill or loosen the conflicting spec.
     */
    record CliVersionConflict(
            String skillName,
            CliDependency dep,
            String requestedVersion,
            String lockedVersion,
            List<String> previouslyRequestedBy
    ) implements PlanAction {
        @Override public Severity severity() { return Severity.DANGER; }
        @Override public Section section() { return Section.CLI; }
        @Override public String title() {
            return "CONFLICT  [" + dep.backend() + "] " + dep.name()
                    + "  requested " + (requestedVersion == null ? "any" : requestedVersion)
                    + ", locked at " + lockedVersion;
        }
        @Override public List<String> notes() {
            List<String> out = new java.util.ArrayList<>();
            out.add("needed by: " + skillName);
            if (!previouslyRequestedBy.isEmpty()) {
                out.add("locked by: " + String.join(", ", previouslyRequestedBy));
            }
            out.add("resolve: bump one skill's spec to match, or delete the conflicting entry in cli-lock.toml");
            return out;
        }
    }

    record PathHint(String path) implements PlanAction {
        @Override public Severity severity() { return Severity.INFO; }
        @Override public Section section() { return Section.NOTES; }
        @Override public String title() {
            return "after install, add to PATH:  export PATH=\"" + path + ":$PATH\"";
        }
    }

    // ------------------------------------------------------------------ utils

    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / (1024.0 * 1024.0));
    }
}
