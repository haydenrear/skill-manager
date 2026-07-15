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
            .tags("project", "resolve", "git", "issue-75", "issue-115")
            .timeout("180s")
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
                // A transitive dependency named by direct-git coordinate, the
                // form `github:owner/repo` takes in a real manifest (served over
                // git+file:// so the clone stays offline). Before ticket 115 the
                // resolver dropped this edge, so tg-git-child never installed
                // and never reached the lock or the project child home.
                Path gitChild = scaffoldSkill(units, "tg-git-child", "");
                gitInitCommit(gitChild);
                Path parent = scaffoldSkill(units, "tg-parent",
                        "skill_references = [\"" + child + "\", \"git+file://" + gitChild + "\"]\n");
                Path plugin = scaffoldPlugin(units, "tg-plugin");
                Path prompts = scaffoldDocRepo(units, "tg-prompts");
                Path harness = scaffoldHarness(units, "tg-harness", child, prompts);
                Files.writeString(projectDir.resolve("skill-project.toml"), """
                        [project]
                        name = "tg-resolved-project"

                        [skills.parent]
                        source = "%s"

                        [plugins.helper]
                        source = "%s"

                        [docs.prompts]
                        source = "%s"

                        [harnesses.default]
                        source = "%s"
                        """.formatted(parent, plugin, prompts, harness));
            } catch (Exception e) {
                return NodeResult.fail("project.dependencies.resolved",
                        "could not scaffold project resolve fixture: " + e.getMessage());
            }

            ProcessRecord resolve = run(ctx, "resolve", home, repoRoot, sm,
                    "project", "resolve", "--skip-gateway", "--project-dir", projectDir.toString());
            ProcessRecord resolveAgain = run(ctx, "resolve-again", home, repoRoot, sm,
                    "project", "resolve", "--skip-gateway", "--project-dir", projectDir.toString());
            ProcessRecord sync = run(ctx, "sync", home, repoRoot, sm,
                    "project", "sync", "--skip-gateway", "--project-dir", projectDir.toString());
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
            boolean lockHasGitChild = lockText.contains("name = \"tg-git-child\"");
            boolean lockHasPlugin = lockText.contains("name = \"tg-plugin\"");
            boolean lockHasDoc = lockText.contains("name = \"tg-prompts\"");
            boolean lockHasHarness = lockText.contains("name = \"tg-harness\"");

            boolean parentInstalled = Files.isRegularFile(Path.of(home, "skills", "tg-parent", "SKILL.md"));
            boolean childInstalled = Files.isRegularFile(Path.of(home, "skills", "tg-child", "SKILL.md"));
            boolean gitChildInstalled = Files.isRegularFile(Path.of(home, "skills", "tg-git-child", "SKILL.md"));
            boolean pluginInstalled = Files.isRegularFile(Path.of(home, "plugins", "tg-plugin", ".claude-plugin/plugin.json"));
            boolean docInstalled = Files.isRegularFile(Path.of(home, "docs", "tg-prompts", "skill-manager.toml"));
            boolean harnessInstalled = Files.isRegularFile(Path.of(home, "harnesses", "tg-harness", "harness.toml"));
            boolean docCopy = Files.isRegularFile(projectDir.resolve("docs/agents/review.md"));
            boolean claudeImport = read(projectDir.resolve("CLAUDE.md")).contains("docs/agents/review.md");
            boolean claudeHarnessSkill = Files.exists(projectDir.resolve(".claude/skills/tg-child"));
            boolean codexHarnessSkill = Files.exists(projectDir.resolve(".codex/skills/tg-child"));
            boolean geminiHarnessSkill = Files.exists(projectDir.resolve(".gemini/skills/tg-child"));
            boolean claudePlugin = Files.exists(projectDir.resolve(".claude/plugins/tg-plugin"));
            Path childHome = projectDir.resolve(".skill-manager");
            boolean childHomeInitialized = Files.isDirectory(childHome)
                    && Files.isDirectory(projectDir.resolve(".codex"))
                    && Files.isDirectory(projectDir.resolve(".claude"))
                    && Files.isDirectory(projectDir.resolve(".gemini"));
            boolean gitChildProjected = Files.isRegularFile(childHome.resolve("skills/tg-git-child/SKILL.md"));
            boolean childUnits = Files.isRegularFile(childHome.resolve("skills/tg-child/SKILL.md"))
                    && Files.isRegularFile(childHome.resolve("skills/tg-parent/SKILL.md"))
                    && Files.isRegularFile(childHome.resolve("plugins/tg-plugin/.claude-plugin/plugin.json"))
                    && Files.isRegularFile(childHome.resolve("docs/tg-prompts/skill-manager.toml"))
                    && Files.isRegularFile(childHome.resolve("harnesses/tg-harness/harness.toml"));
            Path childRecord = Path.of(home, "child-homes", "project_tg-resolved-project", "child-home.json");
            String childRecordText = read(childRecord);
            boolean childRegistry = Files.isRegularFile(childRecord)
                    && childRecordText.contains("\"id\" : \"project:tg-resolved-project\"")
                    && childRecordText.contains(childHome.toString())
                    && childRecordText.contains("tg-parent")
                    && childRecordText.contains("tg-child")
                    && childRecordText.contains("tg-plugin")
                    && childRecordText.contains("tg-prompts")
                    && childRecordText.contains("tg-harness");
            boolean projectionsUseChildStore = pointsTo(projectDir.resolve(".codex/skills/tg-child"),
                    childHome.resolve("skills/tg-child"))
                    && pointsTo(projectDir.resolve(".claude/skills/tg-child"),
                            childHome.resolve("skills/tg-child"))
                    && pointsTo(projectDir.resolve(".gemini/skills/tg-child"),
                            childHome.resolve("skills/tg-child"))
                    && pointsTo(projectDir.resolve(".claude/plugins/tg-plugin"),
                            childHome.resolve("plugins/tg-plugin"));
            boolean removeBlocked = remove.exitCode() != 0 && readLog(ctx, "remove-claimed").contains("tg-resolved-project");
            boolean showResolved = show.exitCode() == 0
                    && readLog(ctx, "show").contains("resolved:")
                    && readLog(ctx, "show").contains("bindings:");
            boolean syncPlaceholder = sync.exitCode() == 0
                    && readLog(ctx, "sync").contains("project sync is a placeholder")
                    && readLog(ctx, "sync").contains("uninstall/reinstall placeholder");
            boolean secondResolveOk = resolveAgain.exitCode() == 0
                    && readLog(ctx, "resolve-again").contains("resolved project tg-resolved-project");

            ProcessRecord projectRemove = run(ctx, "project-remove", home, repoRoot, sm,
                    "project", "remove", "--skip-gateway", "tg-resolved-project");
            boolean projectRemoveOk = projectRemove.exitCode() == 0
                    && readLog(ctx, "project-remove").contains("removed project tg-resolved-project");
            boolean registrationRemoved = !Files.exists(projectHome);
            boolean childRegistryRemoved = !Files.exists(childRecord);
            boolean childHomeCleared = !Files.exists(childHome.resolve("skills/tg-child"))
                    && !Files.exists(childHome.resolve("skills/tg-parent"))
                    && !Files.exists(childHome.resolve("plugins/tg-plugin"))
                    && !Files.exists(childHome.resolve("docs/tg-prompts"))
                    && !Files.exists(childHome.resolve("harnesses/tg-harness"));
            boolean projectBindingsRemoved = !Files.exists(projectDir.resolve("docs/agents/review.md"))
                    && !Files.exists(projectDir.resolve(".claude/skills/tg-child"))
                    && !Files.exists(projectDir.resolve(".codex/skills/tg-child"))
                    && !Files.exists(projectDir.resolve(".gemini/skills/tg-child"))
                    && !Files.exists(projectDir.resolve(".claude/plugins/tg-plugin"));
            boolean parentUnitsRemainAfterProjectRemove =
                    Files.isRegularFile(Path.of(home, "skills", "tg-parent", "SKILL.md"))
                            && Files.isRegularFile(Path.of(home, "skills", "tg-child", "SKILL.md"))
                            && Files.isRegularFile(Path.of(home, "plugins", "tg-plugin", ".claude-plugin/plugin.json"))
                            && Files.isRegularFile(Path.of(home, "docs", "tg-prompts", "skill-manager.toml"))
                            && Files.isRegularFile(Path.of(home, "harnesses", "tg-harness", "harness.toml"));

            boolean pass = resolve.exitCode() == 0
                    && secondResolveOk
                    && syncPlaceholder
                    && showResolved
                    && lockWritten
                    && lockHasParent
                    && lockHasChild
                    && lockHasGitChild
                    && lockHasPlugin
                    && lockHasDoc
                    && lockHasHarness
                    && parentInstalled
                    && childInstalled
                    && gitChildInstalled
                    && gitChildProjected
                    && pluginInstalled
                    && docInstalled
                    && harnessInstalled
                    && docCopy
                    && claudeImport
                    && claudeHarnessSkill
                    && codexHarnessSkill
                    && geminiHarnessSkill
                    && claudePlugin
                    && childHomeInitialized
                    && childUnits
                    && childRegistry
                    && projectionsUseChildStore
                    && removeBlocked
                    && projectRemoveOk
                    && registrationRemoved
                    && childRegistryRemoved
                    && childHomeCleared
                    && projectBindingsRemoved
                    && parentUnitsRemainAfterProjectRemove;

            return (pass
                    ? NodeResult.pass("project.dependencies.resolved")
                    : NodeResult.fail("project.dependencies.resolved",
                            "resolve=" + resolve.exitCode()
                                    + " resolveAgain=" + resolveAgain.exitCode()
                                    + " sync=" + sync.exitCode()
                                    + " show=" + show.exitCode()
                                    + " remove=" + remove.exitCode()
                                    + " projectRemove=" + projectRemove.exitCode()
                                    + " secondResolveOk=" + secondResolveOk
                                    + " syncPlaceholder=" + syncPlaceholder
                                    + " lockWritten=" + lockWritten
                                    + " lockParent=" + lockHasParent
                                    + " lockChild=" + lockHasChild
                                    + " lockGitChild=" + lockHasGitChild
                                    + " gitChildInstalled=" + gitChildInstalled
                                    + " gitChildProjected=" + gitChildProjected
                                    + " lockPlugin=" + lockHasPlugin
                                    + " lockDoc=" + lockHasDoc
                                    + " lockHarness=" + lockHasHarness
                                    + " parentInstalled=" + parentInstalled
                                    + " childInstalled=" + childInstalled
                                    + " pluginInstalled=" + pluginInstalled
                                    + " docInstalled=" + docInstalled
                                    + " harnessInstalled=" + harnessInstalled
                                    + " docCopy=" + docCopy
                                    + " claudeImport=" + claudeImport
                                    + " claudeHarnessSkill=" + claudeHarnessSkill
                                    + " codexHarnessSkill=" + codexHarnessSkill
                                    + " geminiHarnessSkill=" + geminiHarnessSkill
                                    + " claudePlugin=" + claudePlugin
                                    + " childHomeInitialized=" + childHomeInitialized
                                    + " childUnits=" + childUnits
                                    + " childRegistry=" + childRegistry
                                    + " projectionsUseChildStore=" + projectionsUseChildStore
                                    + " removeBlocked=" + removeBlocked
                                    + " projectRemoveOk=" + projectRemoveOk
                                    + " registrationRemoved=" + registrationRemoved
                                    + " childRegistryRemoved=" + childRegistryRemoved
                                    + " childHomeCleared=" + childHomeCleared
                                    + " projectBindingsRemoved=" + projectBindingsRemoved
                                    + " parentUnitsRemainAfterProjectRemove=" + parentUnitsRemainAfterProjectRemove))
                    .process(resolve)
                    .process(resolveAgain)
                    .process(sync)
                    .process(show)
                    .process(remove)
                    .process(projectRemove)
                    .assertion("resolve_command_ok", resolve.exitCode() == 0)
                    .assertion("resolve_existing_project_is_idempotent", secondResolveOk)
                    .assertion("project_sync_placeholder_ok", syncPlaceholder)
                    .assertion("show_reports_lock_counts", showResolved)
                    .assertion("project_lock_written", lockWritten)
                    .assertion("lock_records_direct_and_transitive_units",
                            lockHasParent && lockHasChild && lockHasPlugin && lockHasDoc && lockHasHarness)
                    .assertion("lock_records_git_coordinate_transitive_unit", lockHasGitChild)
                    .assertion("git_coordinate_transitive_unit_installed", gitChildInstalled)
                    .assertion("git_coordinate_transitive_unit_projected_into_child_home",
                            gitChildProjected)
                    .assertion("units_installed_in_home",
                            parentInstalled && childInstalled && pluginInstalled && docInstalled && harnessInstalled)
                    .assertion("doc_binding_materialized", docCopy && claudeImport)
                    .assertion("project_agent_skill_bindings_materialized",
                            claudeHarnessSkill && codexHarnessSkill && geminiHarnessSkill)
                    .assertion("project_agent_plugin_binding_materialized", claudePlugin)
                    .assertion("project_child_home_scaffolded", childHomeInitialized)
                    .assertion("project_child_home_units_projected", childUnits)
                    .assertion("parent_child_home_registry_claims_project_units", childRegistry)
                    .assertion("project_agent_projections_point_at_child_store", projectionsUseChildStore)
                    .assertion("plain_remove_blocked_by_project_lock", removeBlocked)
                    .assertion("project_remove_command_ok", projectRemoveOk)
                    .assertion("project_remove_clears_registration", registrationRemoved)
                    .assertion("project_remove_clears_child_home_registry", childRegistryRemoved)
                    .assertion("project_remove_clears_child_home_generated_units", childHomeCleared)
                    .assertion("project_remove_clears_project_bindings", projectBindingsRemoved)
                    .assertion("project_remove_keeps_parent_home_units", parentUnitsRemainAfterProjectRemove)
                    .metric("resolveExitCode", resolve.exitCode())
                    .metric("resolveAgainExitCode", resolveAgain.exitCode())
                    .metric("syncExitCode", sync.exitCode())
                    .metric("showExitCode", show.exitCode())
                    .metric("removeExitCode", remove.exitCode())
                    .metric("projectRemoveExitCode", projectRemove.exitCode())
                    .publish("projectName", "tg-resolved-project")
                    .publish("projectDir", projectDir.toString())
                    .publish("lockFile", lock.toString());
        });
    }

    /** Make {@code dir} a git repo on {@code main} so it can be cloned over {@code git+file://}. */
    private static void gitInitCommit(Path dir) throws Exception {
        git(dir, "init", "-b", "main", "--quiet");
        git(dir, "add", "-A");
        git(dir, "-c", "user.email=fixture@skillmanager.local", "-c", "user.name=fixture",
                "commit", "--quiet", "-m", "project resolve fixture");
    }

    private static void git(Path dir, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>(
                java.util.List.of("git", "-C", dir.toString()));
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("git " + String.join(" ", args) + " timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed: " + process.exitValue());
        }
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

    private static Path scaffoldPlugin(Path root, String name) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"), """
                {
                  "name": "%s",
                  "version": "0.1.0",
                  "description": "graph fixture plugin"
                }
                """.formatted(name));
        Files.writeString(dir.resolve("skill-manager-plugin.toml"), """
                [plugin]
                name = "%s"
                version = "0.1.0"
                description = "graph fixture plugin"
                """.formatted(name));
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
