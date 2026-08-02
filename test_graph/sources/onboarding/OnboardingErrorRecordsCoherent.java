///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>Step 10 — persisted error records: is the {@code file:} source contract
 * coherent, and does a record print once and clear itself?</b>
 *
 * <h2>The incoherent contract</h2>
 *
 * <p>{@code skill-project.toml} accepts {@code source = "file:///abs/path"}.
 * {@code project resolve} installs it and exits 0, printing
 * {@code ✓ installed acme-lint} and a summary. From then on <b>every</b>
 * invocation — {@code list}, {@code --help}, {@code exec},
 * {@code home describe}, {@code bindings list} — appends
 *
 * <pre>
 * ⚠ skills with outstanding errors (1) — 1 distinct cause(s) — re-run after fixing:
 *   acme-lint:
 *     - NEEDS_GIT_MIGRATION: not git-tracked; file/local installs do not sync …
 * </pre>
 *
 * <p>and {@code sync} exits non-zero because of it. {@code coords-and-distribution.md}
 * does say {@code file:} is "for local dry-run and validation only" — but the
 * project manifest schema accepts it, resolve celebrates it, and the permanent
 * error state is discovered only afterwards.
 *
 * <p><b>Assertion (a disjunction):</b> either {@code project resolve} REJECTS a
 * {@code file:} unit source, or a {@code file:}-installed unit does not leave a
 * permanent error record on every subsequent command. Accept-install-celebrate-
 * then-error-forever is not a coherent contract either way.
 *
 * <p><b>Vacuous-pass risk:</b> a disjunction passes if either branch holds, and
 * it would pass trivially if the fixture's unit happened to be git-tracked.
 * <br><b>Companion:</b> the fixture unit is asserted to have no {@code .git}
 * and no git remote BEFORE anything is concluded, and the resolve is asserted
 * to have exited 0 — so the "rejected" branch is known not to have been taken,
 * and the node RECORDS which branch the implementation chose rather than
 * hiding it behind a green tick.
 *
 * <h2>The dedup-and-clear guard</h2>
 *
 * <p>A persisted error affecting N units must print ONCE, not per unit, and
 * removing the cause must remove it from the banner.
 *
 * <p><b>Vacuous-pass risk:</b> counting occurrences in a log too short to
 * contain a duplicate.
 * <br><b>Companion:</b> the banner's own {@code (N)} count must equal the
 * number of affected units and N must be ≥2, so a single-unit home cannot make
 * "printed once" true by arithmetic. Then the cause is removed and the banner
 * must shrink.
 */
public class OnboardingErrorRecordsCoherent {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.error.records.coherent")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.project.markdown.imports.checked")
            .tags("onboarding", "errors")
            .timeout("900s");

    static final String BANNER = "skills with outstanding errors";
    static final String CAUSE = "NEEDS_GIT_MIGRATION";
    static final Pattern BANNER_COUNT =
            Pattern.compile("skills with outstanding errors \\((\\d+)\\)");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path proj = path(ctx, "onboarding.fixture.built", "proj");
            Path sources = path(ctx, "onboarding.fixture.built", "sourcesDir");
            Path home = path(ctx, "onboarding.bootstrapped", "projectHome");
            String syncLogPath = ctx.get("onboarding.synced", "syncLog").orElse("");
            if (proj == null || home == null || sources == null) {
                return NodeResult.fail("onboarding.error.records.coherent",
                        "missing upstream context");
            }

            // --- the companion that keeps the disjunction honest ---------------
            Path lintSource = sources.resolve(OnboardingSupport.LINT);
            boolean theFixtureUnitIsNotGitTracked =
                    !Files.exists(lintSource.resolve(".git"))
                            && !HomeSyncSupport.git(lintSource, "rev-parse", "--git-dir").ok();
            boolean theResolveThatInstalledItExitedZero =
                    OnboardingSupport.storeUnits(home).contains(OnboardingSupport.LINT);

            // --- which branch did the implementation choose? --------------------
            List<ProcessRecord> readOnly = new ArrayList<>();
            readOnly.add(OnboardingSupport.sm(ctx, "err-list", home, proj, "list"));
            readOnly.add(OnboardingSupport.sm(ctx, "err-home-describe", home, proj,
                    "home", "describe"));
            readOnly.add(OnboardingSupport.sm(ctx, "err-bindings", home, proj,
                    "bindings", "list"));
            List<String> commandsCarryingTheBanner = new ArrayList<>();
            for (ProcessRecord p : readOnly) {
                if (OnboardingSupport.log(ctx, p).contains(BANNER)) {
                    commandsCarryingTheBanner.add(p.label());
                }
            }
            boolean aFileInstalledUnitLeavesNoPermanentErrorRecord =
                    commandsCarryingTheBanner.isEmpty();
            String branch = !theResolveThatInstalledItExitedZero
                    ? "REJECTED: project resolve refused the file: source"
                    : aFileInstalledUnitLeavesNoPermanentErrorRecord
                            ? "ACCEPTED AND CLEAN: installed, and no permanent error record"
                            : "INCOHERENT: installed and celebrated, then errors on every"
                                    + " subsequent command (" + commandsCarryingTheBanner + ")";
            boolean theFileSourceContractIsCoherent =
                    !theResolveThatInstalledItExitedZero
                            || aFileInstalledUnitLeavesNoPermanentErrorRecord;

            // --- dedup: the record prints once, not per unit ---------------------
            String syncLog = syncLogPath.isEmpty() ? ""
                    : OnboardingSupport.read(Path.of(syncLogPath));
            int bannerOccurrences = OnboardingSupport.count(syncLog, BANNER);
            int causeOccurrences = OnboardingSupport.count(syncLog, CAUSE + ":");
            String countRaw = OnboardingSupport.firstGroup(syncLog, BANNER_COUNT);
            int affected = countRaw == null ? -1 : Integer.parseInt(countRaw);
            // The floor: with fewer than two affected units, "printed once" is
            // arithmetic rather than evidence.
            boolean atLeastTwoUnitsShareTheCause = affected >= 2;
            boolean theRecordPrintedOnceRatherThanPerUnit =
                    bannerOccurrences <= 1 && causeOccurrences <= 1;
            boolean theLogWasLongEnoughToContainADuplicate =
                    syncLog.split("\n", -1).length >= 40;

            // --- clear: removing the cause removes it from the banner -------------
            //
            // Uninstall one affected unit and the count must drop by exactly
            // one. A banner that stayed put would mean the record is not
            // self-clearing; one that vanished entirely would mean it is not
            // per-unit.
            // The victim must be a unit the PROJECT does not declare. A declared
            // one is claimed by the project lock and `uninstall` refuses it —
            // measured: the banner then stayed at its original count and this
            // assertion went red on the harness's choice of unit rather than on
            // the product.
            String victim = null;
            for (String unit : OnboardingSupport.storeUnits(home)) {
                if (unit.equals(OnboardingSupport.GAMMA)) { victim = unit; break; }
            }
            ProcessRecord uninstall = victim == null ? null
                    : OnboardingSupport.sm(ctx, "uninstall-" + victim, home, proj,
                            "uninstall", victim);
            ProcessRecord after = OnboardingSupport.sm(ctx, "err-list-after", home, proj, "list");
            String afterLog = OnboardingSupport.log(ctx, after);
            String afterCountRaw = OnboardingSupport.firstGroup(afterLog, BANNER_COUNT);
            int afterCount = afterCountRaw == null ? 0 : Integer.parseInt(afterCountRaw);
            boolean theBannerShrankWhenTheCauseWasRemoved =
                    uninstall != null && uninstall.exitCode() == 0
                            && affected > 0 && afterCount == affected - 1;

            boolean pass = theFixtureUnitIsNotGitTracked
                    && theFileSourceContractIsCoherent
                    && atLeastTwoUnitsShareTheCause
                    && theLogWasLongEnoughToContainADuplicate
                    && theRecordPrintedOnceRatherThanPerUnit
                    && theBannerShrankWhenTheCauseWasRemoved;

            NodeResult result = pass
                    ? NodeResult.pass("onboarding.error.records.coherent")
                    : NodeResult.fail("onboarding.error.records.coherent",
                            "branch=" + branch
                                    + " affected=" + affected
                                    + " bannerOccurrences=" + bannerOccurrences
                                    + " causeOccurrences=" + causeOccurrences
                                    + " afterCount=" + afterCount);
            for (ProcessRecord p : readOnly) result = result.process(p);
            if (uninstall != null) result = result.process(uninstall);
            return result.process(after)
                    .assertion("the_fixture_unit_is_not_git_tracked_so_the_branch_is_known",
                            theFixtureUnitIsNotGitTracked)
                    .assertion("the_file_source_contract_is_coherent",
                            theFileSourceContractIsCoherent)
                    .assertion("at_least_two_units_share_the_cause_so_dedup_is_measurable",
                            atLeastTwoUnitsShareTheCause)
                    .assertion("the_log_was_long_enough_to_contain_a_duplicate",
                            theLogWasLongEnoughToContainADuplicate)
                    .assertion("the_error_record_printed_once_rather_than_per_unit",
                            theRecordPrintedOnceRatherThanPerUnit)
                    .assertion("the_banner_shrank_by_one_when_one_cause_was_removed",
                            theBannerShrankWhenTheCauseWasRemoved)
                    .metric("unitsAffected", affected)
                    .metric("bannerOccurrencesInSyncLog", bannerOccurrences)
                    .metric("unitsAffectedAfterUninstall", afterCount)
                    .log("branch chosen by the implementation: " + branch)
                    .log("read-only commands carrying the banner: " + commandsCarryingTheBanner);
        });
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
