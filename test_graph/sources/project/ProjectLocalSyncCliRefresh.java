///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Project-level regression for local sync. A project resolves a local skill
 * with no CLI deps, the local source later adds a skill-script CLI, and
 * `skill-manager sync <name> --from <dir>` must refresh both the parent home
 * and the claiming project child home.
 */
public class ProjectLocalSyncCliRefresh {
    static final NodeSpec SPEC = NodeSpec.of("project.local.sync.cli.refresh")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("project", "sync", "cli", "local")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Env env = Env.from(ctx);
            if (env == null) {
                return NodeResult.fail("project.local.sync.cli.refresh", "missing env.prepared context");
            }
            try {
                env = env.isolated("project-local-sync-home");
            } catch (Exception e) {
                return NodeResult.fail("project.local.sync.cli.refresh",
                        "could not create isolated home: " + e.getMessage());
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            Path projectDir;
            Path skillDir;
            try {
                projectDir = Files.createTempDirectory("sm-project-local-sync-cli-");
                skillDir = scaffoldSkill(projectDir.resolve("units"), "tg-project-local-cli-skill");
                Files.writeString(projectDir.resolve("skill-project.toml"), """
                        [project]
                        name = "tg-project-local-sync-cli"

                        [skills.demo]
                        source = "%s"
                        """.formatted(skillDir));
            } catch (Exception e) {
                return NodeResult.fail("project.local.sync.cli.refresh",
                        "fixture setup failed: " + e.getMessage());
            }

            ProcessRecord resolve = run(ctx, "resolve", env, repoRoot, sm,
                    "project", "resolve", "--skip-gateway", "--project-dir", projectDir.toString());
            Path parentCli = Path.of(env.home).resolve("bin/cli/tg-local-auto-cli");
            Path projectCli = projectDir.resolve(".skill-manager/bin/cli/tg-local-auto-cli");
            boolean parentStartsWithoutCli = !Files.exists(parentCli);
            boolean projectStartsWithoutCli = !Files.exists(projectCli);

            try {
                addSkillScriptCli(skillDir, "tg-local-auto-cli");
            } catch (Exception e) {
                return NodeResult.fail("project.local.sync.cli.refresh",
                        "could not add cli fixture: " + e.getMessage())
                        .process(resolve);
            }

            ProcessRecord sync = run(ctx, "local-sync", env, repoRoot, sm,
                    "sync", "tg-project-local-cli-skill",
                    "--from", skillDir.toString(),
                    "--yes",
                    "--skip-mcp",
                    "--skip-agents");

            boolean parentCliInstalled = Files.isExecutable(parentCli);
            boolean projectCliInstalled = Files.isExecutable(projectCli);
            boolean pass = resolve.exitCode() == 0
                    && sync.exitCode() == 0
                    && parentStartsWithoutCli
                    && projectStartsWithoutCli
                    && parentCliInstalled
                    && projectCliInstalled;

            return (pass
                    ? NodeResult.pass("project.local.sync.cli.refresh")
                    : NodeResult.fail("project.local.sync.cli.refresh",
                            "resolve=" + resolve.exitCode()
                                    + " sync=" + sync.exitCode()
                                    + " parentStartsWithoutCli=" + parentStartsWithoutCli
                                    + " projectStartsWithoutCli=" + projectStartsWithoutCli
                                    + " parentCliInstalled=" + parentCliInstalled
                                    + " projectCliInstalled=" + projectCliInstalled))
                    .process(resolve)
                    .process(sync)
                    .assertion("project_resolve_ok", resolve.exitCode() == 0)
                    .assertion("local_sync_ok", sync.exitCode() == 0)
                    .assertion("parent_cli_absent_before_sync", parentStartsWithoutCli)
                    .assertion("project_cli_absent_before_sync", projectStartsWithoutCli)
                    .assertion("parent_cli_installed_after_local_sync", parentCliInstalled)
                    .assertion("project_child_cli_installed_after_local_sync", projectCliInstalled);
        });
    }

    private static Path scaffoldSkill(Path root, String name) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: project local sync fixture
                ---
                Body.
                """.formatted(name));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "project local sync fixture"
                """.formatted(name));
        return dir;
    }

    private static void addSkillScriptCli(Path skillDir, String toolName) throws Exception {
        Files.writeString(skillDir.resolve("skill-manager.toml"), Files.readString(skillDir.resolve("skill-manager.toml"))
                + """

                [[cli_dependencies]]
                name = "%1$s"
                spec = "skill-script:%1$s"

                [cli_dependencies.install.any]
                script = "install-%1$s.sh"
                binary = "%1$s"
                """.formatted(toolName));
        Path scripts = skillDir.resolve("skill-scripts");
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve("install-" + toolName + ".sh"), """
                #!/usr/bin/env sh
                set -eu
                mkdir -p "$SKILL_MANAGER_BIN_DIR"
                cat > "$SKILL_MANAGER_BIN_DIR/%1$s" <<'EOF'
                #!/usr/bin/env sh
                echo %1$s
                EOF
                chmod +x "$SKILL_MANAGER_BIN_DIR/%1$s"
                """.formatted(toolName));
    }

    private static ProcessRecord run(NodeContext ctx, String label, Env env,
                                     Path repoRoot, Path sm, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = sm.toString();
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        env.apply(pb, repoRoot);
        return Procs.run(ctx, label, pb);
    }

    private record Env(String home, String claudeHome, String codexHome, String geminiHome) {
        static Env from(NodeContext ctx) {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || geminiHome == null) return null;
            return new Env(home, claudeHome, codexHome, geminiHome);
        }

        Env isolated(String dirName) throws Exception {
            Path privateHome = Path.of(home).resolve(dirName);
            Path privateAgentHome = privateHome.resolve("agent-home");
            Path privateCodexHome = privateAgentHome.resolve(".codex");
            Path privateGeminiHome = privateAgentHome.resolve(".gemini");
            Files.createDirectories(privateAgentHome.resolve(".claude"));
            Files.createDirectories(privateCodexHome);
            Files.createDirectories(privateGeminiHome);
            Files.writeString(privateHome.resolve("policy.toml"), """
                    require_confirmation = false
                    [install]
                    require_confirmation_for_hooks = false
                    require_confirmation_for_mcp = false
                    require_confirmation_for_cli_deps = false
                    require_confirmation_for_executable_commands = false
                    """);
            return new Env(privateHome.toString(), privateAgentHome.toString(),
                    privateCodexHome.toString(), privateGeminiHome.toString());
        }

        void apply(ProcessBuilder pb, Path repoRoot) {
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);
            pb.environment().put("GEMINI_HOME", geminiHome);
        }
    }
}
