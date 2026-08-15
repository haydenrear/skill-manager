package dev.skillmanager.artifacts;

import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

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
 * {@code $SKILL_MANAGER_HOME/artifacts.lock.toml} — the one ledger that names
 * every derived thing in a home.
 *
 * <h2>A new file rather than a section of {@code cli-lock.toml}</h2>
 *
 * <p>Both were on the table and the reasons are structural, not stylistic.
 *
 * <ol>
 *   <li>{@code cli-lock.toml}'s key space is {@code [backend][tool]}. It can
 *       name one of the nine classes; the other eight have no key in it. A
 *       projection is not a tool, and a per-unit digest row is not a backend.</li>
 *   <li>{@code cli-lock.toml} is an <b>input to re-provisioning</b>:
 *       {@code HomeCloner} skips {@code venvs/}, {@code tools/} and {@code npm/}
 *       precisely so a clone can rebuild them "from {@code cli-lock.toml}". A
 *       derived index inside a provisioning input means a bad index is a broken
 *       install.</li>
 *   <li>{@link dev.skillmanager.lock.CliLock#save} rewrites that file <em>whole</em>
 *       from its in-memory model. Any artifact section added beside the tool
 *       tables would be silently dropped by every existing writer — the recorder
 *       and the dependency cleaner both call {@code save} — which is a data-loss
 *       path, not a hypothetical.</li>
 *   <li>It costs nothing on the axis that mattered: both files are root-level,
 *       so {@link dev.skillmanager.store.HomeCloner#classify} gives both
 *       {@code Surface.STATE} and both get identical treatment by a clone.</li>
 * </ol>
 *
 * <h2>How this survives {@code HomeCloner}</h2>
 *
 * <p>A clone re-anchors {@code Surface.STATE} through the production serde for
 * the record classes it models, and {@code reanchorRemainingState} byte-
 * substitutes the source home's path inside anything it does not. The
 * substitution branch is the one to stay out of: it is a catch-all whose own
 * javadoc asks the writer of the file to "consider encoding it at write time".
 *
 * <p>So this file encodes it at write time, in the strongest available form —
 * <b>it contains no absolute path at all</b>, and {@link #save} refuses to
 * write one. Consequences, in order:
 *
 * <ul>
 *   <li>the byte-substitution pass finds no needle, so the file crosses a clone
 *       <em>byte-identical</em>;</li>
 *   <li>{@code HomeCloner.verify} finds no surviving reference, so it can never
 *       be the thing that fails a clone;</li>
 *   <li>ids are therefore identical on both sides by construction rather than by
 *       a re-anchoring pass getting it right, which is what ARTI-07 needs to
 *       compare a ticket home against the home it was cloned from;</li>
 *   <li>and it cannot inherit a claim over somebody else's directory. That is
 *       the failure mode {@code DROPPED_STATE_DIRS} and {@code insideNewHome}
 *       exist for: a copied {@code projects/} registration made a clone
 *       materialize agent directories in five unrelated checkouts. An artifact
 *       whose output lands outside the home — every agent-side projection —
 *       therefore records no path here at all. It records {@link Row#source()},
 *       the home-relative record that already owns that claim and already has
 *       the machinery to filter it.</li>
 * </ul>
 *
 * <h2>An index of identity, not a copy of state</h2>
 *
 * <p>No fingerprint, hash, version or timestamp of an artifact is stored here.
 * Every such fact already has an owner — {@code installed/<u>.json}'s
 * {@code gitHash}, {@code cli-lock.toml}'s {@code install_fingerprint}, a
 * projection's {@code boundHash}, {@code home.digest.json}'s per-unit digest —
 * and a second copy is a second thing that can disagree with the disk. This
 * epic exists because a recorded hash is not automatically true; reproducing
 * three more of them would be the same defect with better coverage.
 *
 * <p>What follows from that is the property the ticket actually needs: because
 * the ledger holds only identity, it is <b>always rebuildable</b> from the
 * records it references, so a home that predates it lists correctly with no
 * rebuild ({@link ArtifactBackfill}) and the file is an optimisation and a
 * memory of what USED to exist — never a prerequisite.
 */
public final class ArtifactLedger {

    public static final String FILENAME = "artifacts.lock.toml";

    /** Bumped when the on-disk shape changes in a way a reader must notice. */
    public static final int SCHEMA = 1;

    /** One persisted row: identity and shape, and nothing that can go stale. */
    public record Row(
            String id,
            ArtifactKind kind,
            String owner,
            List<String> inputs,
            /** Home-relative output paths only. External outputs are not stored. */
            List<String> outputs,
            String source
    ) {
        public Row {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
        }

        public static Row of(Artifact artifact) {
            List<String> homeOutputs = new ArrayList<>();
            for (Artifact.Output output : artifact.outputs()) {
                if (output.scope() == Artifact.Scope.HOME) homeOutputs.add(output.path());
            }
            return new Row(artifact.id(), artifact.kind(), artifact.owner(),
                    artifact.inputs(), homeOutputs, artifact.source());
        }
    }

    private final int schema;
    private final String recordedAt;
    private final Map<String, Row> rows;

    private ArtifactLedger(int schema, String recordedAt, Map<String, Row> rows) {
        this.schema = schema;
        this.recordedAt = recordedAt;
        this.rows = rows;
    }

    public static ArtifactLedger empty() {
        return new ArtifactLedger(SCHEMA, null, new LinkedHashMap<>());
    }

    public static ArtifactLedger of(List<Artifact> artifacts) {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (Artifact artifact : artifacts) rows.put(artifact.id(), Row.of(artifact));
        return new ArtifactLedger(SCHEMA, Instant.now().toString(), rows);
    }

    public static Path file(SkillStore store) {
        return store.root().resolve(FILENAME);
    }

    public int schema() { return schema; }

    public String recordedAt() { return recordedAt; }

    public List<Row> rows() { return List.copyOf(rows.values()); }

    public Optional<Row> byId(String id) { return Optional.ofNullable(rows.get(id)); }

    public boolean isEmpty() { return rows.isEmpty(); }

    /**
     * Read the ledger, or an empty one when there is none. A ledger that is
     * absent is the ordinary case for every home that predates this ticket and
     * is never an error — the listing backfills instead.
     */
    public static ArtifactLedger load(SkillStore store) throws IOException {
        Path file = file(store);
        if (!Files.isRegularFile(file)) return empty();
        TomlParseResult toml = Toml.parse(Files.readString(file));
        if (toml.hasErrors()) {
            throw new IOException(FILENAME + " has errors: " + toml.errors());
        }
        int fileSchema = toml.contains("schema")
                ? (int) toml.getLong("schema", () -> SCHEMA) : SCHEMA;
        String at = toml.getString("recorded_at");
        Map<String, Row> rows = new LinkedHashMap<>();
        TomlArray array = toml.getArray("artifact");
        for (int i = 0; array != null && i < array.size(); i++) {
            // Not `array.containsTables()`: tomlj 1.1.1 throws
            // UnsupportedOperationException from it ("arrays are
            // heterogeneous"). Ask each element instead.
            Object element = array.get(i);
            if (!(element instanceof TomlTable table)) continue;
            String id = table.getString("id");
            ArtifactKind kind = ArtifactKind.fromId(table.getString("kind"));
            // A row this version cannot name is dropped rather than guessed at.
            // Forgetting one artifact is a listing that says so through
            // Origin.HOME; inventing a kind is a listing that lies.
            if (id == null || id.isBlank() || kind == null) continue;
            rows.put(id, new Row(id, kind, table.getString("owner"),
                    strings(table.getArray("inputs")),
                    strings(table.getArray("outputs")),
                    table.getString("source")));
        }
        return new ArtifactLedger(fileSchema, at, rows);
    }

    /**
     * Write the ledger, refusing any absolute path.
     *
     * <p>The refusal is the enforcement of this class's whole contract, and it
     * is a check rather than a comment because the comment version of it is
     * what the byte-substitution catch-all in {@code HomeCloner} exists to
     * clean up after. A caller that hands over an absolute path has produced a
     * row that would either leak the source home into a copy or claim a
     * directory the copy does not own; both are worth failing on.
     */
    public void save(SkillStore store) throws IOException {
        Fs.ensureDir(store.root());
        StringBuilder sb = new StringBuilder();
        sb.append("# skill-manager artifact ledger — every derived thing this home holds.\n");
        sb.append("# Auto-managed by `skill-manager artifacts record`; rebuildable at any\n");
        sb.append("# time from the records the `source` fields name. Identity only: no\n");
        sb.append("# fingerprint, hash or timestamp of an artifact is copied here, and no\n");
        sb.append("# absolute path is written, so a home clone carries this file unchanged.\n");
        sb.append("schema = ").append(SCHEMA).append('\n');
        String at = recordedAt == null ? Instant.now().toString() : recordedAt;
        sb.append("recorded_at = ").append(string(at)).append("\n");
        String home = store.root().toAbsolutePath().normalize().toString();
        for (Row row : rows.values()) {
            sb.append("\n[[artifact]]\n");
            sb.append("id = ").append(string(relative(row, row.id(), home))).append('\n');
            sb.append("kind = ").append(string(row.kind().id())).append('\n');
            if (row.owner() != null) sb.append("owner = ").append(string(row.owner())).append('\n');
            if (!row.inputs().isEmpty()) {
                sb.append("inputs = ").append(stringArray(row, row.inputs(), home)).append('\n');
            }
            if (!row.outputs().isEmpty()) {
                sb.append("outputs = ").append(stringArray(row, row.outputs(), home)).append('\n');
            }
            if (row.source() != null) {
                sb.append("source = ").append(string(relative(row, row.source(), home))).append('\n');
            }
        }
        Path file = file(store);
        Path tmp = file.resolveSibling(FILENAME + ".tmp");
        Files.writeString(tmp, sb.toString());
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ utils

    /**
     * The check that enforces this class's contract.
     *
     * <p>Three shapes are refused, and the second is the one a naive
     * "does it start with a slash" test misses: an absolute path EMBEDDED in a
     * typed reference. {@code installed/<u>.json}'s {@code origin} is usually a
     * git URL and may be a local directory, so {@code git:/Users/…/checkout} is
     * a reachable value and is a claim over a checkout elsewhere on the
     * machine. {@code https://host/path} is not — the {@code //} after the
     * scheme separates a URL authority from a filesystem root, and that is the
     * distinction the second test makes.
     */
    private static String relative(Row row, String value, String homeRoot) throws IOException {
        if (value == null) return null;
        String reason = null;
        if (value.startsWith("/") || value.startsWith("~")
                || value.matches("^[A-Za-z]:[\\\\/].*")) {
            reason = "it is an absolute path";
        } else if (value.matches(".*:/(?!/).*")) {
            reason = "it embeds an absolute path after a scheme";
        } else if (homeRoot != null && !homeRoot.isBlank() && value.contains(homeRoot)) {
            reason = "it names this home's own root";
        }
        if (reason == null) return value;
        throw new IOException(FILENAME + ": refusing to write \"" + value + "\" for artifact "
                + row.id() + " — " + reason + ", and this ledger holds home-relative values"
                + " only, so that a home clone carries it unchanged and inherits no claim"
                + " over a directory outside the copy");
    }

    private static String stringArray(Row row, List<String> values, String homeRoot)
            throws IOException {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(string(relative(row, values.get(i), homeRoot)));
        }
        return sb.append(']').toString();
    }

    private static String string(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static List<String> strings(TomlArray array) {
        if (array == null) return List.of();
        List<String> out = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (value != null) out.add(value.toString());
        }
        return List.copyOf(out);
    }
}
