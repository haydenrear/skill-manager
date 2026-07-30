///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeCloneSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The kernel boundary, driven through the real {@code skill-manager exec} with
 * the shipped profile and the shipped parameters.
 *
 * <h2>Isolation stops being a convention here</h2>
 *
 * <p>Everything else in these graphs asserts that skill-manager <em>wrote where
 * it meant to</em>. That is a claim about code, and #18, #30 and #47 were each
 * what happens when a claim about code is where the checking stops: a process
 * wrote into the operator's home because <b>nothing stopped it</b>, and each fix
 * was a convention that regresses the moment someone forgets a variable.
 *
 * <p>This node asserts the other thing: that a launch through a home which opted
 * in <b>cannot</b> write outside its worktree, whatever the code does.
 *
 * <h2>Asserted on BYTES, never on status</h2>
 *
 * <p>A sandbox denial arrives at a shell as a message on stderr, and
 * {@code sh -c 'echo x > denied'} then exits <b>0</b>. Measured, twice, during
 * this ticket's research — once by an agent that had been warned about it in the
 * same brief. So every enforcement assertion here is the presence or absence of
 * a FILE and the content of pre-existing bytes.
 *
 * <h2>Three controls, because a boundary is the easiest thing to fake</h2>
 *
 * <ol>
 *   <li><b>The permitted write.</b> The agent must still be able to edit the
 *       source tree it was given the ticket for. Without this half, a profile
 *       that denied everything — or a launch that never ran — would pass.</li>
 *   <li><b>The unsandboxed control.</b> A second home that did <em>not</em> opt
 *       in writes into its own decoy freely. Without it, "no file appeared"
 *       could be a permissions accident, a wrong path, or a command that never
 *       ran, and the node would report enforcement it never had.</li>
 *   <li><b>The widened profile.</b> One broad allow appended to
 *       {@code launch.sb} must stop the launch, not silently permit the write —
 *       SBPL is last-match-wins and the researcher's own first draft ended in
 *       exactly such a line while reporting success.</li>
 * </ol>
 *
 * <h2>Why the decoys are under {@code build/} and not in a temp directory</h2>
 *
 * <p>The shipped profile grants writes under {@code $TMPDIR}, {@code /tmp} and
 * {@code /var/tmp} — a measured necessity, since {@code /bin/bash} 3.2 writes
 * here-document bodies to {@code /tmp} regardless of {@code TMPDIR}. A decoy
 * built with {@code createTempDirectory} is therefore <em>writable</em>, the
 * write succeeds, and the assertion passes for the wrong reason. This was hit
 * while building this node. Under {@code build/} the decoys are outside every
 * allowed subtree, so the shipped parameterization is what is being measured.
 */
public class HomeCloneSeatbeltConfinesTheWorktree {

    static final NodeSpec SPEC = NodeSpec.of("home.clone.seatbelt.confines.the.worktree")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("home-clone", "sandbox", "oracle")
            .timeout("300s");

    /** The four shapes the operator's real homes take, as sibling directories. */
    private static final List<String> HOME_SHAPES =
            List.of(".skill-manager", ".claude", ".codex", ".gemini");

    /** exec's exit code for "you asked to be sandboxed and I could not do it". */
    private static final int SEATBELT_REFUSED = 11;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            if (!Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) {
                System.out.println("SKIPPED: /usr/bin/sandbox-exec is not executable here, so "
                        + "there is no kernel boundary to measure. Deprecated since 2017 with no "
                        + "sanctioned successor; the opt-in design means its absence costs "
                        + "enforcement and keeps behaviour.");
                return NodeResult.pass(SPEC.id())
                        .assertion("the_boundary_was_measured_OR_the_skip_states_its_reason", true)
                        .metric("skipped", 1);
            }

            Path base = Path.of(System.getProperty("user.dir"))
                    .resolve("build/seatbelt-" + ctx.runId()).toAbsolutePath().normalize();
            Files.createDirectories(base);

            // ---------------------------------------------- the confined home
            Path worktree = base.resolve("worktree");
            Path store = worktree.resolve(".skill-manager");
            Files.createDirectories(store);
            Path decoy = operatorDecoy(base.resolve("operator-decoy"));

            ProcessRecord shims = HomeCloneSupport.sm(ctx, "seatbelt.shims", store.toString(),
                    "home", "shims", "--home", store.toString(), "--init", "--sandbox");
            boolean theHomeOptedIn = shims.exitCode() == 0
                    && Files.isRegularFile(store.resolve("launch.sb"));

            ProcessRecord printed = HomeCloneSupport.sm(ctx, "seatbelt.printenv", store.toString(),
                    "exec", "--home", store.toString(), "--no-reconcile", "--ack-drift",
                    "--print-env");
            String printedEnv = HomeCloneSupport.log(ctx, "seatbelt.printenv");
            boolean theLaunchReportsItselfConfined =
                    printed.exitCode() == 0 && printedEnv.contains("SKILL_MANAGER_SEATBELT=");

            Path permitted = worktree.resolve("edited-by-the-agent.txt");
            ProcessRecord confined = HomeCloneSupport.sm(ctx, "seatbelt.confined", store.toString(),
                    "exec", "--home", store.toString(), "--no-reconcile", "--ack-drift",
                    "--", "/bin/sh", "-c", script(permitted, decoy));

            boolean aWriteInsideTheWorktreeLands =
                    "WORKTREE".equals(read(permitted).strip());
            List<String> escaped = new ArrayList<>();
            List<String> overwritten = new ArrayList<>();
            for (String shape : HOME_SHAPES) {
                if (Files.exists(decoy.resolve(shape).resolve("new.txt"),
                        LinkOption.NOFOLLOW_LINKS)) {
                    escaped.add(shape);
                }
                if (!"ORIGINAL".equals(read(decoy.resolve(shape).resolve("settings.json")).strip())) {
                    overwritten.add(shape);
                }
            }
            boolean noWriteReachedAnyOperatorHomeShape = escaped.isEmpty();
            boolean everyPreExistingByteIsIntact = overwritten.isEmpty();

            // ------------------------------------------ the unsandboxed control
            //
            // The same script, the same shapes, a home that did NOT opt in. It
            // must succeed. Without this the four assertions above could be
            // reporting a typo.
            Path plainWorktree = base.resolve("worktree-control");
            Path plainStore = plainWorktree.resolve(".skill-manager");
            Files.createDirectories(plainStore);
            Path controlDecoy = operatorDecoy(base.resolve("operator-decoy-control"));
            HomeCloneSupport.sm(ctx, "seatbelt.control.shims", plainStore.toString(),
                    "home", "shims", "--home", plainStore.toString(), "--init");
            Path controlPermitted = plainWorktree.resolve("edited-by-the-agent.txt");
            HomeCloneSupport.sm(ctx, "seatbelt.control", plainStore.toString(),
                    "exec", "--home", plainStore.toString(), "--no-reconcile", "--ack-drift",
                    "--", "/bin/sh", "-c", script(controlPermitted, controlDecoy));
            int controlEscapes = 0;
            for (String shape : HOME_SHAPES) {
                if (Files.exists(controlDecoy.resolve(shape).resolve("new.txt"),
                        LinkOption.NOFOLLOW_LINKS)) {
                    controlEscapes++;
                }
            }
            boolean anUnconfinedLaunchWritesTheDecoyFreely =
                    controlEscapes == HOME_SHAPES.size();

            // -------------------------------------------- the widened profile
            Files.writeString(store.resolve("launch.sb"),
                    Files.readString(store.resolve("launch.sb"))
                            + "\n(allow file-write* (subpath \"/\"))\n");
            Path afterWidening = worktree.resolve("ran-under-a-widened-profile.txt");
            ProcessRecord widened = HomeCloneSupport.sm(ctx, "seatbelt.widened", store.toString(),
                    "exec", "--home", store.toString(), "--no-reconcile", "--ack-drift",
                    "--", "/bin/sh", "-c", "echo RAN > '" + afterWidening + "'");
            boolean aWidenedProfileRefusesTheLaunch =
                    widened.exitCode() == SEATBELT_REFUSED
                            && !Files.exists(afterWidening, LinkOption.NOFOLLOW_LINKS);

            boolean pass = theHomeOptedIn && theLaunchReportsItselfConfined
                    && aWriteInsideTheWorktreeLands && noWriteReachedAnyOperatorHomeShape
                    && everyPreExistingByteIsIntact && anUnconfinedLaunchWritesTheDecoyFreely
                    && aWidenedProfileRefusesTheLaunch;
            String detail = "shims=" + shims.exitCode() + " confined=" + confined.exitCode()
                    + " widened=" + widened.exitCode() + " escaped=" + escaped
                    + " overwritten=" + overwritten + " controlEscapes=" + controlEscapes;
            return (pass ? NodeResult.pass(SPEC.id()) : NodeResult.fail(SPEC.id(), detail))
                    .assertion("home_shims_sandbox_emits_both_halves_of_the_opt_in", theHomeOptedIn)
                    .assertion("the_launch_reports_itself_confined",
                            theLaunchReportsItselfConfined)
                    .assertion("a_write_inside_the_worktree_lands", aWriteInsideTheWorktreeLands)
                    .assertion("no_write_reaches_any_operator_home_shape_outside_the_worktree",
                            noWriteReachedAnyOperatorHomeShape)
                    .assertion("every_pre_existing_byte_in_those_shapes_is_intact",
                            everyPreExistingByteIsIntact)
                    .assertion("an_unconfined_launch_writes_the_same_decoy_freely",
                            anUnconfinedLaunchWritesTheDecoyFreely)
                    .assertion("a_widened_profile_refuses_the_launch_instead_of_permitting_it",
                            aWidenedProfileRefusesTheLaunch)
                    .metric("operatorHomeShapesEscaped", escaped.size())
                    .metric("controlEscapes", controlEscapes)
                    .process(shims)
                    .process(confined)
                    .process(widened)
                    .log(detail);
        });
    }

    /**
     * One shell invocation that writes inside the worktree and then tries every
     * operator-home shape. It ends in {@code exit 0} on purpose: the node must
     * not be able to pass by reading the shell's status, and making the status
     * useless is the cheapest way to guarantee nobody starts.
     */
    private static String script(Path permitted, Path decoy) {
        StringBuilder script = new StringBuilder("echo WORKTREE > '" + permitted + "'\n");
        for (String shape : HOME_SHAPES) {
            Path dir = decoy.resolve(shape);
            script.append("echo LEAK > '").append(dir.resolve("new.txt")).append("'\n");
            script.append("echo LEAK > '").append(dir.resolve("settings.json")).append("'\n");
        }
        return script.append("exit 0\n").toString();
    }

    /** A directory shaped like the operator's real home: the four agent roots. */
    private static Path operatorDecoy(Path root) throws IOException {
        for (String shape : HOME_SHAPES) {
            Path dir = root.resolve(shape);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("settings.json"), "ORIGINAL");
        }
        return root;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException absent) {
            return "";
        }
    }
}
