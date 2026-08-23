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
 * Asserts that the registry server's startup-time bootstrap (see
 * {@code SkillBootstrapper}) seeded the bundled skills into storage.
 * Hits the public list endpoint — no auth needed for read — and looks
 * for {@code skill-manager}, {@code skill-publisher}, and
 * {@code skill-dev-skill} in the response body. The check is intentionally
 * a substring match so the test is
 * resilient to JSON shape tweaks.
 *
 * <p>If this fails, the bootstrap bean either didn't run, didn't find
 * its source dirs, or hit a publish exception — see registry.log in the
 * diagnostics artifact.
 */
public class OnboardSeededByServer {
    static final NodeSpec SPEC = NodeSpec.of("onboard.seeded.by.server")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("registry.up")
            .tags("onboard", "registry")
            .timeout("90s")
            // HIS-17 / DEF-065. Was 15s. That budget is BELOW this graph's
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
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (registryUrl == null) {
                return NodeResult.fail("onboard.seeded.by.server", "missing registry.up context");
            }
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            String body = fetch(http, registryUrl + "/skills");
            boolean managerSeen = body != null && body.contains("\"skill-manager\"");
            // skill-publisher-skill ships the skt plugin now; the server
            // seeds the plugin's CONTAINED skills (skt, unit-authoring).
            boolean sktSeen = body != null && body.contains("\"skt\"");
            boolean authoringSeen = body != null && body.contains("\"unit-authoring\"");
            boolean devSeen = body != null && body.contains("\"skill-dev-skill\"");
            return (managerSeen && sktSeen && authoringSeen && devSeen
                    ? NodeResult.pass("onboard.seeded.by.server")
                    : NodeResult.fail("onboard.seeded.by.server",
                            "missing seeded skills — manager=" + managerSeen
                                    + " skt=" + sktSeen
                                    + " unitAuthoring=" + authoringSeen
                                    + " skillDev=" + devSeen))
                    .assertion("skill_manager_seeded", managerSeen)
                    .assertion("skt_seeded", sktSeen)
                    .assertion("unit_authoring_seeded", authoringSeen)
                    .assertion("skill_dev_seeded", devSeen);
        });
    }

    private static String fetch(HttpClient http, String url) {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return null;
            return resp.body();
        } catch (Exception e) {
            return null;
        }
    }
}
