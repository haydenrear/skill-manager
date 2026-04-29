///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Core transparency guarantee: organic ranking must not be reordered
 * or filtered by the presence of active campaigns. This asserts that
 * the {@code items} array is identical (same names, same order) with
 * and without ads — for queries where at least one campaign matches.
 */
public class SponsoredOrganicUnchanged {
    static final NodeSpec SPEC = NodeSpec.of("sponsored.organic.unchanged")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("campaigns.created")
            .tags("ads", "transparency")
            .timeout("15s")
            .retries(2);
    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (registryUrl == null) return NodeResult.fail("sponsored.organic.unchanged", "missing registry url");

            JsonNode withAds = fetch(registryUrl + "/skills/search?q=review");
            JsonNode noAds = fetch(registryUrl + "/skills/search?q=review&no_ads=true");

            List<String> withOrganic = names(withAds.path("items"));
            List<String> noOrganic = names(noAds.path("items"));

            boolean sameOrder = withOrganic.equals(noOrganic);
            boolean sameCount = withAds.path("count").asInt() == noAds.path("count").asInt();

            return (sameOrder && sameCount
                    ? NodeResult.pass("sponsored.organic.unchanged")
                    : NodeResult.fail("sponsored.organic.unchanged",
                            "with_ads=" + withOrganic + " no_ads=" + noOrganic))
                    .assertion("organic_count_identical", sameCount)
                    .assertion("organic_order_identical", sameOrder)
                    .metric("organic_items", withOrganic.size());
        });
    }

    static JsonNode fetch(String url) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new ObjectMapper().readTree(resp.body());
    }

    static List<String> names(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(n.path("name").asText());
        return out;
    }
}
