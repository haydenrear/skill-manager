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
 * Proves the {@code account.created} user can log in with the password
 * they registered with — the "before" baseline for
 * {@code password.changed}. Uses the shared {@link BrowserLoginFlow}
 * so this is really only asserting the originally-provisioned password
 * works end-to-end.
 */
public class InitialLogin {
    static final NodeSpec SPEC = NodeSpec.of("initial.login")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("account.created", "selenium.ready")
            .tags("auth", "browser")
            .sideEffects("proc:spawn", "net:local")
            .timeout("120s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            String username = ctx.get("account.created", "username").orElse(null);
            String password = ctx.get("account.created", "password").orElse(null);
            if (home == null || registryUrl == null || username == null || password == null) {
                return NodeResult.fail("initial.login", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            BrowserLoginFlow.Result r = BrowserLoginFlow.run(
                    sm.toString(), home, registryUrl, repoRoot.toString(), username, password);

            return (r.fullySucceeded()
                    ? NodeResult.pass("initial.login")
                    : NodeResult.fail("initial.login",
                            "original-password login failed: rc=" + r.cliExitCode
                                    + "\ncli output:\n" + String.join("\n", r.cliOutput)))
                    .assertion("original_password_accepted", r.usernameMatches);
        });
    }
}
