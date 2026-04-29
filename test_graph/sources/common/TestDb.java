import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Thin JDBC helper for test-graph nodes that want to query the registry's
 * Postgres database directly.
 *
 * <p>The contract is: whichever node/source imports a server JPA entity
 * (e.g. {@code ImpressionRow}) picks up the authoritative table + column
 * names. This helper intentionally uses plain SQL so it stays zero-dep;
 * the entity classes document (and own) the schema.
 *
 * <p>All methods target the database at {@code SKILL_REGISTRY_DB_URL}
 * (same env var the server respects), defaulting to the compose-default
 * test DB URL.
 */
public final class TestDb implements AutoCloseable {

    public static final String DEFAULT_URL =
            "jdbc:postgresql://localhost:5432/skill_registry_test";

    private final Connection conn;

    private TestDb(Connection conn) { this.conn = conn; }

    public static TestDb open() throws SQLException {
        String url = firstNonBlank(System.getenv("SKILL_REGISTRY_DB_URL"), DEFAULT_URL);
        String user = firstNonBlank(System.getenv("SKILL_REGISTRY_DB_USER"), "postgres");
        String pass = firstNonBlank(System.getenv("SKILL_REGISTRY_DB_PASSWORD"), "postgres");
        return new TestDb(DriverManager.getConnection(url, user, pass));
    }

    public long countImpressions(String campaignId) throws SQLException {
        return countWhere("impressions", "campaign_id", campaignId);
    }

    public long countConversions(String campaignId) throws SQLException {
        return countWhere("conversions", "campaign_id", campaignId);
    }

    public long countUsers() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM users");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** Owner of a skill name, or null if the name isn't claimed. */
    public String ownerOf(String skillName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT owner_username FROM skill_names WHERE name = ?")) {
            ps.setString(1, skillName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Publisher of a specific {@code name@version}, or null if not present. */
    public String publisherOf(String skillName, String version) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT published_by FROM skill_versions WHERE name = ? AND version = ?")) {
            ps.setString(1, skillName);
            ps.setString(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void truncateAll() throws SQLException {
        // Drop in FK-safe order; dev/test-only, so the brute RESTART IDENTITY CASCADE is fine.
        try (PreparedStatement ps = conn.prepareStatement(
                "TRUNCATE TABLE impressions, conversions, skill_versions, skill_names, users "
                        + "RESTART IDENTITY CASCADE")) {
            ps.executeUpdate();
        } catch (SQLException first) {
            // Tables may not exist yet on first run (registry hasn't booted).
            // Swallow only the "relation does not exist" class so real errors surface.
            if (!"42P01".equals(first.getSQLState())) throw first;
        }

        // Hibernate's `ddl-auto=update` only ADDS columns — it never relaxes
        // NOT NULL constraints. The github-publish path inserts rows with
        // sha256/size_bytes null, but a database left over from a pre-refactor
        // server boot still has those columns NOT NULL. Drop the constraints
        // explicitly here; ALTER ... DROP NOT NULL is idempotent.
        for (String alter : new String[]{
                "ALTER TABLE skill_versions ALTER COLUMN sha256 DROP NOT NULL",
                "ALTER TABLE skill_versions ALTER COLUMN size_bytes DROP NOT NULL",
        }) {
            try (PreparedStatement ps = conn.prepareStatement(alter)) {
                ps.executeUpdate();
            } catch (SQLException e) {
                // 42P01 = relation doesn't exist yet (first ever run).
                // 42703 = column doesn't exist (table predates the github columns; benign).
                if (!"42P01".equals(e.getSQLState()) && !"42703".equals(e.getSQLState())) throw e;
            }
        }
    }

    private long countWhere(String table, String column, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** Escape hatch for one-off queries that don't justify a dedicated method. */
    public Connection connection() { return conn; }

    /** Most-recent unused password-reset token for {@code username}, or null. */
    public String latestPasswordResetToken(String username) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT token FROM password_reset_tokens "
                        + "WHERE username = ? AND used_at IS NULL "
                        + "ORDER BY expires_at DESC LIMIT 1")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Override public void close() throws SQLException { conn.close(); }

    private static String firstNonBlank(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }
}
