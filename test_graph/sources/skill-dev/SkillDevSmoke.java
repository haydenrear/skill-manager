///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * End-to-end smoke test for the skill-dev-skill package and its CLI.
 *
 * <p>Deliberately does not depend on {@code env.prepared}: this node owns a
 * private {@code SKILL_MANAGER_HOME}, {@code CLAUDE_HOME}, {@code CODEX_HOME},
 * project checkout, and git-backed fixture skill. That keeps the graph from
 * reading or mutating the developer's real skill-manager install.
 */
public class SkillDevSmoke {
    static final NodeSpec SPEC = NodeSpec.of("skill-dev.smoke")
            .kind(NodeSpec.Kind.ACTION)
            .tags("skill-dev", "skill-script", "worktree")
            .sideEffects("fs:write", "proc:spawn")
            .timeout("180s")
            .output("home", "string")
            .output("projectDir", "string")
            .output("worktreeDir", "string");

    private static final String FIXTURE_NAME = "skill-dev-smoke-fixture";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            List<ProcessRecord> procs = new ArrayList<>();
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize().toAbsolutePath();
            Path sm = repoRoot.resolve("skill-manager");
            Path skillDevSkill = repoRoot.resolve("skill-dev-skill");

            Path root = Files.createTempDirectory("skill-dev-smoke-");
            Path home = root.resolve("skill-manager-home");
            Path agentHome = root.resolve("agent-home");
            Path codexHome = agentHome.resolve(".codex");
            Path geminiHome = agentHome.resolve(".gemini");
            Path project = root.resolve("project");
            Path fixture = root.resolve("fixture-skill");
            Files.createDirectories(home);
            Files.createDirectories(agentHome);
            Files.createDirectories(codexHome);
            Files.createDirectories(geminiHome);
            Files.createDirectories(project);
            Files.createDirectories(fixture);
            writePermissivePolicy(home);

            Map<String, String> env = Map.of(
                    "SKILL_MANAGER_HOME", home.toString(),
                    "SKILL_MANAGER_INSTALL_DIR", repoRoot.toString(),
                    "CLAUDE_HOME", agentHome.toString(),
                    "CODEX_HOME", codexHome.toString(),
                    "GEMINI_HOME", geminiHome.toString(),
                    "PATH", repoRoot + System.getProperty("path.separator") + System.getenv("PATH")
            );

            initProject(project);
            String fixtureHead = initFixtureSkill(fixture);

            ProcessRecord installDev = run(ctx, procs, "install-skill-dev", env, root,
                    sm.toString(), "install", "file://" + skillDevSkill, "--yes");
            Path skillDev = home.resolve("bin/cli/skill-dev");
            boolean skillDevInstalled = installDev.exitCode() == 0 && Files.isExecutable(skillDev);

            ProcessRecord installFixture = run(ctx, procs, "install-fixture", env, root,
                    sm.toString(), "install", "file:" + fixture, "--yes");
            Path installedJson = home.resolve("installed").resolve(FIXTURE_NAME + ".json");
            JsonNode sourceRecord = Files.isRegularFile(installedJson)
                    ? new ObjectMapper().readTree(installedJson.toFile())
                    : null;
            boolean fixtureInstalled = installFixture.exitCode() == 0
                    && Files.isDirectory(home.resolve("skills").resolve(FIXTURE_NAME).resolve("latest").resolve(".git"))
                    && sourceRecord != null
                    && "GIT".equals(textOrNull(sourceRecord, "kind"))
                    && fixtureHead.equals(textOrNull(sourceRecord, "gitHash"));

            ProcessRecord open = run(ctx, procs, "skill-dev-open", env, project,
                    skillDev.toString(), "open", FIXTURE_NAME);
            Path worktree = project.resolve("skill-dev").resolve(FIXTURE_NAME);
            boolean openOk = open.exitCode() == 0
                    && Files.exists(worktree.resolve(".git"))
                    && Files.readString(project.resolve(".gitignore")).contains("skill-dev/");

            Files.writeString(worktree.resolve("SKILL.md"),
                    Files.readString(worktree.resolve("SKILL.md")) + "\nWorktree edit applied by skill-dev smoke.\n");
            ProcessRecord commit = run(ctx, procs, "worktree-commit", env, worktree,
                    "git", "-c", "user.email=skill-dev-smoke@skillmanager.local",
                    "-c", "user.name=skill-dev-smoke",
                    "commit", "-am", "skill-dev smoke edit");
            boolean commitOk = commit.exitCode() == 0;

            ProcessRecord status = run(ctx, procs, "skill-dev-status", env, project,
                    skillDev.toString(), "status", FIXTURE_NAME);
            boolean statusOk = status.exitCode() == 0;

            ProcessRecord sync = run(ctx, procs, "skill-dev-sync", env, project,
                    skillDev.toString(), "sync", FIXTURE_NAME);
            Path installedSkillMd = home.resolve("skills").resolve(FIXTURE_NAME).resolve("latest").resolve("SKILL.md");
            boolean syncOk = sync.exitCode() == 0
                    && Files.readString(installedSkillMd).contains("Worktree edit applied by skill-dev smoke.");

            ProcessRecord close = run(ctx, procs, "skill-dev-close", env, project,
                    skillDev.toString(), "close", FIXTURE_NAME);
            boolean closeOk = close.exitCode() == 0 && !Files.exists(worktree);

            boolean noGlobalHome = home.startsWith(root) && agentHome.startsWith(root) && codexHome.startsWith(root);
            boolean pass = skillDevInstalled && fixtureInstalled && openOk && commitOk
                    && statusOk && syncOk && closeOk && noGlobalHome;

            NodeResult result = pass
                    ? NodeResult.pass("skill-dev.smoke")
                    : NodeResult.fail("skill-dev.smoke",
                            "installedCli=" + skillDevInstalled
                                    + " fixtureInstalled=" + fixtureInstalled
                                    + " open=" + openOk
                                    + " commit=" + commitOk
                                    + " status=" + statusOk
                                    + " sync=" + syncOk
                                    + " close=" + closeOk
                                    + " isolated=" + noGlobalHome);
            for (ProcessRecord proc : procs) result.process(proc);
            return result
                    .assertion("private_skill_manager_home", noGlobalHome)
                    .assertion("skill_dev_cli_installed", skillDevInstalled)
                    .assertion("fixture_installed_as_git", fixtureInstalled)
                    .assertion("open_created_ignored_worktree", openOk)
                    .assertion("git_passthrough_status_ok", statusOk)
                    .assertion("sync_applied_worktree_edit", syncOk)
                    .assertion("close_removed_worktree", closeOk)
                    .publish("home", home.toString())
                    .publish("projectDir", project.toString())
                    .publish("worktreeDir", worktree.toString());
        });
    }

    private static ProcessRecord run(
            com.hayden.testgraphsdk.sdk.NodeContext ctx,
            List<ProcessRecord> procs,
            String label,
            Map<String, String> env,
            Path cwd,
            String... argv) {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(cwd.toFile());
        pb.environment().putAll(env);
        ProcessRecord record = Procs.run(ctx, label, pb);
        procs.add(record);
        return record;
    }

    private static void initProject(Path project) throws IOException, InterruptedException {
        runChecked(project, "git", "init", "-b", "main", "--quiet");
        Files.writeString(project.resolve("README.md"), "skill-dev smoke project\n");
        runChecked(project, "git", "add", "README.md");
        runChecked(project, "git",
                "-c", "user.email=project@skillmanager.local",
                "-c", "user.name=project",
                "commit", "--quiet", "-m", "initial project");
    }

    private static String initFixtureSkill(Path fixture) throws IOException, InterruptedException {
        Files.writeString(fixture.resolve("SKILL.md"),
                "---\n"
                        + "name: " + FIXTURE_NAME + "\n"
                        + "description: Fixture used by the skill-dev-smoke graph.\n"
                        + "skill-imports: []\n"
                        + "---\n\n"
                        + "# " + FIXTURE_NAME + "\n"
                        + "Initial content.\n");
        Files.writeString(fixture.resolve("skill-manager.toml"),
                "[skill]\n"
                        + "name = \"" + FIXTURE_NAME + "\"\n"
                        + "version = \"0.1.0\"\n"
                        + "description = \"Fixture used by the skill-dev-smoke graph.\"\n");
        runChecked(fixture, "git", "init", "-b", "main", "--quiet");
        runChecked(fixture, "git", "add", "-A");
        runChecked(fixture, "git",
                "-c", "user.email=fixture@skillmanager.local",
                "-c", "user.name=fixture",
                "commit", "--quiet", "-m", "initial fixture");
        return readCommand(fixture, "git", "rev-parse", "HEAD").trim();
    }

    private static void writePermissivePolicy(Path home) throws IOException {
        Files.writeString(home.resolve("policy.toml"), """
                require_confirmation = false
                [install]
                require_confirmation_for_hooks = false
                require_confirmation_for_mcp = false
                require_confirmation_for_cli_deps = false
                require_confirmation_for_executable_commands = false
                """);
    }

    private static void runChecked(Path cwd, String... argv) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(System.out);
        int rc = p.waitFor();
        if (rc != 0) throw new IOException(String.join(" ", argv) + " failed with rc=" + rc);
    }

    private static String readCommand(Path cwd, String... argv) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) throw new IOException(String.join(" ", argv) + " failed with rc=" + rc + ": " + out);
        return out;
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
