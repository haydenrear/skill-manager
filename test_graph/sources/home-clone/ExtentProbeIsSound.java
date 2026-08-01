///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/StorageSharing.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two soundness gates inside the extent probe, driven from tables rather
 * than from a filesystem that happens to produce the hazard.
 *
 * <h2>Why this is a node and not a comment</h2>
 *
 * <p>Issue #131. {@code extentprobe.py} answers "do these two files share
 * their backing blocks", and the failure that would make it worthless is a
 * <b>false positive</b>: reporting sharing that is not there turns every
 * caller's cost claim into a tautology. Two ways it could produce one were
 * found and are now refused:
 *
 * <ul>
 *   <li><b>{@code fe_physical} is not always a device address.</b> For an
 *       extent flagged {@code UNKNOWN}, {@code DELALLOC}, {@code ENCODED},
 *       {@code DATA_INLINE} or {@code DATA_TAIL} the field means something
 *       else. {@code DATA_TAIL} is the sharp one: a tail-packed file's data
 *       shares a block <em>with other files' tails</em>, so two unrelated
 *       files report one address while sharing nothing.</li>
 *   <li><b>{@code st_dev} is not always a backing store.</b> Overlayfs reports
 *       one synthetic {@code st_dev} for every file it serves while FIEMAP
 *       answers from the real backing device — measured, a lower-layer and an
 *       upper-layer file both reported {@code st_dev=62}. Where the layers are
 *       on different physical devices, two unrelated files can collide on
 *       {@code dev:offset} and on {@code dev:inode} alike.</li>
 * </ul>
 *
 * <p>Neither hazard is produced by a healthy host, which is exactly why they
 * need their own node: a gate that only runs when the hazard is present is a
 * gate nobody ever watches run, and it can be deleted without any graph going
 * red. Both gates are pure functions in the probe, and
 * {@code extentprobe.py --self-test} drives them over a table of flag words
 * and synthetic {@code mountinfo} text.
 *
 * <h2>Anti-vacuity</h2>
 *
 * <p>A self-test that ran zero cases would exit 0. This node therefore asserts
 * the case COUNT as well as the verdicts, and asserts that the named cases it
 * cares about are present by name — a gate deleted along with its case would
 * otherwise leave a shorter, still-green table.
 *
 * <p>Deterministic and offline: no uv, no network, no store. It is the guard
 * that keeps meaning when
 * {@code shared.store.materialization.costs.far.less.than.a.copy} skips.
 */
public class ExtentProbeIsSound {

    static final NodeSpec SPEC = NodeSpec.of("extent.probe.is.sound")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("home-clone", "cost", "oracle", "soundness")
            .timeout("120s");

    /**
     * Cases that must exist by name. Each is one refusal that, if it stopped
     * refusing, would let the probe report sharing that is not there.
     */
    private static final List<String> REQUIRED = List.of(
            "an_unknown_extent_is_refused",
            "a_delayed_allocation_extent_is_refused",
            "an_encoded_extent_is_refused",
            "an_inline_data_extent_is_refused",
            "a_tail_packed_extent_is_refused",
            "an_overlay_layered_across_two_devices_is_refused",
            "an_overlay_layer_that_cannot_be_stat_ed_is_refused",
            // …and the same gates read through the code that has to consult
            // them. A correct gate nothing calls decides about nothing, which
            // is the exact shape of issue #135 in the other graph.
            "the_fiemap_reader_refuses_an_unaddressable_extent",
            "the_fiemap_reader_refuses_a_tail_packed_extent",
            "an_unverifiable_overlay_refuses_every_measurable_file",
            "and_reports_nothing_as_shared_rather_than_guessing");

    /** …and cases that must PASS while refusing nothing, so the gates are not just "refuse". */
    private static final List<String> MUST_STILL_BE_ACCEPTED = List.of(
            "a_plain_extent_is_addressable",
            "a_shared_or_merged_extent_is_still_addressable",
            "an_overlay_whose_layers_share_a_device_is_sound",
            "a_plain_filesystem_has_no_device_scope_hazard",
            "a_sound_mount_table_measures_the_tree_and_finds_the_hard_link");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String python = StorageSharing.python();
            if (python == null) {
                System.out.println("SKIPPED: no python3 on PATH to run the probe's self-test with");
                return NodeResult.pass(SPEC.id())
                        .assertion("the_probe_self_test_ran_OR_the_skip_states_its_reason", true)
                        .metric("skipped", 1)
                        .log("SKIPPED: no python3 on PATH");
            }
            Path script = StorageSharing.script();
            if (!Files.isReadable(script)) {
                // A missing instrument and a held property must not produce
                // the same envelope. Never a skip.
                return NodeResult.fail(SPEC.id(), "the extent probe is missing from " + script)
                        .assertion("the_extent_probe_is_present", false);
            }

            Run run = run(python, script);
            Map<String, Boolean> cases = parse(run.output());
            System.out.println(run.output().strip());

            boolean theSelfTestExitedZero = run.exit() == 0;
            boolean everyCaseHeld = !cases.isEmpty()
                    && cases.values().stream().allMatch(Boolean::booleanValue);
            List<String> failed = new ArrayList<>();
            for (Map.Entry<String, Boolean> entry : cases.entrySet()) {
                if (!entry.getValue()) failed.add(entry.getKey());
            }

            List<String> missing = new ArrayList<>();
            for (String required : REQUIRED) if (!cases.containsKey(required)) missing.add(required);
            for (String required : MUST_STILL_BE_ACCEPTED) {
                if (!cases.containsKey(required)) missing.add(required);
            }
            boolean everyRefusalIsStillTested = missing.isEmpty();

            // The self-test's own count, read from its trailer, must agree with
            // the number of case lines: a truncated run must not read as a
            // short but complete table.
            int declared = declaredTotal(run.output());
            boolean theTableWasReportedWhole = declared == cases.size() && declared > 0;

            boolean pass = theSelfTestExitedZero && everyCaseHeld
                    && everyRefusalIsStillTested && theTableWasReportedWhole;
            String detail = "exit=" + run.exit() + " cases=" + cases.size()
                    + " declared=" + declared + " failed=" + failed + " missing=" + missing;
            return (pass ? NodeResult.pass(SPEC.id()) : NodeResult.fail(SPEC.id(), detail))
                    .assertion("the_probes_own_soundness_self_test_passes", theSelfTestExitedZero)
                    .assertion("every_soundness_case_the_probe_declares_holds", everyCaseHeld)
                    .assertion("every_false_positive_refusal_is_still_under_test",
                            everyRefusalIsStillTested)
                    .assertion("the_self_test_reported_a_whole_table_rather_than_a_short_one",
                            theTableWasReportedWhole)
                    .metric("selfTestCases", cases.size())
                    .metric("selfTestFailures", failed.size())
                    .log(detail);
        });
    }

    private record Run(int exit, String output) {}

    private static Run run(String python, Path script) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(python, "-W", "ignore",
                script.toString(), "--self-test").redirectErrorStream(true);
        try {
            Process process = pb.start();
            String out = new String(process.getInputStream().readAllBytes());
            return new Run(process.waitFor(), out);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("probe self-test interrupted", interrupted);
        }
    }

    /** {@code selftest <name> ok=<0|1>} lines, in the order the probe printed them. */
    private static Map<String, Boolean> parse(String output) {
        LinkedHashMap<String, Boolean> cases = new LinkedHashMap<>();
        for (String line : output.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 3 && parts[0].equals("selftest") && parts[2].startsWith("ok=")) {
                cases.put(parts[1], parts[2].equals("ok=1"));
            }
        }
        return cases;
    }

    /** The {@code selftest-total N} trailer, or -1 when it never arrived. */
    private static int declaredTotal(String output) {
        for (String line : output.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[0].equals("selftest-total")) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException malformed) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
