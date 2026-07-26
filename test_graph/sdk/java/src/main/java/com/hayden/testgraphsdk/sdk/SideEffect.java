package com.hayden.testgraphsdk.sdk;

import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Typed side-effect metadata for {@link NodeSpec}. Later execution tickets
 * attach behavior to selected forms; this class only validates and serializes
 * the declared contract.
 */
public final class SideEffect {
    public enum EnvironmentAction { PROVISION, REUSE, DEPLOY, RESET, DESTROY }

    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "db", Set.of("writes"),
            "fs", Set.of("tmp"),
            "net", Set.of("external", "local"),
            "process", Set.of("gradle"),
            "environment", Set.of("provision", "reuse", "deploy", "reset", "destroy")
    );

    private final String raw;

    private SideEffect(String raw) {
        this.raw = raw;
    }

    public static SideEffect of(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("sideEffects contains a blank side effect");
        }
        if (value.equals("browser")) return new SideEffect(value);

        int separator = value.indexOf(':');
        if (separator <= 0 || separator >= value.length() - 1) {
            throw new IllegalArgumentException(
                    "malformed side effect '" + raw + "'; expected a registered form like browser, net:local, or env:[KEY]");
        }

        String family = value.substring(0, separator);
        String action = value.substring(separator + 1);
        if (family.equals("env")) {
            validateEnv(action, raw);
            return new SideEffect(value);
        }

        Set<String> allowed = ALLOWED.get(family);
        if (allowed == null || !allowed.contains(action)) {
            throw new IllegalArgumentException("unsupported side effect '" + raw + "'");
        }
        return new SideEffect(value);
    }

    public static SideEffect browser() { return of("browser"); }
    public static SideEffect dbWrites() { return of("db:writes"); }
    public static SideEffect fsTmp() { return of("fs:tmp"); }
    public static SideEffect netExternal() { return of("net:external"); }
    public static SideEffect netLocal() { return of("net:local"); }
    public static SideEffect processGradle() { return of("process:gradle"); }
    public static SideEffect env(String key) { return of("env:[" + Objects.requireNonNull(key, "key") + "]"); }
    public static SideEffect envAll() { return of("env:[*]"); }
    public static SideEffect environment(EnvironmentAction action) {
        return of("environment:" + Objects.requireNonNull(action, "action").name().toLowerCase(Locale.ROOT));
    }

    public String raw() { return raw; }

    @Override
    public String toString() { return raw; }

    private static void validateEnv(String action, String raw) {
        if (!action.startsWith("[") || !action.endsWith("]")) {
            throw new IllegalArgumentException(
                    "malformed env side effect '" + raw + "'; expected env:[KEY] or env:[*]");
        }
        String key = action.substring(1, action.length() - 1);
        if (!key.equals("*") && !ENV_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "malformed env side effect '" + raw + "'; expected env:[KEY] or env:[*]");
        }
    }
}
