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
        Path dest = destRoot.resolve(skillName);
        if (Files.exists(dest)) {
            deleteRecursive(dest);
        }
        Files.createDirectories(dest);

        Map<String, String> tokens = Map.of(
                "__SKILL_NAME__", skillName,
                "__SERVER_ID__", serverId,
                "__SCOPE__", scope,
                "__MCP_URL__", mcpUrl
        );

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
