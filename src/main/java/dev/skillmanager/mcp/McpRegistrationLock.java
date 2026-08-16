package dev.skillmanager.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.shared.util.Fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What THIS home asked the gateway to register, keyed by server id.
 *
 * <h2>The digest was already computed and then thrown away</h2>
 *
 * <p>{@code McpWriter.installOne} computes
 * {@code GatewayClient.specDigest(registerPayload(...))} on every pass, compares
 * it against the gateway's, and discards it. Nothing in the home kept it, so
 * "does the registration still describe what this home declares" could only be
 * answered by talking to a running gateway — and the class scored
 * {@code PRESENCE} in the artifact census because a name in
 * {@code gateway-data/dynamic-servers.json} was the only evidence a registration
 * had ever happened.
 *
 * <p>Persisting the digest skill-manager itself computed makes that question
 * answerable OFFLINE and with no gateway change at all: recompute the payload
 * from the installed unit's declaration and compare. That is a real staleness
 * test for the declaration half, and it is the half this end owns.
 *
 * <h2>What is deliberately NOT copied here</h2>
 *
 * <p>The gateway's own {@code spec_digest} for the same server. Two reasons, and
 * the first alone is sufficient: {@code Artifact}'s rule is that a record
 * REFERENCES the owner of a fact rather than becoming a second copy of it, and a
 * second copy is a second thing that can disagree with the disk. The gateway
 * already persists its digest in its own data directory.
 *
 * <p>The second is that the two digests are not comparable and this file must
 * not look like it thinks they are. The gateway digests its NORMALIZED
 * {@code load_spec} — a pydantic {@code model_dump} with every optional
 * materialised as an explicit null — while {@code GatewayClient.serializeLoad}
 * emits only the keys the variant in use needs. Verified numerically on the
 * project home: {@code 3543db6a…} against {@code 5dfd7168…}, over key sets
 * {@code [binary, docker, env, npm, package, shell, transport, type, uv,
 * version]} and {@code [env, package, type, version]}. That mismatch is its own
 * cross-repo ticket (#121) and needs both ends; this file needs neither.
 *
 * <h2>The grade is {@code declared}, and that is the honest one</h2>
 *
 * <p>The payload is built from the unit's declared {@code mcp_dependencies} plus
 * the init values this install resolved out of its environment. It does not
 * observe the server the gateway actually resolved and ran: a dep naming
 * {@code npm @runpod/mcp-server latest} hashes identically before and after
 * upstream publishes new bytes, exactly as {@code pip:ruff==0.6.9} does. So the
 * grade recorded here is {@link Fingerprint.Kind#DECLARED} — it catches a
 * manifest edit and cannot catch upstream moving, which is what
 * {@code DECLARED} means and is not a weaker version of {@code RESOLVED}
 * pretending otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"schemaVersion", "updatedAt", "servers"})
public record McpRegistrationLock(int schemaVersion, String updatedAt, List<Entry> servers) {

    public static final String FILENAME = "mcp-lock.json";

    public static final int SCHEMA_VERSION = 1;

    /** The scheme name recorded beside every digest this file holds. */
    public static final String SCHEME = "mcp-register-payload-v1";

    /** What {@link #specDigest} covers, in one phrase, for whoever opens the file. */
    public static final String BASIS =
            "the gateway register payload this home posted: the unit's declared mcp spec, "
                    + "the deploy decision, and the init values resolved from this install's "
                    + "environment — not the server the gateway resolved and ran";

    public McpRegistrationLock {
        servers = servers == null ? List.of() : List.copyOf(servers);
    }

    /**
     * One server's row.
     *
     * <p>{@code specDigestKind} is a closed token ({@code resolved} /
     * {@code declared} / {@code unknown}) and {@code specDigestBasis} is prose,
     * for the same reason {@code cli-lock.toml} splits them: a consumer grading
     * these rows reads the token, and must never recover a category by
     * pattern-matching the sentence.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"serverId", "declaredBy", "scope", "recordedAt", "specDigestScheme",
            "specDigest", "specDigestKind", "specDigestBasis", "specDigestGap"})
    public record Entry(
            String serverId,
            /** The unit whose {@code mcp_dependencies} produced this registration. */
            String declaredBy,
            String scope,
            String recordedAt,
            String specDigestScheme,
            String specDigest,
            String specDigestKind,
            String specDigestBasis,
            String specDigestGap
    ) {

        /** The row as a {@link Fingerprint}, or empty when it carries no digest at all. */
        public Optional<Fingerprint> fingerprint() {
            if (specDigest != null && !specDigest.isBlank()) {
                Fingerprint.Kind kind = Fingerprint.Kind.fromToken(specDigestKind);
                String basis = specDigestBasis == null || specDigestBasis.isBlank()
                        ? BASIS : specDigestBasis;
                // A row written before the grade existed reads UNKNOWN. It is
                // never guessed into RESOLVED — the reason the token exists is
                // that a reader cannot tell from the digest.
                return Optional.of(new Fingerprint(specDigest,
                        kind == null ? Fingerprint.Kind.UNKNOWN : kind, basis, null));
            }
            return specDigestGap == null || specDigestGap.isBlank()
                    ? Optional.empty() : Optional.of(Fingerprint.gap(specDigestGap));
        }

        public static Entry of(String serverId, String declaredBy, String scope,
                               Fingerprint fingerprint) {
            return new Entry(serverId, declaredBy, scope, Instant.now().toString(),
                    fingerprint != null && fingerprint.present() ? SCHEME : null,
                    fingerprint == null ? null : fingerprint.value(),
                    fingerprint == null || fingerprint.kind() == null
                            ? null : fingerprint.kind().token(),
                    fingerprint == null ? null : fingerprint.basis(),
                    fingerprint == null ? null : fingerprint.gap());
        }
    }

    public static Path file(Path homeRoot) {
        return homeRoot.resolve(FILENAME);
    }

    /** The lock in {@code homeRoot}, or an empty one when it has never been written. */
    public static McpRegistrationLock read(Path homeRoot) {
        Path f = file(homeRoot);
        if (!Files.isRegularFile(f)) return empty();
        try {
            return mapper().readValue(f.toFile(), McpRegistrationLock.class);
        } catch (IOException e) {
            // An unreadable lock is the no-record case, not a fatal one: this
            // file is evidence about registrations, never an input to making
            // them, so a corrupt one must not stop a sync from registering.
            return empty();
        }
    }

    public static McpRegistrationLock empty() {
        return new McpRegistrationLock(SCHEMA_VERSION, null, List.of());
    }

    public Optional<Entry> server(String serverId) {
        return servers.stream().filter(s -> s.serverId().equals(serverId)).findFirst();
    }

    /** This lock with {@code entry} replacing any row for the same server id. */
    public McpRegistrationLock with(Entry entry) {
        Map<String, Entry> by = new LinkedHashMap<>();
        for (Entry existing : servers) by.put(existing.serverId(), existing);
        by.put(entry.serverId(), entry);
        List<Entry> next = new ArrayList<>(by.values());
        next.sort((a, b) -> a.serverId().compareTo(b.serverId()));
        return new McpRegistrationLock(SCHEMA_VERSION, Instant.now().toString(), next);
    }

    /** This lock without the row for {@code serverId}, if any. */
    public McpRegistrationLock without(String serverId) {
        List<Entry> next = new ArrayList<>();
        for (Entry existing : servers) {
            if (!existing.serverId().equals(serverId)) next.add(existing);
        }
        return new McpRegistrationLock(SCHEMA_VERSION, Instant.now().toString(), next);
    }

    public void write(Path homeRoot) throws IOException {
        Fs.ensureDir(homeRoot);
        mapper().writerWithDefaultPrettyPrinter().writeValue(file(homeRoot).toFile(), this);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }
}
