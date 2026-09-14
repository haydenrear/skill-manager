///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;

/**
 * An orphaned projection record (DEF-OHV-002, OHV-2 (c)):
 * {@code installed/hv-gone.projections.json} with no
 * {@code installed/hv-gone.json} and no {@code hv-gone} unit directory. Measured
 * in 6 homes at kickoff, including this repo's project home
 * ({@code installed/skill-manager.projections.json}).
 *
 * <p>Before OHV-2 nothing reported or pruned it. Now {@code home repair} names
 * {@code ORPHANED_PROJECTION_RECORD}, {@code home verify} exits 1 naming it, and
 * {@code --fix} deletes the record and nothing else.
 */
public class OrphanedProjectionRecordIsReported {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.orphaned.projection.record")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "projection-record", "ohv-2")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> HomeVerdictsSupport.runShape(ctx, SPEC,
                new HomeVerdictsSupport.Shape("orphaned-projection-record",
                        "ORPHANED_PROJECTION_RECORD", 1, true),
                (Path subject, Path neighbour) -> HomeVerdictsSupport.orphanRecord(subject, "hv-gone")));
    }
}
