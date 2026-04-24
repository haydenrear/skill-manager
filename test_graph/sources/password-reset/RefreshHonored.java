///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simulates an expired bearer and verifies the CLI transparently
 * refreshes it: takes the token file from {@code final.login},
 * overwrites the {@code access_token} with a garbage value (keeping
 * the refresh token), runs a command that needs authentication, and
 * asserts it still succeeds and the stored access token was rotated.
 *
 * <p>Skips if the server didn't mint a refresh token — which would
 * itself be a regression, so the node fails rather than passes in that
 * case.
 */
public class RefreshHonored {
    static final NodeSpec SPEC = NodeSpec.of("refresh.honored")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("final.login")
            .tags("auth", "refresh")
            .sideEffects("proc:spawn", "net:local")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("refresh.honored", "missing upstream context");
            }
            Path tokenFile = Path.of(home, "auth.token");
            if (!Files.isRegularFile(tokenFile)) {
                return NodeResult.fail("refresh.honored", "no token file at " + tokenFile);
            }

            ObjectMapper json = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> stored = json.readValue(Files.readString(tokenFile), Map.class);
            String originalAccess = (String) stored.get("access_token");
            String refresh = (String) stored.get("refresh_token");
            if (refresh == null || refresh.isBlank()) {
                return NodeResult.fail("refresh.honored",
                        "final.login didn't persist a refresh token — SAS refresh flow regression")
                        .assertion("refresh_token_was_persisted", false);
            }

            // Poison the access token; keep the refresh token.
            Map<String, Object> poisoned = new LinkedHashMap<>(stored);
            poisoned.put("access_token", "not-a-real-jwt");
            Files.writeString(tokenFile, json.writeValueAsString(poisoned));

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "login", "show",
                    "--registry", registryUrl)
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int rc = proc.waitFor();

            boolean exitedZero = rc == 0;
            boolean meEchoed = output.contains("username:");

            @SuppressWarnings("unchecked")
            Map<String, Object> afterRefresh = json.readValue(Files.readString(tokenFile), Map.class);
            String newAccess = (String) afterRefresh.get("access_token");
            boolean accessRotated = newAccess != null
                    && !newAccess.isBlank()
                    && !"not-a-real-jwt".equals(newAccess)
                    && !newAccess.equals(originalAccess);

            boolean ok = exitedZero && meEchoed && accessRotated;
            return (ok
                    ? NodeResult.pass("refresh.honored")
                    : NodeResult.fail("refresh.honored",
                            "rc=" + rc + " meEchoed=" + meEchoed + " accessRotated=" + accessRotated
                                    + "\nstdout:\n" + output))
                    .assertion("refresh_token_was_persisted", true)
                    .assertion("cli_exit_zero", exitedZero)
                    .assertion("me_endpoint_succeeded", meEchoed)
                    .assertion("access_token_rotated_on_disk", accessRotated);
        });
    }
}
