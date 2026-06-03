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
 * External regression for ISSUE-75-2. Resolves a skill project manifest into
 * installed home units, project doc/harness bindings, a project lock, and a
 * remove guard for project-claimed units.
 */
public class ProjectDependenciesResolved {
    static final NodeSpec SPEC = NodeSpec.of("project.dependencies.resolved")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("env.prepared")
            .tags("project", "resolve", "issue-75")
            .timeout("120s")
            .output("projectName", "string")
            .output("projectDir", "string")
            .output("lockFile", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("project.dependencies.resolved", "missing env.prepared.home");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            Path projectDir;
            try {
                projectDir = Files.createTempDirectory("sm-project-resolve-");
                Path units = projectDir.resolve("units");
                Path child = scaffoldSkill(units, "tg-child", "");
                Path parent = scaffoldSkill(units, "tg-parent",
                        "skill_references = [\"" + child + "\"]\n");
                Path prompts = scaffoldDocRepo(units, "tg-prompts");
                Path harness = scaffoldHarness(units, "tg-harness", child, prompts);
                Files.writeString(projectDir.resolve("skill-project.toml"), """
                        [project]
                        name = "tg-resolved-project"

                        [skills.parent]
                        source = "%s"

                        [docs.prompts]
                        source = "%s"

                        [harnesses.default]
                        source = "%s"
                        """.formatted(parent, prompts, harness));
            } catch (Exception e) {
                return NodeResult.fail("project.dependencies.resolved",
                        "could not scaffold project resolve fixture: " + e.getMessage());
            }

            ProcessRecord resolve = run(ctx, "resolve", home, repoRoot, sm,
                    "project", "resolve", "--skip-gateway", "--project-dir", projectDir.toString());
            ProcessRecord show = run(ctx, "show", home, repoRoot, sm,
                    "project", "show", "tg-resolved-project");
            ProcessRecord remove = run(ctx, "remove-claimed", home, repoRoot, sm,
                    "remove", "tg-parent");

            Path projectHome = Path.of(home, "projects", "tg-resolved-project");
            Path lock = projectHome.resolve("project-lock.toml");
            boolean lockWritten = Files.isRegularFile(lock);
            String lockText = read(lock);
            boolean lockHasParent = lockText.contains("name = \"tg-parent\"");
            boolean lockHasChild = lockText.contains("name = \"tg-child\"");
            boolean lockHasDoc = lockText.contains("name = \"tg-prompts\"");
            boolean lockHasHarness = lockText.contains("name = \"tg-harness\"");

            boolean parentInstalled = Files.isRegularFile(Path.of(home, "skills", "tg-parent", "SKILL.md"));
            boolean childInstalled = Files.isRegularFile(Path.of(home, "skills", "tg-child", "SKILL.md"));
            boolean docInstalled = Files.isRegularFile(Path.of(home, "docs", "tg-prompts", "skill-manager.toml"));
            boolean harnessInstalled = Files.isRegularFile(Path.of(home, "harnesses", "tg-harness", "harness.toml"));
            boolean docCopy = Files.isRegularFile(projectDir.resolve("docs/agents/review.md"));
            boolean claudeImport = read(projectDir.resolve("CLAUDE.md")).contains("docs/agents/review.md");
            boolean codexHarnessSkill = Files.exists(projectDir.resolve(".codex/skills/tg-child"));
            Path childHome = projectDir.resolve(".skill-manager");
            boolean childHomeInitialized = Files.isDirectory(childHome)
                    && Files.isDirectory(projectDir.resolve(".codex"))
                    && Files.isDirectory(projectDir.resolve(".claude"))
                    && Files.isDirectory(projectDir.resolve(".gemini"));
            boolean childUnits = Files.isRegularFile(childHome.resolve("skills/tg-child/SKILL.md"))
                    && Files.isRegularFile(childHome.resolve("skills/tg-parent/SKILL.md"))
                    && Files.isRegularFile(childHome.resolve("docs/tg-prompts/skill-manager.toml"))
                    && Files.isRegularFile(childHome.resolve("harnesses/tg-harness/harness.toml"));
            Path childRecord = Path.of(home, "child-homes", "project_tg-resolved-project", "child-home.json");
            String childRecordText = read(childRecord);
            boolean childRegistry = Files.isRegularFile(childRecord)
                    && childRecordText.contains("\"id\" : \"project:tg-resolved-project\"")
                    && childRecordText.contains(childHome.toString())
                    && childRecordText.contains("tg-parent")
                    && childRecordText.contains("tg-child")
                    && childRecordText.contains("tg-prompts")
                    && childRecordText.contains("tg-harness");
            boolean projectionsUseChildStore = pointsTo(projectDir.resolve(".codex/skills/tg-child"),
                    childHome.resolve("skills/tg-child"));
            boolean removeBlocked = remove.exitCode() != 0 && readLog(ctx, "remove-claimed").contains("tg-resolved-project");
            boolean showResolved = show.exitCode() == 0
                    && readLog(ctx, "show").contains("resolved:")
                    && readLog(ctx, "show").contains("bindings:");

            boolean pass = resolve.exitCode() == 0
                    && showResolved
                    && lockWritten
                    && lockHasParent
                    && lockHasChild
                    && lockHasDoc
                    && lockHasHarness
                    && parentInstalled
                    && childInstalled
                    && docInstalled
                    && harnessInstalled
                    && docCopy
                    && claudeImport
                    && codexHarnessSkill
                    && childHomeInitialized
                    && childUnits
                    && childRegistry
                    && projectionsUseChildStore
                    && removeBlocked;

            return (pass
                    ? NodeResult.pass("project.dependencies.resolved")
                    : NodeResult.fail("project.dependencies.resolved",
                            "resolve=" + resolve.exitCode()
                                    + " show=" + show.exitCode()
                                    + " remove=" + remove.exitCode()
                                    + " lockWritten=" + lockWritten
                                    + " lockParent=" + lockHasParent
                                    + " lockChild=" + lockHasChild
                                    + " lockDoc=" + lockHasDoc
                                    + " lockHarness=" + lockHasHarness
                                    + " parentInstalled=" + parentInstalled
                                    + " childInstalled=" + childInstalled
                                    + " docInstalled=" + docInstalled
                                    + " harnessInstalled=" + harnessInstalled
                                    + " docCopy=" + docCopy
                                    + " claudeImport=" + claudeImport
                                    + " codexHarnessSkill=" + codexHarnessSkill
                                    + " childHomeInitialized=" + childHomeInitialized
                                    + " childUnits=" + childUnits
                                    + " childRegistry=" + childRegistry
                                    + " projectionsUseChildStore=" + projectionsUseChildStore
                                    + " removeBlocked=" + removeBlocked))
                    .process(resolve)
                    .process(show)
                    .process(remove)
                    .assertion("resolve_command_ok", resolve.exitCode() == 0)
                    .assertion("show_reports_lock_counts", showResolved)
                    .assertion("project_lock_written", lockWritten)
                    .assertion("lock_records_direct_and_transitive_units",
                            lockHasParent && lockHasChild && lockHasDoc && lockHasHarness)
                    .assertion("units_installed_in_home",
                            parentInstalled && childInstalled && docInstalled && harnessInstalled)
                    .assertion("doc_binding_materialized", docCopy && claudeImport)
                    .assertion("harness_binding_materialized", codexHarnessSkill)
                    .assertion("project_child_home_scaffolded", childHomeInitialized)
                    .assertion("project_child_home_units_projected", childUnits)
                    .assertion("parent_child_home_registry_claims_project_units", childRegistry)
                    .assertion("project_agent_projections_point_at_child_store", projectionsUseChildStore)
                    .assertion("plain_remove_blocked_by_project_lock", removeBlocked)
                    .metric("resolveExitCode", resolve.exitCode())
                    .metric("showExitCode", show.exitCode())
                    .metric("removeExitCode", remove.exitCode())
                    .publish("projectName", "tg-resolved-project")
                    .publish("projectDir", projectDir.toString())
                    .publish("lockFile", lock.toString());
        });
    }

    private static Path scaffoldSkill(Path root, String name, String extraToml) throws Exception {
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
                %s
                """.formatted(name, extraToml));
        return dir;
    }

    private static Path scaffoldDocRepo(Path root, String name) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [doc-repo]
                name = "%s"
                version = "0.1.0"

                [[sources]]
                id = "review"
                file = "review.md"
                agents = ["claude", "codex"]
                """.formatted(name));
        Files.writeString(dir.resolve("review.md"), "review prompts\n");
        return dir;
    }

    private static Path scaffoldHarness(Path root, String name, Path skill, Path doc) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("harness.toml"), """
                [harness]
                name = "%s"
                version = "0.1.0"
                units = ["%s"]
                docs = ["%s"]
                """.formatted(name, skill, doc));
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

    private static boolean pointsTo(Path projection, Path expected) {
        try {
            if (!Files.exists(projection)) return false;
            if (Files.isSymbolicLink(projection)) {
                Path link = Files.readSymbolicLink(projection);
                Path resolved = link.isAbsolute()
                        ? link.normalize()
                        : projection.getParent().resolve(link).normalize();
                return resolved.equals(expected.toAbsolutePath().normalize());
            }
            return projection.toRealPath().equals(expected.toRealPath());
        } catch (Exception e) {
            return false;
        }
    }
}
