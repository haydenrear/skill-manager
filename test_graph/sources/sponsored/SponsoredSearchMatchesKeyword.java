///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
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
 * Each keyword-targeted campaign should surface in the sponsored lane
 * only for queries that actually match its keywords/categories — not
 * for unrelated queries. Proves keyword leakage isn't happening.
 */
public class SponsoredSearchMatchesKeyword {
    static final NodeSpec SPEC = NodeSpec.of("sponsored.search.matches.keyword")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("campaigns.created")
            .tags("ads", "search")
            .timeout("20s")
            .retries(2);
    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (registryUrl == null) return NodeResult.fail("sponsored.search.matches.keyword", "missing registry url");

            JsonNode reviewResp = search(registryUrl, "code-review");
            JsonNode formatResp = search(registryUrl, "format");
            JsonNode unrelatedResp = search(registryUrl, "database-backup");

            var reviewSkills = sponsoredSkillNames(reviewResp);
            var formatSkills = sponsoredSkillNames(formatResp);
            var unrelatedSkills = sponsoredSkillNames(unrelatedResp);

            boolean reviewHasReviewer = reviewSkills.contains("reviewer-skill");
            boolean formatHasFormatter = formatSkills.contains("formatter-skill");
            boolean formatExcludesReviewer = !formatSkills.contains("reviewer-skill");
            boolean unrelatedEmpty = unrelatedSkills.isEmpty();

            return (reviewHasReviewer && formatHasFormatter && formatExcludesReviewer && unrelatedEmpty
                    ? NodeResult.pass("sponsored.search.matches.keyword")
                    : NodeResult.fail("sponsored.search.matches.keyword",
                            "review=" + reviewSkills + " format=" + formatSkills
                                    + " unrelated=" + unrelatedSkills))
                    .assertion("review_keyword_surfaces_reviewer", reviewHasReviewer)
                    .assertion("format_keyword_surfaces_formatter", formatHasFormatter)
                    .assertion("format_does_not_leak_reviewer_campaign", formatExcludesReviewer)
                    .assertion("unrelated_query_has_no_ads", unrelatedEmpty);
        });
    }

    static JsonNode search(String baseUrl, String query) throws Exception {
        URI uri = URI.create(baseUrl + "/skills/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new ObjectMapper().readTree(resp.body());
    }

    static java.util.Set<String> sponsoredSkillNames(JsonNode resp) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (JsonNode n : resp.path("sponsored")) {
            out.add(n.path("name").asText());
        }
        return out;
    }
}
