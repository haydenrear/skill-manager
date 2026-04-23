///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../common/TestDb.java
//DEPS org.postgresql:postgresql:42.7.4

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

/**
 * Queries Postgres directly to confirm that publishing recorded the
 * authoritative ownership + version rows — the HTTP 201 alone doesn't
 * prove the service layer actually inserted them.
 *
 * <p>Asserts:
 *   skill_names.hello-skill.owner_username  == skill-manager-ci
 *   skill_versions.hello-skill@0.1.0.published_by == skill-manager-ci
 */
public class OwnershipRecorded {
    static final NodeSpec SPEC = NodeSpec.of("ownership.recorded")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hello.published")
            .tags("registry", "ownership", "db")
            .timeout("15s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String expected = ctx.get("ci.logged.in", "clientId").orElse("skill-manager-ci");
            try (TestDb db = TestDb.open()) {
                String owner = db.ownerOf("hello-skill");
                String publisher = db.publisherOf("hello-skill", "0.1.0");
                boolean ownerOk = expected.equals(owner);
                boolean publisherOk = expected.equals(publisher);
                return (ownerOk && publisherOk
                        ? NodeResult.pass("ownership.recorded")
                        : NodeResult.fail("ownership.recorded",
                                "owner=" + owner + " publisher=" + publisher + " expected=" + expected))
                        .assertion("name_owned_by_publisher", ownerOk)
                        .assertion("version_published_by_same", publisherOk);
            }
        });
    }
}
