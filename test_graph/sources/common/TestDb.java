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

    public void truncateAll() throws SQLException {
        // Drop in FK-safe order; dev/test-only, so the brute RESTART IDENTITY CASCADE is fine.
        try (PreparedStatement ps = conn.prepareStatement(
                "TRUNCATE TABLE impressions, conversions, users RESTART IDENTITY CASCADE")) {
            ps.executeUpdate();
        } catch (SQLException first) {
            // Tables may not exist yet on first run (registry hasn't booted).
            // Swallow only the "relation does not exist" class so real errors surface.
            if (!"42P01".equals(first.getSQLState())) throw first;
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

    @Override public void close() throws SQLException { conn.close(); }

    private static String firstNonBlank(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }
}
