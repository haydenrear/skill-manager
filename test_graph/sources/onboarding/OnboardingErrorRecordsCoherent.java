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
 * <p>The implementation took the second branch: a deliberate local install is
 * a state the tool is at peace with, reported on every sync ("nothing upstream
 * to sync") and recorded as no error at all. The question the node asks is
 * therefore about ONE unit — is {@code acme-lint}, the {@code file:}-installed
 * one, still held at fault? — rather than about the banner as a whole, which
 * would go red the moment any unrelated unit legitimately carried a cause.
 *
 * <p><b>Vacuous-pass risk:</b> a disjunction passes if either branch holds, and
 * it would pass trivially if the fixture's unit happened to be git-tracked.
 * <br><b>Companion:</b> the fixture unit is asserted to have no {@code .git}
 * and no git remote BEFORE anything is concluded, and the resolve is asserted
 * to have exited 0 — so the "rejected" branch is known not to have been taken,
 * and the node RECORDS which branch the implementation chose rather than
 * hiding it behind a green tick. Both the printed banner AND the record on
 * disk are checked, because a renderer that merely stopped printing the unit
 * would leave the record for the next renderer to find.
 *
 * <h2>The dedup-and-clear guard</h2>
 *
 * <p>A persisted error affecting N units must print ONCE, not per unit, and
 * removing the cause must remove it from the banner.
 *
 * <p><b>Vacuous-pass risk 1:</b> counting occurrences in a log that never
 * printed the banner at all.
 * <br><b>Companion:</b> the banner and its cause must each appear EXACTLY once
 * — not "at most once" — and the banner's own {@code (N)} count must equal the
 * number of affected units, with N ≥ 2, so a single-unit home cannot make
 * "printed once" true by arithmetic. Then the cause is removed and the banner
 * must shrink by exactly one.
 *
 * <p>This companion used to be a LINE COUNT on the sync log (≥ 40 lines), which
 * measured how chatty the console was rather than whether the banner was
 * printed. It went red the moment {@code sync}'s successful output was
 * quietened from 42 lines to 5, while every fact it stood for still held. A
 * guard whose subject is the volume of unrelated output is a guard that fires
 * on unrelated changes and stays silent on the one it was built for.
 *
 * <p><b>Vacuous-pass risk 2 — the one this node was itself guilty of.</b>
 * Until the {@code file:} contract above was fixed, this guard got its "two
 * units sharing one cause" for free: every unit in the fixture is installed
 * from a local path, and every local install carried a permanent
 * {@code NEEDS_GIT_MIGRATION} record. The guard was riding on the defect
 * measured six lines above it — so fixing that defect would have taken the
 * guard's subject away with it and left {@code unitsAffected: 0} reading as a
 * pass. That is the fourth assertion in this graph's history that only held
 * while something was broken.
 * <br><b>Companion:</b> the cause is now PLANTED, as the shape the error was
 * written for — two units whose provenance record the fixture deletes, which
 * the reconciler re-onboards as {@code installSource: UNKNOWN}. Their error is
 * correct and no choice of source would remove it. The node asserts that the
 * shared cause comes from those units specifically, and that the unit the
 * clear half removes is one that actually carried it.
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
            //
            // The question is about ONE unit, not about the banner as a whole.
            // Asserting "no banner at all" would have been right only while
            // every unit in this home was errored, and it would go red the
            // moment any unrelated unit legitimately carried a cause — which
            // is exactly the state the dedup half below deliberately plants.
            // So: does anything in this home still hold acme-lint, the
            // file:-installed unit, at fault?
            List<ProcessRecord> readOnly = new ArrayList<>();
            readOnly.add(OnboardingSupport.sm(ctx, "err-list", home, proj, "list"));
            readOnly.add(OnboardingSupport.sm(ctx, "err-home-describe", home, proj,
                    "home", "describe"));
            readOnly.add(OnboardingSupport.sm(ctx, "err-bindings", home, proj,
                    "bindings", "list"));
            List<String> commandsBlamingTheFileInstalledUnit = new ArrayList<>();
            for (ProcessRecord p : readOnly) {
                if (blames(OnboardingSupport.log(ctx, p), OnboardingSupport.LINT)) {
                    commandsBlamingTheFileInstalledUnit.add(p.label());
                }
            }
            // The state behind the output. A banner that merely stopped
            // PRINTING the unit would leave the record on disk, and the next
            // renderer would find it again.
            boolean theRecordItselfIsClean = !OnboardingSupport.unitRecordSays(
                    home, OnboardingSupport.LINT, "NEEDS_GIT_MIGRATION");
            boolean aFileInstalledUnitLeavesNoPermanentErrorRecord =
                    commandsBlamingTheFileInstalledUnit.isEmpty() && theRecordItselfIsClean;
            String branch = !theResolveThatInstalledItExitedZero
                    ? "REJECTED: project resolve refused the file: source"
                    : aFileInstalledUnitLeavesNoPermanentErrorRecord
                            ? "ACCEPTED AND CLEAN: installed, and no permanent error record"
                            : "INCOHERENT: installed and celebrated, then errors on every"
                                    + " subsequent command ("
                                    + commandsBlamingTheFileInstalledUnit
                                    + ", recordClean=" + theRecordItselfIsClean + ")";
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
            // WHICH units share it is the part that keeps this guard honest.
            // Before the file: contract was fixed the answer was "all of them,
            // because every unit here is a local install" — the guard was
            // riding on the defect measured six lines above it, and fixing that
            // defect would have taken the guard's subject away while leaving it
            // green. The cause must now come from the planted provenance-less
            // units, whose error is correct and which no choice of source
            // would remove.
            int provenancelessCarryingTheCause = 0;
            for (String unit : OnboardingSupport.PROVENANCELESS) {
                if (OnboardingSupport.unitRecordSays(home, unit, CAUSE)) {
                    provenancelessCarryingTheCause++;
                }
            }
            boolean theCauseIsCarriedByUnitsThatCannotBeFixedByChoosingDifferently =
                    provenancelessCarryingTheCause >= 2;
            boolean theRecordPrintedOnceRatherThanPerUnit =
                    bannerOccurrences <= 1 && causeOccurrences <= 1;
            // The floor that keeps "printed once" from being true over a log
            // that says nothing at all.
            //
            // It used to be a LINE COUNT (`syncLog >= 40 lines`), on the
            // reasoning that a log too short cannot contain a duplicate. That
            // proxy measured the CONSOLE'S VERBOSITY, not this property: a sync
            // that prints one line per (unit × agent) is over forty lines
            // whatever it says about error records, and a sync that sends its
            // per-item detail to a run log is under ten whatever it says. When
            // `sync`'s successful output went from 42 lines to 5, the proxy
            // went red while every fact it stood for was still true — the
            // banner was printed, once, over two affected units.
            //
            // The honest floor is what the assertion is actually about: the
            // banner and its cause were PRINTED (so "≤ 1" is not "0"), and
            // there were ≥ 2 affected units — asserted separately, above — so
            // printing per unit would have produced a different number. Both
            // halves are semantic now, and neither moves when the console does.
            boolean theBannerWasPrintedSoPrintedOnceIsNotVacuous =
                    bannerOccurrences == 1 && causeOccurrences == 1;

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
            // It must also be a unit that ACTUALLY carries the cause, or the
            // count cannot drop — which is why the victim comes from the
            // planted provenance-less set rather than from an arbitrary unit
            // the project happens not to declare.
            String victim = null;
            for (String unit : OnboardingSupport.storeUnits(home)) {
                if (OnboardingSupport.PROVENANCELESS.contains(unit)) { victim = unit; break; }
            }
            boolean theVictimActuallyCarriesTheCause = victim != null
                    && OnboardingSupport.unitRecordSays(home, victim, CAUSE);
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
                    && theCauseIsCarriedByUnitsThatCannotBeFixedByChoosingDifferently
                    && theVictimActuallyCarriesTheCause
                    && theBannerWasPrintedSoPrintedOnceIsNotVacuous
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
                    .assertion("the_shared_cause_is_planted_rather_than_borrowed_from_a_defect",
                            theCauseIsCarriedByUnitsThatCannotBeFixedByChoosingDifferently)
                    .assertion("the_unit_removed_by_the_clear_half_actually_carried_the_cause",
                            theVictimActuallyCarriesTheCause)
                    .assertion("the_banner_was_printed_so_printed_once_is_not_vacuous",
                            theBannerWasPrintedSoPrintedOnceIsNotVacuous)
                    .assertion("the_error_record_printed_once_rather_than_per_unit",
                            theRecordPrintedOnceRatherThanPerUnit)
                    .assertion("the_banner_shrank_by_one_when_one_cause_was_removed",
                            theBannerShrankWhenTheCauseWasRemoved)
                    .metric("unitsAffected", affected)
                    .metric("provenancelessUnitsCarryingTheCause", provenancelessCarryingTheCause)
                    .metric("bannerOccurrencesInSyncLog", bannerOccurrences)
                    .metric("unitsAffectedAfterUninstall", afterCount)
                    .log("branch chosen by the implementation: " + branch)
                    .log("read-only commands blaming the file:-installed unit: "
                    + commandsBlamingTheFileInstalledUnit);
        });
    }

    /**
     * Does this output hold {@code unit} at fault in an outstanding-errors
     * banner?
     *
     * <p>The banner names its units on one indented, comma-separated line
     * ending in a colon, under the {@code BANNER} header:
     *
     * <pre>
     * ⚠ skills with outstanding errors (2) — 1 distinct cause(s) — re-run after fixing:
     *
     *   ob-gamma, ob-umbrella:
     *     - NEEDS_GIT_MIGRATION: …
     * </pre>
     *
     * <p>Scoped to that block rather than a whole-log substring search,
     * because the log also carries the unit's name in every ordinary row —
     * {@code list} prints it, {@code bindings list} prints it three times —
     * and a contains-check over the whole output would report the unit as
     * blamed on any run that merely mentioned it.
     */
    private static boolean blames(String log, String unit) {
        boolean inBanner = false;
        for (String raw : log.split("\n", -1)) {
            String line = raw.strip();
            if (line.contains(BANNER)) { inBanner = true; continue; }
            if (!inBanner) continue;
            if (line.isEmpty()) continue;
            if (!line.endsWith(":") || line.startsWith("-")) continue;
            for (String named : line.substring(0, line.length() - 1).split(",")) {
                if (named.strip().equals(unit)) return true;
            }
        }
        return false;
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
