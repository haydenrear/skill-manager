///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Regression guards over what {@code home clone} already got right, plus
 * the deterministic half of the gateway defect.</b>
 *
 * <p>These are the cheapest nodes in the graph and the most valuable per line:
 * each of them describes a fix that cost a round trip to find, and none of them
 * is covered anywhere else.
 *
 * <h2>1. The clone's success message states what it did AND did not check</h2>
 *
 * <p>The line is honest in an unusual and load-bearing way — it names four
 * things it verified and then names the thing it did not:
 *
 * <pre>
 * ✓ cloned home … checked: nothing in it resolves back to &lt;src&gt;; no path in it
 *   reaches another Skill Manager home; no record or link in it names another
 *   home's agent directories (.claude, .codex, .gemini).
 *   NOT checked: paths outside all of those — toolchains, project checkouts,
 *   anything else on this machine.
 * </pre>
 *
 * <p>That {@code NOT checked} clause is exactly where the inherited-claims
 * defect lives, which is why the clause matters: the message is honest and the
 * gap is real, and a future edit that quietly drops the clause would make the
 * message a lie without changing any behaviour.
 *
 * <p><b>Vacuous-pass risk:</b> substring-matching one clause and calling it
 * done. <b>Companion:</b> all four checked clauses AND the {@code NOT checked:}
 * clause are required, each asserted separately so a failure names which one
 * went missing.
 *
 * <h2>2. A clone inherits zero project registrations</h2>
 *
 * <p><b>Vacuous-pass risk:</b> passing because the source had none — a
 * purpose-built fixture home is clean by construction, which is exactly why
 * this class of defect survives. <b>Companion, mandatory:</b> the fixture
 * planted ≥2 registrations naming checkouts outside the home and asserted them
 * present; this node re-asserts the source still has them before checking that
 * the clone has none. If the pollution is absent the node FAILS rather than
 * skipping.
 *
 * <h2>3. A fresh clone is not "drifted"</h2>
 *
 * <p>{@code bootstrap} and {@code clone} baseline the copy against its own
 * content, so the exit-8 change-awareness gate does not fire on a home's first
 * launch. <b>Vacuous-pass risk:</b> passing because no launch was ever
 * attempted. <b>Companion:</b> the drift baseline line must be present in the
 * bootstrap output AND a launch must have actually run — {@code exec
 * --print-env} through the home's own pinned shim, exit 0.
 *
 * <h2>4. The gateway file half — and what is deliberately NOT asserted</h2>
 *
 * <p>A clone records {@code gateway.owned=false} and a URL, and attaches to
 * whatever process owns that URL. The full property worth asserting is "a home
 * created with a redirected {@code HOME} does not attach to a gateway owned by
 * another home" — and it is <b>not automatable</b>. It needs a live gateway
 * process owned by a different home on a hard-coded port
 * ({@code GatewayConfig.DEFAULT_URL = http://127.0.0.1:51717}); two graphs
 * running concurrently on one machine would contend for that port, which IS the
 * defect, so a node that started a second gateway would be measuring the CI
 * scheduler rather than the product.
 *
 * <p>So the FILE half is asserted here — deterministic, offline, no process
 * involved — and the process half is recorded as a <b>manual check</b>:
 *
 * <pre>
 * With the operator's gateway running, run bootstrap-home.sh under
 * HOME=&lt;fixture&gt; and confirm the printed
 *   gateway: attached to &lt;url&gt; (the source home owns it)
 * names the operator's ~/.skill-manager/gateway.pid process:
 *   lsof -nP -iTCP:51717 -sTCP:LISTEN
 * Observed: it did — a fully redirected HOME still binds its agents' MCP tool
 * list to the operator's global-home-owned gateway. "Per-checkout isolation" is
 * not true of MCP.
 * </pre>
 */
public class OnboardingCloneIsHonest {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.clone.is.honest")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.bootstrapped")
            .tags("onboarding", "clone", "regression")
            .timeout("600s");

    /** The four things the clone message claims to have checked. */
    static final List<String> CHECKED_CLAUSES = List.of(
            "checked:",
            "resolves back to",
            "reaches another Skill Manager home",
            "names another home's agent directories");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path proj = path(ctx, "onboarding.fixture.built", "proj");
            Path srcHome = path(ctx, "onboarding.fixture.built", "srcHome");
            Path srcAgents = path(ctx, "onboarding.fixture.built", "srcAgents");
            Path foreignBase = path(ctx, "onboarding.fixture.built", "foreignBase");
            Path home = path(ctx, "onboarding.bootstrapped", "projectHome");
            String logPath = ctx.get("onboarding.bootstrapped", "bootstrapLog").orElse("");
            if (proj == null || srcHome == null || home == null || logPath.isEmpty()) {
                return NodeResult.fail("onboarding.clone.is.honest", "missing upstream context");
            }
            String log = OnboardingSupport.read(Path.of(logPath));

            // --- 1. the message states what it checked and what it did not -----
            List<String> missingClauses = new ArrayList<>();
            for (String clause : CHECKED_CLAUSES) {
                if (!log.contains(clause)) missingClauses.add(clause);
            }
            boolean theCloneMessageNamesEverythingItChecked = missingClauses.isEmpty();
            boolean theCloneMessageNamesWhatItDidNotCheck = log.contains("NOT checked:");

            // --- 2. registrations are not inherited -----------------------------
            //
            // The mandatory precondition first: the SOURCE must still carry the
            // registrations the fixture planted. "The clone has none" is
            // worthless over a source that had none.
            var srcList = OnboardingSupport.sm(ctx, "src-projects", srcHome,
                    srcAgents == null ? srcHome : srcAgents, "project", "list");
            String srcListOut = OnboardingSupport.log(ctx, srcList);
            int foreignInSource = 0;
            List<String> foreignNames = new ArrayList<>();
            if (foreignBase != null) {
                for (String name : OnboardingSupport.names(foreignBase)) {
                    if (srcListOut.contains(name)) {
                        foreignInSource++;
                        foreignNames.add(name);
                    }
                }
            }
            boolean theSourceHomeStillCarriesForeignRegistrations = foreignInSource >= 2;

            var cloneList = OnboardingSupport.sm(ctx, "clone-projects", home, proj,
                    "project", "list");
            String cloneListOut = OnboardingSupport.log(ctx, cloneList);
            List<String> inheritedRegistrations = new ArrayList<>();
            for (String name : foreignNames) {
                if (cloneListOut.contains(name)) inheritedRegistrations.add(name);
            }
            boolean theCloneInheritedNoForeignRegistrations = inheritedRegistrations.isEmpty();
            // The tool says so too, and the wording is the operator-visible half.
            boolean theCloneReportedNotInheritingThem = log.contains("not inherited");

            // --- 3. a clone is not drifted ---------------------------------------
            boolean theCloneRecordedItsOwnDriftBaseline =
                    log.contains("a clone is not drifted");
            var launch = OnboardingSupport.pinned(ctx, "clone-first-launch", home, proj,
                    "exec", "--print-env");
            boolean theHomesOwnShimLaunchedWithoutTrippingTheDriftGate =
                    launch.exitCode() == 0;

            // --- 4. the gateway file half -----------------------------------------
            Path gatewayFile = home.resolve("gateway.properties");
            String gateway = OnboardingSupport.read(gatewayFile);
            boolean theCloneRecordedThatItDoesNotOwnTheGateway =
                    gateway.contains("gateway.owned=false");
            boolean theCloneRecordedAGatewayUrl = gateway.contains("gateway.url=");
            // The companion for those two: the file must exist and be non-empty,
            // or "does not contain gateway.owned=true" would be true of nothing.
            boolean theGatewayFileExists = Files.isRegularFile(gatewayFile)
                    && !gateway.isBlank();

            boolean pass = theCloneMessageNamesEverythingItChecked
                    && theCloneMessageNamesWhatItDidNotCheck
                    && theSourceHomeStillCarriesForeignRegistrations
                    && theCloneInheritedNoForeignRegistrations
                    && theCloneReportedNotInheritingThem
                    && theCloneRecordedItsOwnDriftBaseline
                    && theHomesOwnShimLaunchedWithoutTrippingTheDriftGate
                    && theGatewayFileExists
                    && theCloneRecordedThatItDoesNotOwnTheGateway
                    && theCloneRecordedAGatewayUrl;

            return (pass
                    ? NodeResult.pass("onboarding.clone.is.honest")
                    : NodeResult.fail("onboarding.clone.is.honest",
                            "missingClauses=" + missingClauses
                                    + " notCheckedClause=" + theCloneMessageNamesWhatItDidNotCheck
                                    + " foreignRegistrationsInSource=" + foreignInSource
                                    + " inheritedByClone=" + inheritedRegistrations
                                    + " driftBaseline=" + theCloneRecordedItsOwnDriftBaseline
                                    + " launchExit=" + launch.exitCode()
                                    + " gateway=" + gateway.strip()))
                    .process(srcList)
                    .process(cloneList)
                    .process(launch)
                    .assertion("the_clone_message_names_everything_it_checked",
                            theCloneMessageNamesEverythingItChecked)
                    .assertion("the_clone_message_names_what_it_did_not_check",
                            theCloneMessageNamesWhatItDidNotCheck)
                    .assertion("the_source_home_still_carries_at_least_two_foreign_registrations",
                            theSourceHomeStillCarriesForeignRegistrations)
                    .assertion("the_clone_inherited_no_foreign_project_registrations",
                            theCloneInheritedNoForeignRegistrations)
                    .assertion("the_clone_reported_that_it_did_not_inherit_them",
                            theCloneReportedNotInheritingThem)
                    .assertion("the_clone_baselined_drift_against_its_own_content",
                            theCloneRecordedItsOwnDriftBaseline)
                    .assertion("the_homes_own_shim_launched_without_tripping_the_drift_gate",
                            theHomesOwnShimLaunchedWithoutTrippingTheDriftGate)
                    .assertion("the_clone_wrote_a_gateway_properties_file", theGatewayFileExists)
                    .assertion("the_clone_recorded_that_it_does_not_own_the_gateway",
                            theCloneRecordedThatItDoesNotOwnTheGateway)
                    .assertion("the_clone_recorded_the_gateway_url_it_attached_to",
                            theCloneRecordedAGatewayUrl)
                    .metric("foreignRegistrationsInSource", foreignInSource)
                    .metric("foreignRegistrationsInClone", inheritedRegistrations.size())
                    .log("gateway.properties: " + gateway.strip().replace('\n', ' '))
                    .log("MANUAL CHECK (not automatable, see javadoc): with the operator's"
                            + " gateway running, bootstrap under a redirected HOME and confirm"
                            + " `gateway: attached to <url>` names the process holding"
                            + " 127.0.0.1:51717 per `lsof -nP -iTCP:51717 -sTCP:LISTEN`."
                            + " Observed by hand: it did.");
        });
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
