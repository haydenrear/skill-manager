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
 * Hits the public list endpoint — no auth needed for read — and looks for
 * {@code skill-manager} in the response body. The check is intentionally a
 * substring match so the test is resilient to JSON shape tweaks.
 *
 * <p>Two of the four assertions are INVERTED, and that is the interesting
 * half. The server seeds from directories in this repository's own tree, so
 * the only thing it can seed is what this repository carries. Asserting that
 * {@code skill-dev-skill} (deleted at OUN-4) and {@code skt} /
 * {@code unit-authoring} (SI-18: moved into the tla-spec-dev plugin, in
 * another repository) are ABSENT is what catches a seed list quietly growing
 * a retired or relocated unit back — which is the failure mode a vendored
 * snapshot produces, and the one this epic exists to close.
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
            // A null body is "the registry did not answer", which must not
            // satisfy an absence assertion. Every boolean below is therefore
            // gated on `body != null` INCLUDING the inverted ones — an empty
            // result is not a passing result.
            boolean answered = body != null;
            boolean managerSeen = answered && body.contains("\"skill-manager\"");
            // INVERTED by SI-18, not deleted. skill-publisher-skill/ was a
            // vendored snapshot of the skt plugin and the server seeded skt and
            // unit-authoring out of it. The snapshot is gone and the canonical
            // copies live in github:haydenrear/tla-spec-dev-plugin, which this
            // repository does not vendor — so there is nothing here to seed
            // them FROM, and seeing them in the registry again would mean a
            // vendored copy had come back.
            boolean sktAbsent = answered && !body.contains("\"skt\"");
            boolean authoringAbsent = answered && !body.contains("\"unit-authoring\"");
            // INVERTED by OUN-4, not deleted. skill-dev-skill was seeded here
            // until the unit was retired; asserting it is ABSENT is what
            // catches a seed list that quietly grows the unit back.
            boolean retiredAbsent = answered && !body.contains("\"skill-dev-skill\"");
            return (managerSeen && sktAbsent && authoringAbsent && retiredAbsent
                    ? NodeResult.pass("onboard.seeded.by.server")
                    : NodeResult.fail("onboard.seeded.by.server",
                            "seeded skills wrong — registryAnswered=" + answered
                                    + " manager=" + managerSeen
                                    + " sktAbsent=" + sktAbsent
                                    + " unitAuthoringAbsent=" + authoringAbsent
                                    + " retiredSkillDevAbsent=" + retiredAbsent))
                    .assertion("skill_manager_seeded", managerSeen)
                    .assertion("vendored_skt_is_NOT_seeded", sktAbsent)
                    .assertion("vendored_unit_authoring_is_NOT_seeded", authoringAbsent)
                    .assertion("retired_skill_dev_is_NOT_seeded", retiredAbsent);
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
