///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
/*
 * IMPORTING USER CODE
 * -------------------
 * When this scaffold lives at <user-project>/test_graph/, `../..` from
 * a node script reaches the user project root. Add directives below
 * (move them ABOVE this /* block, next to the existing //SOURCES line):
 *
 *   //SOURCES ../../src/main/java/com/acme/domain/User.java
 *   //SOURCES ../../src/main/java/com/acme/api/*.java
 *   //DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0
 *   //DEPS org.seleniumhq.selenium:selenium-java:4.21.0
 *
 * See <skill>/references/workflows.md, "Import User Code" for detail.
 */

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

/**
 * Testbed node: HEAD-probes a base URL to confirm the target app is up.
 *
 * Transitive in the example graph — not declared explicitly in build.gradle.kts,
 * but pulled in because both {@code user.seeded} and {@code login.smoke}
 * declare {@code dependsOn("app.running")}.
 */
public class AppRunning {
    static final NodeSpec SPEC = NodeSpec.of("app.running")
            .kind(NodeSpec.Kind.TESTBED)
            .tags("app", "testbed")
            .timeout("60s")
            .sideEffects("net:local")
            .input("baseUrl", "string")
            .output("ready", "boolean");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String baseUrl = ctx.input("baseUrl").orElse("http://localhost:8080");
            try {
                int code = head(baseUrl);
                boolean ready = code >= 200 && code < 300;
                return (ready
                        ? NodeResult.pass("app.running")
                        : NodeResult.fail("app.running", "baseUrl returned " + code))
                        .assertion("ready", ready)
                        .metric("statusCode", code)
                        .publish("baseUrl", baseUrl);
            } catch (Exception e) {
                return NodeResult.error("app.running", e).publish("baseUrl", baseUrl);
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
