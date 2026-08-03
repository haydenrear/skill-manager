///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TicketLifecycleSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>Step 2 — the first launch.</b> The change-awareness gate's
 * operator-visible contract, and the shim resolution path underneath it.
 *
 * <h2>What is deliberately NOT asserted</h2>
 *
 * <p>That the first launch is refused. {@code new-change.sh}'s closing banner
 * says "if that first launch is REFUSED with exit 8", and the issue behind this
 * graph repeats it — but a worktree cloned from a home whose baseline is
 * current comes up <b>clean</b>, and this fixture's does. {@code home clone}
 * re-baselines against the copy's own content, and a clone is not drifted.
 * Measured here: {@code home drift} answers {@code {"pending":false}} on a
 * fresh worktree home.
 *
 * <p>Encoding "always exit 8" would be encoding the wrong universality — which
 * is how the last fixture rotted (#135). So the assertion is the contract that
 * holds in BOTH states, and rather than wait for the closed state to arise the
 * node MAKES one: it edits a unit in worktree A's home, records the drift, and
 * exercises the refusing branch against a gate that is genuinely shut. Both
 * branches run every time.
 *
 * <h2>Where the gate actually is</h2>
 *
 * <p>Not in {@code home drift}: that command reports and exits 0 whether or not
 * a change is pending, which is right for a query. The refusal with exit 8 is
 * {@code skill-manager exec} — the thing every launch shim ends in — so that is
 * what this node runs. Asserting the exit code of {@code home drift} would have
 * been asserting nothing while looking like the gate was covered.
 *
 * <h2>The remedy is checked twice, at two strengths</h2>
 *
 * <p>Epic #2's third P0 was a refusal whose printed remedy was not a command;
 * an operator who copy-pasted it got exit 2. So:
 *
 * <ol>
 *   <li><b>It resolves.</b> The first token of the remedy names something that
 *       exists and is executable, resolved absolutely or through {@code PATH}
 *       the way an operator's shell would.</li>
 *   <li><b>It works.</b> The remedy is EXECUTED — through the pin
 *       {@code new-change.sh}'s banner tells the operator to use — and the gate
 *       must then open. A gate that refuses and names a command that does not
 *       clear it is worse than no gate, because the operator's next move is to
 *       launch anyway.</li>
 * </ol>
 *
 * <p>The two are separate assertions on purpose, and a third now joins them:
 * the remedy must name a <em>resolved</em> CLI. This started as the metric
 * {@code remedyIsAbsolutelyResolved}, pinned at 0, recording that
 * {@code HomeCloseOut} named its CLI absolutely (through
 * {@code HomeDescriptor.resolveCli}) while the drift refusal printed a bare
 * {@code skill-manager} — on a machine whose PATH carries an older release,
 * the difference between a remedy and a no-op, because 0.19.2 answers an
 * unknown subcommand with top-level usage and exit 0 (#61).
 *
 * <p>#142 closed that gap: both paths go through
 * {@code HomeDescriptor.cliInvocation}. "Resolves and is executable" and
 * "names the build that understands THIS home" are different claims — PATH can
 * satisfy the first with a stale binary — so the stronger one is asserted
 * rather than merely measured, and the metric stays as the number it moved.
 *
 * <h2>The shims resolve, without launching a model</h2>
 *
 * <p>{@code exec --print-env} is machinery: it computes the launch environment
 * and the PATH precedence that binds an agent to this home. Running it proves
 * the pin resolves and the descriptor is readable. Running {@code claude} would
 * prove nothing about skill-manager and would need a model.
 */
public class TicketLifecycleFirstLaunch {

    static final NodeSpec SPEC = NodeSpec.of("ticket.lifecycle.first.launch")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("ticket.lifecycle.provisioned")
            .tags("ticket-lifecycle", "drift", "launch")
            .timeout("900s");

    /** The exit code a launch refused by the change-awareness gate uses. */
    private static final int DRIFT_REFUSED = 8;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String homeARaw = ctx.get("ticket.lifecycle.provisioned", "homeA").orElse(null);
            String worktreeARaw = ctx.get("ticket.lifecycle.provisioned", "worktreeA").orElse(null);
            String ambient = ctx.get("ticket.lifecycle.fixture.built", "ambientHome").orElse(null);
            if (homeARaw == null || worktreeARaw == null || ambient == null) {
                return NodeResult.fail("ticket.lifecycle.first.launch", "missing upstream context");
            }
            Path homeA = Path.of(homeARaw);
            Path pin = homeA.resolve("bin").resolve("cli").resolve("skill-manager");

            // --- the state a fresh clone is actually in ----------------------
            ProcessRecord freshDrift = TicketLifecycleSupport.plain(ctx, "drift-fresh", null,
                    ambient, List.of(pin.toString(), "home", "drift", "--home", homeARaw, "--json"));
            Map<String, Object> freshReport =
                    TicketLifecycleSupport.jsonOf(Procs.logFile(ctx, "drift-fresh"));
            boolean theGateGivesAMachineReadableAnswer =
                    freshDrift.exitCode() == 0 && freshReport.containsKey("pending");
            boolean aFreshCloneIsNotDrifted = !HomeSyncSupport.flag(freshReport, "pending");
            // ... and therefore its first launch is NOT refused. This is the
            // half the banner gets wrong, so it is asserted rather than assumed.
            ProcessRecord freshLaunch = TicketLifecycleSupport.plain(ctx, "launch-fresh", null,
                    ambient, List.of(pin.toString(), "exec", "--home", homeARaw,
                            "--home-root", worktreeARaw, "--", "true"));
            boolean aCleanHomeLaunches = freshLaunch.exitCode() == 0;

            // --- now close the gate on purpose -------------------------------
            Path edited = TicketLifecycleSupport
                    .unitDir(homeA, TicketLifecycleSupport.SHARED).resolve("references/page.md");
            HomeSyncSupport.append(edited, "a change made under the agent's feet\n");
            ProcessRecord record = TicketLifecycleSupport.plain(ctx, "drift-record", null, ambient,
                    List.of(pin.toString(), "home", "drift", "--home", homeARaw, "--record",
                            "--json"));
            Map<String, Object> pendingReport =
                    TicketLifecycleSupport.jsonOf(Procs.logFile(ctx, "drift-record"));
            // `--record` answers with the SAME exit code the launch gate uses
            // (8) when it finds a change, and 0 when it does not — so a script
            // can branch on the record pass itself rather than re-querying.
            // Measured, not assumed: the first version of this node asserted 0
            // and failed on a home that had correctly detected the change.
            boolean theChangeIsRecordedAsPending = record.exitCode() == DRIFT_REFUSED
                    && HomeSyncSupport.flag(pendingReport, "pending");

            ProcessRecord refusedLaunch = TicketLifecycleSupport.plain(ctx, "launch-refused", null,
                    ambient, List.of(pin.toString(), "exec", "--home", homeARaw,
                            "--home-root", worktreeARaw, "--", "true"));
            String refusalText = HomeSyncSupport.log(ctx, "launch-refused");
            boolean theLaunchIsRefused = refusedLaunch.exitCode() == DRIFT_REFUSED;
            // It has to NAME what moved. A gate that says "something changed"
            // leaves the agent to guess which skill it is now acting on.
            boolean theRefusalNamesTheUnitThatMoved =
                    refusalText.contains("skill:" + TicketLifecycleSupport.SHARED);

            // --- the remedy: does it resolve, and does it work? --------------
            List<String> remedies = remediesIn(refusalText);
            List<String> unresolvable = new ArrayList<>();
            boolean everyRemedyIsAbsolute = !remedies.isEmpty();
            for (String remedy : remedies) {
                String head = remedy.strip().split("\\s+")[0];
                if (!head.startsWith("/")) everyRemedyIsAbsolute = false;
                if (resolve(head) == null) unresolvable.add(remedy);
            }
            boolean everyRemedyNamesAnExistingExecutable =
                    !remedies.isEmpty() && unresolvable.isEmpty();
            boolean theRemedyNamesTheAckSubcommand = remedies.stream()
                    .anyMatch(r -> r.contains("home drift") && r.contains("--ack"));

            // Executed, not paraphrased — through the pin the provisioning
            // banner names, which is the spelling an operator in a fresh
            // worktree is told to use.
            ProcessRecord ack = TicketLifecycleSupport.plain(ctx, "drift-ack", null, ambient,
                    List.of(pin.toString(), "home", "drift", "--home", homeARaw, "--ack", "--json"));
            ProcessRecord launchAfterAck = TicketLifecycleSupport.plain(ctx, "launch-after-ack",
                    null, ambient, List.of(pin.toString(), "exec", "--home", homeARaw,
                            "--home-root", worktreeARaw, "--", "true"));
            boolean runningTheRemedyOpensTheGate =
                    ack.exitCode() == 0 && launchAfterAck.exitCode() == 0;

            // --- the launch environment resolves to THIS home ----------------
            ProcessRecord printEnv = TicketLifecycleSupport.plain(ctx, "exec-print-env", null,
                    ambient, List.of(pin.toString(), "exec", "--home", homeARaw,
                            "--home-root", worktreeARaw, "--print-env"));
            String envText = HomeSyncSupport.log(ctx, "exec-print-env");
            boolean theLaunchEnvironmentResolvesToThisHome = printEnv.exitCode() == 0
                    && envText.contains("SKILL_MANAGER_HOME=" + homeARaw)
                    && envText.contains("CLAUDE_CONFIG_DIR=" + worktreeARaw);
            // The home's own bin/cli comes FIRST on the launch PATH: that is
            // what makes a bare `skill-manager` inside an agent session mean
            // this home's build rather than whatever the operator installed.
            boolean thisHomesBinIsFirstOnTheLaunchPath = envText.lines()
                    .filter(l -> l.startsWith("PATH="))
                    .anyMatch(l -> l.substring("PATH=".length())
                            .startsWith(homeA.resolve("bin").resolve("cli").toString() + ":"));
            boolean everyShimIsExecutable = true;
            for (String agent : List.of("claude", "codex", "gemini")) {
                if (!Files.isExecutable(homeA.resolve("bin").resolve("launch").resolve(agent))) {
                    everyShimIsExecutable = false;
                }
            }

            boolean pass = theGateGivesAMachineReadableAnswer && aFreshCloneIsNotDrifted
                    && aCleanHomeLaunches && theChangeIsRecordedAsPending && theLaunchIsRefused
                    && theRefusalNamesTheUnitThatMoved && everyRemedyNamesAnExistingExecutable
                    && theRemedyNamesTheAckSubcommand && runningTheRemedyOpensTheGate
                    && theLaunchEnvironmentResolvesToThisHome
                    && thisHomesBinIsFirstOnTheLaunchPath && everyShimIsExecutable;

            return (pass
                    ? NodeResult.pass("ticket.lifecycle.first.launch")
                    : NodeResult.fail("ticket.lifecycle.first.launch",
                            "freshDrift=" + freshDrift.exitCode()
                                    + " freshLaunch=" + freshLaunch.exitCode()
                                    + " recorded=" + theChangeIsRecordedAsPending
                                    + " refusedExit=" + refusedLaunch.exitCode()
                                    + " namesUnit=" + theRefusalNamesTheUnitThatMoved
                                    + " remedies=" + remedies + " unresolvable=" + unresolvable
                                    + " ack=" + ack.exitCode()
                                    + " launchAfterAck=" + launchAfterAck.exitCode()
                                    + " printEnv=" + printEnv.exitCode()
                                    + " pathFirst=" + thisHomesBinIsFirstOnTheLaunchPath))
                    .process(freshDrift).process(freshLaunch).process(record).process(refusedLaunch)
                    .process(ack).process(launchAfterAck).process(printEnv)
                    .assertion("the_drift_query_gives_a_machine_readable_answer",
                            theGateGivesAMachineReadableAnswer)
                    .assertion("a_freshly_cloned_worktree_home_is_not_reported_as_drifted",
                            aFreshCloneIsNotDrifted)
                    .assertion("a_clean_worktree_homes_first_launch_is_not_refused",
                            aCleanHomeLaunches)
                    .assertion("recording_a_change_reports_it_pending_and_exits_like_the_gate",
                            theChangeIsRecordedAsPending)
                    .assertion("a_launch_against_an_unread_change_is_refused_with_exit_8",
                            theLaunchIsRefused)
                    .assertion("the_refusal_names_the_unit_that_moved",
                            theRefusalNamesTheUnitThatMoved)
                    .assertion("every_remedy_the_refusal_prints_names_an_existing_executable",
                            everyRemedyNamesAnExistingExecutable)
                    .assertion("the_refusal_names_the_subcommand_that_clears_it",
                            theRemedyNamesTheAckSubcommand)
                    .assertion("running_the_remedy_opens_the_gate", runningTheRemedyOpensTheGate)
                    .assertion("the_launch_environment_resolves_to_this_worktrees_own_home",
                            theLaunchEnvironmentResolvesToThisHome)
                    .assertion("this_homes_own_cli_is_first_on_the_launch_path",
                            thisHomesBinIsFirstOnTheLaunchPath)
                    .assertion("all_three_launch_shims_are_executable", everyShimIsExecutable)
                    // Was a metric pinned at 0 while the drift refusal printed a
                    // bare `skill-manager` and only HomeCloseOut resolved its
                    // own. #142 closed that: both now go through
                    // HomeDescriptor.cliInvocation, so this is a contract every
                    // refusal owes rather than a difference between two of them,
                    // and it is asserted. `everyRemedyIsAbsolute` is false on an
                    // empty remedy list, so a refusal that stops printing
                    // remedies fails here instead of passing vacuously.
                    .assertion("every_remedy_names_a_resolved_cli_not_a_bare_skill_manager",
                            everyRemedyIsAbsolute)
                    .metric("remediesChecked", remedies.size())
                    .metric("remedyIsAbsolutelyResolved", everyRemedyIsAbsolute ? 1 : 0)
                    .metric("freshDriftExit", freshDrift.exitCode())
                    .metric("refusedLaunchExit", refusedLaunch.exitCode());
        });
    }

    /**
     * Every command the refusal offered.
     *
     * <p>Read from the printed TEXT rather than from a structured field,
     * because epic #2's remedy defect was in the human output while the
     * {@code --json} consumer had already been fixed — a check that read only
     * the structured field would have reported that defect as absent.
     */
    private static List<String> remediesIn(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            int open = line.indexOf('`');
            while (open >= 0) {
                int close = line.indexOf('`', open + 1);
                if (close < 0) break;
                String candidate = line.substring(open + 1, close).strip();
                if (candidate.contains("skill-manager ") && !out.contains(candidate)) {
                    out.add(candidate);
                }
                open = line.indexOf('`', close + 1);
            }
        }
        return out;
    }

    /**
     * The file a shell would run for {@code head}: the path itself when
     * absolute, otherwise the first {@code PATH} entry holding it.
     */
    private static Path resolve(String head) {
        if (head.contains("/")) {
            Path direct = Path.of(head);
            return Files.isExecutable(direct) ? direct : null;
        }
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String entry : path.split(":")) {
            if (entry.isBlank()) continue;
            Path candidate = Path.of(entry).resolve(head);
            if (Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }
}
