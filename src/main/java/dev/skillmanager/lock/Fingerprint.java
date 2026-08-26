package dev.skillmanager.lock;

import java.util.Locale;

/**
 * What a backend can say about the INPUTS to the artifact it installed.
 *
 * <h2>Why this is a value and not a {@code String}</h2>
 *
 * <p>Before this record the recorder held a {@code String fingerprint} that was
 * either a hex digest or {@code null}, and {@code null} meant four different
 * things that a reader could not tell apart: this backend has no fingerprint
 * scheme; this backend has one but the unit is not on disk; this dep declares
 * nothing to hash; and — for the four backends the recorder never asked —
 * nobody looked. An artifact with no fingerprint then falls back to
 * {@code CliPresence.alreadyProvided}, which answers "does it exist and run",
 * so all four spellings of "unknown" were reported as the same thing that
 * "current" is reported as. That is the proxy-check defect one layer up from
 * where {@code CliArtifact} caught it.
 *
 * <p>So a fingerprint is a value with an invariant, checked in the compact
 * constructor: it is EITHER a digest — with a {@link Kind} grading it and a
 * {@link #basis} saying what it covers — OR a {@link #gap} saying in one
 * sentence why no digest was computable. Never both, never neither, and never
 * an empty digest, which is the "neither" case wearing the first one's shape.
 * An unfingerprintable artifact is a known gap a listing can print; it is not a
 * silently-current one.
 *
 * <h2>{@link #kind} is asserted by the producer; {@link #basis} is for a human</h2>
 *
 * <p>The schemes differ in strength and the difference is material. A digest
 * over {@code pip:ruff==0.6.9} answers "did my declared input change" and
 * cannot answer "did the thing I installed move underneath me", because a pin
 * never moves. A digest that also covers the version resolved into
 * {@code venvs/ruff} answers both.
 *
 * <p>Both facts are recorded, as two fields, on purpose:
 *
 * <ul>
 *   <li>{@link #kind} is a closed enum the BACKEND asserts, because the backend
 *       is the only thing that knows — pip knows whether it found the
 *       {@code .dist-info}, tar knows it never looks at a disk at all. A
 *       consumer grading these rows reads this field.</li>
 *   <li>{@link #basis} is prose, for whoever opens {@code cli-lock.toml} and
 *       wants to know WHICH facts went in rather than which grade came out.</li>
 * </ul>
 *
 * <p>They are not collapsed into one, and a consumer must not recover the kind
 * by pattern-matching the basis. Inferring a category from free text is the
 * same shape as inferring "current" from "a file exists" — the defect this epic
 * exists to remove — and it would leave the epic's headline metric at the mercy
 * of a sentence's wording.
 *
 * @param value the hex digest, or null when {@link #gap} is set
 * @param kind  the grade of evidence the digest rests on; null iff
 *              {@code value} is null
 * @param basis a short phrase naming the facts the digest covers; null iff
 *              {@code value} is null
 * @param gap   why no digest was computable; null iff {@code value} is non-null
 */
public record Fingerprint(String value, Kind kind, String basis, String gap) {

    /**
     * How strong the evidence under a digest is.
     *
     * <p>Declared last-is-weakest on purpose: {@link #UNKNOWN} is not a passing
     * grade, and a consumer that treats an ungraded row as {@link #RESOLVED}
     * has invented the fact the grade exists to record.
     */
    public enum Kind {
        /**
         * The digest covers something OBSERVED about the installed artifact —
         * the bytes of a scripts tree, the version a package manager resolved
         * into this home. Moves when the artifact moves, whether or not the
         * declaration did.
         */
        RESOLVED,
        /**
         * The digest covers the DECLARATION only. Moves when a manifest is
         * edited and cannot move when upstream does; for a spec that pins
         * nothing it cannot move at all. Honest, and weaker.
         */
        DECLARED,
        /**
         * A digest whose grade was never written down — a row recorded before
         * this field existed. Never guessed into {@link #RESOLVED}: the reason
         * the field exists is that a reader cannot tell from the digest.
         */
        UNKNOWN;

        /** The stable lowercase token used in {@code cli-lock.toml}. */
        public String token() { return name().toLowerCase(Locale.ROOT); }

        /** Inverse of {@link #token()}; null for an unrecognized token. */
        public static Kind fromToken(String token) {
            if (token == null) return null;
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            for (Kind k : values()) if (k.token().equals(normalized)) return k;
            return null;
        }
    }

    public Fingerprint {
        if ((value == null) == (gap == null)) {
            throw new IllegalArgumentException(
                    "a Fingerprint is either a digest with a basis or a gap with a reason — "
                            + "never both and never neither (value=" + value + ", gap=" + gap + ")");
        }
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(
                    "an empty digest is the no-fingerprint case wearing a fingerprint's shape; "
                            + "use Fingerprint.gap(why)");
        }
        if (value != null && (basis == null || basis.isBlank())) {
            throw new IllegalArgumentException(
                    "a fingerprint digest must say what it covers; basis was blank");
        }
        if (value != null && kind == null) {
            throw new IllegalArgumentException(
                    "a fingerprint digest must carry the grade its producer asserts; "
                            + "a reader must not have to infer one");
        }
    }

    /**
     * A digest covering something observed about the installed artifact. Only
     * this grade can answer "did the thing I installed move".
     */
    public static Fingerprint resolved(String digest, String basis) {
        return new Fingerprint(digest, Kind.RESOLVED, basis, null);
    }

    /** A digest covering the declaration alone, and saying so. */
    public static Fingerprint declared(String digest, String basis) {
        return new Fingerprint(digest, Kind.DECLARED, basis, null);
    }

    /** A digest read back from a row written before the grade was recorded. */
    public static Fingerprint ungraded(String digest, String basis) {
        return new Fingerprint(digest, Kind.UNKNOWN, basis, null);
    }

    /** No digest, and the reason — which is recorded, not merely logged. */
    public static Fingerprint gap(String why) {
        return new Fingerprint(null, null, null,
                why == null || why.isBlank() ? "no reason given" : why);
    }

    public boolean present() { return value != null; }

    /** Whether this digest rests on something observed about the artifact. */
    public boolean isResolved() { return kind == Kind.RESOLVED; }
}
