package dev.skillmanager.artifacts;

import java.util.Locale;

/**
 * The classes of derived thing a Skill Manager home holds.
 *
 * <p>One constant per class the artifact census enumerates
 * ({@code evals/artifacts/census.py} in the integration repository), which is
 * the instrument this epic is measured by. The census splits {@code bin/cli}
 * into two rows by backend because one half records an input fingerprint and
 * the other does not; that is a property of the <em>backend</em>, not of the
 * artifact, so it is one kind here with the backend in the id.
 *
 * <p>The census does not count harness instances, though its row is named
 * "plugin marketplace + harness registration". {@link #HARNESS_INSTANCE} is a
 * kind here because a {@code .harness-instance.json} is a derived record with
 * an owning unit and an output tree exactly like the other eight, and leaving
 * it out would put an artifact in the home that no listing can name. The
 * difference is reconciled in the PR rather than hidden.
 *
 * <h2>Nothing here is a verdict</h2>
 *
 * <p>A kind says what a thing IS. Whether it is current, and by what evidence,
 * is {@link Artifact#agreement()} and {@link Artifact#materialization()} —
 * deliberately separate, because the epic's central finding is that a home
 * conflates "there is a file there" with "it is the right file".
 */
public enum ArtifactKind {

    /** A unit's bytes in the store: {@code skills/<n>}, {@code plugins/<n>}, … */
    UNIT_STORE,

    /** A generated executable in {@code bin/cli/}, one per locked CLI dep. */
    CLI_SHIM,

    /**
     * A machine-provisioned tree under {@code cache/}, {@code venvs/},
     * {@code tools/}, {@code npm/} or {@code pm/}. Nothing in a home declares
     * these today — they exist only as whatever an installer wrote — which is
     * why a backfilled listing gives them no inputs and no source record.
     */
    PROVISIONED_TREE,

    /** One agent-visible link, copy or import directive a binding produced. */
    PROJECTION,

    /** One plugin row in the generated {@code plugin-marketplace/} tree. */
    MARKETPLACE_ENTRY,

    /** One instantiated harness sandbox under {@code harnesses/instances/}. */
    HARNESS_INSTANCE,

    /** One MCP server registered with the gateway. */
    MCP_REGISTRATION,

    /** A doc unit's managed import set — the store copy plus what it projects. */
    DOC_IMPORT,

    /**
     * One unit's entry in {@code home.digest.json}. Referenced, never copied:
     * the digest is 5.3 MB over ~30k file entries on a real home, and a second
     * copy of it is a second thing that can disagree with the disk.
     */
    UNIT_DIGEST;

    /** The stable, lowercase-hyphen form used in ids, JSON and the ledger. */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Inverse of {@link #id()}; null for an unknown token. */
    public static ArtifactKind fromId(String token) {
        if (token == null) return null;
        String normalized = token.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ArtifactKind kind : values()) {
            if (kind.name().equals(normalized)) return kind;
        }
        return null;
    }
}
