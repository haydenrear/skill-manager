///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@code no_ads=true} must strip the sponsored lane regardless of active
 * campaigns. The organic lane is expected to match the lane you'd get
 * without the flag (checked separately by {@link SponsoredOrganicUnchanged}).
 */
public class SponsoredNoAdsSuppresses {
    static final NodeSpec SPEC = NodeSpec.of("sponsored.no.ads.suppresses")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("campaigns.created")
            .tags("ads", "search")
            .timeout("15s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (registryUrl == null) return NodeResult.fail("sponsored.no.ads.suppresses", "missing registry url");

            JsonNode withAds = fetch(registryUrl, "/skills/search?q=review");
            JsonNode noAds = fetch(registryUrl, "/skills/search?q=review&no_ads=true");

            int withCount = withAds.path("sponsored_count").asInt();
            int noCount = noAds.path("sponsored_count").asInt();

            boolean hadAds = withCount > 0;
            boolean noAdsZero = noCount == 0 && noAds.path("sponsored").size() == 0;

            return (hadAds && noAdsZero
                    ? NodeResult.pass("sponsored.no.ads.suppresses")
                    : NodeResult.fail("sponsored.no.ads.suppresses",
                            "withCount=" + withCount + " noCount=" + noCount))
                    .assertion("default_has_sponsored", hadAds)
                    .assertion("no_ads_flag_empties_sponsored", noAdsZero);
        });
    }

    static JsonNode fetch(String baseUrl, String pathAndQuery) throws Exception {
        URI uri = URI.create(baseUrl + pathAndQuery);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new ObjectMapper().readTree(resp.body());
    }
}
