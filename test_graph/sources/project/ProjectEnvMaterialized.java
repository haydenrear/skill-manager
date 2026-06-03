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
 * External regression for ISSUE-75-3. Materializes a project env through the
 * public CLI and validates the generated uv project, project-local vendor
 * paths, tool shims, docs, and lock rows.
 */
public class ProjectEnvMaterialized {
    static final NodeSpec SPEC = NodeSpec.of("project.env.materialized")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("env.prepared")
            .tags("project", "env", "issue-75")
            .timeout("120s")
            .output("projectName", "string")
            .output("projectDir", "string")
            .output("envRoot", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("project.env.materialized", "missing env.prepared.home");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path projectDir;
            try {
                projectDir = Files.createTempDirectory("sm-project-env-");
                Path units = projectDir.resolve("units");
                Path skill = scaffoldSkill(units, "tg-env-skill");
                Files.writeString(projectDir.resolve("skill-project.toml"), """
                        [project]
                        name = "tg-env-project"

                        [skills.env]
                        source = "%s"

                        [envs.dev]
                        python = "3.12"
                        dependencies = ["pytest==8.4.0"]
                        skill_packages = ["tg-env-skill"]
                        tools = ["pytest"]
                        """.formatted(skill));
            } catch (Exception e) {
                return NodeResult.fail("project.env.materialized",
                        "could not scaffold project env fixture: " + e.getMessage());
            }

            ProcessRecord resolve = run(ctx, "resolve", home, repoRoot, sm,
                    "project", "resolve", "--skip-gateway", "--project-dir", projectDir.toString());
            ProcessRecord sync = run(ctx, "env-sync", home, repoRoot, sm,
                    "env", "sync", "dev", "--skip-uv", "--project-dir", projectDir.toString());
            ProcessRecord show = run(ctx, "show", home, repoRoot, sm,
                    "project", "show", "tg-env-project");

            Path envRoot = projectDir.resolve(".skill-manager/envs/dev");
            Path pyproject = envRoot.resolve("pyproject.toml");
            Path vendorSkill = projectDir.resolve(".skill-manager/vendor/tg-env-skill/SKILL.md");
            Path shim = projectDir.resolve(".skill-manager/bin/pytest");
            Path docs = projectDir.resolve(".skill-manager/env.md");
            Path lock = Path.of(home, "projects", "tg-env-project", "project-lock.toml");
            String pyprojectText = read(pyproject);
            String docsText = read(docs);
            String lockText = read(lock);
            String showLog = readLog(ctx, "show");

            boolean pyprojectRendered = Files.isRegularFile(pyproject)
                    && pyprojectText.contains("requires-python = \">=3.12\"")
                    && pyprojectText.contains("\"pytest==8.4.0\"")
                    && pyprojectText.contains("tg-env-skill = { path = \"../../vendor/tg-env-skill\", editable = true }");
            boolean vendorRendered = Files.isRegularFile(vendorSkill);
            boolean shimRendered = Files.isExecutable(shim) && read(shim).contains("uv\" run --project");
            boolean docsRendered = Files.isRegularFile(docs)
                    && docsText.contains("source of truth is skill-project.toml")
                    && docsText.contains("skill-manager env sync dev");
            boolean lockRendered = Files.isRegularFile(lock)
                    && lockText.contains("[[envs]]")
                    && lockText.contains("name = \"dev\"")
                    && lockText.contains("vendor_units = [\"tg-env-skill\"]")
                    && lockText.contains("tools = [\"pytest\"]");
            boolean showReportsEnvLock = show.exitCode() == 0 && showLog.contains("env locks:1");

            boolean pass = resolve.exitCode() == 0
                    && sync.exitCode() == 0
                    && pyprojectRendered
                    && vendorRendered
                    && shimRendered
                    && docsRendered
                    && lockRendered
                    && showReportsEnvLock;

            return (pass
                    ? NodeResult.pass("project.env.materialized")
                    : NodeResult.fail("project.env.materialized",
                            "resolve=" + resolve.exitCode()
                                    + " sync=" + sync.exitCode()
                                    + " show=" + show.exitCode()
                                    + " pyproject=" + pyprojectRendered
                                    + " vendor=" + vendorRendered
                                    + " shim=" + shimRendered
                                    + " docs=" + docsRendered
                                    + " lock=" + lockRendered
                                    + " showEnvLock=" + showReportsEnvLock))
                    .process(resolve)
                    .process(sync)
                    .process(show)
                    .assertion("resolve_command_ok", resolve.exitCode() == 0)
                    .assertion("env_sync_command_ok", sync.exitCode() == 0)
                    .assertion("pyproject_uses_project_relative_vendor_path", pyprojectRendered)
                    .assertion("skill_unit_vendored_project_locally", vendorRendered)
                    .assertion("tool_shim_executable", shimRendered)
                    .assertion("env_docs_rendered_as_documentation", docsRendered)
                    .assertion("project_lock_records_env_realization", lockRendered)
                    .assertion("project_show_reports_env_lock_count", showReportsEnvLock)
                    .metric("resolveExitCode", resolve.exitCode())
                    .metric("syncExitCode", sync.exitCode())
                    .metric("showExitCode", show.exitCode())
                    .publish("projectName", "tg-env-project")
                    .publish("projectDir", projectDir.toString())
                    .publish("envRoot", envRoot.toString());
        });
    }

    private static Path scaffoldSkill(Path root, String name) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: graph fixture
                ---
                Body.
                """.formatted(name));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "graph fixture"
                """.formatted(name));
        return dir;
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

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return "";
        }
    }
}
