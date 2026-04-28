///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../common/TestDb.java
//DEPS org.postgresql:postgresql:42.7.4

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Runs {@code skill-manager reset-password --email ...} and asserts a
 * live row lands in {@code password_reset_tokens}. Publishes the raw
 * token so the next node can POST it to
 * {@code /auth/password-reset/confirm}.
 *
 * <p>Reading the token straight from the DB keeps the test graph
 * email-free: the real email still gets logged server-side (or sent
 * via SMTP if {@code CLOUD_COM_LLC_PASSWORD} is set), but the test
 * doesn't need to parse it.
 */
public class ResetRequested {
    static final NodeSpec SPEC = NodeSpec.of("reset.requested")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("initial.login")
            .tags("auth", "password-reset")
            .timeout("30s")
            .output("resetToken", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            String username = ctx.get("account.created", "username").orElse(null);
            String email = ctx.get("account.created", "email").orElse(null);
            if (home == null || registryUrl == null || username == null || email == null) {
                return NodeResult.fail("reset.requested", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "reset-password",
                    "--email", email,
                    "--registry", registryUrl);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "reset-password", pb);
            int rc = proc.exitCode();
            if (rc != 0) {
                return NodeResult.fail("reset.requested", "reset-password exited " + rc)
                        .process(proc)
                        .assertion("cli_exit_zero", false);
            }

            String token;
            boolean futureExpiry = false;
            try (TestDb db = TestDb.open()) {
                token = db.latestPasswordResetToken(username);
                if (token != null) {
                    try (PreparedStatement ps = db.connection().prepareStatement(
                            "SELECT expires_at FROM password_reset_tokens WHERE token = ?")) {
                        ps.setString(1, token);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                Timestamp exp = rs.getTimestamp(1);
                                futureExpiry = exp != null && exp.toInstant().isAfter(Instant.now());
                            }
                        }
                    }
                }
            }
            if (token == null) {
                return NodeResult.fail("reset.requested", "no unused token row for " + username)
                        .assertion("cli_exit_zero", true)
                        .assertion("token_row_persisted", false);
            }
            return NodeResult.pass("reset.requested")
                    .process(proc)
                    .assertion("cli_exit_zero", true)
                    .assertion("token_row_persisted", true)
                    .assertion("token_not_expired", futureExpiry)
                    .publish("resetToken", token);
        });
    }
}
