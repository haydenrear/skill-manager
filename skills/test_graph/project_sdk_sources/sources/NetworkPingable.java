///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
/*
 * IMPORTING USER CODE — uncomment directives like these above this block:
 *   //SOURCES ../../src/main/java/com/acme/net/Probe.java
 *   //DEPS org.apache.httpcomponents.client5:httpclient5:5.3.1
 * See test-graph-skill/SKILL.md → "Importing user code".
 */

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

/**
 * Evidence node whose spec declares NO dependencies.
 *
 * Its ordering in the graph comes entirely from the DSL — the example
 * build.gradle.kts adds `.dependsOn("app.running")` here, which forces
 * this node to run after the testbed. A second DSL overlay on
 * `login.smoke` adds `.dependsOn("network.pingable")` so this node
 * also runs before the assertion.
 *
 * If the DSL-added deps failed to make it into the topo sort, this
 * node would end up in the wrong slot (or first) and the upstream
 * context it reads would be missing. The test exists to catch that.
 */
public class NetworkPingable {
    static final NodeSpec SPEC = NodeSpec.of("network.pingable")
            .kind(NodeSpec.Kind.EVIDENCE)
            .tags("network");
    // NOTE: no .dependsOn(...) — the graph adds edges via the DSL.

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String baseUrl = ctx.get("app.running", "baseUrl").orElse("(missing)");
            return NodeResult.pass("network.pingable")
                    .assertion("ran_after_app_running", !baseUrl.equals("(missing)"))
                    .publish("sawBaseUrl", baseUrl);
        });
    }
}
