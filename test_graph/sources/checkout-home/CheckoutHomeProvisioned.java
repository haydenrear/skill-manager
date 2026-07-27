///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../home-clone/HomeCloneSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Provisions the cloned home's launch surface with {@code home shims}, then
 * asserts what that command is now contracted to produce.
 *
 * <h2>Why a provisioning step, and what the first version of this node got wrong</h2>
 *
 * <p>The first version asserted the CLI entrypoint directly on a fresh clone and
 * failed. That reproduced issue #10's second defect, but it also mis-stated it.
 * {@code home clone} copies and re-anchors whatever {@code bin/} the SOURCE had;
 * it does not invent a launch surface. A clone of a fixture home that never had
 * shims correctly has no shims, and asserting otherwise would have pushed the
 * fix into the wrong command.
 *
 * <p>The real defect was narrower and worse: {@code <home>/bin/cli/skill-manager}
 * had TWO readers — the launcher script and {@code HomeDescriptor.resolveCli} —
 * and NO writer, anywhere. {@code home shims} now writes it alongside the
 * launchers, because it is the same category of artifact (a generated,
 * relocatable shim the home owns) and that command already owns them.
 *
 * <h2>The exit-127 assertion is the actual bug being pinned</h2>
 *
 * <p>The reported symptom of #10 was a shim that printed a usage error and
 * <em>exited 0</em> — a silent no-launch, which reads as success to every caller
 * and to every graph node that checks an exit code. So this node does not merely
 * assert the file exists. It runs the shim with no CLI reachable and asserts it
 * exits NON-ZERO. A file that exists and lies is worse than one that is absent.
 *
 * <p>That run doubles as the recursion check. {@link
 * dev.skillmanager.launch.LaunchEnv} puts {@code <home>/bin/cli} at the front of
 * the launch PATH, so a shim there resolving {@code skill-manager} from PATH
 * would find itself and spin. The shim strips its own directory first; if that
 * ever regresses, this invocation hangs and the node's timeout reports it.
 */
public class CheckoutHomeProvisioned {
    static final NodeSpec SPEC = NodeSpec.of("checkout.home.provisioned")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("home.cloned.into.project")
            .tags("checkout-home", "home", "shims")
            // Short on purpose. The no-CLI invocation below must terminate; if
            // the shim ever recurses into itself, this bound is what turns an
            // unbounded spin into a reported failure.
            .timeout("120s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String store = ctx.get("home.clone.fixture.built", "cloneStore").orElse(null);
            if (store == null) {
                return NodeResult.fail("checkout.home.provisioned", "missing upstream context");
            }
            Path home = Path.of(store);

            ProcessRecord shims =
                    HomeCloneSupport.sm(ctx, "home-shims", store, "home", "shims", "--home", store);
            boolean homeShimsExitsZero = shims.exitCode() == 0;

            Path cli = home.resolve("bin").resolve("cli").resolve("skill-manager");
            boolean theCliEntrypointWasWritten = Files.exists(cli, LinkOption.NOFOLLOW_LINKS);
            boolean theCliEntrypointIsExecutable = Files.isExecutable(cli);

            List<String> launchers = HomeCloneSupport.names(home.resolve("bin").resolve("launch"));
            boolean everyAgentLauncherWasWritten =
                    launchers.containsAll(List.of("claude", "codex", "gemini"));

            // The shim body must carry no absolute path, or a cloned home stops
            // working the moment it is moved — the property the whole
            // per-project-home design rests on.
            String body = HomeCloneSupport.read(cli);
            boolean theCliEntrypointCarriesNoAbsoluteHomePath =
                    !body.isBlank() && !body.contains(store);

            // Run it with nothing findable: SKILL_MANAGER_CLI cleared and PATH
            // holding only the shim's own directory. The only reachable
            // "skill-manager" is the shim itself.
            boolean theCliEntrypointRefusesRatherThanSucceedingSilently = false;
            int refusalExit = -1;
            if (theCliEntrypointIsExecutable) {
                ProcessBuilder pb = new ProcessBuilder(cli.toString(), "--version");
                pb.environment().remove("SKILL_MANAGER_CLI");
                pb.environment().put("PATH", cli.getParent().toString());
                ProcessRecord refusal = Procs.run(ctx, "cli-with-no-backing", pb);
                refusalExit = refusal.exitCode();
                theCliEntrypointRefusesRatherThanSucceedingSilently = refusalExit != 0;
            }

            boolean pass = homeShimsExitsZero && theCliEntrypointWasWritten
                    && theCliEntrypointIsExecutable && everyAgentLauncherWasWritten
                    && theCliEntrypointCarriesNoAbsoluteHomePath
                    && theCliEntrypointRefusesRatherThanSucceedingSilently;

            return (pass
                    ? NodeResult.pass("checkout.home.provisioned")
                    : NodeResult.fail("checkout.home.provisioned",
                            "shimsExit=" + shims.exitCode() + " cli=" + cli
                                    + " exists=" + theCliEntrypointWasWritten
                                    + " launchers=" + launchers
                                    + " noAbsolutePath=" + theCliEntrypointCarriesNoAbsoluteHomePath
                                    + " refusalExit=" + refusalExit))
                    .assertion("home_shims_exits_zero", homeShimsExitsZero)
                    .assertion("the_cli_entrypoint_was_written", theCliEntrypointWasWritten)
                    .assertion("the_cli_entrypoint_is_executable", theCliEntrypointIsExecutable)
                    .assertion("every_agent_launcher_was_written", everyAgentLauncherWasWritten)
                    .assertion("the_cli_entrypoint_carries_no_absolute_home_path",
                            theCliEntrypointCarriesNoAbsoluteHomePath)
                    .assertion("the_cli_entrypoint_refuses_rather_than_succeeding_silently",
                            theCliEntrypointRefusesRatherThanSucceedingSilently)
                    .metric("refusalExitCode", refusalExit)
                    .process(shims);
        });
    }
}
