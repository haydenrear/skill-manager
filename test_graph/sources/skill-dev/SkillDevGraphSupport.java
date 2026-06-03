import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class SkillDevGraphSupport {
    static final String SKILL = "skill-dev-edit-skill";
    static final String PLUGIN = "skill-dev-edit-plugin";
    static final String DOC = "skill-dev-edit-doc";
    static final String HARNESS = "skill-dev-edit-harness";
    static final String CONFLICT = "skill-dev-conflict-skill";

    private SkillDevGraphSupport() {}

    static Path repoRoot() {
        return Path.of(System.getProperty("user.dir")).resolve("..").normalize().toAbsolutePath();
    }

    static Path skillManager() {
        return repoRoot().resolve("skill-manager");
    }

    static Path skillDev(Path home) {
        return home.resolve("bin/cli/skill-dev");
    }

    static Map<String, String> env(String home, String claudeHome, String codexHome, String geminiHome, String gatewayUrl, String registryUrl) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        out.put("SKILL_MANAGER_HOME", home);
        out.put("SKILL_MANAGER_INSTALL_DIR", repoRoot().toString());
        out.put("CLAUDE_HOME", claudeHome);
        out.put("CODEX_HOME", codexHome);
        out.put("GEMINI_HOME", geminiHome);
        out.put("SKILL_MANAGER_GATEWAY_URL", gatewayUrl);
        out.put("SKILL_MANAGER_REGISTRY_URL", registryUrl);
        out.put("PATH", repoRoot() + System.getProperty("path.separator") + System.getenv("PATH"));
        return out;
    }

    static ProcessRecord run(NodeContext ctx, String label, Map<String, String> env, Path cwd, String... argv) {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(cwd.toFile());
        pb.environment().putAll(env);
        return Procs.run(ctx, label, pb);
    }

    static void initGit(Path dir) throws IOException, InterruptedException {
        runChecked(dir, "git", "init", "-b", "main", "--quiet");
        runChecked(dir, "git", "add", "-A");
        runChecked(dir, "git",
                "-c", "user.email=fixture@skillmanager.local",
                "-c", "user.name=fixture",
                "commit", "--quiet", "-m", "initial");
    }

    static void runChecked(Path cwd, String... argv) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(System.out);
        int rc = p.waitFor();
        if (rc != 0) throw new IOException(String.join(" ", argv) + " failed with rc=" + rc);
    }

    static String readCommand(Path cwd, String... argv) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) throw new IOException(String.join(" ", argv) + " failed with rc=" + rc + ": " + out);
        return out;
    }

    static void createProject(Path project) throws IOException, InterruptedException {
        Files.createDirectories(project);
        Files.writeString(project.resolve("README.md"), "skill-dev integration project\n");
        initGit(project);
    }

    static void createSkill(Path dir, String name, String body) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: Skill-dev fixture skill.
                skill-imports: []
                ---

                # %s

                %s
                """.formatted(name, name, body));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "Skill-dev fixture skill."
                """.formatted(name));
        initGit(dir);
    }

    static void createPlugin(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.createDirectories(dir.resolve("skills/plugin-impl"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"), """
                {
                  "name": "%s",
                  "version": "0.1.0",
                  "description": "Skill-dev fixture plugin."
                }
                """.formatted(PLUGIN));
        Files.writeString(dir.resolve("skill-manager-plugin.toml"), """
                [plugin]
                name = "%s"
                version = "0.1.0"
                description = "Skill-dev fixture plugin."
                """.formatted(PLUGIN));
        Files.writeString(dir.resolve("skills/plugin-impl/SKILL.md"), """
                ---
                name: plugin-impl
                description: Contained fixture skill.
                skill-imports: []
                ---

                # plugin-impl
                """);
        Files.writeString(dir.resolve("skills/plugin-impl/skill-manager.toml"), """
                [skill]
                name = "plugin-impl"
                version = "0.1.0"
                description = "Contained fixture skill."
                """);
        initGit(dir);
    }

    static void createDoc(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir.resolve("claude-md"));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [[sources]]
                id = "review"
                file = "claude-md/review.md"
                agents = ["claude", "codex"]

                [doc-repo]
                name = "%s"
                version = "0.1.0"
                description = "Skill-dev fixture doc-repo."
                """.formatted(DOC));
        Files.writeString(dir.resolve("claude-md/review.md"), "# Review\n\nInitial doc body.\n");
        initGit(dir);
    }

    static void createHarness(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("harness.toml"), """
                [harness]
                name = "%s"
                version = "0.1.0"
                description = "Skill-dev fixture harness."
                units = []
                docs = []
                """.formatted(HARNESS));
        Files.writeString(dir.resolve("README.md"), "# " + HARNESS + "\n");
        initGit(dir);
    }

    static JsonNode installedRecord(Path home, String name) throws IOException {
        return new ObjectMapper().readTree(home.resolve("installed").resolve(name + ".json").toFile());
    }

    static boolean kindIs(Path home, String name, String kind) throws IOException {
        return kind.equals(installedRecord(home, name).get("unitKind").asText());
    }

    static boolean runEditCycle(NodeContext ctx, Map<String, String> env, Path project, Path home,
                                String name, Path storeFile, String marker, List<ProcessRecord> procs)
            throws IOException {
        Path skillDev = skillDev(home);
        ProcessRecord open = run(ctx, name + "-open", env, project, skillDev.toString(), "open", name);
        procs.add(open);
        Path worktree = project.resolve("skill-dev").resolve(name);
        Path worktreeFile = worktree.resolve(home.relativize(storeFile).subpath(2, home.relativize(storeFile).getNameCount()));
        Files.writeString(worktreeFile, Files.readString(worktreeFile) + "\n" + marker + "\n");
        ProcessRecord commit = run(ctx, name + "-commit", env, worktree,
                "git", "-c", "user.email=skill-dev@skillmanager.local",
                "-c", "user.name=skill-dev", "commit", "-am", "edit " + name);
        procs.add(commit);
        ProcessRecord sync = run(ctx, name + "-sync", env, project, skillDev.toString(), "sync", name);
        procs.add(sync);
        ProcessRecord close = run(ctx, name + "-close", env, project, skillDev.toString(), "close", name);
        procs.add(close);
        return open.exitCode() == 0
                && commit.exitCode() == 0
                && sync.exitCode() == 0
                && close.exitCode() == 0
                && !Files.exists(worktree)
                && Files.readString(storeFile).contains(marker);
    }
}
