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
 * <h2>The exit-127 assertion was ONE-SIDED, and a one-sided assertion is how a
 * dead shim passes</h2>
 *
 * <p>The reported symptom of #10 was a shim that printed a usage error and
 * <em>exited 0</em> — a silent no-launch, which reads as success to every caller
 * and to every graph node that checks an exit code. So this node runs the shim
 * with no CLI reachable and asserts it refuses.
 *
 * <p>That is necessary and it is not sufficient, and the gap had a live defect
 * sitting in it. {@code LauncherShims.cliScript()} emitted {@code printf '%%s'}
 * — correct in {@link dev.skillmanager.launch.LauncherShims#script(String)},
 * which ends in {@code .formatted(agent)}, and wrong here, where nothing
 * unescapes it. Bash printed the two characters {@code %s}, the filtered PATH
 * became the single entry {@code %s}, {@code command -v skill-manager} found
 * nothing, and the shim took the "no CLI provisioned" branch UNCONDITIONALLY —
 * exit 127 on every one of seven onboarded homes, with a working CLI on PATH.
 * This node passed throughout, reporting {@code refusalExitCode: 127}, because
 * a shim that ALWAYS refuses satisfies "it refuses rather than succeeding
 * silently" perfectly.
 *
 * <p>So the claim is now two-sided, and the succeeding half is asserted on the
 * exec'd CLI's OUTPUT rather than on a zero exit — a shim that exits 0 without
 * launching anything is exactly the failure #10 was about.
 *
 * <h2>The recursion guard is now genuinely exercised</h2>
 *
 * <p>{@link dev.skillmanager.launch.LaunchEnv} puts {@code <home>/bin/cli} at
 * the FRONT of the launch PATH, so a shim there resolving {@code skill-manager}
 * from PATH would find itself and spin. The shim strips its own directory
 * first — and with {@code printf '%%s'} broken that filter had never once run,
 * so the guard was untested in practice rather than merely under-tested. The
 * third case below puts the shim's own directory first on PATH and a working
 * stub second, exactly as a real launch arranges them: a shim that fails to
 * exclude itself recurses instead of finding the stub, and the bounded wait
 * turns that into a reported failure rather than a hang.
 *
 * <h2>Every case runs on a hermetic PATH</h2>
 *
 * <p>The shim needs {@code dirname} and {@code tr}, so PATH cannot simply be
 * emptied. Each case is given a {@code tools/} directory holding links to those
 * two and nothing else, which is what makes "no CLI is reachable" a fact about
 * the case rather than a hope about the machine — and what stops the refusal
 * case from silently finding the operator's real CLI.
 */
public class CheckoutHomeProvisioned {
    static final NodeSpec SPEC = NodeSpec.of("checkout.home.provisioned")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("home.cloned.into.project")
            .tags("checkout-home", "home", "shims")
            // Three bounded shim invocations plus `home shims` itself. The
            // per-invocation bound (BOUND_SECONDS) is what turns a self-exec
            // spin into a NAMED failure; this is only the outer backstop, so it
            // has to be comfortably larger than 3 x that bound rather than
            // tighter than it.
            .timeout("300s");

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

            // A doubled percent in a text block with no `.formatted()` reaches
            // bash literally. Asserted on the generated BYTES as well as on
            // behaviour, because this is the cheapest place to catch the next
            // copy-paste from `script(String)` and it names the cause rather
            // than a symptom.
            boolean theCliEntrypointCarriesNoUnformattedPercent = !body.contains("%%");

            Path sandbox = Files.createTempDirectory("checkout-home-cli-");
            Path tools = hermeticTools(sandbox);
            Path stubDir = stubCli(sandbox);
            String shimDir = cli.getParent().toString();

            // (1) NOTHING reachable. The only `skill-manager` on PATH is the
            // shim itself, so every correct outcome is a refusal — asserted on
            // the diagnostic, not merely on a non-zero exit, because `set -e`
            // tripping over a missing `dirname` is also a non-zero exit and is
            // not the branch this is about.
            Run refusal = run(ctx, "cli-with-no-backing", cli,
                    shimDir + ":" + tools, sandbox);
            boolean theCliEntrypointRefusesRatherThanSucceedingSilently =
                    refusal.exit() != 0 && refusal.out().contains("no CLI is provisioned");

            // (2) A CLI IS reachable. The shim must exec it — proven by the
            // exec'd process's own output and its view of the arguments, since
            // a shim that exits 0 without launching is the defect #10 named.
            Run launched = run(ctx, "cli-with-a-real-backing", cli,
                    stubDir + ":" + tools, sandbox);
            boolean theCliEntrypointExecsTheCliItFinds =
                    launched.exit() == 0 && launched.out().contains(STUB_SENTINEL)
                            && launched.out().contains("args=--version");

            // (3) The shim's own directory FIRST, exactly as LaunchEnv arranges
            // a launch PATH, with the working stub behind it. Excluding itself
            // is the only way to reach the stub; failing to recurses.
            Run selfFirst = run(ctx, "cli-with-self-first-on-path", cli,
                    shimDir + ":" + stubDir + ":" + tools, sandbox);
            boolean theCliEntrypointNeverResolvesToItself =
                    !selfFirst.timedOut() && selfFirst.exit() == 0
                            && selfFirst.out().contains(STUB_SENTINEL);

            boolean pass = homeShimsExitsZero && theCliEntrypointWasWritten
                    && theCliEntrypointIsExecutable && everyAgentLauncherWasWritten
                    && theCliEntrypointCarriesNoAbsoluteHomePath
                    && theCliEntrypointCarriesNoUnformattedPercent
                    && theCliEntrypointRefusesRatherThanSucceedingSilently
                    && theCliEntrypointExecsTheCliItFinds
                    && theCliEntrypointNeverResolvesToItself;

            return (pass
                    ? NodeResult.pass("checkout.home.provisioned")
                    : NodeResult.fail("checkout.home.provisioned",
                            "shimsExit=" + shims.exitCode() + " cli=" + cli
                                    + " exists=" + theCliEntrypointWasWritten
                                    + " launchers=" + launchers
                                    + " noAbsolutePath=" + theCliEntrypointCarriesNoAbsoluteHomePath
                                    + " noDoubledPercent="
                                    + theCliEntrypointCarriesNoUnformattedPercent
                                    + " refusal=" + refusal
                                    + " launched=" + launched
                                    + " selfFirst=" + selfFirst))
                    .assertion("home_shims_exits_zero", homeShimsExitsZero)
                    .assertion("the_cli_entrypoint_was_written", theCliEntrypointWasWritten)
                    .assertion("the_cli_entrypoint_is_executable", theCliEntrypointIsExecutable)
                    .assertion("every_agent_launcher_was_written", everyAgentLauncherWasWritten)
                    .assertion("the_cli_entrypoint_carries_no_absolute_home_path",
                            theCliEntrypointCarriesNoAbsoluteHomePath)
                    .assertion("the_cli_entrypoint_carries_no_unformatted_percent",
                            theCliEntrypointCarriesNoUnformattedPercent)
                    .assertion("the_cli_entrypoint_refuses_rather_than_succeeding_silently",
                            theCliEntrypointRefusesRatherThanSucceedingSilently)
                    .assertion("the_cli_entrypoint_execs_the_cli_it_finds",
                            theCliEntrypointExecsTheCliItFinds)
                    .assertion("the_cli_entrypoint_never_resolves_to_itself",
                            theCliEntrypointNeverResolvesToItself)
                    .metric("refusalExitCode", refusal.exit())
                    .process(shims);
        });
    }

    /** Printed by the stub CLI; finding it proves the shim really exec'd. */
    private static final String STUB_SENTINEL = "STUB-CLI-WAS-EXECED";

    /** How long a shim invocation may take before it is treated as recursion. */
    private static final long BOUND_SECONDS = 45;

    private record Run(int exit, String out, boolean timedOut) {
        @Override
        public String toString() {
            return "{exit=" + exit + " timedOut=" + timedOut
                    + " out=" + out.replace('\n', '|').trim() + "}";
        }
    }

    /**
     * Run the shim with {@code path} as its whole PATH, bounded.
     *
     * <p>Bounded rather than left to the node timeout because the failure this
     * is guarding is an unbounded self-exec: a node timeout reports "the node
     * took too long", while this reports which invocation spun and with what
     * PATH. Spawned by hand rather than through {@code Procs.run} for the same
     * reason — that helper's {@code waitFor()} has no bound.
     */
    private static Run run(com.hayden.testgraphsdk.sdk.NodeContext ctx, String label, Path cli,
                           String path, Path cwd) throws Exception {
        Path log = Procs.logFile(ctx, label);
        ProcessBuilder pb = new ProcessBuilder(cli.toString(), "--version");
        pb.directory(cwd.toFile());
        pb.environment().remove("SKILL_MANAGER_CLI");
        pb.environment().put("PATH", path);
        pb.redirectErrorStream(true).redirectOutput(log.toFile());
        Process proc = pb.start();
        boolean finished = proc.waitFor(BOUND_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            proc.descendants().forEach(ProcessHandle::destroyForcibly);
            proc.destroyForcibly();
            proc.waitFor();
            return new Run(-1, HomeCloneSupport.read(log), true);
        }
        return new Run(proc.exitValue(), HomeCloneSupport.read(log), false);
    }

    /**
     * A PATH directory holding exactly the two external programs the shim needs
     * ({@code dirname} and {@code tr}) and nothing else.
     *
     * <p>Without it, "no CLI is reachable" would be a claim about the machine
     * rather than about the case: emptying PATH makes the shim fail on a
     * missing {@code dirname} — a non-zero exit that looks like a refusal and
     * is not one — while leaving {@code /usr/bin} on it would let the refusal
     * case find a real skill-manager on some hosts.
     */
    private static Path hermeticTools(Path sandbox) throws Exception {
        Path tools = Files.createDirectories(sandbox.resolve("tools"));
        for (String tool : List.of("bash", "dirname", "tr")) {
            Path found = which(tool);
            if (found != null) {
                Path link = tools.resolve(tool);
                if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createSymbolicLink(link, found);
                }
            }
        }
        return tools;
    }

    private static Path which(String tool) {
        for (String dir : List.of("/usr/bin", "/bin", "/usr/local/bin")) {
            Path candidate = Path.of(dir, tool);
            if (Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    /**
     * A directory holding a working {@code skill-manager} that is NOT the shim.
     *
     * <p>A stub rather than the real CLI on purpose: the claim is "the shim
     * exec'd what it found and handed the arguments over", and a stub can prove
     * that from its own output in a way a real {@code --version} banner cannot
     * be distinguished from anything else that might print one.
     */
    private static Path stubCli(Path sandbox) throws Exception {
        Path dir = Files.createDirectories(sandbox.resolve("stub-bin"));
        Path stub = dir.resolve("skill-manager");
        Files.writeString(stub, """
                #!/bin/sh
                echo "%s"
                echo "args=$*"
                exit 0
                """.formatted(STUB_SENTINEL));
        stub.toFile().setExecutable(true, false);
        return dir;
    }
}
