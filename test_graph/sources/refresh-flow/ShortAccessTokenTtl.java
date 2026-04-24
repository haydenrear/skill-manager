///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

/**
 * Fixture that publishes a short access-token TTL. {@code registry.up}
 * picks this up via {@code ctx.get("short.access.token.ttl", "seconds")}
 * and threads it into the server via {@code
 * SKILL_REGISTRY_ACCESS_TOKEN_TTL_SECONDS}. Only the refresh-flow graph
 * references this node; in other graphs the default 1h TTL applies.
 */
public class ShortAccessTokenTtl {
    static final String TTL_SECONDS = "3";
    /** Zero clock skew so the server stops honoring a token the instant its exp passes. */
    static final String CLOCK_SKEW_SECONDS = "0";

    static final NodeSpec SPEC = NodeSpec.of("short.access.token.ttl")
            .kind(NodeSpec.Kind.FIXTURE)
            .tags("auth", "refresh")
            .timeout("5s")
            .output("seconds", "string")
            .output("clockSkewSeconds", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> NodeResult.pass("short.access.token.ttl")
                .assertion("ttl_published", true)
                .publish("seconds", TTL_SECONDS)
                .publish("clockSkewSeconds", CLOCK_SKEW_SECONDS));
    }
}
