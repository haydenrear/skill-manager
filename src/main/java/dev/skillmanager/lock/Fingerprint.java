package dev.skillmanager.lock;

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
 * constructor: it is EITHER a digest with a {@link #basis} saying what the
 * digest covers, OR a {@link #gap} saying in one sentence why no digest was
 * computable. Never both, never neither. An unfingerprintable artifact is a
 * known gap that a listing can print; it is not a silently-current one.
 *
 * <h2>{@link #basis} is not decoration</h2>
 *
 * <p>The schemes differ in strength and the difference is material. A digest
 * over {@code pip:ruff==0.6.9} answers "did my declared input change" and
 * cannot answer "did the thing I installed move underneath me", because a pin
 * never moves. A digest that also covers the version resolved into
 * {@code venvs/ruff} answers both. Both are honest CONTENT fingerprints over
 * declared inputs, and they do not detect the same class of staleness, so the
 * record says which one it is rather than leaving the next reader to infer it
 * from the backend id.
 *
 * @param value the hex digest, or null when {@link #gap} is set
 * @param basis a short phrase naming the facts the digest covers; null iff
 *              {@code value} is null
 * @param gap   why no digest was computable; null iff {@code value} is non-null
 */
public record Fingerprint(String value, String basis, String gap) {

    public Fingerprint {
        if ((value == null) == (gap == null)) {
            throw new IllegalArgumentException(
                    "a Fingerprint is either a digest with a basis or a gap with a reason — "
                            + "never both and never neither (value=" + value + ", gap=" + gap + ")");
        }
        if (value != null && (basis == null || basis.isBlank())) {
            throw new IllegalArgumentException(
                    "a fingerprint digest must say what it covers; basis was blank");
        }
    }

    /** A computed digest, with the phrase naming what went into it. */
    public static Fingerprint over(String digest, String basis) {
        return new Fingerprint(digest, basis, null);
    }

    /** No digest, and the reason — which is recorded, not merely logged. */
    public static Fingerprint gap(String why) {
        return new Fingerprint(null, null,
                why == null || why.isBlank() ? "no reason given" : why);
    }

    public boolean present() { return value != null; }
}
