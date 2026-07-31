package dev.skillmanager.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.shared.util.Fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * What every unit in a home contained, file by file, at one moment.
 *
 * <h2>Why per file and not just per home</h2>
 *
 * <p>The purpose of this record is to let a pull tell an agent <em>what
 * changed</em>. An agent read a skill twenty minutes ago and has been acting on
 * it since; a sync that quietly replaces that skill invalidates everything the
 * agent believes without producing a single visible symptom. "The home changed"
 * is not actionable — "these three lines of the skill you are following changed"
 * is. So the digest is a map of paths, and {@link DriftReport} is a diff.
 *
 * <h2>{@code .git} is excluded</h2>
 *
 * <p>A git directory rewrites itself during read-only commands. Including it
 * would report drift on every sync, and a signal that fires every time is one
 * that gets ignored — which is the same outcome as not having it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"schemaVersion", "computedAt", "digest", "units"})
public record HomeDigest(
        int schemaVersion,
        String computedAt,
        String digest,
        List<UnitDigest> units
) {

    public static final String FILENAME = "home.digest.json";

    public static final int SCHEMA_VERSION = 1;

    /** Directory names never included in a digest, at any depth. */
    public static final Set<String> EXCLUDED = Set.of(".git");

    public HomeDigest {
        units = units == null ? List.of() : List.copyOf(units);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"name", "kind", "digest", "entries"})
    public record UnitDigest(String name, String kind, String digest, Map<String, String> entries) {
        public UnitDigest {
            entries = entries == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(entries));
        }
    }

    public Optional<UnitDigest> unit(String name) {
        return units.stream().filter(u -> u.name().equals(name)).findFirst();
    }

    public Set<String> unitNames() {
        return units.stream().map(UnitDigest::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    // ------------------------------------------------------------- compute

    /**
     * Digest every unit the store currently holds.
     *
     * <p>Driven off {@code listInstalledUnits} so it sees exactly the units the
     * rest of skill-manager sees, rather than whatever directories happen to
     * exist. A directory that does not parse as a unit is not something an agent
     * can be following.
     */
    public static HomeDigest compute(SkillStore store) throws IOException {
        List<UnitDigest> units = new ArrayList<>();
        for (AgentUnit unit : store.listInstalledUnits().units()) {
            Path dir = store.unitDir(unit.name(), unit.kind()).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
            Map<String, String> entries = ChildHomeMaterializer.entryDigests(dir, EXCLUDED);
            units.add(new UnitDigest(unit.name(), unit.kind().name(), digestOf(entries), entries));
        }
        units.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new HomeDigest(SCHEMA_VERSION, Instant.now().toString(), digestOfUnits(units), units);
    }

    private static String digestOf(Map<String, String> entries) {
        MessageDigest digest = sha256();
        for (String rel : new TreeSet<>(entries.keySet())) {
            update(digest, rel);
            update(digest, entries.get(rel));
        }
        return hex(digest.digest());
    }

    private static String digestOfUnits(List<UnitDigest> units) {
        MessageDigest digest = sha256();
        for (UnitDigest unit : units) {
            update(digest, unit.kind() + ":" + unit.name());
            update(digest, unit.digest());
        }
        return hex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xf, 16))
                .append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }

    // ---------------------------------------------------------------- serde

    public static Path file(SkillStore store) {
        return store.root().resolve(FILENAME);
    }

    public void write(SkillStore store) throws IOException {
        Fs.ensureDir(store.root());
        mapper().writerWithDefaultPrettyPrinter().writeValue(file(store).toFile(), this);
    }

    /**
     * The recorded digest, or empty when there is none.
     *
     * <p>An unreadable file is treated as absent rather than fatal. A corrupt
     * baseline means "we cannot say what changed", and the answer to that is to
     * record a fresh one, not to refuse every command in the home — the digest
     * is a comparison aid, and the guarantees that actually protect content (the
     * hold-back rule) do not depend on it.
     */
    public static Optional<HomeDigest> read(SkillStore store) {
        Path f = file(store);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.ofNullable(mapper().readValue(f.toFile(), HomeDigest.class));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }
}
