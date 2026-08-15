package dev.skillmanager.artifacts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON contract of {@code skill-manager artifacts stale --json}.
 *
 * <p>Shaped as an API for the same two consumers {@link ArtifactReport} names —
 * ARTI-09's Python kernel and ARTI-10's {@code skt} surface — and with the same
 * rules: {@code schema} first and versioned, lowercase-hyphen enum tokens, one
 * object shape per row, and the summary computed here so that two consumers
 * counting the same home cannot disagree through two counting implementations.
 *
 * <h2>{@code unverifiable} is a top-level list, not a footnote</h2>
 *
 * <p>The default human rendering leads with what is stale, because that is what
 * an operator can act on. The JSON carries both lists at the same level and
 * counts all three states, because the rule this ticket had to hold —
 * <b>a missing input is unverifiable, never current</b> — is only checkable by
 * a consumer that can see the unverifiable set. A schema that reported "stale"
 * and "everything else" would let the rule be violated silently, which is the
 * shape of the bug {@code skt check} shipped and then had to unship.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"schema", "home", "summary", "stale", "unverifiable"})
public record StaleReport(
        int schema,
        String home,
        Summary summary,
        List<VerdictView> stale,
        List<VerdictView> unverifiable
) {

    /** Bumped when a consumer would have to change to keep reading this. */
    public static final int SCHEMA = 1;

    @JsonPropertyOrder({"id", "kind", "owner", "freshness", "reason", "because"})
    public record VerdictView(
            String id,
            String kind,
            String owner,
            String freshness,
            String reason,
            List<String> because
    ) {}

    @JsonPropertyOrder({"artifacts", "stale", "unverifiable", "current", "by_kind"})
    public record Summary(
            int artifacts,
            int stale,
            int unverifiable,
            int current,
            /**
             * STALE count per kind — named for what it counts. It was
             * {@code by_kind}, which reads as a breakdown of {@code artifacts}
             * beside four totals that are not, and a versioned schema should
             * not need a footnote to be read correctly.
             */
            @JsonProperty("stale_by_kind") Map<String, Integer> staleByKind
    ) {}

    public static StaleReport of(String home, ArtifactFreshness freshness) {
        return of(home, freshness, null);
    }

    /**
     * @param kind restrict the whole report — rows AND counts — to one kind, or
     *        null for every kind. Filtering the rows and leaving the counts
     *        unfiltered is how a report comes to contradict itself in the same
     *        breath.
     */
    public static StaleReport of(String home, ArtifactFreshness freshness, ArtifactKind kind) {
        List<ArtifactFreshness.Verdict> staleVerdicts =
                freshness.withFreshness(ArtifactFreshness.Freshness.STALE, kind);
        List<VerdictView> stale = views(staleVerdicts);
        List<VerdictView> unverifiable = views(
                freshness.withFreshness(ArtifactFreshness.Freshness.UNVERIFIABLE, kind));

        Map<String, Integer> staleByKind = new LinkedHashMap<>();
        for (ArtifactKind value : ArtifactKind.values()) {
            if (kind == null || value == kind) staleByKind.put(value.id(), 0);
        }
        for (ArtifactFreshness.Verdict verdict : staleVerdicts) {
            staleByKind.merge(verdict.kind().id(), 1, Integer::sum);
        }

        return new StaleReport(SCHEMA, home,
                new Summary(freshness.total(kind), stale.size(), unverifiable.size(),
                        freshness.count(ArtifactFreshness.Freshness.CURRENT, kind), staleByKind),
                stale, unverifiable);
    }

    private static List<VerdictView> views(List<ArtifactFreshness.Verdict> verdicts) {
        List<VerdictView> out = new ArrayList<>(verdicts.size());
        for (ArtifactFreshness.Verdict verdict : verdicts) {
            out.add(new VerdictView(verdict.id(), verdict.kind().id(), verdict.owner(),
                    verdict.freshness().token(), verdict.reason(), verdict.because()));
        }
        return out;
    }
}
