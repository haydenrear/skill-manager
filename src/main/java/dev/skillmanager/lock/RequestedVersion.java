package dev.skillmanager.lock;

import dev.skillmanager.model.CliDependency;

/**
 * Pulls a {@code (tool, version)} identity out of a CLI spec, per backend.
 *
 * <ul>
 *   <li>{@code pip:ruff==0.6.0}           → tool=ruff, version=0.6.0</li>
 *   <li>{@code pip:ruff&gt;=0.6}             → tool=ruff, version=null (range, not pin)</li>
 *   <li>{@code npm:typescript@5.4.5}      → tool=typescript, version=5.4.5</li>
 *   <li>{@code npm:@scope/pkg@1.0}        → tool=@scope/pkg, version=1.0</li>
 *   <li>{@code brew:fd}                    → tool=fd, version=null (brew tracks its own formula version)</li>
 *   <li>{@code tar:rg} + {@code min_version="14.0"} → tool=rg, version=14.0</li>
 * </ul>
 */
public final class RequestedVersion {

    public record Requested(String tool, String version) {}

    private RequestedVersion() {}

    public static Requested of(CliDependency dep) {
        String backend = dep.backend();
        String pkg = dep.packageRef();
        return switch (backend) {
            case "pip" -> fromPip(pkg, dep.name());
            case "npm" -> fromNpm(pkg, dep.name());
            case "brew" -> new Requested(dep.name(), null);
            case "tar" -> new Requested(dep.name(), dep.minVersion());
            default -> new Requested(dep.name(), dep.minVersion());
        };
    }

    private static Requested fromPip(String pkg, String fallbackName) {
        if (pkg == null || pkg.isBlank()) return new Requested(fallbackName, null);
        int eq = pkg.indexOf("==");
        if (eq >= 0) return new Requested(pkg.substring(0, eq).trim(), pkg.substring(eq + 2).trim());
        // Any other operator (>=, <=, ~=, !=) is a range — record tool only.
        for (String op : new String[] {">=", "<=", "~=", "!=", ">", "<"}) {
            int idx = pkg.indexOf(op);
            if (idx >= 0) return new Requested(pkg.substring(0, idx).trim(), null);
        }
        return new Requested(pkg.trim(), null);
    }

    private static Requested fromNpm(String pkg, String fallbackName) {
        if (pkg == null || pkg.isBlank()) return new Requested(fallbackName, null);
        // Last `@` that's not at index 0 splits tool from version — handles @scope/pkg@1.2.3.
        int idx = pkg.lastIndexOf('@');
        if (idx > 0) return new Requested(pkg.substring(0, idx).trim(), pkg.substring(idx + 1).trim());
        return new Requested(pkg.trim(), null);
    }
}
