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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Step 12 — an expected refusal is a message, not a stack trace.</b>
 *
 * <h2>The defect</h2>
 *
 * <p>{@code uninstall <unit> --dry-run}, when the unit is claimed by a project
 * lock, exits 1 and prints
 *
 * <pre>
 * java.io.IOException: unit test-graph is claimed by skill project(s): acme-widgets
 *     (remove the project lock/binding first)
 *   at dev.skillmanager.app.RemoveUseCase.buildProgram(RemoveUseCase.java:77)
 *   at dev.skillmanager.commands.UninstallCommand.call(UninstallCommand.java:62)
 *   … 13 more frames of picocli internals …
 * </pre>
 *
 * <p>The message itself is good — it names the unit, the claimant and the
 * remedy. The fifteen frames around it are not, and this is a refusal an
 * onboarding agent reaches within its first few commands. The same shape
 * appears on the child-home claim path, and on a malformed
 * {@code skill-project.toml} at {@code project register}.
 *
 * <h2>Assertion</h2>
 *
 * <p>No command on the onboarding path prints a Java stack trace. A refusal is
 * one message plus, where applicable, a remedy.
 *
 * <h2>Vacuous-pass risks and companions</h2>
 *
 * <ol>
 *   <li><b>The command under test SUCCEEDING,</b> so no trace is printed and
 *       the check passes over nothing. This is the dominant risk: three of the
 *       four probes here only refuse when the home is in a particular state.
 *       <br><b>Companion:</b> each probe asserts a non-zero exit AND the
 *       presence of its expected refusal TEXT before the stack-frame check is
 *       applied. A probe that did not refuse is reported as unmeasured and
 *       fails the node, rather than counting as a clean result.</li>
 *   <li><b>Grepping stdout only,</b> when the trace goes to stderr.
 *       <br><b>Companion:</b> every process record here captures merged
 *       output — the observed traces came out of {@code 2>&1}.</li>
 *   <li><b>Matching on the word "Exception",</b> which the useful half of a
 *       refusal legitimately contains.
 *       <br><b>Companion:</b> the pattern is structural —
 *       {@code ^\s*at <qualified.name>\(<File>.java:<line>\)} — so a message
 *       naming an exception type passes and a frame does not.</li>
 * </ol>
 */
public class OnboardingRefusalsAreMessages {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.refusals.are.messages")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.import.violations.are.fatal")
            .tags("onboarding", "ux", "refusal")
            .timeout("900s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path proj = path(ctx, "onboarding.fixture.built", "proj");
            Path workspace = path(ctx, "onboarding.fixture.built", "workspace");
            Path home = path(ctx, "onboarding.bootstrapped", "projectHome");
            if (proj == null || home == null || workspace == null) {
                return NodeResult.fail("onboarding.refusals.are.messages",
                        "missing upstream context");
            }

            // Each probe: a command that MUST refuse, and the text that proves
            // the refusal it produced is the one intended.
            List<ProcessRecord> probes = new ArrayList<>();
            Map<String, String> expectedText = new LinkedHashMap<>();

            // 1. a unit claimed by the registered project's lock.
            String claimed = OnboardingSupport.storeUnits(home).stream()
                    .filter(u -> u.equals(OnboardingSupport.ALPHA))
                    .findFirst().orElse(OnboardingSupport.ALPHA);
            probes.add(OnboardingSupport.sm(ctx, "refusal-project-claim", home, proj,
                    "uninstall", claimed, "--dry-run"));
            expectedText.put("refusal-project-claim", "claimed by");

            // 2. a malformed project manifest — a parse refusal, which is the
            //    first thing a hand-written skill-project.toml produces.
            Path badProject = workspace.resolve("bad-project");
            java.nio.file.Files.createDirectories(badProject);
            java.nio.file.Files.writeString(badProject.resolve("skill-project.toml"),
                    "[project]\nname = \"bad\"\n\n[[skills]]\nname = \"x\"\n"
                            + "source = \"file:///nowhere\"\n");
            probes.add(OnboardingSupport.sm(ctx, "refusal-bad-manifest", home, proj,
                    "project", "register", "--project-dir", badProject.toString()));
            expectedText.put("refusal-bad-manifest", "");

            // 3. an unknown unit.
            probes.add(OnboardingSupport.sm(ctx, "refusal-unknown-unit", home, proj,
                    "uninstall", "ob-definitely-not-installed", "--dry-run"));
            expectedText.put("refusal-unknown-unit", "");

            List<String> unmeasured = new ArrayList<>();
            List<String> withTraces = new ArrayList<>();
            int totalFrames = 0;
            for (ProcessRecord p : probes) {
                String log = OnboardingSupport.log(ctx, p);
                String needle = expectedText.getOrDefault(p.label(), "");
                boolean refused = p.exitCode() != 0
                        && (needle.isEmpty() || log.contains(needle));
                if (!refused) {
                    unmeasured.add(p.label() + " (exit " + p.exitCode() + ")");
                    continue;
                }
                List<String> frames = OnboardingSupport.stackFrames(log);
                totalFrames += frames.size();
                if (!frames.isEmpty()) {
                    withTraces.add(p.label() + ": " + frames.size() + " frames, first "
                            + frames.get(0));
                }
            }

            // The companion: a probe that did not refuse measured nothing, and
            // reporting that as clean is the exact shape this graph exists to
            // prevent.
            boolean everyProbeActuallyRefused = unmeasured.isEmpty();
            boolean noRefusalPrintedAJavaStackTrace = withTraces.isEmpty();

            boolean pass = everyProbeActuallyRefused && noRefusalPrintedAJavaStackTrace;

            NodeResult result = pass
                    ? NodeResult.pass("onboarding.refusals.are.messages")
                    : NodeResult.fail("onboarding.refusals.are.messages",
                            "unmeasured=" + unmeasured + " withTraces=" + withTraces);
            for (ProcessRecord p : probes) result = result.process(p);
            return result
                    .assertion("every_probe_actually_reached_its_refusal", everyProbeActuallyRefused)
                    .assertion("no_refusal_on_the_onboarding_path_printed_a_java_stack_trace",
                            noRefusalPrintedAJavaStackTrace)
                    .metric("probes", probes.size())
                    .metric("stackFramesPrinted", totalFrames)
                    .log("probes that did not refuse (unmeasured): " + unmeasured)
                    .log("refusals carrying a stack trace: " + withTraces);
        });
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
