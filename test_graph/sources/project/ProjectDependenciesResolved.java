///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.LinkOption;
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
            boolean lockHasPlugin = lockText.contains("name = \"tg-plugin\"");
            boolean lockHasDoc = lockText.contains("name = \"tg-prompts\"");
            boolean lockHasHarness = lockText.contains("name = \"tg-harness\"");

            boolean parentInstalled = Files.isRegularFile(Path.of(home, "skills", "tg-parent", "SKILL.md"));
            boolean childInstalled = Files.isRegularFile(Path.of(home, "skills", "tg-child", "SKILL.md"));
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
            // Reachability. NOTE: Files.isRegularFile FOLLOWS symlinks, so this
            // is equally true of a child unit that is a symlink into the parent
            // store and of an independent copy. Kept because reachability is
            // still required; independence is asserted separately below.
            boolean childUnits = Files.isRegularFile(childHome.resolve("skills/tg-child/SKILL.md"))
                    && Files.isRegularFile(childHome.resolve("skills/tg-parent/SKILL.md"))
                    && Files.isRegularFile(childHome.resolve("plugins/tg-plugin/.claude-plugin/plugin.json"))
                    && Files.isRegularFile(childHome.resolve("docs/tg-prompts/skill-manager.toml"))
                    && Files.isRegularFile(childHome.resolve("harnesses/tg-harness/harness.toml"));

            // Independence: a child unit must be its own tree, not the parent
            // store's tree reached under another name. Checked NOFOLLOW plus
            // toRealPath, because every follow-links check above is blind to it.
            Path parentHome = Path.of(home);
            java.util.List<Path> parentUnitRoots = parentUnitRoots(parentHome);
            String[][] childUnitPaths = {
                    {"skills/tg-child", "skill", "tg-child"},
                    {"skills/tg-parent", "skill", "tg-parent"},
                    {"plugins/tg-plugin", "plugin", "tg-plugin"},
                    {"docs/tg-prompts", "doc", "tg-prompts"},
                    {"harnesses/tg-harness", "harness", "tg-harness"},
            };
            java.util.List<String> notIndependent = new java.util.ArrayList<>();
            java.util.List<String> notCopyRecorded = new java.util.ArrayList<>();
            java.util.List<String> storeLinks = new java.util.ArrayList<>();
            for (String[] entry : childUnitPaths) {
                Path unitDir = childHome.resolve(entry[0]);
                if (!isRealDirectory(unitDir) || realPathInsideAny(unitDir, parentUnitRoots)) {
                    notIndependent.add(entry[0]);
                }
                String record = read(childHome.resolve(".materialization")
                        .resolve(entry[1]).resolve(entry[2] + ".json"));
                if (!record.contains("\"mode\" : \"COPY\"")) notCopyRecorded.add(entry[0]);
                // Only unit directories. bin/ shims below the child home are
                // symlinks into the parent toolchain by design.
                for (String link : storeLinksBelow(unitDir, parentUnitRoots)) {
                    storeLinks.add(entry[0] + "/" + link);
                }
            }
            boolean childUnitsAreIndependentCopies = notIndependent.isEmpty();
            boolean childUnitsRecordedAsCopies = notCopyRecorded.isEmpty();
            boolean noChildUnitLinksIntoParentStore = storeLinks.isEmpty();

            // Independence, empirically: an agent's write below the child unit
            // must not change the parent store's copy. The exact original bytes
            // are restored afterwards so the teardown assertions below still see
            // an unmodified child home (a modified one is deliberately held back
            // by project remove, which is covered by the project-child-home
            // graph rather than here).
            Path childSkillMd = childHome.resolve("skills/tg-child/SKILL.md");
            Path parentSkillMd = Path.of(home, "skills", "tg-child", "SKILL.md");
            String childBefore = read(childSkillMd);
            String parentBefore = read(parentSkillMd);
            boolean childEditIsolated;
            try {
                Files.writeString(childSkillMd, childBefore + "AGENT-EDIT-PROBE\n");
                // read() returns "" on a missing file, so both parent-side
                // conjuncts would be satisfied by an absent parent unit. Require
                // real content on both sides before believing the isolation held.
                childEditIsolated = !childBefore.isEmpty()
                        && !parentBefore.isEmpty()
                        && read(parentSkillMd).equals(parentBefore)
                        && !read(parentSkillMd).contains("AGENT-EDIT-PROBE")
                        && read(childSkillMd).contains("AGENT-EDIT-PROBE");
                Files.writeString(childSkillMd, childBefore);
            } catch (Exception e) {
                childEditIsolated = false;
            }
            boolean childEditRestored = read(childSkillMd).equals(childBefore);

            // The assertion that replaces `project_sync_placeholder_ok`: a real
            // sync must not destroy an agent's edit. Checked on the BYTES, not on
            // the sync's own report — a sync that overwrote the unit and then
            // truthfully reported "nothing held back" would satisfy any
            // report-only check, because after the overwrite the unit really is
            // not modified any more.
            String agentEdit = childBefore + "AGENT-EDIT-SURVIVES-SYNC\n";
            boolean syncKeptAgentEdit;
            // Seeded with the first sync's record rather than null: the envelope
            // serializes every process it is handed, and a node that fails while
            // planting the probe must still produce a readable report.
            ProcessRecord syncAfterEdit = sync;
            try {
                Files.writeString(childSkillMd, agentEdit);
                syncAfterEdit = run(ctx, "sync-after-edit", home, repoRoot, sm,
                        "project", "sync", "--skip-gateway", "--project-dir", projectDir.toString());
                syncKeptAgentEdit = syncAfterEdit.exitCode() == 0
                        && read(childSkillMd).equals(agentEdit)
                        && readLog(ctx, "sync-after-edit").contains("held back");
                Files.writeString(childSkillMd, childBefore);
            } catch (Exception e) {
                syncKeptAgentEdit = false;
            }
            boolean agentEditProbeRestored = read(childSkillMd).equals(childBefore);

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
            // `project sync` used to be a placeholder that tore the realization
            // down and re-resolved it; the assertion below used to grep that
            // admission out of its own output. It now pulls each unit's trunk and
            // reconciles in place, so the contract to check is the mode it reports
            // and the fact that it reports a pull at all.
            boolean syncPullsAndReconciles = sync.exitCode() == 0
                    && readLog(ctx, "sync").contains("mode:             pull-reconcile")
                    && readLog(ctx, "sync").contains("pulled:")
                    && !readLog(ctx, "sync").contains("placeholder");
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
                    && syncPullsAndReconciles
                    && syncKeptAgentEdit
                    && agentEditProbeRestored
                    && showResolved
                    && lockWritten
                    && lockHasParent
                    && lockHasChild
                    && lockHasPlugin
                    && lockHasDoc
                    && lockHasHarness
                    && parentInstalled
                    && childInstalled
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
                    && childUnitsAreIndependentCopies
                    && childUnitsRecordedAsCopies
                    && noChildUnitLinksIntoParentStore
                    && childEditIsolated
                    && childEditRestored
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
                                    + " syncPullsAndReconciles=" + syncPullsAndReconciles
                                    + " syncKeptAgentEdit=" + syncKeptAgentEdit
                                    + " agentEditProbeRestored=" + agentEditProbeRestored
                                    + " lockWritten=" + lockWritten
                                    + " lockParent=" + lockHasParent
                                    + " lockChild=" + lockHasChild
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
                                    + " notIndependent=" + notIndependent
                                    + " notCopyRecorded=" + notCopyRecorded
                                    + " storeLinksInChildUnits=" + storeLinks
                                    + " childEditIsolated=" + childEditIsolated
                                    + " childEditRestored=" + childEditRestored
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
                    .process(syncAfterEdit)
                    .process(show)
                    .process(remove)
                    .process(projectRemove)
                    .assertion("resolve_command_ok", resolve.exitCode() == 0)
                    .assertion("resolve_existing_project_is_idempotent", secondResolveOk)
                    .assertion("project_sync_pulls_and_reconciles_in_place", syncPullsAndReconciles)
                    .assertion("project_sync_did_not_destroy_the_agent_edit", syncKeptAgentEdit)
                    .assertion("agent_edit_probe_restored", agentEditProbeRestored)
                    .assertion("show_reports_lock_counts", showResolved)
                    .assertion("project_lock_written", lockWritten)
                    .assertion("lock_records_direct_and_transitive_units",
                            lockHasParent && lockHasChild && lockHasPlugin && lockHasDoc && lockHasHarness)
                    .assertion("units_installed_in_home",
                            parentInstalled && childInstalled && pluginInstalled && docInstalled && harnessInstalled)
                    .assertion("doc_binding_materialized", docCopy && claudeImport)
                    .assertion("project_agent_skill_bindings_materialized",
                            claudeHarnessSkill && codexHarnessSkill && geminiHarnessSkill)
                    .assertion("project_agent_plugin_binding_materialized", claudePlugin)
                    .assertion("project_child_home_scaffolded", childHomeInitialized)
                    .assertion("project_child_home_units_projected", childUnits)
                    .assertion("project_child_home_units_are_independent_copies",
                            childUnitsAreIndependentCopies)
                    .assertion("project_child_home_units_recorded_as_copies", childUnitsRecordedAsCopies)
                    .assertion("no_child_home_entry_links_into_the_parent_store",
                            noChildUnitLinksIntoParentStore)
                    .assertion("child_home_edit_does_not_reach_the_parent_store", childEditIsolated)
                    .assertion("child_home_edit_probe_restored", childEditRestored)
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

    /** Real directory, not a symlink — the check {@code Files.isDirectory} cannot make. */
    private static boolean isRealDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    /** True when {@code path}'s real location lies inside {@code root}. */
    private static boolean realPathInside(Path path, Path root) {
        try {
            return path.toRealPath().startsWith(root.toRealPath());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The parent store's four unit roots. Scoped deliberately: a child home may
     * legitimately sit UNDER the parent home (harness instantiation puts one
     * there), so "inside the parent home" is not the same question as "inside
     * the parent store's units", and only the latter is the isolation boundary.
     */
    private static java.util.List<Path> parentUnitRoots(Path parentHome) {
        return java.util.List.of(
                parentHome.resolve("skills"),
                parentHome.resolve("plugins"),
                parentHome.resolve("docs"),
                parentHome.resolve("harnesses"));
    }

    private static boolean realPathInsideAny(Path path, java.util.List<Path> roots) {
        for (Path root : roots) {
            if (realPathInside(path, root)) return true;
        }
        return false;
    }

    /**
     * Every symlink at or below {@code root} that resolves inside {@code store}.
     * Each one is a live write-through channel from the child home into the
     * parent store, which is exactly what COPY materialization removes.
     */
    private static java.util.List<String> storeLinksBelow(Path root, java.util.List<Path> stores) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.List<Path> real = new java.util.ArrayList<>();
        for (Path store : stores) {
            try {
                real.add(store.toRealPath());
            } catch (Exception missing) {
                // A kind with no installed units has no directory; nothing to link into.
            }
        }
        try {
            collectStoreLinks(root, root, real, out);
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    private static void collectStoreLinks(Path base, Path current, java.util.List<Path> storeReal,
                                          java.util.List<String> out) throws Exception {
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(current)) {
            Path raw = Files.readSymbolicLink(current);
            Path resolved = raw.isAbsolute()
                    ? raw.normalize()
                    : current.getParent().resolve(raw).normalize();
            Path real;
            try {
                real = resolved.toRealPath();
            } catch (Exception broken) {
                real = resolved;
            }
            for (Path store : storeReal) {
                if (real.startsWith(store)) {
                    out.add(base.relativize(current) + " -> " + raw);
                    break;
                }
            }
            return;
        }
        if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(current)) {
                for (Path child : entries.sorted().toList()) {
                    collectStoreLinks(base, child, storeReal, out);
                }
            }
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
