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
            Path customProjectDir;
            Path reservedProjectDir;
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
                customProjectDir = Files.createTempDirectory("sm-project-manifest-custom-");
                Files.writeString(customProjectDir.resolve("skill-project.toml"), """
                        [project]
                        name = "tg-custom-project"
                        """);
                Files.writeString(customProjectDir.resolve("agent-harness.toml"), """
                        [project]
                        name = "tg-custom-project"

                        [skills.custom]
                        source = "skill:custom"

                        [envs.dev]
                        dependencies = ["pytest"]
                        """);
                reservedProjectDir = Files.createTempDirectory("sm-project-manifest-reserved-");
                Files.writeString(reservedProjectDir.resolve("registration.toml"), """
                        [project]
                        name = "tg-reserved-project"

                        [skills.reserved]
                        source = "skill:reserved"
                        """);
            } catch (Exception e) {
                return NodeResult.fail("project.manifest.registered",
                        "could not scaffold project manifest: " + e.getMessage());
            }

            ProcessRecord register = run(ctx, "register", home, repoRoot, sm,
                    "project", "register", "--project-dir", projectDir.toString());
            ProcessRecord registerCustom = run(ctx, "register-custom", home, repoRoot, sm,
                    "project", "register", "--project-dir", customProjectDir.toString(),
                    "--manifest", "agent-harness.toml");
            ProcessRecord registerReserved = run(ctx, "register-reserved", home, repoRoot, sm,
                    "project", "register", "--project-dir", reservedProjectDir.toString(),
                    "--manifest", "registration.toml");
            ProcessRecord show = run(ctx, "show", home, repoRoot, sm,
                    "project", "show", "tg-project");
            ProcessRecord showCustom = run(ctx, "show-custom", home, repoRoot, sm,
                    "project", "show", "tg-custom-project");
            ProcessRecord list = run(ctx, "list", home, repoRoot, sm,
                    "project", "list");

            Path registrationDir = Path.of(home, "projects", "tg-project");
            Path customRegistrationDir = Path.of(home, "projects", "tg-custom-project");
            Path registrationToml = registrationDir.resolve("registration.toml");
            Path snapshot = registrationDir.resolve("skill-project.toml");
            Path customSnapshot = customRegistrationDir.resolve("agent-harness.toml");
            boolean registered = Files.isRegularFile(registrationToml);
            boolean snapshotted = Files.isRegularFile(snapshot);
            boolean customSnapshotted = Files.isRegularFile(customSnapshot);
            boolean reservedRejected = registerReserved.exitCode() != 0
                    && !Files.exists(Path.of(home, "projects", "tg-reserved-project"));
            boolean noSkillInstalled = !Files.exists(Path.of(home, "skills", "test-graph"));
            boolean noCustomSkillInstalled = !Files.exists(Path.of(home, "skills", "custom"));
            boolean noPluginInstalled = !Files.exists(Path.of(home, "plugins", "reviewer"));
            boolean noHarnessInstalled = !Files.exists(Path.of(home, "harnesses", "codex-harness"));
            boolean noDocInstalled = !Files.exists(Path.of(home, "docs", "agent-prompts"));

            String showLog = readLog(ctx, "show");
            String showCustomLog = readLog(ctx, "show-custom");
            String listLog = readLog(ctx, "list");
            boolean showSummarizesIntent = show.exitCode() == 0
                    && showLog.contains("PROJECT  tg-project")
                    && showLog.contains("skills:   1")
                    && showLog.contains("envs:     1")
                    && showLog.contains("libs:     1");
            boolean showCustomSummarizesIntent = showCustom.exitCode() == 0
                    && showCustomLog.contains("PROJECT  tg-custom-project")
                    && showCustomLog.contains("skills:   1")
                    && showCustomLog.contains("envs:     1");
            boolean listIncludesProject = list.exitCode() == 0
                    && listLog.contains("tg-project")
                    && listLog.contains(projectDir.toString())
                    && listLog.contains("tg-custom-project")
                    && listLog.contains(customProjectDir.toString());

            boolean pass = register.exitCode() == 0
                    && registerCustom.exitCode() == 0
                    && reservedRejected
                    && registered
                    && snapshotted
                    && customSnapshotted
                    && showSummarizesIntent
                    && showCustomSummarizesIntent
                    && listIncludesProject
                    && noSkillInstalled
                    && noCustomSkillInstalled
                    && noPluginInstalled
                    && noHarnessInstalled
                    && noDocInstalled;

            NodeResult result = pass
                    ? NodeResult.pass("project.manifest.registered")
                    : NodeResult.fail("project.manifest.registered",
                            "register=" + register.exitCode()
                                    + " registerCustom=" + registerCustom.exitCode()
                                    + " registerReserved=" + registerReserved.exitCode()
                                    + " show=" + show.exitCode()
                                    + " showCustom=" + showCustom.exitCode()
                                    + " list=" + list.exitCode()
                                    + " registered=" + registered
                                    + " snapshotted=" + snapshotted
                                    + " customSnapshotted=" + customSnapshotted
                                    + " reservedRejected=" + reservedRejected
                                    + " showIntent=" + showSummarizesIntent
                                    + " showCustomIntent=" + showCustomSummarizesIntent
                                    + " listIncludes=" + listIncludesProject
                                    + " noSkill=" + noSkillInstalled
                                    + " noCustomSkill=" + noCustomSkillInstalled
                                    + " noPlugin=" + noPluginInstalled
                                    + " noHarness=" + noHarnessInstalled
                                    + " noDoc=" + noDocInstalled);
            return result
                    .process(register)
                    .process(registerCustom)
                    .process(registerReserved)
                    .process(show)
                    .process(showCustom)
                    .process(list)
                    .assertion("register_command_ok", register.exitCode() == 0)
                    .assertion("custom_manifest_register_command_ok", registerCustom.exitCode() == 0)
                    .assertion("reserved_manifest_register_rejected", reservedRejected)
                    .assertion("registration_metadata_written", registered)
                    .assertion("manifest_snapshot_written", snapshotted)
                    .assertion("custom_manifest_snapshot_written", customSnapshotted)
                    .assertion("show_summarizes_portable_intent", showSummarizesIntent)
                    .assertion("custom_show_summarizes_portable_intent", showCustomSummarizesIntent)
                    .assertion("list_includes_registered_project", listIncludesProject)
                    .assertion("skills_not_installed", noSkillInstalled)
                    .assertion("custom_skill_not_installed", noCustomSkillInstalled)
                    .assertion("plugins_not_installed", noPluginInstalled)
                    .assertion("harnesses_not_installed", noHarnessInstalled)
                    .assertion("docs_not_installed", noDocInstalled)
                    .metric("registerExitCode", register.exitCode())
                    .metric("registerCustomExitCode", registerCustom.exitCode())
                    .metric("registerReservedExitCode", registerReserved.exitCode())
                    .metric("showExitCode", show.exitCode())
                    .metric("showCustomExitCode", showCustom.exitCode())
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
