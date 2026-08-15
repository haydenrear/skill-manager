package dev.skillmanager.artifacts;

import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The read model: everything the home can see now, overlaid with everything the
 * ledger says it once had.
 *
 * <p>The overlay is the whole reason to keep a ledger at all, and it is one
 * rule: <b>the home wins on facts, the ledger wins on existence.</b> A live
 * record always describes the artifact better than a snapshot of it, so a
 * backfilled artifact keeps its own outputs, presence and agreement. But an
 * artifact the home can no longer see is not gone — a cloned ticket home does
 * not carry {@code cache/}, {@code venvs/}, {@code tools/} or {@code npm/} at
 * all, so ten provisioned trees vanish from the backfill and reappear from the
 * ledger as {@link Artifact.Origin#LEDGER} with
 * {@link Artifact.Materialization#DECLARED_ONLY}.
 *
 * <p>That single row is the thing ARTI-07 needs and nothing in a home has
 * today: "this artifact is supposed to exist here, and it does not". It only
 * works because ids are stable across homes — the tree recorded in the source
 * home and the tree missing from the clone have to be the same name, or the
 * overlay is comparing two unrelated lists.
 */
public final class ArtifactIndex {

    private final Path home;
    private final boolean ledgerPresent;
    private final ArtifactLedger ledger;
    private final List<Artifact> artifacts;

    private ArtifactIndex(Path home, boolean ledgerPresent, ArtifactLedger ledger,
                          List<Artifact> artifacts) {
        this.home = home;
        this.ledgerPresent = ledgerPresent;
        this.ledger = ledger;
        this.artifacts = artifacts;
    }

    public static ArtifactIndex of(SkillStore store) throws IOException {
        Path root = store.root().toAbsolutePath().normalize();
        boolean present = Files.isRegularFile(ArtifactLedger.file(store));
        ArtifactLedger ledger = ArtifactLedger.load(store);
        List<Artifact> derived = new ArtifactBackfill(store).collect();

        Map<String, Artifact> merged = new LinkedHashMap<>();
        for (Artifact artifact : derived) {
            Optional<ArtifactLedger.Row> row = ledger.byId(artifact.id());
            merged.put(artifact.id(), row.isEmpty() ? artifact
                    : reconcile(artifact, row.get()));
        }
        for (ArtifactLedger.Row row : ledger.rows()) {
            if (merged.containsKey(row.id())) continue;
            merged.put(row.id(), fromLedger(root, row));
        }
        List<Artifact> ordered = new ArrayList<>(merged.values());
        ordered.sort((a, b) -> {
            int byKind = a.kind().compareTo(b.kind());
            return byKind != 0 ? byKind : a.id().compareTo(b.id());
        });
        return new ArtifactIndex(root, present, ledger, List.copyOf(ordered));
    }

    /**
     * A live artifact that the ledger also knows about.
     *
     * <p>Every live fact is kept. The one thing the ledger may contribute is a
     * DECLARATION the home cannot produce: a provisioned tree has no inputs
     * when it is backfilled, because nothing in a home declares one, and a
     * previously recorded row is the only place that knowledge can come from.
     */
    private static Artifact reconcile(Artifact live, ArtifactLedger.Row row) {
        List<String> inputs = live.inputs().isEmpty() ? row.inputs() : live.inputs();
        String source = live.source() != null ? live.source() : row.source();
        return new Artifact(live.id(), live.kind(), live.owner() != null ? live.owner() : row.owner(),
                inputs, live.outputs(), source, live.recorded(), live.actual(),
                live.agreement(), Artifact.Origin.LEDGER_AND_HOME,
                // Kept, and never taken from the row: the ledger has no
                // observed inputs to contribute because it is never given any.
                live.observedInputs());
    }

    /** An artifact the ledger declares and the home can no longer see. */
    private static Artifact fromLedger(Path home, ArtifactLedger.Row row) {
        List<Artifact.Output> outputs = new ArrayList<>();
        for (String path : row.outputs()) {
            outputs.add(Artifact.Output.inHome(path,
                    ArtifactBackfill.presenceOf(home.resolve(path))));
        }
        return new Artifact(row.id(), row.kind(), row.owner(), row.inputs(), outputs,
                row.source(), Map.of(), Map.of(),
                Artifact.Agreement.UNRECORDED, Artifact.Origin.LEDGER);
    }

    public Path home() { return home; }

    public boolean ledgerPresent() { return ledgerPresent; }

    public ArtifactLedger ledger() { return ledger; }

    public List<Artifact> artifacts() { return artifacts; }

    public Optional<Artifact> byId(String id) {
        if (id == null) return Optional.empty();
        for (Artifact artifact : artifacts) {
            if (artifact.id().equals(id)) return Optional.of(artifact);
        }
        return Optional.empty();
    }

    /** Ids that start with {@code prefix} — what a near-miss lookup suggests. */
    public List<String> idsMatching(String fragment) {
        List<String> out = new ArrayList<>();
        if (fragment == null || fragment.isBlank()) return out;
        String needle = fragment.toLowerCase(java.util.Locale.ROOT);
        for (Artifact artifact : artifacts) {
            if (artifact.id().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                out.add(artifact.id());
            }
        }
        return out;
    }
}
