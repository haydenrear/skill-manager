///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
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

/**
 * Two campaigns match the keyword "review":
 *   - reviewer-skill @ 500¢
 *   - formatter-skill @ 1000¢ (higher bid)
 *
 * The formatter-skill slot must rank first because bid desc is the
 * primary sort key. Without this guarantee the auction semantics are
 * broken and we can't charge for "top slot" ordering.
 */
public class SponsoredHigherBidWins {
    static final NodeSpec SPEC = NodeSpec.of("sponsored.higher.bid.wins")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("campaigns.created")
            .tags("ads", "auction")
            .timeout("15s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (registryUrl == null) return NodeResult.fail("sponsored.higher.bid.wins", "missing registry url");

            URI uri = URI.create(registryUrl + "/skills/search?q=review");
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode root = new ObjectMapper().readTree(resp.body());
            JsonNode sponsored = root.path("sponsored");

            if (sponsored.size() < 2) {
                return NodeResult.fail("sponsored.higher.bid.wins",
                        "expected at least 2 sponsored placements, got " + sponsored.size() + ": " + sponsored)
                        .assertion("two_or_more_placements", false);
            }

            String firstSkill = sponsored.get(0).path("name").asText();
            long firstBid = sponsored.get(0).path("bid_cents").asLong();
            String secondSkill = sponsored.get(1).path("name").asText();
            long secondBid = sponsored.get(1).path("bid_cents").asLong();

            boolean orderedByBid = firstBid >= secondBid;
            boolean highBidFirst = "formatter-skill".equals(firstSkill) && firstBid == 1000L;

            return (orderedByBid && highBidFirst
                    ? NodeResult.pass("sponsored.higher.bid.wins")
                    : NodeResult.fail("sponsored.higher.bid.wins",
                            "first=" + firstSkill + "@" + firstBid
                                    + " second=" + secondSkill + "@" + secondBid))
                    .assertion("slots_ordered_by_bid_desc", orderedByBid)
                    .assertion("highest_bidder_in_first_slot", highBidFirst)
                    .metric("first_bid_cents", firstBid)
                    .metric("second_bid_cents", secondBid);
        });
    }
}
