package dev.skillmanager.server.publish;

import java.util.regex.Pattern;

/**
 * Strict semver 2.0.0 validator.
 *
 * <p>We enforce semver at publish time because the ecosystem already assumes
 * it: resolver/lockfile logic, "latest" resolution, range matching, client
 * caches — all break down when a registry accepts "v1" or "1.0" or
 * "nightly-2024-02-01". Reject at the boundary, not three layers deep.
 *
 * <p>Pattern mirrors the reference regex from semver.org (2.0.0 appendix B),
 * including optional pre-release and build-metadata segments.
 */
public final class Semver {

    private static final Pattern PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)"
                    + "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
                    + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

    private Semver() {}

    public static boolean isValid(String version) {
        return version != null && PATTERN.matcher(version).matches();
    }

    public static void require(String version) {
        if (!isValid(version)) {
            throw new IllegalArgumentException("version is not valid semver: " + version);
        }
    }
}
