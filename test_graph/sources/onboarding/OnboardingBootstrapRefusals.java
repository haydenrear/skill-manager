///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <b>Step 1 — the two ways a fresh machine's bootstrap refuses, and whether
 * either of them tells you what to do next.</b>
 *
 * <p>Both cases in one node on purpose. The assertion is comparative: exit 5
 * (a source home that exists and has no skills) prints an excellent remedy
 * including the full four-variable env prefix, and exit 1 (no source home at
 * all) prints a bare error. A node that only looked at the exit-1 case could
 * assert "the output is non-empty" and pass — it IS non-empty, it just has no
 * command in it. Having both in the same run makes the predicate testable:
 * whatever "names a runnable command" means, the exit-5 case must satisfy it
 * and the exit-1 case must not.
 *
 * <h2>Assertions</h2>
 *
 * <ol>
 *   <li><b>Exit 1, no source home.</b> {@code HOME} points at a fixture with no
 *       {@code .skill-manager} in it. Refuses with
 *       {@code error: source home does not exist: <HOME>/.skill-manager}.
 *       Every non-zero exit from {@code bootstrap-home.sh} should print at
 *       least one runnable command as a remedy — this one prints none, and
 *       it is the one path a genuinely fresh machine takes.</li>
 *   <li><b>It wrote nothing.</b> The more important half, and the one a
 *       "did it print a remedy" check would miss entirely: neither the fixture
 *       {@code HOME} nor the project directory may be modified by a run that
 *       refused.</li>
 *   <li><b>Exit 5, empty source home.</b> Exactly 5, not merely non-zero: the
 *       absent-source path exits 1 and a "non-zero" predicate cannot tell them
 *       apart. Must never print the word {@code verified}, and its remedy must
 *       carry all four of {@code SKILL_MANAGER_HOME}, {@code CLAUDE_CONFIG_DIR},
 *       {@code CODEX_HOME}, {@code GEMINI_HOME} — a remedy with
 *       {@code SKILL_MANAGER_HOME} alone is the spelling that writes the
 *       operator's real agent configs.</li>
 * </ol>
 *
 * <h2>Companions</h2>
 *
 * <ul>
 *   <li><b>The remedy predicate is shown discriminating.</b> The same
 *       "contains a line that looks like a command" function is applied to both
 *       logs; the node fails unless exit-5 satisfies it. A predicate that
 *       matched everything would fail here, and one that matched nothing would
 *       fail there.</li>
 *   <li><b>The wrote-nothing oracle is shown detecting a plant.</b> A file is
 *       planted under a copy of the same tree and the same digest comparison
 *       must report it. Otherwise "no differences" is indistinguishable from
 *       "could not look" — this project has been misled by that zero four
 *       times.</li>
 *   <li><b>The empty home is verified empty first.</b> Exit 5 is only about an
 *       empty home; a populated source would exit 0 and an absent one would
 *       exit 1, and either would make the assertion about a different code
 *       path.</li>
 * </ul>
 */
public class OnboardingBootstrapRefusals {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.bootstrap.refusals")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.fixture.built")
            .tags("onboarding", "refusal", "script")
            .timeout("600s");

    /**
     * A line that could be pasted into a shell: it starts with an absolute
     * path, with {@code env}, or with a known binary name.
     *
     * <p>Prose sentences that merely NAME a subcommand do not qualify, and that
     * is deliberate — a bare command name in prose is the failing case this
     * assertion is about.
     */
    static final Pattern RUNNABLE = Pattern.compile(
            "^\\s{0,6}(/[^\\s]+|env\\s+\\S+=|skill-manager\\s+\\w|bash\\s+/|rm -rf )");

    static boolean namesARunnableCommand(String log) {
        for (String line : log.split("\n", -1)) {
            if (RUNNABLE.matcher(line).find()) return true;
        }
        return false;
    }

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String fixtureId = "onboarding.fixture.built";
            Path fakeHome = path(ctx, fixtureId, "fakeHome");
            Path fakeProj = path(ctx, fixtureId, "fakeProj");
            Path emptyHome = path(ctx, fixtureId, "emptyHome");
            Path ambient = path(ctx, fixtureId, "ambient");
            Path scriptsDir = path(ctx, fixtureId, "scriptsDir");
            if (fakeHome == null || fakeProj == null || emptyHome == null || ambient == null
                    || scriptsDir == null) {
                return NodeResult.fail("onboarding.bootstrap.refusals", "missing upstream context");
            }
            Path bootstrap = scriptsDir.resolve("bootstrap-home.sh");

            // --- precondition: the empty home IS empty ----------------------
            boolean theEmptySourceHomeExistsAndHasNoSkills =
                    Files.isDirectory(emptyHome)
                            && Files.isDirectory(emptyHome.resolve("skills"))
                            && OnboardingSupport.names(emptyHome.resolve("skills")).isEmpty();
            boolean theAbsentSourceHomeIsReallyAbsent =
                    !Files.exists(fakeHome.resolve(".skill-manager"));

            // --- case one: no source home at all -----------------------------
            // The snapshot is scoped to the PROJECT and to the fixture HOME's
            // .skill-manager, not to the whole fixture HOME. A bootstrap run
            // with $HOME redirected causes jbang and maven to materialize
            // ~/.jbang and ~/.m2 there — measured, 300+ entries — and counting a
            // toolchain's cache as "the script wrote something" would make this
            // assertion permanently and meaninglessly red.
            Map<String, String> homeBefore = OnboardingSupport.snapshot(
                    fakeHome.resolve(".skill-manager"));
            Map<String, String> projBefore = OnboardingSupport.snapshot(fakeProj);
            ProcessRecord absent = OnboardingSupport.scriptWith(ctx, "bootstrap-no-source",
                    fakeProj, bootstrap, ambient,
                    pb -> {
                        // The whole point of this case: the script must find no
                        // source home ANYWHERE. HOME is the fixture's, the agent
                        // variables are stripped, and SKILL_MANAGER_HOME is
                        // stripped too — with it set the script resolves THAT
                        // home and takes the empty-home path (exit 5) instead of
                        // the no-home path (exit 1), which is a true answer to a
                        // question this case is not asking. Measured: without
                        // the strip, both cases reported exit 5.
                        pb.environment().put("HOME", fakeHome.toString());
                        pb.environment().put("JAVA_TOOL_OPTIONS", "-Duser.home=" + fakeHome);
                        OnboardingSupport.unsetAgentVars(pb);
                        OnboardingSupport.unsetStoreHomeVar(pb);
                    },
                    "--root", fakeProj.toString());
            String absentLog = OnboardingSupport.log(ctx, absent);
            boolean theAbsentSourceRefusalExitsOne = absent.exitCode() == 1;
            boolean theAbsentSourceRefusalNamesTheMissingHome =
                    absentLog.contains("source home does not exist");
            boolean theAbsentSourceRefusalPrintsARunnableRemedy =
                    namesARunnableCommand(absentLog);

            List<String> homeChanges = HomeSyncSupport.difference(homeBefore,
                    OnboardingSupport.snapshot(fakeHome.resolve(".skill-manager")));
            List<String> projChanges = HomeSyncSupport.difference(projBefore,
                    OnboardingSupport.snapshot(fakeProj));
            boolean aRefusedBootstrapWroteNothing =
                    homeChanges.isEmpty() && projChanges.isEmpty();

            // The sensitivity proof for that zero, in the same run: the same
            // digest comparison, over the same tree, with one file planted.
            Path canary = fakeProj.resolve(".wrote-nothing-canary");
            Files.writeString(canary, "planted\n");
            boolean theWroteNothingOracleDetectsAPlantedFile =
                    !HomeSyncSupport.difference(projBefore,
                            OnboardingSupport.snapshot(fakeProj)).isEmpty();
            Files.deleteIfExists(canary);

            // --- case two: an empty source home -------------------------------
            ProcessRecord empty = OnboardingSupport.scriptWith(ctx, "bootstrap-empty-source",
                    fakeProj, bootstrap, ambient,
                    pb -> {
                        pb.environment().put("HOME", fakeHome.toString());
                        pb.environment().put("JAVA_TOOL_OPTIONS", "-Duser.home=" + fakeHome);
                        OnboardingSupport.unsetAgentVars(pb);
                    },
                    "--root", fakeProj.toString(), "--source", emptyHome.toString());
            String emptyLog = OnboardingSupport.log(ctx, empty);
            boolean theEmptySourceRefusalExitsExactlyFive = empty.exitCode() == 5;
            boolean theEmptySourceRefusalNeverClaimsVerified = !emptyLog.contains("verified");
            boolean theEmptySourceRefusalNamesTheEmptiness =
                    emptyLog.contains("no skills");
            boolean theEmptySourceRemedyCarriesAllFourEnvVars =
                    emptyLog.contains("SKILL_MANAGER_HOME=")
                            && emptyLog.contains("CLAUDE_CONFIG_DIR=")
                            && emptyLog.contains("CODEX_HOME=")
                            && emptyLog.contains("GEMINI_HOME=");
            // The discriminating half: this case MUST satisfy the same predicate
            // the absent-source case is measured against. If it did not, the
            // predicate would be broken rather than the absent case defective.
            boolean theRemedyPredicateIsSatisfiedByTheExitFiveCase =
                    namesARunnableCommand(emptyLog);

            boolean pass = theEmptySourceHomeExistsAndHasNoSkills
                    && theAbsentSourceHomeIsReallyAbsent
                    && theAbsentSourceRefusalExitsOne
                    && theAbsentSourceRefusalNamesTheMissingHome
                    && theAbsentSourceRefusalPrintsARunnableRemedy
                    && aRefusedBootstrapWroteNothing
                    && theWroteNothingOracleDetectsAPlantedFile
                    && theEmptySourceRefusalExitsExactlyFive
                    && theEmptySourceRefusalNeverClaimsVerified
                    && theEmptySourceRefusalNamesTheEmptiness
                    && theEmptySourceRemedyCarriesAllFourEnvVars
                    && theRemedyPredicateIsSatisfiedByTheExitFiveCase;

            return (pass
                    ? NodeResult.pass("onboarding.bootstrap.refusals")
                    : NodeResult.fail("onboarding.bootstrap.refusals",
                            "absentExit=" + absent.exitCode()
                                    + " absentRemedy=" + theAbsentSourceRefusalPrintsARunnableRemedy
                                    + " emptyExit=" + empty.exitCode()
                                    + " homeChanges=" + homeChanges
                                    + " projChanges=" + projChanges))
                    .process(absent)
                    .process(empty)
                    .assertion("the_empty_source_home_exists_and_has_no_skills",
                            theEmptySourceHomeExistsAndHasNoSkills)
                    .assertion("the_absent_source_home_is_really_absent",
                            theAbsentSourceHomeIsReallyAbsent)
                    .assertion("a_bootstrap_with_no_source_home_exits_one",
                            theAbsentSourceRefusalExitsOne)
                    .assertion("that_refusal_names_the_missing_home",
                            theAbsentSourceRefusalNamesTheMissingHome)
                    .assertion("that_refusal_prints_at_least_one_runnable_command",
                            theAbsentSourceRefusalPrintsARunnableRemedy)
                    .assertion("a_refused_bootstrap_wrote_nothing_anywhere",
                            aRefusedBootstrapWroteNothing)
                    .assertion("the_wrote_nothing_oracle_detects_a_planted_file",
                            theWroteNothingOracleDetectsAPlantedFile)
                    .assertion("a_bootstrap_from_an_empty_source_home_exits_exactly_five",
                            theEmptySourceRefusalExitsExactlyFive)
                    .assertion("the_empty_home_refusal_never_prints_the_word_verified",
                            theEmptySourceRefusalNeverClaimsVerified)
                    .assertion("the_empty_home_refusal_says_the_home_has_no_skills",
                            theEmptySourceRefusalNamesTheEmptiness)
                    .assertion("the_empty_home_remedy_carries_all_four_agent_env_vars",
                            theEmptySourceRemedyCarriesAllFourEnvVars)
                    .assertion("the_remedy_predicate_is_satisfied_by_the_exit_five_case",
                            theRemedyPredicateIsSatisfiedByTheExitFiveCase)
                    .metric("absentExit", absent.exitCode())
                    .metric("emptyExit", empty.exitCode())
                    .log("absent-source refusal: " + firstLines(absentLog, 6))
                    .log("empty-source refusal: " + firstLines(emptyLog, 10));
        });
    }

    private static Path path(com.hayden.testgraphsdk.sdk.NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }

    private static String firstLines(String text, int n) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) continue;
            out.add(line.strip());
            if (out.size() >= n) break;
        }
        return String.join(" | ", out);
    }
}
