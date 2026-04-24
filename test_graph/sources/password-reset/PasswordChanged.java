///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../common/TestDb.java
//DEPS org.postgresql:postgresql:42.7.4

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

/**
 * Consumes the reset token by POSTing to
 * {@code /auth/password-reset/confirm} with the token (from
 * {@code reset.requested}) and a new password, which it republishes
 * so {@code final.login} can sign in with it.
 *
 * <p>Double-checks the DB afterwards: the token's {@code used_at}
 * should be stamped, and the user's {@code password_hash} should have
 * changed from what {@code account.created} left behind — if either
 * is wrong the service-layer contract has regressed.
 */
public class PasswordChanged {
    static final String NEW_PASSWORD = "graph-user-NEW-password-2026";

    static final NodeSpec SPEC = NodeSpec.of("password.changed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("reset.requested")
            .tags("auth", "password-reset")
            .timeout("30s")
            .output("newPassword", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            String username = ctx.get("account.created", "username").orElse(null);
            String token = ctx.get("reset.requested", "resetToken").orElse(null);
            if (registryUrl == null || username == null || token == null) {
                return NodeResult.fail("password.changed", "missing upstream context");
            }

            String hashBefore;
            try (TestDb db = TestDb.open();
                 PreparedStatement ps = db.connection().prepareStatement(
                         "SELECT password_hash FROM users WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    hashBefore = rs.next() ? rs.getString(1) : null;
                }
            }

            String form = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(NEW_PASSWORD, StandardCharsets.UTF_8);
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(registryUrl + "/auth/password-reset/confirm"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean confirm200 = resp.statusCode() == 200;

            Timestamp usedAt;
            String hashAfter;
            try (TestDb db = TestDb.open()) {
                try (PreparedStatement ps = db.connection().prepareStatement(
                        "SELECT used_at FROM password_reset_tokens WHERE token = ?")) {
                    ps.setString(1, token);
                    try (ResultSet rs = ps.executeQuery()) {
                        usedAt = rs.next() ? rs.getTimestamp(1) : null;
                    }
                }
                try (PreparedStatement ps = db.connection().prepareStatement(
                        "SELECT password_hash FROM users WHERE username = ?")) {
                    ps.setString(1, username);
                    try (ResultSet rs = ps.executeQuery()) {
                        hashAfter = rs.next() ? rs.getString(1) : null;
                    }
                }
            }
            boolean tokenStampedUsed = usedAt != null;
            boolean hashChanged = hashBefore != null && hashAfter != null && !hashBefore.equals(hashAfter);

            boolean ok = confirm200 && tokenStampedUsed && hashChanged;
            return (ok
                    ? NodeResult.pass("password.changed")
                    : NodeResult.fail("password.changed",
                            "confirm=" + resp.statusCode() + " usedAt=" + usedAt
                                    + " hashChanged=" + hashChanged))
                    .assertion("confirm_returned_200", confirm200)
                    .assertion("token_marked_used", tokenStampedUsed)
                    .assertion("password_hash_changed", hashChanged)
                    .publish("newPassword", NEW_PASSWORD);
        });
    }
}
