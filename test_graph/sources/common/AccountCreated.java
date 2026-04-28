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

/**
 * Drives {@code skill-manager create-account} and confirms the resulting
 * {@code users} row has a BCrypt password hash. Publishes the username +
 * password so {@code browser.authorized} can log in as this user without
 * baking a separate fixture.
 */
public class AccountCreated {
    static final String USERNAME = "graph-user";
    static final String EMAIL = "graph-user@skill-manager.test";
    static final String PASSWORD = "graph-user-password-2026";
    static final String DISPLAY = "Graph Test User";

    static final NodeSpec SPEC = NodeSpec.of("account.created")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("registry.up")
            .tags("auth", "signup")
            .timeout("30s")
            .output("username", "string")
            .output("password", "string")
            .output("email", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("account.created", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "create-account",
                    "--username", USERNAME,
                    "--email", EMAIL,
                    "--password", PASSWORD,
                    "--display-name", DISPLAY,
                    "--registry", registryUrl);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "create-account", pb);
            int rc = proc.exitCode();
            if (rc != 0) {
                return NodeResult.fail("account.created", "create-account exited " + rc)
                        .process(proc)
                        .assertion("cli_exit_zero", false).metric("exitCode", rc);
            }

            boolean rowPresent;
            boolean hashLooksBcrypt;
            String email;
            try (TestDb db = TestDb.open();
                 PreparedStatement ps = db.connection().prepareStatement(
                         "SELECT email, password_hash FROM users WHERE username = ?")) {
                ps.setString(1, USERNAME);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return NodeResult.fail("account.created", "no users row for " + USERNAME)
                                .assertion("row_exists", false);
                    }
                    email = rs.getString(1);
                    String hash = rs.getString(2);
                    rowPresent = true;
                    hashLooksBcrypt = hash != null && hash.startsWith("$2") && hash.length() == 60;
                }
            }

            return NodeResult.pass("account.created")
                    .process(proc)
                    .assertion("row_exists", rowPresent)
                    .assertion("email_persisted", EMAIL.equals(email))
                    .assertion("password_hash_is_bcrypt", hashLooksBcrypt)
                    .publish("username", USERNAME)
                    .publish("password", PASSWORD)
                    .publish("email", EMAIL);
        });
    }
}
