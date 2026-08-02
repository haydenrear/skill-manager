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
 *       include the two known remedies — the {@code home verify} sentence in
 *       {@code bootstrap-home.sh}'s warning, and the re-provision remedy
 *       ({@code sync --force-scripts}) as {@code home verify} itself prints it.
 *       If neither is found the extractor is broken and the node fails.
 *       <br><b>The second one moved, and its old spelling must not come back.</b>
 *       {@code bootstrap-home.sh} and {@code home clone} both used to print
 *       {@code SKILL_MANAGER_HOME=<home> skill-manager sync --force-scripts},
 *       which pins the store axis and not the agent-config axis and therefore
 *       writes the OPERATOR'S {@code ~/.claude.json} when run as printed
 *       (skill-manager#145). Both paragraphs were deleted on purpose
 *       (git-integration-repo@8efd2ae, skill-manager@2ec5bcf). So this node
 *       asserts the property in the one place the remedy survives — {@code home
 *       verify}, the command that enforces the gate, where it is spelled with
 *       all four variables — and asserts its ABSENCE from the bootstrap output.
 *       Those two are each other's companion: the absence cannot pass by the
 *       search being broken, because the identical search finds the presence.</li>
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
 *
 * <p><b>Both halves of that companion were missing.</b> The fixture planted no
 * mention — its units are authored by the fixture and none of them had any
 * reason to name the home they would be installed into — and the "count ≥ 1"
 * was not enforced: it lived only in a {@code !thereWereMentions ||} guard, so
 * zero mentions made the tolerance TRUE instead of making the node fail. The
 * count was reported as {@code mentions=0} in the failure message of an
 * assertion that had passed. Both are fixed here: {@code ob-script} ships a
 * references page naming the source home's absolute path (planted in the unit
 * SOURCE, so the product's own install places it), the node asserts that page
 * is present IN THE HOME IT VERIFIES before it looks at any count, and the
 * count and the tolerance are separate assertions in the pass conjunction.
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
            // The console the bootstrap printed PLUS the run log it named. The
            // script demotes its transcript — including every byte the CLI
            // children wrote — to that file, so an absence checked against the
            // console alone would be an absence from the half the text moved out
            // of — and an absence is the one assertion shape that passes over an
            // empty string. `withNamedLog` throws if the footer names a file it
            // cannot read, which is the only way this could have gone quiet.
            String bootstrapLog = OnboardingSupport.withNamedLog(bootstrapLogPath);

            // --- the extractor's own floor -------------------------------------
            List<String> remedies = new ArrayList<>();
            for (String raw : bootstrapLog.split("\n", -1)) {
                String line = raw.strip();
                if (line.contains("skill-manager ") || line.contains("home verify")) {
                    remedies.add(line);
                }
            }
            boolean theExtractorFoundRemedies = !remedies.isEmpty();
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
            // The CONSOLE, deliberately, and not the run log behind it. The
            // mention count and the first three entries under it are `Log.info`
            // — console-and-log — because a tolerated category reported as a
            // bare number is a number with nothing behind it. That is the
            // product's contract, so this node holds it to the console; reading
            // the run log too would make the node pass in the one case it exists
            // to catch, a future edit that demotes the count to `Log.detail`.
            String workingLog = OnboardingSupport.log(ctx, working);
            boolean theWorkingSpellingParses = working.exitCode() != 2;

            // --- the re-provision remedy, where it is now printed ------------------
            //
            // This replaces `bootstrapLog.contains("sync --force-scripts")`,
            // which was the second half of the extractor's floor and is now
            // unfindable there BY DESIGN. git-integration-repo@8efd2ae DELETED
            // that paragraph from bootstrap-home.sh and skill-manager@2ec5bcf
            // deleted it from `home clone`, because the spelling they printed —
            // `SKILL_MANAGER_HOME=<home> skill-manager sync --force-scripts` —
            // pins where the UNITS live and not where the AGENT CONFIGS live,
            // and run as printed it writes the operator's own ~/.claude.json
            // (skill-manager#145). Re-asserting the text would demand a remedy
            // back whose whole defect is that it is not runnable as printed,
            // which is the opposite of what this node is for.
            //
            // So the floor is kept as a PROPERTY, in the one place the remedy is
            // now printed: `home verify`, the command that enforces the gate,
            // spelling both axes so it is runnable as printed. The two
            // assertions are each other's companion — the absence below cannot
            // pass by the search being broken, because the identical search
            // finds it here.
            String reprovisionRemedy = remedyLineNaming(workingLog, "sync --force-scripts");
            boolean theEnforcingCommandPrintsTheReprovisionRemedy = !reprovisionRemedy.isEmpty();
            boolean theReprovisionRemedyPinsBothAxes =
                    reprovisionRemedy.contains("SKILL_MANAGER_HOME=")
                            && reprovisionRemedy.contains("CLAUDE_CONFIG_DIR=")
                            && reprovisionRemedy.contains("CODEX_HOME=")
                            && reprovisionRemedy.contains("GEMINI_HOME=");
            boolean theDeletedHijackingRemedyStayedDeleted =
                    !bootstrapLog.contains("sync --force-scripts");

            // --- historical path mentions are tolerated, not treated as leaks -----
            //
            // THE COMPANION IS ENFORCED HERE, WHICH IT WAS NOT BEFORE. It used
            // to sit only in the `!thereWere… ||` guard of the tolerance
            // predicate, so a home with no mentions made the tolerance TRUE
            // rather than making the node fail — which is exactly what happened:
            // the fixture never planted a mention, `mentions=0` was reported in
            // the failure message of an assertion that had passed, and the
            // eighth "assertion that only held while something else was broken"
            // was one edit away from being permanent.
            //
            // Two steps now, in order. The plant must be IN the home being
            // verified — asserted against the file, not inferred from the count
            // the tolerance is about — and only then is the count required.
            String mentionRel = ctx.get("onboarding.fixture.built", "mentionRel").orElse("");
            boolean thePlantedMentionSurvivedIntoTheHomeUnderTest = !mentionRel.isEmpty()
                    && OnboardingSupport.read(home.resolve(mentionRel))
                            .contains(srcHome.toString());
            String mentionLine = firstLineContaining(workingLog, " unit-content file(s) mention");
            int mentions = countMentions(workingLog);
            boolean thereWereHistoricalMentionsToTolerate = mentions >= 1;
            // The tolerance is read off the MENTION line, not off the whole
            // output: the diagnostic-message line (#144) says "tolerated" too,
            // and a whole-log substring match would let a home with no authored
            // mention at all satisfy an assertion about authored mentions.
            boolean historicalMentionsAreReportedAsTolerated =
                    mentionLine.contains("tolerated") && mentionLine.contains("--strict");

            // --- the --yes asymmetry ------------------------------------------------
            ProcessRecord uninstallYes = OnboardingSupport.sm(ctx, "uninstall-yes", home, proj,
                    "uninstall", "ob-definitely-not-installed", "--yes", "--dry-run");
            String yesLog = OnboardingSupport.log(ctx, uninstallYes);
            boolean uninstallRejectsTheFlagInstallAccepts =
                    uninstallYes.exitCode() == 2 && yesLog.contains("Unknown option");
            boolean theTwoCommandsAgreeOnTheYesFlag = !uninstallRejectsTheFlagInstallAccepts;

            boolean pass = theExtractorFoundRemedies
                    && theExtractorFoundTheKnownHomeVerifySentence
                    && theEnforcingCommandPrintsTheReprovisionRemedy
                    && theReprovisionRemedyPinsBothAxes
                    && theDeletedHijackingRemedyStayedDeleted
                    && thePrintedRemedyIsRunnableAsPrinted
                    && theWorkingSpellingParses
                    && thePlantedMentionSurvivedIntoTheHomeUnderTest
                    && thereWereHistoricalMentionsToTolerate
                    && historicalMentionsAreReportedAsTolerated
                    && theTwoCommandsAgreeOnTheYesFlag;

            return (pass
                    ? NodeResult.pass("onboarding.remedies.are.runnable")
                    : NodeResult.fail("onboarding.remedies.are.runnable",
                            "remediesFound=" + remedies.size()
                                    + " bareVerifyExit=" + bare.exitCode()
                                    + " workingVerifyExit=" + working.exitCode()
                                    + " uninstallYesExit=" + uninstallYes.exitCode()
                                    + " plantedMentionInHome="
                                    + thePlantedMentionSurvivedIntoTheHomeUnderTest
                                    + " mentions=" + mentions
                                    + " mentionLine=" + mentionLine
                                    + " reprovisionRemedy=" + reprovisionRemedy))
                    .process(bare).process(working).process(uninstallYes)
                    .assertion("the_remedy_extractor_found_at_least_one_remedy",
                            theExtractorFoundRemedies)
                    .assertion("the_extractor_found_the_known_home_verify_sentence",
                            theExtractorFoundTheKnownHomeVerifySentence)
                    .assertion("the_command_that_enforces_the_gate_prints_the_reprovision_remedy",
                            theEnforcingCommandPrintsTheReprovisionRemedy)
                    .assertion("the_reprovision_remedy_pins_the_store_and_all_three_agent_homes",
                            theReprovisionRemedyPinsBothAxes)
                    .assertion("the_bootstrap_did_not_reprint_the_remedy_that_hijacks_the_machine",
                            theDeletedHijackingRemedyStayedDeleted)
                    .assertion("the_home_verify_remedy_is_runnable_as_printed",
                            thePrintedRemedyIsRunnableAsPrinted)
                    .assertion("the_spelling_found_only_in_help_at_least_parses",
                            theWorkingSpellingParses)
                    .assertion("the_planted_content_mention_survived_into_the_home_verified",
                            thePlantedMentionSurvivedIntoTheHomeUnderTest)
                    .assertion("there_was_at_least_one_historical_mention_to_tolerate",
                            thereWereHistoricalMentionsToTolerate)
                    .assertion("historical_path_mentions_are_reported_as_tolerated_not_as_leaks",
                            historicalMentionsAreReportedAsTolerated)
                    .assertion("install_and_uninstall_agree_on_the_yes_flag",
                            theTwoCommandsAgreeOnTheYesFlag)
                    .metric("remediesExtracted", remedies.size())
                    .metric("bareVerifyExit", bare.exitCode())
                    .metric("workingVerifyExit", working.exitCode())
                    .metric("toleratedContentMentions", mentions)
                    .log("remedy lines: " + remedies)
                    .log("the reprovision remedy, as printed by `home verify`: "
                            + reprovisionRemedy)
                    .log("tolerated-mention line: " + mentionLine)
                    .log("bare `home verify --root <home>` said: "
                            + firstLine(bareLog));
        });
    }

    /** {@code N unit-content file(s) mention <path>} */
    private static int countMentions(String log) {
        String s = firstLineContaining(log, " unit-content file(s) mention");
        int i = s.indexOf(" unit-content file(s) mention");
        if (i <= 0) return 0;
        try {
            return Integer.parseInt(s.substring(0, i).trim().split("\\s+")[0]);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** The first stripped line holding {@code needle}, or {@code ""}. */
    private static String firstLineContaining(String text, String needle) {
        for (String line : text.split("\n", -1)) {
            String s = line.strip();
            if (s.contains(needle)) return s;
        }
        return "";
    }

    /**
     * The line on which {@code command} is offered as a remedy, or {@code ""}.
     *
     * <p>By line rather than by whole-text {@code contains}, because the
     * question this node asks is whether that ONE line is runnable as printed:
     * a spelling assembled from a command on one line and an environment prefix
     * on another is not something a reader can copy.
     */
    private static String remedyLineNaming(String text, String command) {
        return firstLineContaining(text, command);
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
