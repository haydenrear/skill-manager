///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Directly asserts the contract of the bearer token cached by
 * {@code ci.logged.in}:
 *
 * <ul>
 *   <li>authenticated {@code GET /auth/me} returns 200 with the expected
 *       {@code username} claim (proves the server's {@code JwtDecoder}
 *       validates what the auth server minted — signature, kid, exp)</li>
 *   <li>unauthenticated {@code POST /skills/{n}/{v}} returns 401 (proves
 *       {@link SecurityConfig} is actually guarding the mutating surface
 *       — otherwise publish nodes would pass on a regression that let
 *       anonymous writes through)</li>
 * </ul>
 */
public class JwtValid {
    static final NodeSpec SPEC = NodeSpec.of("jwt.valid")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("ci.logged.in")
            .tags("auth", "assertion")
            .timeout("15s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            String expectedUser = ctx.get("ci.logged.in", "clientId").orElse("skill-manager-ci");
            if (home == null || registryUrl == null) {
                return NodeResult.fail("jwt.valid", "missing upstream context");
            }

            Path tokenFile = Path.of(home, "auth.token");
            if (!Files.isRegularFile(tokenFile)) {
                return NodeResult.fail("jwt.valid", "no token at " + tokenFile);
            }
            // AuthStore writes a JSON blob now; pull the access_token field
            // without dragging Jackson into this node.
            String fileBody = Files.readString(tokenFile).trim();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(fileBody);
            if (!m.find()) {
                return NodeResult.fail("jwt.valid", "no access_token in " + tokenFile + ": " + fileBody);
            }
            String token = m.group(1);

            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

            HttpResponse<String> me = http.send(
                    HttpRequest.newBuilder(URI.create(registryUrl + "/auth/me"))
                            .header("Authorization", "Bearer " + token)
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean authed200 = me.statusCode() == 200;
            boolean identityMatches = me.body().contains("\"username\":\"" + expectedUser + "\"");

            HttpResponse<String> unauth = http.send(
                    HttpRequest.newBuilder(URI.create(registryUrl + "/skills/jwt-probe/0.0.0"))
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean unauth401 = unauth.statusCode() == 401;

            boolean ok = authed200 && identityMatches && unauth401;
            return (ok
                    ? NodeResult.pass("jwt.valid")
                    : NodeResult.fail("jwt.valid",
                            "/auth/me=" + me.statusCode() + " body=" + me.body()
                                    + " unauthPost=" + unauth.statusCode()))
                    .assertion("authed_me_is_200", authed200)
                    .assertion("identity_matches_expected_user", identityMatches)
                    .assertion("unauthed_publish_is_401", unauth401)
                    .metric("me_status", me.statusCode())
                    .metric("unauth_publish_status", unauth.statusCode());
        });
    }
}
