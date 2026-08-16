package dev.skillmanager.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.lock.Fingerprints;
import dev.skillmanager.store.HomeDigest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * What the generated marketplace was generated FROM, one row per plugin.
 *
 * <h2>Why a sidecar and not a field in {@code marketplace.json}</h2>
 *
 * <p>{@code marketplace.json} is not skill-manager's file. It is read by
 * {@code claude plugin} and {@code codex plugin marketplace} against
 * Anthropic's {@code marketplace.schema.json}, and a plugin entry carrying an
 * unrecognised key is a bet on those CLIs being lenient — a bet whose downside
 * is every plugin in the home becoming unloadable. This file holds the same
 * facts one directory over, is owned entirely by skill-manager, and is removed
 * with the marketplace root it sits in.
 *
 * <h2>What the digest covers, and why that is {@code resolved}</h2>
 *
 * <p>A marketplace entry is derived from the installed plugin set, so this
 * digests, per plugin:
 *
 * <ul>
 *   <li>the entry's own identity — its name, the {@code ./plugins/<n>} source
 *       the manifest names, and the link target stored in the symlink tree;</li>
 *   <li><b>the plugin's contents in the store</b>, as a per-file digest of
 *       {@code plugins/<n>} with {@code .git} excluded.</li>
 * </ul>
 *
 * <p>The second half is what makes the grade {@link Fingerprint.Kind#RESOLVED}
 * rather than {@link Fingerprint.Kind#DECLARED}, and the distinction is not
 * cosmetic. A digest over the declared plugin SET — names and source paths —
 * moves when a plugin is installed or removed and cannot move when the plugin
 * the entry exposes is updated underneath it. A digest that also covers the
 * bytes moves in both cases. The set-only form is an honest {@code declared}
 * and it is what this file records when the store directory cannot be read;
 * it is never relabelled {@code resolved} to make the entry look stronger.
 *
 * <p>The walk reuses {@link ChildHomeMaterializer#entryDigests} and
 * {@link HomeDigest#EXCLUDED} rather than growing a second definition of "the
 * content of a unit" — a second walker is free to disagree with the first about
 * symlinks, executable bits and which entries count at all, and then nothing
 * says which is right.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"schemaVersion", "generatedAt", "marketplaceName", "plugins"})
public record MarketplaceInputs(
        int schemaVersion,
        String generatedAt,
        String marketplaceName,
        List<Entry> plugins
) {

    /** Beside {@code plugins/} and {@code .claude-plugin/}, inside the marketplace root. */
    public static final String FILENAME = ".marketplace-inputs.json";

    public static final int SCHEMA_VERSION = 1;

    /** Versioned per {@link Fingerprints}: changing what it covers means a new suffix. */
    public static final String SCHEME = "marketplace-entry-v1";

    public MarketplaceInputs {
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
    }

    /**
     * One plugin's row.
     *
     * <p>{@code inputFingerprintKind} is the closed token
     * ({@code resolved}/{@code declared}/{@code unknown}) a consumer grades on;
     * {@code inputFingerprintBasis} is prose for whoever opens the file. They
     * are two fields for the reason {@code cli-lock.toml} keeps them apart —
     * recovering a category by pattern-matching a sentence is the same defect
     * as inferring "current" from "a file exists".
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"name", "source", "target", "inputFingerprintScheme", "inputFingerprint",
            "inputFingerprintKind", "inputFingerprintBasis", "inputFingerprintGap"})
    public record Entry(
            String name,
            String source,
            /** The link target stored in {@code plugin-marketplace/plugins/<n>}. */
            String target,
            String inputFingerprintScheme,
            String inputFingerprint,
            String inputFingerprintKind,
            String inputFingerprintBasis,
            String inputFingerprintGap
    ) {

        /** The row as a {@link Fingerprint}, or empty when it records neither. */
        public Optional<Fingerprint> fingerprint() {
            if (inputFingerprint != null && !inputFingerprint.isBlank()) {
                Fingerprint.Kind kind = Fingerprint.Kind.fromToken(inputFingerprintKind);
                return Optional.of(new Fingerprint(inputFingerprint,
                        // A row from before the grade existed reads UNKNOWN and
                        // is never guessed into RESOLVED.
                        kind == null ? Fingerprint.Kind.UNKNOWN : kind,
                        inputFingerprintBasis == null || inputFingerprintBasis.isBlank()
                                ? "an ungraded digest recorded before this field existed"
                                : inputFingerprintBasis,
                        null));
            }
            return inputFingerprintGap == null || inputFingerprintGap.isBlank()
                    ? Optional.empty() : Optional.of(Fingerprint.gap(inputFingerprintGap));
        }

        public static Entry of(String name, String source, String target, Fingerprint fp) {
            return new Entry(name, source, target,
                    fp != null && fp.present() ? SCHEME : null,
                    fp == null ? null : fp.value(),
                    fp == null || fp.kind() == null ? null : fp.kind().token(),
                    fp == null ? null : fp.basis(),
                    fp == null ? null : fp.gap());
        }
    }

    // ------------------------------------------------------------ fingerprint

    /**
     * The digest for one entry, computed from what the generator can SEE at
     * generation time.
     *
     * @param pluginDir the plugin's directory in the store, or null when this
     *                  home does not have it — which yields a graded gap rather
     *                  than a digest over half the inputs
     */
    public static Fingerprint fingerprintOf(String name, String source, String target,
                                            Path pluginDir) {
        Fingerprints digest = Fingerprints.scheme(SCHEME)
                .field("plugin", name)
                .field("source", source)
                .field("target", target);
        if (pluginDir == null || !Files.isDirectory(pluginDir)) {
            return Fingerprint.gap("the plugin's store directory is not readable in this home, "
                    + "so the contents this entry exposes could not be digested");
        }
        Map<String, String> entries;
        try {
            entries = ChildHomeMaterializer.entryDigests(pluginDir, HomeDigest.EXCLUDED);
        } catch (IOException e) {
            return Fingerprint.gap("the plugin's store directory could not be walked: "
                    + e.getMessage());
        }
        // Sorted, because a digest whose value depends on directory-iteration
        // order is a digest that moves without its inputs moving.
        for (String relative : new TreeSet<>(entries.keySet())) {
            digest.field("file", relative).field("sha", entries.get(relative));
        }
        return Fingerprint.resolved(digest.hex(),
                "the entry's name, source path and stored link target, plus a per-file digest "
                        + "of every byte under plugins/" + name + " (.git excluded)");
    }

    // ------------------------------------------------------------------ serde

    public static Path file(Path marketplaceRoot) {
        return marketplaceRoot.resolve(FILENAME);
    }

    /** The sidecar in {@code marketplaceRoot}, or empty when it has never been written. */
    public static Optional<MarketplaceInputs> read(Path marketplaceRoot) {
        Path f = file(marketplaceRoot);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.of(mapper().readValue(f.toFile(), MarketplaceInputs.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<Entry> plugin(String name) {
        return plugins.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    public static MarketplaceInputs of(String marketplaceName, List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> a.name().compareTo(b.name()));
        return new MarketplaceInputs(SCHEMA_VERSION, Instant.now().toString(),
                marketplaceName, sorted);
    }

    public void write(Path marketplaceRoot) throws IOException {
        Files.createDirectories(marketplaceRoot);
        mapper().writerWithDefaultPrettyPrinter().writeValue(file(marketplaceRoot).toFile(), this);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }
}
