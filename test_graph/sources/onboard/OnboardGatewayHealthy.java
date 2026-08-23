///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Verifies the second half of {@code skill-manager onboard}: the
 * virtual MCP gateway is reachable on the env.prepared port. Onboard
 * should leave it up; this is a thin /health probe that catches the
 * case where the install half passed but the gateway crashed during
 * its first POST register.
 */
public class OnboardGatewayHealthy {
    static final NodeSpec SPEC = NodeSpec.of("onboard.gateway.healthy")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboard.completed")
            .tags("onboard", "gateway")
            .timeout("90s")
            // HIS-17 / DEF-065. Was 10s. That budget is BELOW this graph's
            // own fixed per-node cost: measured over 14 node gaps in run
            // 20260823-165545, 13.15-17.66s, mean 14.65s, and jbang startup
            // alone is 5.68s on an idle machine with a warm cache. This node's
            // BODY is milliseconds. It could do nothing at all and still time
            // out, and .retries(2) turned that into flake rather than a verdict.
            //
            // 90s is not a measurement of this node -- nothing here takes 90s.
            // It is headroom over a fixed cost nobody has attacked yet. The
            // number that matters is the 14s floor and the OTLP exporter
            // failing on every node of every graph; DEF-065 stays OPEN on it.
            .retries(2);
    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String port = ctx.get("env.prepared", "gatewayPort").orElse(null);
            if (port == null) {
                return NodeResult.fail("onboard.gateway.healthy", "missing env.prepared.gatewayPort");
            }
            String url = "http://127.0.0.1:" + port + "/health";
            boolean reachable = ping(url);
            return (reachable
                    ? NodeResult.pass("onboard.gateway.healthy")
                    : NodeResult.fail("onboard.gateway.healthy", "gateway /health unreachable at " + url))
                    .assertion("gateway_health_reachable", reachable);
        });
    }

    private static boolean ping(String url) {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpResponse<Void> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(3))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }
}
