package dev.skillmanager.bindings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.skillmanager.lock.Fingerprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * Per-instance metadata persisted at {@code <sandboxRoot>/<instanceId>/.harness-instance.json}
 * when {@code harness instantiate} runs. Captures the target
 * paths the instantiator resolved so {@code sync harness:<name>} can
 * re-plan with the same layout without re-deriving them from the env
 * (which may have drifted) or scraping bindings.
 *
 * <p>Lives inside the sandbox dir alongside the user's resolved
 * targetDir — even when {@code projectDir} is elsewhere, the
 * {@code <sandbox>/<id>/} dir still exists as a thin marker holding
 * this file. {@code harness rm} deletes the whole sandbox dir, taking
 * the lock with it.
 *
 * <h2>And the template it was instantiated from</h2>
 *
 * <p>The paths above say WHERE the instance landed. They said nothing about
 * WHAT it was made from, so {@code sync harness:<n>} re-instantiated with
 * {@link ConflictPolicy#OVERWRITE} on every pass and re-running was the only
 * way to learn whether the template had moved. The
 * {@code templateFingerprint*} fields are
 * {@link HarnessInstantiator#fingerprintOf} at the moment of writing —
 * a digest, the grade its producer asserts, and the phrase saying what it
 * covers, in the same three-field shape {@code cli-lock.toml} uses.
 *
 * <p>An instance created before these fields existed carries none of them, and
 * reads back as no fingerprint at all — not as an {@code unknown} one over an
 * invented digest, and not as a passing grade. The first sync of that instance
 * computes a real one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HarnessInstanceLock(
        String harnessName,
        String instanceId,
        Path claudeConfigDir,
        Path codexHome,
        Path geminiHome,
        Path projectDir,
        String createdAt,
        /** {@link HarnessInstantiator#FINGERPRINT_SCHEME} when a digest is present. */
        String templateFingerprintScheme,
        String templateFingerprint,
        /** {@code resolved} / {@code declared} / {@code unknown}. */
        String templateFingerprintKind,
        String templateFingerprintBasis,
        String templateFingerprintGap,
        /** When the fingerprint below was last (re)computed. */
        String templateFingerprintAt
) {

    public static final String FILENAME = ".harness-instance.json";

    /**
     * The pre-fingerprint shape. Kept so callers written before this field
     * existed compile unchanged, and so a lock read back from a home that
     * predates it reports NO fingerprint rather than a guessed one.
     */
    public HarnessInstanceLock(String harnessName, String instanceId, Path claudeConfigDir,
                               Path codexHome, Path geminiHome, Path projectDir,
                               String createdAt) {
        this(harnessName, instanceId, claudeConfigDir, codexHome, geminiHome, projectDir,
                createdAt, null, null, null, null, null, null);
    }

    /**
     * This lock carrying {@code fingerprint}, stamped now.
     *
     * <p>A legacy row — one with no digest and no gap — stays legacy until a
     * pass actually computes one. It is never promoted to
     * {@link Fingerprint.Kind#UNKNOWN} with a made-up digest, and never to
     * {@code resolved}: the whole reason the kind is written down is that a
     * reader cannot recover it from the digest, so a reader inventing one is
     * the defect the field exists to prevent.
     */
    public HarnessInstanceLock withTemplateFingerprint(Fingerprint fingerprint) {
        if (fingerprint == null) return this;
        String kind = fingerprint.kind() == null ? null : fingerprint.kind().token();
        // `templateFingerprintAt` says when this DIGEST was computed, so a sync
        // that re-derives the same digest keeps the stamp it already carries.
        // Restamping unconditionally made every `sync harness:<n>` rewrite the
        // instance record whether or not the template had moved — churn in the
        // one file whose job is to say whether it moved.
        boolean unchanged = templateFingerprintAt != null
                && java.util.Objects.equals(templateFingerprint, fingerprint.value())
                && java.util.Objects.equals(templateFingerprintKind, kind)
                && java.util.Objects.equals(templateFingerprintGap, fingerprint.gap());
        return new HarnessInstanceLock(harnessName, instanceId, claudeConfigDir, codexHome,
                geminiHome, projectDir, createdAt,
                fingerprint.present() ? HarnessInstantiator.FINGERPRINT_SCHEME : null,
                fingerprint.value(),
                kind,
                fingerprint.basis(),
                fingerprint.gap(),
                unchanged ? templateFingerprintAt : Instant.now().toString());
    }

    /** The recorded fingerprint, or empty when this lock records neither a digest nor a gap. */
    public Optional<Fingerprint> fingerprint() {
        if (templateFingerprint != null && !templateFingerprint.isBlank()) {
            Fingerprint.Kind kind = Fingerprint.Kind.fromToken(templateFingerprintKind);
            return Optional.of(new Fingerprint(templateFingerprint,
                    kind == null ? Fingerprint.Kind.UNKNOWN : kind,
                    templateFingerprintBasis == null || templateFingerprintBasis.isBlank()
                            ? "an ungraded digest recorded before this field existed"
                            : templateFingerprintBasis,
                    null));
        }
        return templateFingerprintGap == null || templateFingerprintGap.isBlank()
                ? Optional.empty() : Optional.of(Fingerprint.gap(templateFingerprintGap));
    }

    public static Path file(Path sandboxRoot, String instanceId) {
        return sandboxRoot.resolve(instanceId).resolve(FILENAME);
    }

    /**
     * Write with paths stored verbatim. Prefer
     * {@link #write(Path, Path)} — the sandbox root is
     * {@code <home>/harnesses/instances}, and when the caller does not
     * override the agent homes they default to subdirectories of it, so
     * these fields routinely point back into the home that holds this file.
     */
    public void write(Path sandboxRoot) throws IOException {
        write(sandboxRoot, null);
    }

    /** Write with self-references relative to {@code homeRoot}. */
    public void write(Path sandboxRoot, Path homeRoot) throws IOException {
        Path f = file(sandboxRoot, instanceId);
        Files.createDirectories(f.getParent());
        BindingJson.mapperFor(homeRoot).writerWithDefaultPrettyPrinter()
                .writeValue(f.toFile(), this);
    }

    public static Optional<HarnessInstanceLock> read(Path sandboxRoot, String instanceId) {
        return read(sandboxRoot, instanceId, null);
    }

    /** Read, resolving {@code $SKILL_MANAGER_HOME} against {@code homeRoot}. */
    public static Optional<HarnessInstanceLock> read(Path sandboxRoot, String instanceId,
                                                     Path homeRoot) {
        Path f = file(sandboxRoot, instanceId);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.of(BindingJson.mapperFor(homeRoot)
                    .readValue(f.toFile(), HarnessInstanceLock.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
