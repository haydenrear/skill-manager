///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>Step 5 — install the skills the project declared: register, resolve,
 * sync.</b>
 *
 * <p>An ACTION node. Four nodes below it read the logs and the disk state this
 * one leaves, and running the sequence four times would give each of them a
 * different project to talk about.
 *
 * <h2>The two contract surprises this step contains, asserted as guards</h2>
 *
 * <ol>
 *   <li><b>{@code sync} has no {@code --skip-gateway}.</b> {@code project
 *       resolve} and {@code onboard} both take it; {@code sync} takes
 *       {@code --skip-agents} / {@code --skip-mcp} instead. Discovering that
 *       costs a round trip, and the CLI's own suggestion
 *       ({@code Possible solutions: --skip-agents, --skip-mcp}) is the only
 *       thing that makes it cheap. So the run asserts both halves: the flag is
 *       rejected with exit 2, AND the rejection names the alternatives. A
 *       usage error that did not suggest anything would be a regression in the
 *       only thing that made this discoverable.</li>
 *   <li><b>{@code project resolve} in the per-checkout layout is NORMAL.</b>
 *       The project's own home IS the active home; that used to be refused and
 *       is now a warning naming the layout. Only {@code --help} says so. The
 *       explanatory {@code !} line is asserted present — it is the whole
 *       reason an agent does not stop here.</li>
 * </ol>
 *
 * <h2>And the transitive-binding property, which nothing else covers</h2>
 *
 * <p>{@code project resolve} binds units nobody declared: the fixture declares
 * three and the umbrella pulls in a fourth through {@code skill_references}.
 * Asserted as {@code bindings ≥ declared}, with the transitively-resolved unit
 * named explicitly — a count alone would pass if the transitive resolution
 * silently stopped and something else was double-counted.
 */
public class OnboardingSynced {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.synced")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("onboarding.projections.materialized")
            .tags("onboarding", "project", "sync")
            .timeout("1200s")
            .output("registerLog", "string")
            .output("resolveLog", "string")
            .output("syncLog", "string")
            .output("syncExit", "string");

    static final Pattern RESOLVED_COUNT =
            Pattern.compile("^\\s*resolved:\\s*(\\d+)", Pattern.MULTILINE);
    static final Pattern BINDINGS_COUNT =
            Pattern.compile("^\\s*bindings:\\s*(\\d+)", Pattern.MULTILINE);

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path proj = path(ctx, "onboarding.fixture.built", "proj");
            Path home = path(ctx, "onboarding.bootstrapped", "projectHome");
            if (proj == null || home == null) {
                return NodeResult.fail("onboarding.synced", "missing upstream context");
            }

            ProcessRecord register = OnboardingSupport.sm(ctx, "project-register", home, proj,
                    "project", "register", "--project-dir", proj.toString());
            boolean theProjectRegistered = register.exitCode() == 0;

            ProcessRecord list = OnboardingSupport.sm(ctx, "project-list", home, proj,
                    "project", "list");
            boolean theRegistrationIsTheOnlyOne =
                    OnboardingSupport.log(ctx, list).contains("acme-widgets");

            ProcessRecord resolve = OnboardingSupport.sm(ctx, "project-resolve", home, proj,
                    "project", "resolve", "--project-dir", proj.toString(), "--skip-gateway");
            String resolveLog = OnboardingSupport.log(ctx, resolve);
            boolean theResolveExitedZero = resolve.exitCode() == 0;
            // The per-checkout layout is normal and the tool must say so. An
            // agent that reads a bare warning with no explanation stops here.
            boolean theResolveExplainedThePerCheckoutLayout =
                    resolveLog.contains("per-checkout layout");
            String resolvedRaw = OnboardingSupport.firstGroup(resolveLog, RESOLVED_COUNT);
            String bindingsRaw = OnboardingSupport.firstGroup(resolveLog, BINDINGS_COUNT);
            int resolved = resolvedRaw == null ? -1 : Integer.parseInt(resolvedRaw);
            int bound = bindingsRaw == null ? -1 : Integer.parseInt(bindingsRaw);
            boolean theResolveReportedItsCounts = resolved >= 0 && bound >= 0;
            // Three declared, and the umbrella's skill_references pull in a
            // fourth. "bindings >= declared" alone would pass on a run where
            // the transitive resolution stopped, so the transitive unit is
            // named.
            boolean everyDeclaredUnitWasBound = bound >= 3;
            boolean theTransitivelyResolvedUnitIsPresent = OnboardingSupport.storeUnits(home)
                    .contains(OnboardingSupport.TRANSITIVE);

            // The flag that is not there, and the suggestion that makes it cheap.
            ProcessRecord badFlag = OnboardingSupport.sm(ctx, "sync-skip-gateway", home, proj,
                    "sync", "--skip-gateway");
            String badFlagLog = OnboardingSupport.log(ctx, badFlag);
            boolean syncRejectsSkipGatewayWithAUsageError = badFlag.exitCode() == 2
                    && badFlagLog.contains("Unknown option");
            boolean thatRejectionNamesTheRealFlags =
                    badFlagLog.contains("--skip-agents") && badFlagLog.contains("--skip-mcp");

            ProcessRecord sync = OnboardingSupport.sm(ctx, "sync", home, proj, "sync");
            String syncLog = OnboardingSupport.log(ctx, sync);
            // Sync's exit code is NOT asserted zero: the fixture's units are
            // file:-installed and therefore carry a permanent error record,
            // which is itself under test in onboarding.error.records.coherent.
            // What is asserted is that it ran far enough to write the lock.
            boolean theSyncRanToItsLockWrite = syncLog.contains("units.lock.toml");

            boolean pass = theProjectRegistered && theRegistrationIsTheOnlyOne
                    && theResolveExitedZero && theResolveExplainedThePerCheckoutLayout
                    && theResolveReportedItsCounts && everyDeclaredUnitWasBound
                    && theTransitivelyResolvedUnitIsPresent
                    && syncRejectsSkipGatewayWithAUsageError && thatRejectionNamesTheRealFlags
                    && theSyncRanToItsLockWrite;

            return (pass
                    ? NodeResult.pass("onboarding.synced")
                    : NodeResult.fail("onboarding.synced",
                            "register=" + register.exitCode()
                                    + " resolve=" + resolve.exitCode()
                                    + " resolved=" + resolved + " bindings=" + bound
                                    + " badFlagExit=" + badFlag.exitCode()
                                    + " syncExit=" + sync.exitCode()
                                    + " lockWritten=" + theSyncRanToItsLockWrite))
                    .process(register).process(list).process(resolve)
                    .process(badFlag).process(sync)
                    .assertion("the_project_registered", theProjectRegistered)
                    .assertion("project_list_names_the_project", theRegistrationIsTheOnlyOne)
                    .assertion("project_resolve_exited_zero_in_the_per_checkout_layout",
                            theResolveExitedZero)
                    .assertion("project_resolve_explained_the_per_checkout_layout",
                            theResolveExplainedThePerCheckoutLayout)
                    .assertion("project_resolve_reported_resolved_and_binding_counts",
                            theResolveReportedItsCounts)
                    .assertion("every_declared_unit_was_bound", everyDeclaredUnitWasBound)
                    .assertion("a_transitively_resolved_unit_is_installed_like_a_declared_one",
                            theTransitivelyResolvedUnitIsPresent)
                    .assertion("sync_rejects_skip_gateway_with_a_usage_error",
                            syncRejectsSkipGatewayWithAUsageError)
                    .assertion("that_rejection_names_the_flags_that_do_exist",
                            thatRejectionNamesTheRealFlags)
                    .assertion("sync_ran_far_enough_to_write_the_unit_lock",
                            theSyncRanToItsLockWrite)
                    .metric("resolved", resolved)
                    .metric("bindings", bound)
                    .metric("syncExit", sync.exitCode())
                    .publish("registerLog", logPath(ctx, register))
                    .publish("resolveLog", logPath(ctx, resolve))
                    .publish("syncLog", logPath(ctx, sync))
                    .publish("syncExit", String.valueOf(sync.exitCode()));
        });
    }

    private static String logPath(NodeContext ctx, ProcessRecord p) {
        return p.logPath() == null ? "" : ctx.reportDir().resolve(p.logPath()).toString();
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
