package dev.skillmanager.lock;

import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;

/**
 * One row in {@code units.lock.toml}. Captures the resolved identity of an
 * installed unit so {@code sync --lock} can reproduce a vendored install
 * set byte-for-byte.
 *
 * <p>The shape mirrors {@link InstalledUnit} but trims to just the fields
 * needed to reproduce: name, kind, version, where it came from, and the
 * resolved sha (for git-tracked sources) — no errors list, no install
 * timestamp. Resolution-time facts only; runtime drift lives in
 * {@code installed/<name>.json}.
 *
 * <p>{@code origin} is the git remote URL for non-registry installs (or
 * the canonical registry URL for registry installs — empty when the
 * installer didn't capture one). {@code ref} is the install-time branch
 * or tag the user pinned ({@code main}, {@code v1.2.3}, ...); null/empty
 * for sha-detached installs. {@code resolvedSha} is what's actually
 * checked out — for non-git sources (LOCAL_FILE / UNKNOWN) it's null.
 */
public record LockedUnit(
        String name,
        UnitKind kind,
        String version,
        InstalledUnit.InstallSource installSource,
        String origin,
        String ref,
        String resolvedSha
) {
    public LockedUnit {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("LockedUnit.name must be non-empty");
        }
        if (kind == null) {
            throw new IllegalArgumentException("LockedUnit.kind must be non-null");
        }
        if (installSource == null) {
            installSource = InstalledUnit.InstallSource.UNKNOWN;
        }
    }

    /** Convert an {@link InstalledUnit} record to a lock row. */
    public static LockedUnit fromInstalled(InstalledUnit u) {
        return new LockedUnit(
                u.name(),
                u.unitKind(),
                u.version(),
                u.installSource(),
                u.origin(),
                u.gitRef(),
                u.gitHash()
        );
    }
}
