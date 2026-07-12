///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../common/BrowserLoginFlow.java
//DEPS org.seleniumhq.selenium:selenium-java:4.23.0
//DEPS org.seleniumhq.selenium:selenium-chrome-driver:4.23.0

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;

/**
 * End-to-end exercise of {@code skill-manager login}'s browser flow.
 *
 * <p>All the choreography lives in {@link BrowserLoginFlow}; this node
 * just invokes it with the credentials published by {@code
 * account.created} and fans the sub-steps out as discrete assertions
 * so a regression points at the right layer.
 */
public class BrowserAuthorized {
    static final NodeSpec SPEC = NodeSpec.of("browser.authorized")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("account.created", "selenium.ready")
            .tags("auth", "oauth2", "browser")
            .sideEffects("browser", "net:local")
            .timeout("120s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            String username = ctx.get("account.created", "username").orElse(null);
            String password = ctx.get("account.created", "password").orElse(null);
            if (home == null || registryUrl == null || username == null || password == null) {
                return NodeResult.fail("browser.authorized", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            BrowserLoginFlow.Result r = BrowserLoginFlow.run(
                    sm.toString(), home, registryUrl, repoRoot.toString(), username, password);

            return (r.fullySucceeded()
                    ? NodeResult.pass("browser.authorized")
                    : NodeResult.fail("browser.authorized",
                            "rc=" + r.cliExitCode + " tokenCached=" + r.tokenCached
                                    + " meStatus=" + r.meStatus
                                    + "\ncli output:\n" + String.join("\n", r.cliOutput)))
                    .assertion("cli_printed_authorize_url", r.authorizeUrlPrinted)
                    .assertion("login_form_submitted", r.formSubmitted)
                    .assertion("cli_exit_zero", r.cliExitCode == 0)
                    .assertion("token_cached_on_disk", r.tokenCached)
                    .assertion("me_returns_expected_username", r.usernameMatches)
                    .metric("cli_exit_code", r.cliExitCode);
        });
    }
}
