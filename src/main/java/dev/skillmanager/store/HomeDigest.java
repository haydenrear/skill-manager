package dev.skillmanager.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.shared.util.GitIgnoreRules;
import dev.skillmanager.shared.util.Rederivable;

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

    /**
     * <b>2 — re-derivable and unit-declared paths stopped being content.</b>
     *
     * <p>Bumped with {@link #EXCLUDED} and {@code withoutIgnored}, because a
     * digest is only evidence about the set of files it was computed over. A
     * schema-1 record hashed {@code .venv}, {@code build/} and every path the
     * unit's own {@code .gitignore} excludes; diffing it against a schema-2
     * digest reports each of those as a DELETION, which would have gated every
     * existing home behind a drift report of phantom removals on the first run
     * after upgrade. {@link #read} therefore treats an older record as absent,
     * so the first run silently re-baselines instead. This is issue #41's
     * lesson applied to the sibling record: the materialization record already
     * carries its own version for exactly this reason, and this one was writing
     * a version nobody read.
     */
    public static final int SCHEMA_VERSION = 2;

    /**
     * Directory names never included in a digest, at any depth.
     *
     * <p>{@code .git} is the store's own plumbing. The rest is
     * {@link Rederivable}'s derived set -- {@code __pycache__}, {@code .venv},
     * {@code build}, {@code node_modules} and friends -- which
     * {@code HomeCloner}, {@code ChildHomeMaterializer}, {@code ArtifactPrune}
     * and {@code DereferencedStoreLinks} already agree is not unit content.
     * This digest was the one reader that did not ask, so a venv rebuild or a
     * {@code pip install -e .} registered as the home changing underneath the
     * operator and gated the next launch behind an acknowledgement.
     */
    public static final Set<String> EXCLUDED = excluded();

    private static Set<String> excluded() {
        Set<String> all = new LinkedHashSet<>(Set.of(".git"));
        all.addAll(Rederivable.CACHES);
        all.addAll(Rederivable.OUTPUT_ROOTS);
        return Set.copyOf(all);
    }

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
            // A unit's own .gitignore is its author declaring what is not
            // content. Honouring it here is what keeps setuptools `*.egg-info`
            // trees and unit-local run logs -- 22 of the 33 files in the drift
            // that gated this home in practice -- out of a report whose whole
            // job is to show the operator something they need to decide about.
            // A TRACKED file still counts however it was produced: `uv.lock` is
            // regenerated by a build and is content all the same.
            Map<String, String> entries = withoutIgnored(dir,
                    ChildHomeMaterializer.entryDigests(dir, EXCLUDED));
            units.add(new UnitDigest(unit.name(), unit.kind().name(), digestOf(entries), entries));
        }
        units.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new HomeDigest(SCHEMA_VERSION, Instant.now().toString(), digestOfUnits(units), units);
    }

    /**
     * Drop entries the unit's own {@code .gitignore} excludes.
     *
     * <p>Rules are read once per unit. A unit with no {@code .gitignore} gets
     * {@link GitIgnoreRules#NONE} and every entry survives, so this can only
     * ever remove paths an author has already declared uninteresting.
     */
    private static Map<String, String> withoutIgnored(Path unitDir, Map<String, String> entries) {
        GitIgnoreRules rules = GitIgnoreRules.forUnit(unitDir);
        if (rules == GitIgnoreRules.NONE || entries.isEmpty()) return entries;
        Map<String, String> kept = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (!rules.ignores(e.getKey(), false)) kept.put(e.getKey(), e.getValue());
        }
        return kept;
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
            HomeDigest read = mapper().readValue(f.toFile(), HomeDigest.class);
            // A record from an older schema was computed over a different set
            // of files, so it is not evidence about these bytes -- same reading
            // as an unreadable one, and the same answer: record a fresh
            // baseline rather than report a diff that is an artifact of the
            // upgrade. See SCHEMA_VERSION.
            if (read != null && read.schemaVersion() != SCHEMA_VERSION) return Optional.empty();
            return Optional.ofNullable(read);
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }
}
