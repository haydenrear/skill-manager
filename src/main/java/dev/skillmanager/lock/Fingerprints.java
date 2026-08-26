package dev.skillmanager.lock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one encoder every install fingerprint scheme is built with.
 *
 * <h2>The domain-separation prefix is the whole point</h2>
 *
 * <p>{@code SkillScriptBackend.fingerprintScripts} opened with
 * {@code "skill-script-v1\0"} so that a later caller hashing differently
 * structured input could not collide with it. When four more backends grow
 * their own schemes, that stops being a nicety: {@code tar} hashes a URL and a
 * declared sha256, {@code pip} hashes a package spec and a resolved version,
 * and nothing about their field text prevents two of them encoding to the same
 * bytes. Two artifacts with the same digest are one artifact as far as every
 * consumer downstream is concerned, and the consumer this epic is building
 * ({@code artifacts}, ARTI-05's edges) reads digests across backends in one
 * listing.
 *
 * <p>So {@link #scheme(String)} is the only constructor and it is the only way
 * to get an instance: there is no way to start hashing without naming your
 * scheme. Every scheme carries a {@code -vN} suffix, and changing what a scheme
 * covers means a new suffix rather than an edit — an edit silently invalidates
 * every fingerprint already in every {@code cli-lock.toml}, which for
 * {@code skill-script} means re-running arbitrary installer scripts on the next
 * sync of every home on the machine.
 *
 * <h2>Encoding</h2>
 *
 * <p>{@code <scheme>\0} followed, in the order the caller adds them, by
 * {@code <key>:<value>\0} for each field and {@code <key>:<relpath>\0} +
 * {@code <bytes>} + {@code \0} for each file. Null values are skipped and empty
 * ones are not — see {@link #field}, where that distinction is a collision and
 * not a nicety. The fingerprint's {@link Fingerprint#kind()} records which
 * grade of facts were present and its {@link Fingerprint#basis()} records which
 * facts they were.
 */
public final class Fingerprints {

    private final MessageDigest digest;

    private Fingerprints(String scheme) {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        digest.update((scheme + "\0").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Start a digest in {@code scheme}, which must be a versioned name such as
     * {@code "pip-v1"}. Callers never share a scheme across backends.
     */
    public static Fingerprints scheme(String scheme) {
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalArgumentException("a fingerprint scheme must be named");
        }
        return new Fingerprints(scheme);
    }

    /**
     * Add {@code key:value}. A NULL value is skipped — a fact nobody has is
     * absent, exactly as {@link dev.skillmanager.artifacts.Artifact} treats it.
     *
     * <p><b>An EMPTY value is not skipped</b>, and the distinction is
     * load-bearing rather than pedantic. "This field does not apply here" and
     * "this field is the empty string" are different declarations, and
     * collapsing them makes two different inputs produce one digest — the one
     * property this class exists to guarantee. It would also have silently
     * revised an existing scheme: the pre-ARTI-04 encoder wrote {@code arg:\0}
     * for an empty-string arg, so skipping it made {@code args=[""]} collide
     * with {@code args=[]} and moved a digest {@code SkillScriptBackend} GATES
     * on. Latent — no manifest in either live home declares an empty arg — and
     * pinned by a golden vector regardless, because "nothing does that yet" is
     * not a property of an encoding.
     */
    public Fingerprints field(String key, String value) {
        if (value == null) return this;
        digest.update((key + ":" + value + "\0").getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** Add {@code key:value} for each element of {@code values}, in order. */
    public Fingerprints fields(String key, Iterable<String> values) {
        if (values == null) return this;
        for (String value : values) field(key, value);
        return this;
    }

    /**
     * Add {@code key:name}, then every byte of {@code file}, then a terminator.
     * The terminator is what stops {@code ("ab", "")} and {@code ("a", "b")}
     * from hashing alike across two adjacent files.
     */
    public Fingerprints file(String key, String name, Path file) throws IOException {
        field(key, name);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        digest.update((byte) 0);
        return this;
    }

    /** The finished digest, lowercase hex. */
    public String hex() {
        return HexFormat.of().formatHex(digest.digest());
    }
}
