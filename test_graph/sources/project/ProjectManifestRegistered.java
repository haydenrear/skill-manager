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
 * External regression for ISSUE-75-1. Registers a skill project manifest and
 * verifies skill-manager stores portable intent only: manifest snapshot,
 * env/lib declarations, and registration metadata, with no dependency install
 * or materialization.
 */
public class ProjectManifestRegistered {
    static final NodeSpec SPEC = NodeSpec.of("project.manifest.registered")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("env.prepared")
            .tags("project", "manifest", "issue-75")
            .timeout("60s")
            .output("projectName", "string")
            .output("projectDir", "string")
            .output("registrationDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("project.manifest.registered", "missing env.prepared.home");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path projectDir;
            try {
                projectDir = Files.createTempDirectory("sm-project-manifest-");
                Files.writeString(projectDir.resolve("skill-project.toml"), """
                        [project]
                        name = "tg-project"
                        version = "0.1.0"
                        description = "test graph project"

                        [skills.test_graph]
                        source = "skill:test-graph"

                        [plugins.reviewer]
                        source = "plugin:reviewer"

                        [harnesses.codex]
                        source = "harness:codex-harness"

                        [docs.prompts]
                        source = "doc:agent-prompts/review"

                        [[cli_dependencies]]
                        spec = "pip:ruff==0.8.0"

                        [envs.dev]
                        python = "3.12"
                        dependencies = ["pytest"]
                        skill_packages = ["test_graph"]
                        tools = ["ruff"]

                        [[libs]]
                        name = "support-agent"
                        source = "github:haydenrear/support-agent-rears"
                        ref = "main"
                        """);
            } catch (Exception e) {
                return NodeResult.fail("project.manifest.registered",
                        "could not scaffold project manifest: " + e.getMessage());
            }

            ProcessRecord register = run(ctx, "register", home, repoRoot, sm,
                    "project", "register", "--project-dir", projectDir.toString());
            ProcessRecord show = run(ctx, "show", home, repoRoot, sm,
                    "project", "show", "tg-project");
            ProcessRecord list = run(ctx, "list", home, repoRoot, sm,
                    "project", "list");

            Path registrationDir = Path.of(home, "projects", "tg-project");
            Path registrationToml = registrationDir.resolve("registration.toml");
            Path snapshot = registrationDir.resolve("skill-project.toml");
            boolean registered = Files.isRegularFile(registrationToml);
            boolean snapshotted = Files.isRegularFile(snapshot);
            boolean noSkillInstalled = !Files.exists(Path.of(home, "skills", "test-graph"));
            boolean noPluginInstalled = !Files.exists(Path.of(home, "plugins", "reviewer"));
            boolean noHarnessInstalled = !Files.exists(Path.of(home, "harnesses", "codex-harness"));
            boolean noDocInstalled = !Files.exists(Path.of(home, "docs", "agent-prompts"));

            String showLog = readLog(ctx, "show");
            String listLog = readLog(ctx, "list");
            boolean showSummarizesIntent = show.exitCode() == 0
                    && showLog.contains("PROJECT  tg-project")
                    && showLog.contains("skills:   1")
                    && showLog.contains("envs:     1")
                    && showLog.contains("libs:     1");
            boolean listIncludesProject = list.exitCode() == 0
                    && listLog.contains("tg-project")
                    && listLog.contains(projectDir.toString());

            boolean pass = register.exitCode() == 0
                    && registered
                    && snapshotted
                    && showSummarizesIntent
                    && listIncludesProject
                    && noSkillInstalled
                    && noPluginInstalled
                    && noHarnessInstalled
                    && noDocInstalled;

            NodeResult result = pass
                    ? NodeResult.pass("project.manifest.registered")
                    : NodeResult.fail("project.manifest.registered",
                            "register=" + register.exitCode()
                                    + " show=" + show.exitCode()
                                    + " list=" + list.exitCode()
                                    + " registered=" + registered
                                    + " snapshotted=" + snapshotted
                                    + " showIntent=" + showSummarizesIntent
                                    + " listIncludes=" + listIncludesProject
                                    + " noSkill=" + noSkillInstalled
                                    + " noPlugin=" + noPluginInstalled
                                    + " noHarness=" + noHarnessInstalled
                                    + " noDoc=" + noDocInstalled);
            return result
                    .process(register)
                    .process(show)
                    .process(list)
                    .assertion("register_command_ok", register.exitCode() == 0)
                    .assertion("registration_metadata_written", registered)
                    .assertion("manifest_snapshot_written", snapshotted)
                    .assertion("show_summarizes_portable_intent", showSummarizesIntent)
                    .assertion("list_includes_registered_project", listIncludesProject)
                    .assertion("skills_not_installed", noSkillInstalled)
                    .assertion("plugins_not_installed", noPluginInstalled)
                    .assertion("harnesses_not_installed", noHarnessInstalled)
                    .assertion("docs_not_installed", noDocInstalled)
                    .metric("registerExitCode", register.exitCode())
                    .metric("showExitCode", show.exitCode())
                    .metric("listExitCode", list.exitCode())
                    .publish("projectName", "tg-project")
                    .publish("projectDir", projectDir.toString())
                    .publish("registrationDir", registrationDir.toString());
        });
    }

    private static ProcessRecord run(NodeContext ctx, String label, String home,
                                     Path repoRoot, Path sm, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = sm.toString();
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        return Procs.run(ctx, label, pb);
    }

    private static String readLog(NodeContext ctx, String label) {
        try {
            return Files.readString(Procs.logFile(ctx, label));
        } catch (Exception e) {
            return "";
        }
    }
}
