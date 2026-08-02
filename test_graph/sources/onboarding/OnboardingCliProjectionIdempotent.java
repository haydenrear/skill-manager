///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Regression guard — re-running the bootstrap on a populated home is a
 * no-op, including where a CLI shim is a REGULAR FILE.</b>
 *
 * <p>{@code --force} re-runs the steps after the clone on an existing live
 * home, and it is exactly the invocation an onboarding agent reaches when
 * something looked wrong the first time. It used to throw when
 * {@code <home>/bin/cli/<dep>} was a regular file rather than a symlink — the
 * shape the {@code skill-script} CLI backend produces, since its install script
 * writes a plain executable.
 *
 * <h2>Vacuous-pass risk and companion</h2>
 *
 * <p><b>Risk:</b> passing because no regular-file shim exists in the home, so
 * the code path that used to throw is never entered. A home built from
 * symlink-only units satisfies this assertion while proving nothing.
 * <br><b>Companion — mandatory:</b> {@code find <home>/bin/cli -maxdepth 1
 * -type f} must be non-empty BEFORE the re-run, and the node names the file it
 * found. The fixture guarantees one by installing a unit whose CLI dep uses the
 * {@code skill-script} backend; if that ever stops producing a regular file,
 * this node fails rather than silently degrading.
 *
 * <p>The second half of idempotency is the projected link count: {@code --force}
 * must leave the agent-visible projection exactly as it was. Counting only the
 * exit code would pass on a run that exited 0 and deleted every link.
 */
public class OnboardingCliProjectionIdempotent {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.cli.projection.idempotent")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.remedies.are.runnable")
            .tags("onboarding", "idempotency", "regression")
            .timeout("900s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path proj = path(ctx, "onboarding.fixture.built", "proj");
            Path ambient = path(ctx, "onboarding.fixture.built", "ambient");
            Path scriptsDir = path(ctx, "onboarding.fixture.built", "scriptsDir");
            Path home = path(ctx, "onboarding.bootstrapped", "projectHome");
            if (proj == null || home == null || ambient == null || scriptsDir == null) {
                return NodeResult.fail("onboarding.cli.projection.idempotent",
                        "missing upstream context");
            }

            // --- the mandatory precondition -----------------------------------
            Path binCli = home.resolve("bin").resolve("cli");
            List<String> regularFileShims = new ArrayList<>();
            for (String name : OnboardingSupport.names(binCli)) {
                if (Files.isRegularFile(binCli.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                    regularFileShims.add(name);
                }
            }
            boolean theHomeHoldsAtLeastOneRegularFileShim = !regularFileShims.isEmpty();

            int projectedBefore = OnboardingSupport.servableUnits(home, proj).size();
            int storeBefore = OnboardingSupport.storeUnits(home).size();

            ProcessRecord force = OnboardingSupport.script(ctx, "bootstrap-force", proj,
                    scriptsDir.resolve("bootstrap-home.sh"), ambient,
                    "--root", proj.toString(), "--force");
            String log = OnboardingSupport.log(ctx, force);

            boolean theForcedRerunExitedZero = force.exitCode() == 0;
            boolean theForcedRerunThrewNothing =
                    OnboardingSupport.stackFrames(log).isEmpty()
                            && !log.contains("Exception in thread");
            boolean theForcedRerunLeftThePinAlone = log.contains("left as written");

            int projectedAfter = OnboardingSupport.servableUnits(home, proj).size();
            int storeAfter = OnboardingSupport.storeUnits(home).size();
            boolean theProjectedLinkCountIsUnchanged = projectedAfter == projectedBefore;
            boolean theStoreIsUnchanged = storeAfter == storeBefore;
            // And the regular-file shim survived: the throw it used to cause was
            // on the path that would have replaced it.
            List<String> shimsAfter = new ArrayList<>();
            for (String name : OnboardingSupport.names(binCli)) {
                if (Files.isRegularFile(binCli.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                    shimsAfter.add(name);
                }
            }
            boolean theRegularFileShimSurvivedTheRerun =
                    shimsAfter.containsAll(regularFileShims);

            boolean pass = theHomeHoldsAtLeastOneRegularFileShim
                    && theForcedRerunExitedZero && theForcedRerunThrewNothing
                    && theProjectedLinkCountIsUnchanged && theStoreIsUnchanged
                    && theRegularFileShimSurvivedTheRerun;

            return (pass
                    ? NodeResult.pass("onboarding.cli.projection.idempotent")
                    : NodeResult.fail("onboarding.cli.projection.idempotent",
                            "regularFileShims=" + regularFileShims
                                    + " forceExit=" + force.exitCode()
                                    + " projected=" + projectedBefore + "->" + projectedAfter
                                    + " store=" + storeBefore + "->" + storeAfter
                                    + " shimsAfter=" + shimsAfter))
                    .process(force)
                    .assertion("the_home_holds_at_least_one_regular_file_cli_shim",
                            theHomeHoldsAtLeastOneRegularFileShim)
                    .assertion("the_forced_rerun_exited_zero", theForcedRerunExitedZero)
                    .assertion("the_forced_rerun_threw_nothing", theForcedRerunThrewNothing)
                    .assertion("the_projected_link_count_is_unchanged",
                            theProjectedLinkCountIsUnchanged)
                    .assertion("the_store_is_unchanged", theStoreIsUnchanged)
                    .assertion("the_regular_file_shim_survived_the_rerun",
                            theRegularFileShimSurvivedTheRerun)
                    .metric("regularFileShims", regularFileShims.size())
                    .metric("projectedBefore", projectedBefore)
                    .metric("projectedAfter", projectedAfter)
                    .log("regular-file shims before: " + regularFileShims)
                    .log("cli pin line: " + (theForcedRerunLeftThePinAlone
                            ? "left as written" : "NOT reported as left as written"));
        });
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
