//SOURCES SmEnv.java

import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

final class MarkdownImportFixture {
    private MarkdownImportFixture() {}

    /**
     * Install a fixture unit into the sandbox home.
     *
     * <h2>Why {@code HOME} and {@code JAVA_TOOL_OPTIONS} are set too</h2>
     *
     * <p>The five variables above only cover the paths that <em>look up</em> an
     * agent home. Every fallback for "no agent variable was set" ends at the
     * user's home directory, and on macOS the JVM derives {@code user.home}
     * from the OS and IGNORES {@code $HOME}. So a child that is handed only the
     * five variables is still one unanticipated {@code user.home} read away
     * from writing into the operator's real {@code ~/.claude}, {@code ~/.codex}
     * and {@code ~/.gemini} — that is issue #18, which left dangling symlinks
     * into deleted temp dirs in a live agent's skill list.
     *
     * <p>{@code HOME} closes it for anything reading the environment;
     * {@code JAVA_TOOL_OPTIONS=-Duser.home=...} closes it for anything reading
     * the JVM property, because every JVM honours that variable.
     *
     * <p>The five variables themselves come from {@link SmEnv} and are no longer
     * written here. This method used to spell them out, and it is the copy that
     * <em>still leaked</em> — #18's second comment measured
     * {@code dm-target-skill} reaching all three real agent homes from this
     * install while the same install's MCP writes landed in the sandbox. A
     * fourth spelling is not the fix; one is.
     */
    static ProcessRecord install(NodeContext ctx, Path sm, Path repoRoot, String home,
                                 String claudeHome, String codexHome, String geminiHome,
                                 Path unitDir, String label) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                sm.toString(), "install", "file://" + unitDir.toAbsolutePath(), "--yes");
        SmEnv.Sandbox sandbox = claudeHome != null && codexHome != null && geminiHome != null
                ? SmEnv.sandbox(claudeHome, codexHome, geminiHome)
                : SmEnv.sandboxOf(ctx, home);
        SmEnv.apply(pb, home, repoRoot.toString(), sandbox);
        SmEnv.alsoRedirectPosixHome(pb, home);
        return Procs.run(ctx, label, pb);
    }

    static Path skill(Path root, String name, String imports) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), frontmatter(
                "name: " + name + "\n"
                        + "description: Cross-kind markdown import fixture.\n"
                        + imports)
                + "\n# " + name + "\n");
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "Cross-kind markdown import fixture."
                """.formatted(name));
        return dir;
    }

    static Path plugin(Path root, String name, String refPath) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Path parent = Path.of(refPath).getParent();
        if (parent != null) Files.createDirectories(dir.resolve(parent));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"), """
                {"name":"%s","version":"0.1.0","description":"Markdown import fixture plugin."}
                """.formatted(name));
        Files.writeString(dir.resolve("skill-manager-plugin.toml"), """
                [plugin]
                name = "%s"
                version = "0.1.0"
                description = "Markdown import fixture plugin."
                """.formatted(name));
        Files.writeString(dir.resolve(refPath), "# plugin reference\n");
        return dir;
    }

    static Path pluginWithReadme(Path root, String name, String imports) throws Exception {
        Path dir = plugin(root, name, "docs/reference.md");
        Files.writeString(dir.resolve("README.md"), frontmatter(imports) + "\n# " + name + "\n");
        return dir;
    }

    static Path doc(Path root, String name, String refPath) throws Exception {
        Path dir = root.resolve(name);
        Path parent = Path.of(refPath).getParent();
        if (parent != null) Files.createDirectories(dir.resolve(parent));
        Files.writeString(dir.resolve(refPath), "# doc reference\n");
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [doc-repo]
                name = "%s"
                version = "0.1.0"
                description = "Markdown import fixture doc-repo."

                [[sources]]
                id = "reference"
                file = "%s"
                """.formatted(name, refPath));
        return dir;
    }

    static Path docWithSourceImports(Path root, String name, String imports) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir.resolve("claude-md"));
        Files.writeString(dir.resolve("claude-md/reference.md"),
                frontmatter(imports) + "\n# " + name + "\n");
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [doc-repo]
                name = "%s"
                version = "0.1.0"
                description = "Markdown import fixture doc-repo."

                [[sources]]
                id = "reference"
                file = "claude-md/reference.md"
                """.formatted(name));
        return dir;
    }

    static Path harness(Path root, String name, String refPath) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("harness.toml"), """
                [harness]
                name = "%s"
                version = "0.1.0"
                description = "Markdown import fixture harness."
                """.formatted(name));
        Files.writeString(dir.resolve(refPath), "# harness reference\n");
        return dir;
    }

    static String imports(String... entries) {
        return "skill-imports:\n" + String.join("", entries);
    }

    static String entry(String unit, String path, String reason) {
        return "  - unit: " + unit + "\n"
                + "    path: " + path + "\n"
                + "    reason: " + reason + "\n";
    }

    static String logBody(NodeContext ctx, ProcessRecord proc) {
        if (proc.logPath() == null) return "";
        try {
            return Files.readString(ctx.reportDir().resolve(proc.logPath()));
        } catch (Exception e) {
            return "";
        }
    }

    private static String frontmatter(String body) {
        return "---\n" + body + "---\n";
    }
}
