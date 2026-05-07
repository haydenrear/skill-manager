import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Stamps the {@code test_graph/fixtures/echo-skill-template/} directory into
 * a temp location, substituting the token placeholders so the resulting dir
 * can be handed to {@code skill-manager install file:<dir>}.
 *
 * <p>The template is a real checked-in directory — unlike inline-materializing
 * the toml at test runtime, a checked-in fixture is visible in the repo,
 * exerciseable in isolation, and diff-friendly.
 */
public final class TgFixture {

    private TgFixture() {}

    public static Path stampEchoSkill(
            Path templateDir,
            Path destRoot,
            String skillName,
            String serverId,
            String scope,
            String mcpUrl) throws IOException {
        return stampTemplate(templateDir, destRoot, skillName, Map.of(
                "__SKILL_NAME__", skillName,
                "__SERVER_ID__", serverId,
                "__SCOPE__", scope,
                "__MCP_URL__", mcpUrl
        ));
    }

    /**
     * Stamp the {@code umbrella-plugin-template} fixture. Plugin-level
     * deps (CLI + MCP) live in {@code skill-manager-plugin.toml}; the
     * single contained skill at {@code skills/inner-impl/} declares its
     * own (distinct) CLI + MCP deps. Both ends register at install
     * time, which is what the plugin-smoke nodes assert.
     */
    public static Path stampUmbrellaPlugin(
            Path templateDir,
            Path destRoot,
            String pluginName,
            String pluginServerId,
            String skillServerId,
            String scope,
            String mcpUrl) throws IOException {
        return stampTemplate(templateDir, destRoot, pluginName, Map.of(
                "__PLUGIN_NAME__", pluginName,
                "__PLUGIN_SERVER_ID__", pluginServerId,
                "__SKILL_SERVER_ID__", skillServerId,
                "__SCOPE__", scope,
                "__MCP_URL__", mcpUrl
        ));
    }

    /**
     * Stamp the {@code partner-skill-template} fixture. The partner
     * skill claims the same MCP server name as the umbrella plugin's
     * plugin-level dep, so uninstalling the plugin leaves the server
     * registered (skill claim survives the orphan check).
     */
    public static Path stampPartnerSkill(
            Path templateDir,
            Path destRoot,
            String skillName,
            String sharedServerId,
            String scope,
            String mcpUrl) throws IOException {
        return stampTemplate(templateDir, destRoot, skillName, Map.of(
                "__SKILL_NAME__", skillName,
                "__SHARED_SERVER_ID__", sharedServerId,
                "__SCOPE__", scope,
                "__MCP_URL__", mcpUrl
        ));
    }

    /**
     * Stamp the {@code echo-skill-stdio-template} template, which pins a
     * shell-load stdio MCP server invoking the python fixture. The two
     * extra tokens — venv python and fixture script path — are absolute
     * paths the gateway will exec.
     */
    public static Path stampEchoSkillStdio(
            Path templateDir,
            Path destRoot,
            String skillName,
            String serverId,
            String scope,
            String venvPython,
            String fixturePath) throws IOException {
        return stampTemplate(templateDir, destRoot, skillName, Map.of(
                "__SKILL_NAME__", skillName,
                "__SERVER_ID__", serverId,
                "__SCOPE__", scope,
                "__VENV_PYTHON__", venvPython,
                "__FIXTURE_PATH__", fixturePath
        ));
    }

    private static Path stampTemplate(
            Path templateDir,
            Path destRoot,
            String skillName,
            Map<String, String> tokens) throws IOException {
        Path dest = destRoot.resolve(skillName);
        if (Files.exists(dest)) {
            deleteRecursive(dest);
        }
        Files.createDirectories(dest);

        try (Stream<Path> s = Files.walk(templateDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path rel = templateDir.relativize(p);
                Path out = dest.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(out);
                    continue;
                }
                String body = Files.readString(p);
                for (var e : tokens.entrySet()) {
                    body = body.replace(e.getKey(), e.getValue());
                }
                Files.createDirectories(out.getParent());
                Files.writeString(out, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        }
        return dest;
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }
}
