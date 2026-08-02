///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Step 3 — the highest-impact assertion in the graph: does an agent
 * launched against this home actually SEE any skills, and does the tool's own
 * "verified" claim mean that?</b>
 *
 * <h2>The defect</h2>
 *
 * <p>A cloned or freshly-onboarded home has its STORE populated —
 * {@code <home>/skills/<u>/SKILL.md} for every unit — and NOTHING projected
 * into its agent directories. {@code ls <root>/.claude} returns {@code .} and
 * {@code ..}: no {@code skills/} directory at all, identically for
 * {@code .codex} and {@code .gemini}. Every worktree created this way launches
 * an agent that sees zero skills. {@code exec}'s reconcile runs and creates
 * nothing; {@code exec --no-reconcile} documents itself as skipping exactly the
 * refresh that would have fixed it.
 *
 * <p>And in the same breath {@code bootstrap-home.sh} prints
 * {@code verified: N skill(s) servable}. It derives that N from
 * {@code home_skill_count}, which globs {@code $STORE/skills/*}{@code /SKILL.md}
 * — the store, which no agent reads. The word "servable" is unearned: the check
 * that would earn it, "are there resolvable links under
 * {@code <root>/.claude/skills}", is not made. The {@code --onboard} path is
 * blunter still — it prints nine reconcile warnings saying the projections are
 * missing and then prints {@code verified: 3 skill(s) servable} below them.
 *
 * <p>The remedy exists and works ({@code sync --skip-mcp}), and nothing on the
 * {@code wt new} path prints it or runs it.
 *
 * <h2>Assertion</h2>
 *
 * <p>For every unit the home's store holds, a resolvable link exists at
 * {@code <root>/.claude/skills/<u>}, {@code <root>/.codex/skills/<u>} and
 * {@code <root>/.gemini/skills/<u>}, each resolving INSIDE {@code <root>}. And:
 * {@code bootstrap-home.sh} prints {@code verified: N skill(s) servable} only
 * when that holds for all N; otherwise it must not print the word
 * {@code verified} at all.
 *
 * <h2>Vacuous-pass risks and their companions — this is the important part</h2>
 *
 * <ol>
 *   <li><b>Counting {@code <home>/skills/} instead of
 *       {@code <root>/.claude/skills/}.</b> That is precisely the bug. A node
 *       that recounts the store passes on a home with zero projections.
 *       <br><b>Companion:</b> in the same run, DELETE one link from
 *       {@code <root>/.claude/skills/} and re-assert. The oracle must go red. If
 *       it stays green it is counting the store, and the node fails on that
 *       ground rather than on the product's.</li>
 *   <li><b>Counting directory entries without resolving them.</b> A dangling
 *       symlink is still an entry.
 *       <br><b>Companion:</b> repoint one link at a nonexistent target and
 *       re-assert; must go red.</li>
 *   <li><b>Asserting only on {@code .claude}.</b> All three fail together
 *       today, so a single-agent check would have caught this one — but a
 *       future regression could be agent-specific.
 *       <br><b>Companion:</b> all three are asserted independently and reported
 *       separately.</li>
 *   <li><b>The {@code verified:} half passing because the string never
 *       appears.</b> If the bootstrap failed earlier, the word is absent and
 *       "did not claim verified while unprojected" is vacuously true.
 *       <br><b>Companion:</b> the node first requires the bootstrap to have
 *       exited 0 AND to have printed a {@code skills:} line, and only then
 *       evaluates the conditional. The positive direction is asserted too: once
 *       the home IS fully projected, {@code verified:} must appear with N equal
 *       to the projected link count.</li>
 * </ol>
 *
 * <h2>The remedy is run on a PROJECT home, and only on a project home</h2>
 *
 * <p>Deliberately. {@code sync --skip-mcp} is what the reconcile prints, and on
 * a project home it is safe. On a WORKTREE home it is not, and a graph that
 * asserted it there would be encoding the unsafe spelling as the contract:
 *
 * <ul>
 *   <li>{@code home close-out} counts {@code skills/<u>/.git/index} as unit
 *       work. Projecting a worktree home changed only that file — which is
 *       stat-dependent and is not content — and close-out then refused with
 *       {@code BLOCKED skill:<u> (merged) - 1 file(s) come from the source}.
 *       The worktree becomes unclosable.</li>
 *   <li>{@code sync} on a worktree home can INSTALL units the project home
 *       lacks, and every such unit is a teardown blocker in its own right.</li>
 * </ul>
 *
 * <p>The right fix is ledger-first — {@code home clone} already copies and
 * re-anchors {@code installed/<u>.projections.json}, so the records are correct
 * and only the symlinks are missing, and materializing records that already
 * exist touches no unit content. That belongs in the product, not here. What
 * belongs here is the guard that keeps this node from recommending the unsafe
 * route by demonstration: {@code onboarding.worktree.lifecycle} asserts that a
 * freshly projected worktree home is still CLOSABLE, before it plants anything
 * that would legitimately block it.
 *
 * <p>The companions run on a home this node repairs first. The order is:
 * observe the state {@code bootstrap} left, run the documented remedy, prove
 * the oracle discriminates on the repaired home, then restore it — so the
 * nodes below this one get a home an agent could actually launch against,
 * which is the state the rest of the walk assumes.
 */
public class OnboardingProjectionsMaterialized {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.projections.materialized")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.clone.drops.foreign.claims")
            .tags("onboarding", "projection", "blocker")
            .timeout("900s")
            .output("remedyExit", "string")
            .output("remedyLog", "string");

    /** {@code verified:  N skill(s) servable} */
    static final Pattern VERIFIED =
            Pattern.compile("^\\s*verified:\\s*(\\d+) skill\\(s\\) servable", Pattern.MULTILINE);

    /**
     * {@code skills:    N} or its successor {@code projected: N of M into each
     * of .claude .codex .gemini}.
     *
     * <p>Either satisfies "the run got far enough to report a count". Keyed on
     * both because the line this precondition guards changed shape once already,
     * and a precondition that silently stops matching turns the conditional it
     * guards back into a vacuous truth — which is the whole thing it is for.
     */
    static final Pattern SKILLS_LINE =
            Pattern.compile("^\\s*(?:skills:\\s*(\\d+)|projected:\\s*(\\d+) of (\\d+))",
                    Pattern.MULTILINE);

    /** {@code projected: N of M into each of .claude .codex .gemini} */
    static final Pattern PROJECTED =
            Pattern.compile("^\\s*projected:\\s*(\\d+) of (\\d+) into each of",
                    Pattern.MULTILINE);

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path proj = path(ctx, "onboarding.fixture.built", "proj");
            Path home = path(ctx, "onboarding.bootstrapped", "projectHome");
            String bootstrapLogPath = ctx.get("onboarding.bootstrapped", "bootstrapLog")
                    .orElse("");
            if (proj == null || home == null || bootstrapLogPath.isEmpty()) {
                return NodeResult.fail("onboarding.projections.materialized",
                        "missing upstream context");
            }
            String bootstrapLog = OnboardingSupport.read(Path.of(bootstrapLogPath));

            // --- the floor under the verified: assertion ---------------------
            //
            // Without these two the conditional below is vacuously true on any
            // run where the bootstrap failed early.
            boolean theBootstrapRanFarEnoughToReport =
                    SKILLS_LINE.matcher(bootstrapLog).find();
            String verifiedN = OnboardingSupport.firstGroup(bootstrapLog, VERIFIED);
            boolean theBootstrapPrintedVerified = verifiedN != null;

            // --- what an agent launched here would actually see ---------------
            List<String> storeUnits = OnboardingSupport.storeUnits(home);
            List<String> servableBefore = OnboardingSupport.servableUnits(home, proj);
            List<String> perAgentBefore = new ArrayList<>();
            for (String agent : OnboardingSupport.AGENTS) {
                int n = 0;
                for (String unit : storeUnits) {
                    if (OnboardingSupport.servable(proj, agent, unit)) n++;
                }
                perAgentBefore.add(agent + "=" + n + "/" + storeUnits.size());
            }
            boolean everyStoreUnitIsServableOnAllThreeAgents =
                    !storeUnits.isEmpty() && servableBefore.size() == storeUnits.size();

            // The conditional, in both directions.
            boolean verifiedIsEarned = !theBootstrapPrintedVerified
                    || (everyStoreUnitIsServableOnAllThreeAgents
                            && Integer.parseInt(verifiedN) == servableBefore.size());
            boolean theToolDidNotClaimVerifiedOverAnUnprojectedHome =
                    theBootstrapRanFarEnoughToReport && verifiedIsEarned;

            // The stronger form of the same claim, and the one worth keeping:
            // the EXIT CODE agrees with what was actually projected. A shortfall
            // exits 6 with every missing link named and the word `verified`
            // withheld, so an automated caller no longer has to parse prose to
            // learn that the home it just made is unservable. "Did not print a
            // word" was only ever the assertion available before there was a
            // code to read.
            int bootstrapExit = intOf(ctx, "onboarding.bootstrapped", "bootstrapExit");
            boolean theExitCodeAgreesWithWhatWasProjected = bootstrapExit >= 0
                    && (everyStoreUnitIsServableOnAllThreeAgents
                            ? bootstrapExit == 0 : bootstrapExit != 0);

            // And the count it reports is the count an agent can read, not the
            // store's. `projected: N of M` is absent on older builds; when it is
            // there, N must equal what this node measured through the links.
            String projectedRaw = OnboardingSupport.firstGroup(bootstrapLog, PROJECTED);
            boolean theProjectedCountMatchesTheLinksOnDisk = projectedRaw == null
                    || Integer.parseInt(projectedRaw) == servableBefore.size();

            // --- the documented remedy, and the state it produces --------------
            //
            // Run whatever the state above was: the rest of the walk needs a
            // home an agent could launch against, and this is the only command
            // that produces one.
            ProcessRecord remedy = OnboardingSupport.sm(ctx, "sync-skip-mcp", home, proj,
                    "sync", "--skip-mcp");
            List<String> servableAfter = OnboardingSupport.servableUnits(home, proj);
            boolean theDocumentedRemedyProjectsEveryUnit =
                    servableAfter.size() == storeUnits.size() && !storeUnits.isEmpty();

            // Is one pass enough? `sync` has been measured NOT to be a fixpoint
            // across an install: one pass installed two units and then reported
            // those same two as unprojected, and a second pass completed it. A
            // node that ran one sync and asserted the result would be FLAKY
            // rather than wrong, which is worse — it would pass on the runs
            // where the ordering happened to work out. So the second pass is
            // run and required to change nothing.
            ProcessRecord secondPass = OnboardingSupport.sm(ctx, "sync-skip-mcp-again", home, proj,
                    "sync", "--skip-mcp");
            List<String> servableAfterSecondPass = OnboardingSupport.servableUnits(home, proj);
            boolean oneSyncIsAFixpoint =
                    servableAfterSecondPass.equals(servableAfter);
            List<String> escaping = OnboardingSupport.escapingLinks(proj);
            boolean noProjectedLinkEscapesTheCheckout = escaping.isEmpty();

            // --- companions 1 and 2, on the repaired home ----------------------
            //
            // The oracle must be shown going red for each shape of breakage it
            // is supposed to catch. Without these, "N of N servable" is
            // indistinguishable from "the function counted the store".
            String victim = storeUnits.isEmpty() ? null : storeUnits.get(0);
            boolean theOracleGoesRedWhenALinkIsDeleted = false;
            boolean theOracleGoesRedWhenALinkIsDangling = false;
            if (victim != null && theDocumentedRemedyProjectsEveryUnit) {
                Path link = OnboardingSupport.agentSkills(proj, ".claude").resolve(victim);
                Path saved = link.resolveSibling(victim + ".saved");
                Path target = Files.isSymbolicLink(link) ? Files.readSymbolicLink(link) : null;

                Files.deleteIfExists(link);
                theOracleGoesRedWhenALinkIsDeleted =
                        OnboardingSupport.servableUnits(home, proj).size() != storeUnits.size();

                Files.createSymbolicLink(link, Path.of("/nowhere/at/all/" + victim));
                theOracleGoesRedWhenALinkIsDangling =
                        !OnboardingSupport.servable(proj, ".claude", victim);

                Files.deleteIfExists(link);
                if (target != null) Files.createSymbolicLink(link, target);
                Files.deleteIfExists(saved);
            }
            // And the repair must have taken, or every node below this one is
            // measuring a home that is still broken.
            boolean theHomeWasRestoredAfterTheCompanions =
                    OnboardingSupport.servableUnits(home, proj).size() == storeUnits.size();

            boolean pass = theBootstrapRanFarEnoughToReport
                    && everyStoreUnitIsServableOnAllThreeAgents
                    && theToolDidNotClaimVerifiedOverAnUnprojectedHome
                    && theExitCodeAgreesWithWhatWasProjected
                    && theProjectedCountMatchesTheLinksOnDisk
                    && theDocumentedRemedyProjectsEveryUnit
                    && oneSyncIsAFixpoint
                    && noProjectedLinkEscapesTheCheckout
                    && theOracleGoesRedWhenALinkIsDeleted
                    && theOracleGoesRedWhenALinkIsDangling
                    && theHomeWasRestoredAfterTheCompanions;

            return (pass
                    ? NodeResult.pass("onboarding.projections.materialized")
                    : NodeResult.fail("onboarding.projections.materialized",
                            "storeUnits=" + storeUnits.size()
                                    + " servableAfterBootstrap=" + servableBefore.size()
                                    + " perAgent=" + perAgentBefore
                                    + " verifiedPrinted=" + verifiedN
                                    + " servableAfterRemedy=" + servableAfter.size()
                                    + " remedyExit=" + remedy.exitCode()
                                    + " bootstrapExit=" + bootstrapExit
                                    + " projectedLine=" + projectedRaw
                                    + " servableAfterSecondPass="
                                    + servableAfterSecondPass.size()
                                    + " escapingLinks=" + escaping))
                    .process(remedy).process(secondPass)
                    .assertion("the_bootstrap_ran_far_enough_to_report_a_skill_count",
                            theBootstrapRanFarEnoughToReport)
                    .assertion("every_unit_in_the_store_is_servable_on_all_three_agents",
                            everyStoreUnitIsServableOnAllThreeAgents)
                    .assertion("the_tool_did_not_claim_verified_over_an_unprojected_home",
                            theToolDidNotClaimVerifiedOverAnUnprojectedHome)
                    .assertion("the_bootstrap_exit_code_agrees_with_what_it_projected",
                            theExitCodeAgreesWithWhatWasProjected)
                    .assertion("the_projected_count_matches_the_links_on_disk",
                            theProjectedCountMatchesTheLinksOnDisk)
                    .assertion("the_documented_remedy_projects_every_unit",
                            theDocumentedRemedyProjectsEveryUnit)
                    .assertion("a_second_sync_changes_nothing_ie_one_pass_is_a_fixpoint",
                            oneSyncIsAFixpoint)
                    .assertion("no_projected_link_resolves_outside_the_checkout",
                            noProjectedLinkEscapesTheCheckout)
                    .assertion("the_projection_oracle_goes_red_when_a_link_is_deleted",
                            theOracleGoesRedWhenALinkIsDeleted)
                    .assertion("the_projection_oracle_goes_red_when_a_link_is_dangling",
                            theOracleGoesRedWhenALinkIsDangling)
                    .assertion("the_home_was_restored_after_the_companions_ran",
                            theHomeWasRestoredAfterTheCompanions)
                    .metric("storeUnits", storeUnits.size())
                    .metric("servableAfterBootstrap", servableBefore.size())
                    .metric("servableAfterRemedy", servableAfter.size())
                    .metric("verifiedClaimed", verifiedN == null ? -1 : Integer.parseInt(verifiedN))
                    .metric("remedyExit", remedy.exitCode())
                    .log("per-agent after bootstrap: " + perAgentBefore)
                    .log("escaping links: " + escaping)
                    .publish("remedyExit", String.valueOf(remedy.exitCode()))
                    .publish("remedyLog", remedy.logPath() == null ? ""
                            : ctx.reportDir().resolve(remedy.logPath()).toString());
        });
    }

    private static int intOf(NodeContext ctx, String node, String key) {
        try {
            return Integer.parseInt(ctx.get(node, key).orElse("-1"));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
