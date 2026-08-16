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
 * Every projection resolves somewhere this home can account for.
 *
 * <h2>This node is the correction, and the third mutation is the whole point</h2>
 *
 * <p>#124's defect 9 reads "<b>51 of 106 projections serve bytes from another
 * home</b>". Reproduced to the projection — and every one of those 51 is a
 * child-home projection landing in one of the four child homes the project home
 * itself registered, for a unit named in that child's own {@code units} list.
 * Classified with the child-home clause the home is <b>106 of 106 decidable</b>.
 * The measurement is true; the implication drawn from it is not. The full
 * argument and the counts are on
 * {@link HomeIntegrity#projectionSourceIsDecidable}.
 *
 * <p>So this node plants the two states that would be genuinely undecidable —
 * a projection whose target is gone, and a projection into a home this one
 * never registered — and then plants a <b>correctly registered child-home
 * projection and asserts it is NOT reported</b>. That third assertion is the
 * executable form of the correction. Without it, a later change could
 * "strengthen" this check into "resolves inside this home", every fixture in
 * this graph would still pass, and the check would start reporting 51 healthy
 * projections in the operator's home as defects.
 */
public class ProjectionSourceIsDecidable {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.projection.decidable")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "projections")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String homeStr = ctx.get("home.integrity.fixture", "integrityHome").orElse(null);
            String scratchStr = ctx.get("home.integrity.fixture", "scratchRoot").orElse(null);
            if (homeStr == null || scratchStr == null) return unproven("missing fixture context");
            Path home = Path.of(homeStr);
            Path scratch = Path.of(scratchStr);

            HomeIntegrity.Report fresh = HomeIntegrity.projectionSourceIsDecidable(home);

            HomeIntegritySupport.Mutation missing;
            HomeIntegritySupport.Mutation foreign;
            HomeIntegritySupport.Mutation registeredChild;
            try {
                // The mutation subject: this home, with the CLI-written
                // projection records removed. They name absolute paths in
                // env.prepared's agent sandbox, so a COPY of this home reads as
                // six foreign projections before anything is planted — the
                // control would fail and every mutation over it would be
                // meaningless. See PlantProjection's comment; the non-vacuous
                // claim about real projections is made by `fresh` above.
                Path base = scratch.resolve("projection-base");
                HomeIntegritySupport.deleteRecursively(base);
                HomeIntegritySupport.copyTree(home, base);
                for (Path f : HomeIntegrity.listDir(base.resolve("installed"))) {
                    if (f.getFileName().toString().endsWith(".projections.json")) {
                        Files.delete(f);
                    }
                }

                missing = HomeIntegritySupport.probe(base, scratch, "vanished_projection",
                        new PlantProjection(PlantProjection.Target.VANISHED),
                        HomeIntegrity::projectionSourceIsDecidable);
                foreign = HomeIntegritySupport.probe(base, scratch, "unregistered_foreign_home",
                        new PlantProjection(PlantProjection.Target.UNREGISTERED),
                        HomeIntegrity::projectionSourceIsDecidable);
                registeredChild = HomeIntegritySupport.probe(base, scratch,
                        "registered_child_home",
                        new PlantProjection(PlantProjection.Target.REGISTERED_CHILD),
                        HomeIntegrity::projectionSourceIsDecidable);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }

            boolean freshHolds = fresh.holdsNonVacuously();
            boolean childTolerated = !registeredChild.caught();

            boolean pass = freshHolds
                    && missing.caught() && missing.repaired()
                    && foreign.caught() && foreign.repaired()
                    && childTolerated;

            NodeResult result = pass
                    ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(),
                            "freshHolds=" + freshHolds
                                    + " examined=" + fresh.examined()
                                    + " missingCaught=" + missing.caught()
                                    + " missingRepaired=" + missing.repaired()
                                    + " foreignCaught=" + foreign.caught()
                                    + " foreignRepaired=" + foreign.repaired()
                                    + " childTolerated=" + childTolerated
                                    + " | " + fresh.describe());

            result = HomeIntegritySupport.withReport(result, fresh)
                    .assertion("every_projection_in_a_fresh_home_resolves_inside_it", freshHolds);
            result = HomeIntegritySupport.withMutation(result, missing);
            result = HomeIntegritySupport.withMutation(result, foreign);
            return result
                    .assertion("a_projection_into_a_registered_child_home_is_not_a_defect",
                            childTolerated)
                    .log(registeredChild.name() + ": " + registeredChild.evidence());
        });
    }

    /**
     * The name every planted binding uses, so the checker sees a real unit.
     *
     * <p>A projection record naming a unit with no {@code installed/} record
     * would be a different defect, and mixing two into one mutation makes both
     * harder to act on.
     */
    private static final String PLANTED = "hi-planted";

    /**
     * Write a projections record and the link it describes, both anchored in
     * whichever home is handed in.
     *
     * <h3>Why the mutations author the record rather than editing the real one</h3>
     *
     * <p>An agent projection's {@code destPath} is <b>absolute</b>: the real
     * record in the fixture home names paths in {@code env.prepared}'s agent
     * sandbox, which is outside the home entirely. Take a working copy of that
     * home and its projections still point at the original — so the copy reads
     * as 6 foreign projections before anything is planted, the control fails,
     * and every mutation over it is meaningless. That is not a hypothetical: it
     * is what the first version of this node measured, and re-anchoring a
     * copied home is {@code home clone}'s whole job rather than a test's.
     *
     * <p>So the non-vacuous claim about a real home is made by the
     * {@code fresh} check at the top of this node, over the 6 projections the
     * CLI actually wrote. The mutations below then work on a home where the
     * record and the link are authored together and therefore agree — which is
     * the only condition under which "the checker classifies this correctly"
     * is a question with an answer.
     */
    private record PlantProjection(Target target) implements HomeIntegritySupport.Damage {

        enum Target {
            /** Recorded, and the link is not there. */
            VANISHED,
            /** A link into a second home this home never registered. */
            UNREGISTERED,
            /** A link into a second home registered as a child, carrying the unit. */
            REGISTERED_CHILD
        }

        @Override
        public void plant(Path home) throws IOException {
            Path own = home.resolve("skills").resolve(PLANTED);
            Files.createDirectories(own);
            Files.writeString(own.resolve("SKILL.md"), "planted by the home-integrity graph\n");
            Files.writeString(home.resolve("installed").resolve(PLANTED + ".json"), """
                    {
                      "name" : "%s",
                      "version" : "0.1.0",
                      "kind" : "LOCAL",
                      "installedAt" : "2026-08-16T00:00:00Z",
                      "errors" : [ ],
                      "unitKind" : "SKILL"
                    }
                    """.formatted(PLANTED));

            Path dest = home.resolve("agent-projection").resolve(PLANTED);
            Files.createDirectories(dest.getParent());
            Files.deleteIfExists(dest);

            switch (target) {
                case VANISHED -> { /* recorded below, and deliberately not created */ }
                case UNREGISTERED, REGISTERED_CHILD -> {
                    Path other = home.getParent().resolve("hi-other-home-" + target.name());
                    Path otherUnit = other.resolve("skills").resolve(PLANTED);
                    Files.createDirectories(otherUnit);
                    Files.writeString(otherUnit.resolve("SKILL.md"), "another home's copy\n");
                    Files.createSymbolicLink(dest, otherUnit);
                    if (target == Target.REGISTERED_CHILD) {
                        Path rec = home.resolve("child-homes").resolve("project_hi-other")
                                .resolve("child-home.json");
                        Files.createDirectories(rec.getParent());
                        Files.writeString(rec, """
                                {
                                  "id" : "project:hi-other",
                                  "parentHome" : "$SKILL_MANAGER_HOME",
                                  "childHome" : "%s",
                                  "units" : [ "%s" ],
                                  "createdAt" : "2026-08-16T00:00:00Z"
                                }
                                """.formatted(other, PLANTED));
                    }
                }
            }

            Files.writeString(home.resolve("installed").resolve(PLANTED + ".projections.json"),
                    """
                    {
                      "unitName" : "%s",
                      "bindings" : [ {
                        "bindingId" : "default:claude:%s",
                        "unitName" : "%s",
                        "unitKind" : "SKILL",
                        "targetRoot" : "%s",
                        "conflictPolicy" : "ERROR",
                        "createdAt" : "2026-08-16T00:00:00Z",
                        "source" : "DEFAULT_AGENT",
                        "projections" : [ {
                          "bindingId" : "default:claude:%s",
                          "sourcePath" : "$SKILL_MANAGER_HOME/skills/%s",
                          "destPath" : "%s",
                          "kind" : "SYMLINK"
                        } ]
                      } ]
                    }
                    """.formatted(PLANTED, PLANTED, PLANTED,
                            home.resolve("agent-projection"), PLANTED, PLANTED, dest));
        }

        @Override
        public void repair(Path home) throws IOException {
            Files.deleteIfExists(home.resolve("installed").resolve(PLANTED + ".projections.json"));
            Files.deleteIfExists(home.resolve("installed").resolve(PLANTED + ".json"));
            HomeIntegritySupport.deleteRecursively(home.resolve("agent-projection"));
            HomeIntegritySupport.deleteRecursively(home.resolve("skills").resolve(PLANTED));
            HomeIntegritySupport.deleteRecursively(
                    home.resolve("child-homes").resolve("project_hi-other"));
        }
    }

    private static NodeResult unproven(String why) {
        return NodeResult.fail(SPEC.id(), why)
                .assertion("every_projection_in_a_fresh_home_resolves_inside_it", false)
                .assertion("the_planted_vanished_projection_is_caught", false)
                .assertion("repairing_the_planted_vanished_projection_clears_it", false)
                .assertion("the_planted_unregistered_foreign_home_is_caught", false)
                .assertion("repairing_the_planted_unregistered_foreign_home_clears_it", false)
                .assertion("a_projection_into_a_registered_child_home_is_not_a_defect", false);
    }
}
