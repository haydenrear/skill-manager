///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeIntegrity.java
//SOURCES HomeIntegritySupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A unit's recorded {@code gitHash} describes its own store checkout, or the
 * record says why it does not.
 *
 * <p>The argument for the invariant, and the measurement that decided its
 * shape, are on {@link HomeIntegrity#recordAgreesWithStore}. In short: #124's
 * defect 1 is real (3 of 20 records in the operator's project home disagree
 * with their store) and the invariant nonetheless <b>holds</b>, because all
 * three of those units record a {@code MERGE_CONFLICT} error and the invariant
 * as written admits exactly that. The home is not broken; its readers are.
 *
 * <p>So the regression this node plants is the case that would be silent: a
 * record whose hash disagrees with its store and which explains nothing. That
 * is what "3 of 20 disagree" would have meant if the errors had not been there,
 * and it is the state a future change could produce without anybody noticing.
 *
 * <p>The second mutation is the one that proves the disjunct is not a hole big
 * enough to drive the invariant through: a disagreeing record that <em>does</em>
 * carry an error is accepted, and this node asserts that acceptance explicitly
 * rather than leaving it as an untested branch. An escape clause nobody tests
 * is an escape clause that will eventually swallow the check.
 */
public class RecordAgreesWithStore {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.record.agrees.with.store")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "records")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String homeStr = ctx.get("home.integrity.fixture", "integrityHome").orElse(null);
            String scratchStr = ctx.get("home.integrity.fixture", "scratchRoot").orElse(null);
            if (homeStr == null || scratchStr == null) {
                return unproven("missing home.integrity.fixture context");
            }
            Path home = Path.of(homeStr);
            Path scratch = Path.of(scratchStr);

            HomeIntegrity.Report fresh = HomeIntegrity.recordAgreesWithStore(home);

            HomeIntegritySupport.Mutation silent;
            HomeIntegritySupport.Mutation excused;
            try {
                silent = HomeIntegritySupport.probe(home, scratch,
                        "unexplained_hash_disagreement",
                        new Rewrite("0000000000000000000000000000000000000000", false),
                        HomeIntegrity::recordAgreesWithStore);
                excused = HomeIntegritySupport.probe(home, scratch,
                        "hash_disagreement_with_a_recorded_error",
                        new Rewrite("0000000000000000000000000000000000000000", true),
                        HomeIntegrity::recordAgreesWithStore);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }

            // The excused mutation must NOT be caught: it is the disjunct.
            boolean disjunctHonoured = excused.tolerated();
            boolean freshHolds = fresh.holdsNonVacuously();

            boolean pass = freshHolds && silent.caught() && silent.repaired() && disjunctHonoured;

            NodeResult result = pass
                    ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(),
                            "freshHolds=" + freshHolds
                                    + " examined=" + fresh.examined()
                                    + " violations=" + fresh.findings().size()
                                    + " silentCaught=" + silent.caught()
                                    + " silentRepaired=" + silent.repaired()
                                    + " disjunctHonoured=" + disjunctHonoured
                                    + " | " + fresh.describe());

            result = HomeIntegritySupport.withReport(result, fresh)
                    .assertion("every_record_in_a_fresh_home_describes_its_own_store", freshHolds);
            result = HomeIntegritySupport.withMutation(result, silent);
            return result
                    .assertion("a_disagreement_the_record_explains_is_not_reported_as_a_defect",
                            disjunctHonoured)
                    .log(excused.name() + ": " + excused.evidence());
        });
    }

    /**
     * Rewrite one unit's recorded {@code gitHash} so it no longer describes its
     * store, optionally adding the error record that excuses it.
     *
     * <p>Hand-editing the JSON rather than driving the CLI into this state is
     * deliberate. The state exists in the operator's home because a
     * <em>merge conflict</em> put it there, and reproducing a merge conflict
     * inside a test would be reproducing #99, not this invariant. What matters
     * is that the checker reads both fields and weighs them correctly, and that
     * is exactly what a written record tests.
     */
    private record Rewrite(String hash, boolean withError) implements HomeIntegritySupport.Damage {

        @Override
        public void plant(Path home) throws IOException {
            Path rec = home.resolve("installed").resolve(HomeIntegritySupport.UNIT + ".json");
            String body = Files.readString(rec);
            Files.writeString(rec, rewritten(body, hash, withError));
        }

        @Override
        public void repair(Path home) throws IOException {
            Path rec = home.resolve("installed").resolve(HomeIntegritySupport.UNIT + ".json");
            Path store = home.resolve("skills").resolve(HomeIntegritySupport.UNIT);
            String head = HomeIntegrity.gitHead(store);
            String body = Files.readString(rec);
            Files.writeString(rec, rewritten(body, head, false));
        }

        private static String rewritten(String body, String hash, boolean withError) {
            String out = body.replaceAll("\"gitHash\"\\s*:\\s*\"[^\"]*\"",
                    "\"gitHash\" : \"" + hash + "\"");
            if (withError) {
                out = out.replaceAll("\"errors\"\\s*:\\s*\\[\\s*\\]",
                        "\"errors\" : [ { \"kind\" : \"MERGE_CONFLICT\", "
                                + "\"message\" : \"planted by the home-integrity graph\", "
                                + "\"firstSeenAt\" : \"2026-08-16T00:00:00Z\" } ]");
            } else {
                out = out.replaceAll("\"errors\"\\s*:\\s*\\[[^\\]]*\\]", "\"errors\" : [ ]");
            }
            return out;
        }
    }

    /**
     * A bail-out that still makes every claim, set to false.
     *
     * <p>A refusal with a bare sentence reads to the aggregate report as "made
     * no claims", which is indistinguishable from a pass — the lesson
     * {@code HomeTripwireChecked.unproven} records.
     */
    private static NodeResult unproven(String why) {
        return NodeResult.fail(SPEC.id(), why)
                .assertion("every_record_in_a_fresh_home_describes_its_own_store", false)
                .assertion("the_planted_unexplained_hash_disagreement_is_caught", false)
                .assertion("repairing_the_planted_unexplained_hash_disagreement_clears_it", false)
                .assertion("a_disagreement_the_record_explains_is_not_reported_as_a_defect",
                        false);
    }
}
