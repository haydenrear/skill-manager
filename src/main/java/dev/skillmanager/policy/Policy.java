package dev.skillmanager.policy;

import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * User-configurable guardrails for skill-manager.
 *
 * <p>Every install or sync is evaluated against this policy. Actions that
 * violate the policy are either blocked outright or surfaced as
 * {@link dev.skillmanager.plan.PlanAction.Severity#DANGER} in the plan —
 * the user can still approve with {@code --yes}, or set a looser policy.
 *
 * <p>Loaded from {@code ~/.skill-manager/policy.toml} if present, else defaults
 * below apply. The defaults are permissive enough to be useful but surface
 * risky actions prominently:
 * <ul>
 *   <li>all backends allowed</li>
 *   <li>hashes recommended, not required</li>
 *   <li>{@code init_script} execution blocked</li>
 *   <li>docker allowed (any image)</li>
 *   <li>any registry allowed</li>
 *   <li>confirmation required for every destructive action</li>
 * </ul>
 */
public record Policy(
        Set<String> allowedBackends,
        boolean requireHash,
        boolean allowInitScripts,
        boolean allowDocker,
        Set<String> allowedRegistries,
        Set<String> allowedDockerPrefixes,
        boolean requireConfirmation,
        InstallPolicy install
) {

    public static final String FILENAME = "policy.toml";

    public Policy {
        allowedBackends = Collections.unmodifiableSet(new LinkedHashSet<>(allowedBackends));
        allowedRegistries = Collections.unmodifiableSet(new LinkedHashSet<>(allowedRegistries));
        allowedDockerPrefixes = Collections.unmodifiableSet(new LinkedHashSet<>(allowedDockerPrefixes));
        if (install == null) install = InstallPolicy.defaults();
    }

    public static Policy defaults() {
        return new Policy(
                Set.of("tar", "pip", "npm", "brew"),
                false,
                false,
                true,
                Set.of(),
                Set.of(),
                true,
                InstallPolicy.defaults()
        );
    }

    public boolean backendAllowed(String id) {
        return allowedBackends.isEmpty() || allowedBackends.contains(id);
    }

    public boolean registryAllowed(String url) {
        if (allowedRegistries.isEmpty()) return true;
        for (String allowed : allowedRegistries) {
            if (url.startsWith(allowed)) return true;
        }
        return false;
    }

    public boolean dockerImageAllowed(String image) {
        if (!allowDocker) return false;
        if (allowedDockerPrefixes.isEmpty()) return true;
        for (String prefix : allowedDockerPrefixes) {
            if (image.startsWith(prefix)) return true;
        }
        return false;
    }

    public static Policy load(SkillStore store) throws IOException {
        Path file = store.root().resolve(FILENAME);
        if (!Files.isRegularFile(file)) return defaults();
        TomlParseResult toml = Toml.parse(file);
        if (toml.hasErrors()) {
            throw new IOException("policy.toml has errors: " + toml.errors());
        }
        Policy d = defaults();
        InstallPolicy installDefaults = d.install();
        InstallPolicy install = new InstallPolicy(
                bool(toml, "install.require_confirmation_for_hooks",
                        installDefaults.requireConfirmationForHooks()),
                bool(toml, "install.require_confirmation_for_mcp",
                        installDefaults.requireConfirmationForMcp()),
                bool(toml, "install.require_confirmation_for_cli_deps",
                        installDefaults.requireConfirmationForCliDeps()),
                bool(toml, "install.require_confirmation_for_executable_commands",
                        installDefaults.requireConfirmationForExecutableCommands())
        );
        return new Policy(
                stringSet(toml.getArray("allowed_backends"), d.allowedBackends),
                bool(toml, "require_hash", d.requireHash),
                bool(toml, "allow_init_scripts", d.allowInitScripts),
                bool(toml, "allow_docker", d.allowDocker),
                stringSet(toml.getArray("allowed_registries"), d.allowedRegistries),
                stringSet(toml.getArray("allowed_docker_prefixes"), d.allowedDockerPrefixes),
                bool(toml, "require_confirmation", d.requireConfirmation),
                install
        );
    }

    public static void writeDefaultIfMissing(SkillStore store) throws IOException {
        Path file = store.root().resolve(FILENAME);
        if (Files.exists(file)) return;
        Fs.ensureDir(store.root());
        Policy d = defaults();
        String content = """
                # skill-manager policy — guards against hostile skills.
                # Remove or flip flags to tighten / loosen.

                # Which CLI installer backends are permitted.
                # Remove "npm" or "brew" to block them entirely (both can run arbitrary
                # post-install hooks).
                allowed_backends = %s

                # Refuse tar installs that have no sha256. Recommended = true for prod.
                require_hash = %b

                # Run `init_script` in binary MCP load specs. Very dangerous; keep off
                # unless you fully trust the publisher.
                allow_init_scripts = %b

                # Docker pulls and runs.
                allow_docker = %b
                # Optionally restrict to specific image prefixes:
                # allowed_docker_prefixes = ["ghcr.io/your-org/"]
                allowed_docker_prefixes = %s

                # Registries we trust (empty = any).
                # allowed_registries = ["https://registry.internal"]
                allowed_registries = %s

                # Always show the install plan and require `y` (or --yes).
                require_confirmation = %b

                # Per-category confirmation gates.
                # Each flag means "require interactive confirmation for this category".
                # When the flag is on AND the plan-print emits the corresponding `!`-line
                # (e.g. `! HOOKS`), `--yes` cannot bypass — the user has to flip the
                # specific flag to false to install unattended.
                #
                # Default calibration: only genuinely dangerous categories are gated by
                # default. Hooks (arbitrary shell at install time) and executable commands
                # (plugin commands/ binaries) stay on; CLI deps (pip / npm / brew) and
                # MCP registrations are routine enough that gating them by default would
                # break automation without meaningful safety gain. Tighten by setting
                # individual flags to true.
                [install]
                require_confirmation_for_hooks = %b
                require_confirmation_for_mcp = %b
                require_confirmation_for_cli_deps = %b
                require_confirmation_for_executable_commands = %b
                """.formatted(
                        toToml(d.allowedBackends),
                        d.requireHash,
                        d.allowInitScripts,
                        d.allowDocker,
                        toToml(d.allowedDockerPrefixes),
                        toToml(d.allowedRegistries),
                        d.requireConfirmation,
                        d.install.requireConfirmationForHooks(),
                        d.install.requireConfirmationForMcp(),
                        d.install.requireConfirmationForCliDeps(),
                        d.install.requireConfirmationForExecutableCommands());
        Files.writeString(file, content);
    }

    // ------------------------------------------------------------------ utils

    private static boolean bool(TomlParseResult toml, String key, boolean fallback) {
        Boolean b = toml.getBoolean(key);
        return b == null ? fallback : b;
    }

    private static Set<String> stringSet(TomlArray arr, Set<String> fallback) {
        if (arr == null) return fallback;
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            Object v = arr.get(i);
            if (v != null) out.add(v.toString());
        }
        return out;
    }

    private static String toToml(Set<String> values) {
        if (values.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) sb.append(", ");
            sb.append("\"").append(v).append("\"");
            first = false;
        }
        return sb.append("]").toString();
    }

    public List<String> violations(String label, Iterable<String> constraints) {
        return List.of(label);
    }
}
