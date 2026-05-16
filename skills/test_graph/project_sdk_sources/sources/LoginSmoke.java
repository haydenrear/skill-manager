///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
/*
 * IMPORTING USER CODE
 * -------------------
 * If this scaffold lives at <user-project>/test_graph/, add directives
 * (placed BEFORE this /* block, alongside the existing //SOURCES line):
 *
 *   //SOURCES ../../src/main/java/com/acme/domain/User.java
 *   //SOURCES ../../src/main/java/com/acme/http/LoginClient.java
 *   //DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0
 *
 * Then in the body, `import com.acme.domain.User;` etc.
 * See <skill>/references/workflows.md, "Import User Code".
 */

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Collectors;

import com.hayden.testgraphsdk.sdk.ContextItem;
import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

/**
 * Assertion node: probes the login endpoint after app.running + user.seeded
 * complete. Reads {@code user.seeded.userId} from the accumulated Context[]
 * to demonstrate cross-runtime data flow.
 */
public class LoginSmoke {
    static final NodeSpec SPEC = NodeSpec.of("login.smoke")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("app.running", "user.seeded")
            .tags("ui", "smoke")
            .timeout("120s")
            .sideEffects("browser")
            .input("baseUrl", "string")
            .output("success", "boolean")
            .output("screenshotPath", "string?")
            .junitXml()
            .cucumber();

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String baseUrl = ctx.input("baseUrl").orElse("http://localhost:8080");
            String userId = ctx.get("user.seeded", "userId").orElse("(unknown)");
            String loginUrl = baseUrl + "/login";

            // Context[] introspection — proof that the plugin composed every
            // upstream node's published map into this node's --context arg.
            int upstreamCount = ctx.context().size();
            String upstreamIds = ctx.context().stream()
                    .map(ContextItem::nodeId)
                    .collect(Collectors.joining(","));
            String upstreamShape = ctx.context().stream()
                    .map(ci -> ci.nodeId() + "{" +
                            String.join(",", ci.data().keySet()) + "}")
                    .collect(Collectors.joining(";"));

            try {
                int code = head(loginUrl);
                boolean reachable = code >= 200 && code < 400;
                return (reachable
                        ? NodeResult.pass("login.smoke")
                        : NodeResult.fail("login.smoke", "login returned " + code))
                        .assertion("login_endpoint_reachable", reachable)
                        .assertion("redirected_to_dashboard", code == 302 || code == 303)
                        .assertion("saw_all_upstreams", upstreamCount == 3)
                        .metric("statusCode", code)
                        .metric("upstreamCount", upstreamCount)
                        .publish("baseUrl", baseUrl)
                        .publish("attemptedAs", userId)
                        .publish("upstreamIds", upstreamIds)
                        .publish("upstreamShape", upstreamShape);
            } catch (Exception e) {
                return NodeResult.error("login.smoke", e)
                        .publish("baseUrl", baseUrl)
                        .publish("attemptedAs", userId)
                        .publish("upstreamIds", upstreamIds)
                        .publish("upstreamShape", upstreamShape);
            }
        });
    }

    private static int head(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build();
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(req, HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }
}
