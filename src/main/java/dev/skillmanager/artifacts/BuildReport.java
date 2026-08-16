package dev.skillmanager.artifacts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON contract of {@code skill-manager build --json}.
 *
 * <p>Same rules as {@link StaleReport}: {@code schema} first and versioned,
 * lowercase-hyphen tokens, one object shape per row, counts computed here.
 *
 * <h2>{@code freshness_after} is measured, and may be absent</h2>
 *
 * <p>Every row carries the verdict this home reached BEFORE the build and, when
 * the build ran, the verdict re-derived from the home AFTER it. They are
 * separate fields because they are separate observations, and the second is
 * {@code null} on a dry run rather than optimistic — nothing was rebuilt, so
 * there is nothing to have re-measured.
 *
 * <p>{@code freshness_after} is emphatically <b>not</b> "the build returned 0".
 * For the backends that record no install fingerprint it will read
 * {@code unverifiable} on a successful rebuild, and that is the true answer:
 * the rebuild happened and nothing in this home can confirm what it produced
 * (#120). A schema that reported those rows as {@code current} because the
 * command succeeded would be the presence proxy this epic exists to remove,
 * one verb further along.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"schema", "home", "dry_run", "summary", "steps"})
public record BuildReport(
        int schema,
        String home,
        @JsonProperty("dry_run") boolean dryRun,
        Summary summary,
        List<StepView> steps
) {

    /** Bumped when a consumer would have to change to keep reading this. */
    public static final int SCHEMA = 1;

    @JsonPropertyOrder({"id", "kind", "owner", "action", "producer", "reason",
            "freshness_before", "materialization_before",
            "freshness_after", "materialization_after",
            "outcome", "verifiable"})
    public record StepView(
            String id,
            String kind,
            String owner,
            /** {@code rebuild} / {@code already-current} / {@code not-buildable}. */
            String action,
            String producer,
            String reason,
            @JsonProperty("freshness_before") String freshnessBefore,
            @JsonProperty("materialization_before") String materializationBefore,
            /** Re-derived after the run; null on a dry run or when nothing ran. */
            @JsonProperty("freshness_after") String freshnessAfter,
            @JsonProperty("materialization_after") String materializationAfter,
            /** {@code built} / {@code failed} / {@code planned} / {@code skipped}. */
            String outcome,
            /**
             * Whether this home can check what the rebuild produced.
             *
             * <p>Before the run this is a plan-time expectation — false for
             * every backend whose lock row carries no install fingerprint.
             * AFTER a run it is replaced by the measurement, because the
             * expectation can be wrong in the good direction: a {@code pip} row
             * with no fingerprint gets one written by
             * {@code CliInstallRecorder} as part of the rebuild, so the
             * artifact that could not be verified beforehand can be verified
             * afterwards. Reporting the stale expectation over the fresh
             * measurement would be this epic's own mistake in miniature.
             */
            boolean verifiable
    ) {}

    @JsonPropertyOrder({"selected", "rebuilt", "failed", "already_current", "not_buildable",
            "still_stale"})
    public record Summary(
            int selected,
            int rebuilt,
            int failed,
            @JsonProperty("already_current") int alreadyCurrent,
            @JsonProperty("not_buildable") int notBuildable,
            /**
             * Selected artifacts that are still stale after the run — the
             * number an operator needs and the one a "rebuilt: 3" line hides.
             */
            @JsonProperty("still_stale") int stillStale
    ) {}

    /** Outcome tokens, so the command and this document cannot spell them differently. */
    public static final String BUILT = "built";
    public static final String FAILED = "failed";
    public static final String PLANNED = "planned";
    public static final String SKIPPED = "skipped";

    public static BuildReport of(String home, boolean dryRun, ArtifactBuild.Plan plan,
                                 Map<String, String> outcomes,
                                 ArtifactFreshness after) {
        List<StepView> views = new ArrayList<>();
        int rebuilt = 0;
        int failed = 0;
        int alreadyCurrent = 0;
        int notBuildable = 0;
        int stillStale = 0;
        for (ArtifactBuild.Step step : plan.steps()) {
            String outcome = outcomes.getOrDefault(step.id(),
                    switch (step.action()) {
                        case REBUILD -> dryRun ? PLANNED : SKIPPED;
                        case ALREADY_CURRENT, NOT_BUILDABLE -> SKIPPED;
                    });
            ArtifactFreshness.Verdict now = after == null ? null : after.of(step.id());
            if (BUILT.equals(outcome)) rebuilt++;
            if (FAILED.equals(outcome)) failed++;
            if (step.action() == ArtifactBuild.Action.ALREADY_CURRENT) alreadyCurrent++;
            if (step.action() == ArtifactBuild.Action.NOT_BUILDABLE) notBuildable++;
            if (now != null && now.freshness() == ArtifactFreshness.Freshness.STALE) stillStale++;
            views.add(new StepView(step.id(), step.kind().id(), step.owner(),
                    step.action().token(), step.producer(), step.reason(),
                    step.before().token(), Artifact.token(step.materialization()),
                    now == null ? null : now.freshness().token(),
                    now == null ? null : Artifact.token(now.materialization()),
                    outcome,
                    now == null ? !step.unverifiableAfterBuild()
                                : now.freshness() != ArtifactFreshness.Freshness.UNVERIFIABLE));
        }
        return new BuildReport(SCHEMA, home, dryRun,
                new Summary(plan.steps().size(), rebuilt, failed, alreadyCurrent, notBuildable,
                        stillStale),
                views);
    }

    /** An empty selection — a real answer, and it still names the home. */
    public static BuildReport empty(String home, boolean dryRun) {
        return new BuildReport(SCHEMA, home, dryRun,
                new Summary(0, 0, 0, 0, 0, 0), List.of());
    }

    /** Convenience for the command: an outcome map it can fill as effects land. */
    public static Map<String, String> outcomes() { return new LinkedHashMap<>(); }
}
