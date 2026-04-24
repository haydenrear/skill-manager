///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../common/BrowserLoginFlow.java
//DEPS org.seleniumhq.selenium:selenium-java:4.23.0
//DEPS org.seleniumhq.selenium:selenium-chrome-driver:4.23.0

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The "after" side of the reset: signs in with the NEW password
 * published by {@code password.changed}. If the service-layer update
 * worked, the browser flow succeeds and {@code /auth/me} echoes the
 * expected username. If the old password still worked, some other
 * node upstream needs to surface the regression instead of this one.
 */
public class FinalLogin {
    static final NodeSpec SPEC = NodeSpec.of("final.login")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("password.changed")
            .tags("auth", "browser", "password-reset")
            .sideEffects("proc:spawn", "net:local")
            .timeout("120s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            String username = ctx.get("account.created", "username").orElse(null);
            String newPassword = ctx.get("password.changed", "newPassword").orElse(null);
            if (home == null || registryUrl == null || username == null || newPassword == null) {
                return NodeResult.fail("final.login", "missing upstream context");
            }
            // Drop the token cached by initial.login so we really prove the new
            // password works rather than reusing the bearer we already had.
            Files.deleteIfExists(Path.of(home, "auth.token"));

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            BrowserLoginFlow.Result r = BrowserLoginFlow.run(
                    sm.toString(), home, registryUrl, repoRoot.toString(), username, newPassword);

            return (r.fullySucceeded()
                    ? NodeResult.pass("final.login")
                    : NodeResult.fail("final.login",
                            "new-password login failed: rc=" + r.cliExitCode
                                    + "\ncli output:\n" + String.join("\n", r.cliOutput)))
                    .assertion("new_password_accepted", r.usernameMatches);
        });
    }
}
