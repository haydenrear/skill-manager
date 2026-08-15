package dev.skillmanager.artifacts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON contract of {@code skill-manager artifacts list|show --json}.
 *
 * <h2>Designed as an API, not as a print</h2>
 *
 * <p>Two consumers are already scheduled against it — ARTI-09's Python kernel
 * and ARTI-10's {@code skt} surface — so the shape is fixed here rather than
 * inferred later from whatever the renderer happened to emit:
 *
 * <ul>
 *   <li><b>{@code schema} first, and versioned.</b> A consumer can refuse a
 *       shape it does not know instead of mis-parsing it.</li>
 *   <li><b>Enum values are lowercase-hyphen strings</b>, the same tokens the
 *       ledger and the ids use, so {@code jq 'select(.kind == "cli-shim")'}
 *       and {@code artifacts list --kind cli-shim} agree without a mapping
 *       table on either side.</li>
 *   <li><b>Every artifact is one object with the same keys</b>, including the
 *       ones whose value is empty. A consumer indexing by kind should never
 *       have to know that a projection has no {@code recorded} block.</li>
 *   <li><b>{@code summary} is computed here</b>, so two consumers counting the
 *       same home cannot disagree about the totals through two counting
 *       implementations.</li>
 * </ul>
 *
 * <p>Nothing in this file is a verdict about staleness. It reports what is
 * recorded, what is on disk, and whether the two agree; deciding what to do
 * about a disagreement is ARTI-05's and ARTI-06's, and a read command that
 * prescribed a rebuild would be the presence-proxy mistake in a new place.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"schema", "home", "ledger", "artifacts", "summary"})
public record ArtifactReport(
        int schema,
        String home,
        LedgerView ledger,
        List<ArtifactView> artifacts,
        Summary summary
) {

    /** Bumped when a consumer would have to change to keep reading this. */
    public static final int SCHEMA = 1;

    @JsonPropertyOrder({"path", "present", "schema", "recorded_at", "artifacts"})
    public record LedgerView(
            String path,
            boolean present,
            Integer schema,
            @JsonProperty("recorded_at") String recordedAt,
            int artifacts
    ) {}

    @JsonPropertyOrder({"path", "scope", "presence"})
    public record OutputView(String path, String scope, String presence) {}

    @JsonPropertyOrder({"id", "kind", "owner", "materialization", "agreement", "origin",
            "inputs", "outputs", "source", "recorded", "actual"})
    public record ArtifactView(
            String id,
            String kind,
            String owner,
            String materialization,
            String agreement,
            String origin,
            List<String> inputs,
            List<OutputView> outputs,
            String source,
            Map<String, String> recorded,
            Map<String, String> actual
    ) {}

    @JsonPropertyOrder({"artifacts", "by_kind", "by_materialization", "by_agreement", "by_origin"})
    public record Summary(
            int artifacts,
            @JsonProperty("by_kind") Map<String, Integer> byKind,
            @JsonProperty("by_materialization") Map<String, Integer> byMaterialization,
            @JsonProperty("by_agreement") Map<String, Integer> byAgreement,
            @JsonProperty("by_origin") Map<String, Integer> byOrigin
    ) {}

    public static ArtifactView view(Artifact artifact) {
        List<OutputView> outputs = artifact.outputs().stream()
                .map(o -> new OutputView(o.path(), token(o.scope()), token(o.presence())))
                .toList();
        return new ArtifactView(
                artifact.id(),
                artifact.kind().id(),
                artifact.owner(),
                token(artifact.materialization()),
                token(artifact.agreement()),
                token(artifact.origin()),
                artifact.inputs(),
                outputs,
                artifact.source(),
                artifact.recorded(),
                artifact.actual());
    }

    public static ArtifactReport of(ArtifactIndex index, List<Artifact> selected) {
        ArtifactLedger ledger = index.ledger();
        LedgerView ledgerView = new LedgerView(
                ArtifactLedger.FILENAME,
                index.ledgerPresent(),
                index.ledgerPresent() ? ledger.schema() : null,
                index.ledgerPresent() ? ledger.recordedAt() : null,
                ledger.rows().size());

        Map<String, Integer> byKind = new LinkedHashMap<>();
        for (ArtifactKind kind : ArtifactKind.values()) byKind.put(kind.id(), 0);
        Map<String, Integer> byMaterialization = new LinkedHashMap<>();
        for (Artifact.Materialization value : Artifact.Materialization.values()) {
            byMaterialization.put(token(value), 0);
        }
        Map<String, Integer> byAgreement = new LinkedHashMap<>();
        for (Artifact.Agreement value : Artifact.Agreement.values()) {
            byAgreement.put(token(value), 0);
        }
        Map<String, Integer> byOrigin = new LinkedHashMap<>();
        for (Artifact.Origin value : Artifact.Origin.values()) byOrigin.put(token(value), 0);

        List<ArtifactView> views = new java.util.ArrayList<>(selected.size());
        for (Artifact artifact : selected) {
            views.add(view(artifact));
            byKind.merge(artifact.kind().id(), 1, Integer::sum);
            byMaterialization.merge(token(artifact.materialization()), 1, Integer::sum);
            byAgreement.merge(token(artifact.agreement()), 1, Integer::sum);
            byOrigin.merge(token(artifact.origin()), 1, Integer::sum);
        }

        return new ArtifactReport(SCHEMA, index.home().toString(), ledgerView, List.copyOf(views),
                new Summary(views.size(), byKind, byMaterialization, byAgreement, byOrigin));
    }

    private static String token(Enum<?> value) {
        return value == null ? null
                : value.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
