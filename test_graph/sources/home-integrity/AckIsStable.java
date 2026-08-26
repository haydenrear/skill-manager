///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeIntegrity.java
//SOURCES HomeIntegritySupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An acknowledged drift record stays acknowledged, and acknowledges the
 * baseline the home is actually on.
 *
 * <h2>This node runs the real command, because the invariant is about it</h2>
 *
 * <p>Unlike the other checks in this graph, {@code AckIsStable} is not a
 * property of a home at rest — it is a property of what
 * {@code home drift --record} and {@code home drift --ack} leave behind. So
 * this node drives the real CLI through the whole cycle on a working copy:
 * record a baseline, change a unit, record again, acknowledge, and read the
 * result. A hand-written {@code home.drift.json} would test the checker and not
 * the gate.
 *
 * <p>What is asserted, and what was deliberately dropped from #124's version,
 * is argued on {@link HomeIntegrity#ackIsStable}. The short form: "an ack must
 * not leave a fresh unacked record" is a demand that the gate fail open, and
 * {@code DriftGate}'s class comment records the spec run that showed why it
 * must not. Re-pending after a content change is the design. What is assertable
 * is that the ack is not <em>stale on arrival</em> — that the baseline it
 * acknowledged is the home's current digest — and that a second no-op pass does
 * not un-acknowledge it.
 *
 * <p>Verified against the operator's root home on 2026-08-16:
 * {@code acknowledged=true} with {@code report.to} and {@code home.digest.json}
 * both {@code df22c759…}. And verified against this fixture by running the
 * cycle: record → modify → record → ack leaves them equal.
 */
public class AckIsStable {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.ack.is.stable")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "drift")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String homeStr = ctx.get("home.integrity.fixture", "integrityHome").orElse(null);
            String scratchStr = ctx.get("home.integrity.fixture", "scratchRoot").orElse(null);
            if (homeStr == null || scratchStr == null) return unproven("missing fixture context");
            Path scratch = Path.of(scratchStr);
            Path home = scratch.resolve("ack-cycle");

            ProcessRecord baseline;
            ProcessRecord reRecord;
            ProcessRecord ack;
            ProcessRecord noop;
            try {
                HomeIntegritySupport.deleteRecursively(home);
                HomeIntegritySupport.copyTree(Path.of(homeStr), home);

                baseline = HomeIntegritySupport.sm(ctx, "drift-baseline", home,
                        "home", "drift", "--record");
                // A content change the gate must notice.
                Path skill = home.resolve("skills").resolve(HomeIntegritySupport.UNIT)
                        .resolve("SKILL.md");
                Files.writeString(skill, Files.readString(skill)
                        + "\nplanted by the home-integrity graph\n");
                reRecord = HomeIntegritySupport.sm(ctx, "drift-record", home,
                        "home", "drift", "--record");
                ack = HomeIntegritySupport.sm(ctx, "drift-ack", home,
                        "home", "drift", "--ack");
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }

            // Recording found a change, so the gate refused: EXIT 8 is the
            // documented refusal, not an error.
            boolean changeWasSeen = reRecord.exitCode() == 8;
            boolean ackSucceeded = ack.exitCode() == 0;

            var drift = HomeIntegrity.readJson(home.resolve("home.drift.json"));
            var digest = HomeIntegrity.readJson(home.resolve("home.digest.json"));
            boolean acknowledged = drift != null && drift.path("acknowledged").asBoolean(false);
            boolean receipted = drift != null
                    && HomeIntegrity.text(drift, "acknowledgedAt") != null;
            String to = drift == null ? null : drift.path("report").path("to").asText(null);
            String current = digest == null ? null : HomeIntegrity.text(digest, "digest");
            boolean notStaleOnArrival = to != null && to.equals(current);

            HomeIntegrity.Report afterAck = HomeIntegrity.ackIsStable(home);

            // A pass that changes nothing must not re-pend.
            noop = HomeIntegritySupport.sm(ctx, "drift-noop", home,
                    "home", "drift", "--record");
            var afterNoop = HomeIntegrity.readJson(home.resolve("home.drift.json"));
            boolean survivesANoop = afterNoop != null
                    && afterNoop.path("acknowledged").asBoolean(false);

            HomeIntegritySupport.Mutation stale;
            try {
                stale = HomeIntegritySupport.probe(home, scratch, "ack_of_a_baseline_left_behind",
                        new DivergeTheBaseline(), HomeIntegrity::ackIsStable);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }

            boolean pass = baseline.exitCode() == 0 && changeWasSeen && ackSucceeded
                    && acknowledged && receipted && notStaleOnArrival
                    && afterAck.holds() && survivesANoop
                    && stale.caught() && stale.repaired();

            NodeResult result = pass
                    ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(),
                            "baseline=" + baseline.exitCode()
                                    + " changeWasSeen=" + changeWasSeen
                                    + " (record exit " + reRecord.exitCode() + ", 8 expected)"
                                    + " ackSucceeded=" + ackSucceeded
                                    + " acknowledged=" + acknowledged
                                    + " receipted=" + receipted
                                    + " notStaleOnArrival=" + notStaleOnArrival
                                    + " (to=" + HomeIntegrity.shortHash(to)
                                    + " digest=" + HomeIntegrity.shortHash(current) + ")"
                                    + " afterAckHolds=" + afterAck.holds()
                                    + " survivesANoop=" + survivesANoop
                                    + " staleCaught=" + stale.caught()
                                    + " staleRepaired=" + stale.repaired());

            result = HomeIntegritySupport.withReport(result, afterAck)
                    .process(baseline).process(reRecord).process(ack).process(noop)
                    .assertion("a_content_change_pends_the_gate", changeWasSeen)
                    .assertion("acknowledging_it_succeeds", ackSucceeded && acknowledged)
                    .assertion("the_acknowledgement_records_when_it_happened", receipted)
                    .assertion("the_acknowledged_baseline_is_the_homes_current_digest",
                            notStaleOnArrival)
                    .assertion("a_pass_that_changes_nothing_leaves_it_acknowledged",
                            survivesANoop);
            return HomeIntegritySupport.withMutation(result, stale);
        });
    }

    /**
     * An acknowledged record whose baseline the home has already left.
     *
     * <p>This is the failure mode the invariant exists to exclude, and it is not
     * reachable by driving the CLI — which is the point. If {@code acknowledge}
     * ever carried a {@code report} the digest had moved past, the ack would be
     * a receipt for a state nobody is in, and the next drift check would re-pend
     * with no cause visible to the operator. #124's defect 4 was diagnosed as
     * exactly that shape before the sequencing turned out to explain it; the
     * check stays because the shape is still possible.
     */
    private record DivergeTheBaseline() implements HomeIntegritySupport.Damage {

        @Override
        public void plant(Path home) throws IOException {
            Path f = home.resolve("home.digest.json");
            String body = Files.readString(f);
            Files.writeString(f, body.replaceFirst("\"digest\"\\s*:\\s*\"[^\"]*\"",
                    "\"digest\" : \"" + "0".repeat(64) + "\""));
        }

        @Override
        public void repair(Path home) throws IOException {
            var drift = HomeIntegrity.readJson(home.resolve("home.drift.json"));
            String to = drift == null ? null : drift.path("report").path("to").asText(null);
            if (to == null) return;
            Path f = home.resolve("home.digest.json");
            Files.writeString(f, Files.readString(f)
                    .replaceFirst("\"digest\"\\s*:\\s*\"[^\"]*\"", "\"digest\" : \"" + to + "\""));
        }
    }

    private static NodeResult unproven(String why) {
        return NodeResult.fail(SPEC.id(), why)
                .assertion("a_content_change_pends_the_gate", false)
                .assertion("acknowledging_it_succeeds", false)
                .assertion("the_acknowledgement_records_when_it_happened", false)
                .assertion("the_acknowledged_baseline_is_the_homes_current_digest", false)
                .assertion("a_pass_that_changes_nothing_leaves_it_acknowledged", false)
                .assertion("the_planted_ack_of_a_baseline_left_behind_is_caught", false)
                .assertion("repairing_the_planted_ack_of_a_baseline_left_behind_clears_it", false);
    }
}
