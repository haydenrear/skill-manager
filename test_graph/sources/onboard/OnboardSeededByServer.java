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
 * {@code SkillBootstrapper}) seeded both bundled skills into storage.
 * Hits the public list endpoint — no auth needed for read — and looks
 * for {@code skill-manager} and {@code skill-publisher} in the response
 * body. The check is intentionally a substring match so the test is
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
            .timeout("15s")
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
            boolean publisherSeen = body != null && body.contains("\"skill-publisher\"");
            return (managerSeen && publisherSeen
                    ? NodeResult.pass("onboard.seeded.by.server")
                    : NodeResult.fail("onboard.seeded.by.server",
                            "missing seeded skills — manager=" + managerSeen
                                    + " publisher=" + publisherSeen))
                    .assertion("skill_manager_seeded", managerSeen)
                    .assertion("skill_publisher_seeded", publisherSeen);
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
