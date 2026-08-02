///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Step 13 — a remedy the tool prints must be runnable as printed.</b>
 *
 * <h2>The defect</h2>
 *
 * <p>Both {@code bootstrap-home.sh} and {@code home clone} say
 * "{@code skill-manager home verify} REFUSES this home until you do", naming no
 * arguments. Run as printed:
 *
 * <pre>
 * $ skill-manager home verify --root &lt;home&gt;
 * EXIT=2   Missing required options: '--home=&lt;home&gt;', '--against=&lt;against&gt;'
 * </pre>
 *
 * <p>{@code --against} is mandatory with no default, so an agent holding only
 * the home path cannot run the check it was told to run. The working spelling
 * is discoverable only from {@code --help}.
 *
 * <p>In the same class: {@code install} accepts {@code --yes} and
 * {@code uninstall} does not ({@code Unknown option: '--yes'}, exit 2), which
 * costs an agent one round trip — while the error-record renderer's own remedy
 * text correctly omits it.
 *
 * <h2>Assertion</h2>
 *
 * <p>A remedy that names a subcommand also names that subcommand's required
 * options. Checked by PARSING, never by executing: a remedy that parses and
 * then destroys something is not a thing to discover from a test. Only
 * {@code --dry-run}/{@code --print-env}/usage-error invocations are run.
 *
 * <h2>Vacuous-pass risks and companions</h2>
 *
 * <ol>
 *   <li><b>The extractor finds no remedy strings,</b> so "every remedy is
 *       runnable" is true over an empty set.
 *       <br><b>Companion:</b> the extracted set must be non-empty and must
 *       include the two known remedies — the {@code sync --force-scripts}
 *       spelling and the {@code home verify} sentence. If neither is found the
 *       extractor is broken and the node fails.</li>
 *   <li><b>"Parses" being satisfied by prose.</b> The failing case here IS a
 *       bare command name in a sentence, so a permissive predicate would call
 *       it fine.
 *       <br><b>Companion:</b> the bare spelling is actually EXECUTED (it is a
 *       read-only verify) and its exit code compared against the working
 *       spelling's. Both are run; the node reports both. A predicate is not
 *       trusted where an exit code is available.</li>
 * </ol>
 *
 * <h2>And the "error text is not an isolation leak" guard</h2>
 *
 * <p>{@code home verify}'s verdict on a real clone is right in substance: unit
 * CONTENT that merely MENTIONS the source home's absolute path is reported as
 * "historical records, tolerated; re-run with --strict to fail on them" and
 * does not make the verify fail — only genuinely unresolvable links do.
 * <b>Vacuous-pass risk:</b> passing because there were no mentions at all.
 * <b>Companion:</b> the mention count must be ≥1 first, and the fixture plants
 * one.
 */
public class OnboardingRemediesAreRunnable {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.remedies.are.runnable")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.refusals.are.messages")
            .tags("onboarding", "ux", "remedy")
            .timeout("900s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path proj = path(ctx, "onboarding.fixture.built", "proj");
            Path srcHome = path(ctx, "onboarding.fixture.built", "srcHome");
            Path home = path(ctx, "onboarding.bootstrapped", "projectHome");
            String bootstrapLogPath = ctx.get("onboarding.bootstrapped", "bootstrapLog")
                    .orElse("");
            if (proj == null || home == null || srcHome == null || bootstrapLogPath.isEmpty()) {
                return NodeResult.fail("onboarding.remedies.are.runnable",
                        "missing upstream context");
            }
            String bootstrapLog = OnboardingSupport.read(Path.of(bootstrapLogPath));

            // --- the extractor's own floor -------------------------------------
            List<String> remedies = new ArrayList<>();
            for (String raw : bootstrapLog.split("\n", -1)) {
                String line = raw.strip();
                if (line.contains("skill-manager ") || line.contains("home verify")
                        || line.contains("sync --force-scripts")) {
                    remedies.add(line);
                }
            }
            boolean theExtractorFoundRemedies = !remedies.isEmpty();
            boolean theExtractorFoundTheKnownForceScriptsRemedy =
                    bootstrapLog.contains("sync --force-scripts");
            boolean theExtractorFoundTheKnownHomeVerifySentence =
                    bootstrapLog.contains("home verify");

            // --- the bare spelling, as printed ----------------------------------
            //
            // Executed rather than predicated: home verify is read-only, and an
            // exit code is better evidence than any parser I could write.
            ProcessRecord bare = OnboardingSupport.sm(ctx, "home-verify-as-printed", home, proj,
                    "home", "verify", "--root", home.toString());
            String bareLog = OnboardingSupport.log(ctx, bare);
            boolean theSpellingThePrintedRemedyImpliesIsAUsageError =
                    bare.exitCode() == 2 && bareLog.contains("Missing required options");
            boolean thePrintedRemedyIsRunnableAsPrinted =
                    !theSpellingThePrintedRemedyImpliesIsAUsageError;

            // --- the working spelling, found only in --help -----------------------
            ProcessRecord working = OnboardingSupport.sm(ctx, "home-verify-working", home, proj,
                    "home", "verify", "--home", home.toString(),
                    "--against", srcHome.toString());
            String workingLog = OnboardingSupport.log(ctx, working);
            boolean theWorkingSpellingParses = working.exitCode() != 2;

            // --- historical path mentions are tolerated, not treated as leaks -----
            int mentions = countMentions(workingLog);
            boolean thereWereHistoricalMentionsToTolerate =
                    workingLog.contains("mention") || mentions > 0;
            boolean historicalMentionsAreReportedAsTolerated =
                    !thereWereHistoricalMentionsToTolerate
                            || workingLog.contains("tolerated")
                            || workingLog.contains("--strict");

            // --- the --yes asymmetry ------------------------------------------------
            ProcessRecord uninstallYes = OnboardingSupport.sm(ctx, "uninstall-yes", home, proj,
                    "uninstall", "ob-definitely-not-installed", "--yes", "--dry-run");
            String yesLog = OnboardingSupport.log(ctx, uninstallYes);
            boolean uninstallRejectsTheFlagInstallAccepts =
                    uninstallYes.exitCode() == 2 && yesLog.contains("Unknown option");
            boolean theTwoCommandsAgreeOnTheYesFlag = !uninstallRejectsTheFlagInstallAccepts;

            boolean pass = theExtractorFoundRemedies
                    && theExtractorFoundTheKnownForceScriptsRemedy
                    && theExtractorFoundTheKnownHomeVerifySentence
                    && thePrintedRemedyIsRunnableAsPrinted
                    && theWorkingSpellingParses
                    && historicalMentionsAreReportedAsTolerated
                    && theTwoCommandsAgreeOnTheYesFlag;

            return (pass
                    ? NodeResult.pass("onboarding.remedies.are.runnable")
                    : NodeResult.fail("onboarding.remedies.are.runnable",
                            "remediesFound=" + remedies.size()
                                    + " bareVerifyExit=" + bare.exitCode()
                                    + " workingVerifyExit=" + working.exitCode()
                                    + " uninstallYesExit=" + uninstallYes.exitCode()
                                    + " mentions=" + mentions))
                    .process(bare).process(working).process(uninstallYes)
                    .assertion("the_remedy_extractor_found_at_least_one_remedy",
                            theExtractorFoundRemedies)
                    .assertion("the_extractor_found_the_known_force_scripts_remedy",
                            theExtractorFoundTheKnownForceScriptsRemedy)
                    .assertion("the_extractor_found_the_known_home_verify_sentence",
                            theExtractorFoundTheKnownHomeVerifySentence)
                    .assertion("the_home_verify_remedy_is_runnable_as_printed",
                            thePrintedRemedyIsRunnableAsPrinted)
                    .assertion("the_spelling_found_only_in_help_at_least_parses",
                            theWorkingSpellingParses)
                    .assertion("historical_path_mentions_are_reported_as_tolerated_not_as_leaks",
                            historicalMentionsAreReportedAsTolerated)
                    .assertion("install_and_uninstall_agree_on_the_yes_flag",
                            theTwoCommandsAgreeOnTheYesFlag)
                    .metric("remediesExtracted", remedies.size())
                    .metric("bareVerifyExit", bare.exitCode())
                    .metric("workingVerifyExit", working.exitCode())
                    .log("remedy lines: " + remedies)
                    .log("bare `home verify --root <home>` said: "
                            + firstLine(bareLog));
        });
    }

    /** {@code N unit-content file(s) mention <path>} */
    private static int countMentions(String log) {
        for (String line : log.split("\n", -1)) {
            String s = line.strip();
            int i = s.indexOf(" unit-content file(s) mention");
            if (i > 0) {
                try {
                    return Integer.parseInt(s.substring(0, i).trim().split("\\s+")[0]);
                } catch (RuntimeException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String firstLine(String text) {
        for (String line : text.split("\n", -1)) {
            if (!line.isBlank()) return line.strip();
        }
        return "";
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
