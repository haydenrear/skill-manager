///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../common/BrowserLoginFlow.java
//DEPS org.seleniumhq.selenium:selenium-java:4.23.0
//DEPS org.seleniumhq.selenium:selenium-chrome-driver:4.23.0
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Exercises the real OAuth2 refresh flow end-to-end under a 3-second
 * access-token TTL (see {@code short.access.token.ttl}):
 *
 * <ol>
 *   <li>log in via the browser flow — get a token valid for only 3s</li>
 *   <li>sleep past the expiry</li>
 *   <li>run an authed CLI command ({@code skill-manager login show},
 *       which hits {@code /auth/me})</li>
 *   <li>assert the CLI succeeded without asking for re-login — meaning
 *       the refresh-on-401 path inside {@link
 *       dev.skillmanager.registry.RegistryClient} kicked in silently —
 *       and that both the access and refresh tokens in {@code
 *       auth.token} were rotated on disk</li>
 * </ol>
 *
 * <p>Proves the "real" refresh path that the user would actually hit:
 * real JWT, real expiry, real refresh_token grant. Complements
 * {@code refresh.honored} (which fakes expiry by corrupting the file)
 * by catching regressions specific to time-based expiry.
 */
public class RefreshOnExpiry {
    static final NodeSpec SPEC = NodeSpec.of("refresh.on.expiry")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("account.created", "selenium.ready", "short.access.token.ttl")
            .tags("auth", "refresh")
            .sideEffects("proc:spawn", "net:local")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            String username = ctx.get("account.created", "username").orElse(null);
            String password = ctx.get("account.created", "password").orElse(null);
            String ttlStr = ctx.get("short.access.token.ttl", "seconds").orElse("3");
            if (home == null || registryUrl == null || username == null || password == null) {
                return NodeResult.fail("refresh.on.expiry", "missing upstream context");
            }
            int ttlSeconds = Integer.parseInt(ttlStr);

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path tokenFile = Path.of(home, "auth.token");

            // (1) Browser-login under the short-TTL server.
            BrowserLoginFlow.Result login = BrowserLoginFlow.run(
                    sm.toString(), home, registryUrl, repoRoot.toString(), username, password);
            if (!login.fullySucceeded()) {
                return NodeResult.fail("refresh.on.expiry",
                        "initial browser login failed under short-TTL server\ncli output:\n"
                                + String.join("\n", login.cliOutput))
                        .assertion("short_ttl_login_ok", false);
            }

            ObjectMapper json = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> stored = json.readValue(Files.readString(tokenFile), Map.class);
            String originalAccess = (String) stored.get("access_token");
            String originalRefresh = (String) stored.get("refresh_token");
            boolean accessIsJwt = looksLikeJwt(originalAccess);

            // (2) Sleep past the TTL.
            Thread.sleep((ttlSeconds + 2) * 1000L);

            // (3) Run an authed command; refresh should happen inside the
            // CLI and the call should succeed without a login prompt.
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "login", "show",
                    "--registry", registryUrl)
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int rc = proc.waitFor();
            boolean exitOk = rc == 0;
            boolean meOk = output.contains("username: " + username);

            // (4) Verify tokens were rotated on disk.
            @SuppressWarnings("unchecked")
            Map<String, Object> after = json.readValue(Files.readString(tokenFile), Map.class);
            String newAccess = (String) after.get("access_token");
            String newRefresh = (String) after.get("refresh_token");
            boolean accessRotated = newAccess != null && !newAccess.equals(originalAccess) && looksLikeJwt(newAccess);
            boolean refreshRotated = newRefresh != null && !newRefresh.equals(originalRefresh);

            boolean ok = accessIsJwt && exitOk && meOk && accessRotated && refreshRotated;
            return (ok
                    ? NodeResult.pass("refresh.on.expiry")
                    : NodeResult.fail("refresh.on.expiry",
                            "exitOk=" + exitOk + " meOk=" + meOk + " accessRotated=" + accessRotated
                                    + " refreshRotated=" + refreshRotated + "\nstdout:\n" + output))
                    .assertion("original_token_is_valid_jwt", accessIsJwt)
                    .assertion("authed_command_succeeded", exitOk && meOk)
                    .assertion("access_token_rotated", accessRotated)
                    .assertion("refresh_token_rotated", refreshRotated);
        });
    }

    /** Inline JWT shape check so the node stays //DEPS-free on the CLI helper. */
    private static boolean looksLikeJwt(String token) {
        if (token == null) return false;
        String[] p = token.split("\\.");
        if (p.length != 3) return false;
        try {
            java.util.Base64.Decoder d = java.util.Base64.getUrlDecoder();
            String h = new String(d.decode(p[0]), java.nio.charset.StandardCharsets.UTF_8);
            String pl = new String(d.decode(p[1]), java.nio.charset.StandardCharsets.UTF_8);
            return h.startsWith("{") && pl.startsWith("{");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
