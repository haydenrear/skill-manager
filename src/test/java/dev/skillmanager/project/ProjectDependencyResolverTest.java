package dev.skillmanager.project;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.app.RemoveUseCase;
import dev.skillmanager.app.SyncUseCase;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ChildHomeRegistry;
import dev.skillmanager.commands.SyncCommand;
import dev.skillmanager.effects.EffectContext;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class ProjectDependencyResolverTest {

    public static int run() throws Exception {
        return Tests.suite("ProjectDependencyResolverTest")
                .test("resolves transitive skill dependencies into the home store and project lock", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-transitive-");
                        Path child = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "project-child", DepSpec.empty()).sourcePath();
                        Path parent = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "project-parent",
                                DepSpec.of().ref(child.toString()).build()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "transitive-project"

                                [skills.parent]
                                source = "%s"
                                """.formatted(parent));

                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));

                        assertTrue(h.store().containsUnit("project-parent"), "parent installed");
                        assertTrue(h.store().containsUnit("project-child"), "transitive child installed");
                        assertTrue(Files.isRegularFile(h.store().projectsDir()
                                .resolve("transitive-project")
                                .resolve(SkillProjectLock.FILENAME)), "project lock written");
                        Set<String> locked = result.lock().resolvedUnits().stream()
                                .map(SkillProjectLock.ResolvedUnit::name)
                                .collect(Collectors.toSet());
                        assertTrue(locked.contains("project-parent"), "lock records direct skill");
                        assertTrue(locked.contains("project-child"), "lock records transitive skill");
                        assertTrue(Files.exists(repoRoot.resolve(".codex/skills/project-parent")),
                                "direct project skill projected into Codex home");
                        assertTrue(Files.exists(repoRoot.resolve(".gemini/skills/project-child")),
                                "transitive project skill projected into Gemini home");
                    }
                })
                .test("skip-gateway resolves a skill with MCP dependencies without registering them", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-skip-gateway-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"),
                                "project-mcp-skill",
                                DepSpec.of().mcp("project-mcp-server").build()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "skip-gateway-project"

                                [skills.mcp]
                                source = "%s"
                                """.formatted(skill));

                        var noGatewayInstall = InstallUseCase.buildProgram(
                                h.store(), null, null, skill.toString(), null,
                                true, false, false, true);
                        SkillEffect.BuildInstallPlan planEffect =
                                (SkillEffect.BuildInstallPlan) noGatewayInstall.stage1().effects().stream()
                                        .filter(SkillEffect.BuildInstallPlan.class::isInstance)
                                        .findFirst()
                                        .orElseThrow();
                        assertFalse(planEffect.withMcp(),
                                "no-gateway install plan omits MCP registration");
                        var noGatewayTail =
                                noGatewayInstall.stage2().apply(new EffectContext(h.store(), null));
                        assertFalse(noGatewayTail.effects().stream().anyMatch(
                                        effect -> effect instanceof SkillEffect.SyncAgents
                                                || effect instanceof SkillEffect.UnregisterMcpOrphans),
                                "no-gateway install tail omits gateway-dependent effects");
                        assertTrue(noGatewayTail.effects().stream().anyMatch(
                                        SkillEffect.UpdateUnitsLock.class::isInstance),
                                "no-gateway install tail still persists the unit lock");

                        var dryRunInstall = InstallUseCase.buildProgram(
                                h.store(), null, null, skill.toString(), null,
                                false, true);
                        SkillEffect.BuildInstallPlan dryRunPlan =
                                (SkillEffect.BuildInstallPlan) dryRunInstall.stage1().effects().stream()
                                        .filter(SkillEffect.BuildInstallPlan.class::isInstance)
                                        .findFirst()
                                        .orElseThrow();
                        assertTrue(dryRunPlan.withMcp(),
                                "dry-run still describes MCP registration without starting a gateway");

                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));

                        assertTrue(h.store().containsUnit("project-mcp-skill"),
                                "MCP-bearing skill installed without a gateway");
                        assertTrue(result.installed().contains("project-mcp-skill"),
                                "resolver reports the installed unit");
                        assertTrue(result.bindingIds().contains(
                                        "project:skip-gateway-project:unit:project-mcp-skill"),
                                "project binding is still materialized");
                        assertTrue(h.sourceOf("project-mcp-skill")
                                        .map(unit -> unit.errors().isEmpty())
                                        .orElse(false),
                                "gateway skip leaves no registration error");
                    }
                })
                .test("materializes direct skills and plugins into project agent homes", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-agent-homes-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "plain-project-skill", DepSpec.empty()).sourcePath();
                        Path plugin = UnitFixtures.scaffoldPlugin(
                                repoRoot.resolve("units"), "plain-project-plugin", DepSpec.empty()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "agent-home-project"

                                [skills.plain]
                                source = "%s"

                                [plugins.plain]
                                source = "%s"
                                """.formatted(skill, plugin));

                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));

                        assertTrue(Files.exists(repoRoot.resolve(".claude/skills/plain-project-skill")),
                                "Claude project skill projection exists");
                        assertTrue(Files.exists(repoRoot.resolve(".codex/skills/plain-project-skill")),
                                "Codex project skill projection exists");
                        assertTrue(Files.exists(repoRoot.resolve(".gemini/skills/plain-project-skill")),
                                "Gemini project skill projection exists");
                        assertTrue(Files.exists(repoRoot.resolve(".claude/plugins/plain-project-plugin")),
                                "Claude project plugin projection exists");
                        assertTrue(Files.isRegularFile(repoRoot
                                        .resolve(".skill-manager/plugins/plain-project-plugin/.claude-plugin/plugin.json")),
                                "plugin projected into project child store");
                        assertTrue(pointsTo(
                                        repoRoot.resolve(".codex/skills/plain-project-skill"),
                                        repoRoot.resolve(".skill-manager/skills/plain-project-skill")),
                                "skill projection points at project child store");
                        assertTrue(pointsTo(
                                        repoRoot.resolve(".claude/plugins/plain-project-plugin"),
                                        repoRoot.resolve(".skill-manager/plugins/plain-project-plugin")),
                                "plugin projection points at project child store");
                        assertTrue(result.bindingIds().contains("project:agent-home-project:unit:plain-project-skill"),
                                "direct skill binding is locked");
                        assertTrue(result.bindingIds().contains("project:agent-home-project:unit:plain-project-plugin"),
                                "direct plugin binding is locked");
                        assertTrue(new BindingStore(h.store()).read("plain-project-skill")
                                        .findById("project:agent-home-project:unit:plain-project-skill")
                                        .isPresent(),
                                "direct skill binding ledger recorded");
                    }
                })
                .test("project remove prunes agent links even when projection ledger is missing", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-remove-missing-ledger-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "missing-ledger-skill", DepSpec.empty()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "missing-ledger-project"

                                [skills.demo]
                                source = "%s"
                                """.formatted(skill));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));

                        assertTrue(Files.exists(repoRoot.resolve(".claude/skills/missing-ledger-skill")),
                                "Claude projection exists before remove");
                        assertTrue(Files.exists(repoRoot.resolve(".codex/skills/missing-ledger-skill")),
                                "Codex projection exists before remove");
                        assertTrue(Files.exists(repoRoot.resolve(".gemini/skills/missing-ledger-skill")),
                                "Gemini projection exists before remove");

                        new BindingStore(h.store()).delete("missing-ledger-skill");
                        Path replacedProjection = repoRoot.resolve(".codex/skills/missing-ledger-skill");
                        Files.delete(replacedProjection);
                        Files.createDirectories(replacedProjection);
                        Files.writeString(replacedProjection.resolve("user.txt"), "user content\n");
                        ProjectRemoveUseCase.Result removed = new ProjectRemoveUseCase(h.store(), null)
                                .remove(project);

                        assertEquals(1, removed.bindingsRemoved(),
                                "lock-backed fallback counts the project unit binding");
                        assertFalse(Files.exists(repoRoot.resolve(".claude/skills/missing-ledger-skill")),
                                "Claude projection removed without ledger");
                        assertTrue(Files.isRegularFile(replacedProjection.resolve("user.txt")),
                                "ledger fallback does not delete user-owned replacement");
                        assertFalse(Files.exists(repoRoot.resolve(".gemini/skills/missing-ledger-skill")),
                                "Gemini projection removed without ledger");
                    }
                })
                .test("binds selected doc repo source into project root", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-doc-");
                        Path doc = scaffoldDocRepo(repoRoot.resolve("units"), "project-prompts");
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "doc-project"

                                [docs.review]
                                source = "%s"
                                """.formatted(doc));

                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));

                        assertTrue(h.store().containsDocRepo("project-prompts"), "doc repo installed");
                        assertTrue(Files.isRegularFile(repoRoot.resolve("docs/agents/review.md")),
                                "managed doc copy materialized");
                        assertTrue(Files.readString(repoRoot.resolve("CLAUDE.md")).contains("docs/agents/review.md"),
                                "CLAUDE.md import directive materialized");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/docs/project-prompts/skill-manager.toml")),
                                "doc repo projected into project child store");
                        assertTrue(new ChildHomeRegistry(h.store()).childHomesClaiming("project-prompts")
                                .contains("project:doc-project"), "parent registry claims project child doc");
                        assertEquals(1, result.bindingIds().size(), "one selected doc binding");
                        var ledger = new BindingStore(h.store()).read("project-prompts");
                        assertTrue(ledger.findById("project:doc-project:doc:project-prompts:review").isPresent(),
                                "stable project doc binding id recorded");
                    }
                })
                .test("materializes harness bindings into project agent homes", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-harness-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "harness-skill", DepSpec.empty()).sourcePath();
                        Path doc = scaffoldDocRepo(repoRoot.resolve("units"), "harness-prompts");
                        Path harness = scaffoldHarness(repoRoot.resolve("units"), "project-harness", skill, doc);
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "harness-project"

                                [harnesses.default]
                                source = "%s"
                                """.formatted(harness));

                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));

                        assertTrue(Files.exists(repoRoot.resolve(".codex/skills/harness-skill")),
                                "Codex project skill projection exists");
                        assertTrue(Files.exists(repoRoot.resolve(".claude/skills/harness-skill")),
                                "Claude project skill projection exists");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/skills/harness-skill/SKILL.md")),
                                "skill projected into project child store");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/harnesses/project-harness/harness.toml")),
                                "harness projected into project child store");
                        assertTrue(Files.isDirectory(repoRoot.resolve(".gemini")),
                                "Gemini child agent home scaffolded");
                        assertEquals(repoRoot.resolve(".skill-manager").toAbsolutePath().normalize(),
                                result.childHome().layout().childSkillManagerHome(),
                                "child home is project-local .skill-manager");
                        assertTrue(new ChildHomeRegistry(h.store()).exists("project:harness-project"),
                                "parent registry records project child home");
                        assertTrue(new ChildHomeRegistry(h.store()).childHomesClaiming("harness-skill")
                                .contains("project:harness-project"), "parent registry claims child skill");
                        assertTrue(pointsTo(
                                        repoRoot.resolve(".codex/skills/harness-skill"),
                                        repoRoot.resolve(".skill-manager/skills/harness-skill")),
                                "harness projection points at child store skill");
                        assertTrue(Files.isRegularFile(repoRoot.resolve("docs/agents/review.md")),
                                "harness doc copy exists");
                        assertFalse(result.lock().bindings().isEmpty(), "harness bindings locked");
                    }
                })
                .test("resolves named profiles into distinct project child homes", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-profiles-");
                        Path devSkill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "profile-dev-skill", DepSpec.empty()).sourcePath();
                        Path reviewSkill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "profile-review-skill", DepSpec.empty()).sourcePath();
                        SkillProject base = project(repoRoot, """
                                [project]
                                name = "profiled-project"

                                [skills.dev]
                                source = "%s"

                                [skills.review]
                                source = "%s"

                                [profiles.dev]
                                skills = ["dev"]

                                [profiles.review]
                                skills = ["review"]
                                """.formatted(devSkill, reviewSkill));

                        ProjectDependencyResolver.Result dev = resolver(h).resolve(
                                base.withProfile("dev"),
                                new ProjectDependencyResolver.Options(true, false));
                        ProjectDependencyResolver.Result review = resolver(h).resolve(
                                base.withProfile("review"),
                                new ProjectDependencyResolver.Options(true, false));

                        Path devHome = repoRoot.resolve(".skill-manager/profiles/dev");
                        Path reviewHome = repoRoot.resolve(".skill-manager/profiles/review");
                        assertEquals(devHome.toAbsolutePath().normalize(),
                                dev.childHome().layout().childSkillManagerHome(),
                                "dev child home");
                        assertEquals(reviewHome.toAbsolutePath().normalize(),
                                review.childHome().layout().childSkillManagerHome(),
                                "review child home");
                        assertTrue(Files.isRegularFile(devHome.resolve("skills/profile-dev-skill/SKILL.md")),
                                "dev skill projected into dev profile home");
                        assertFalse(Files.exists(devHome.resolve("skills/profile-review-skill")),
                                "review skill does not leak into dev profile home");
                        assertTrue(Files.isRegularFile(reviewHome.resolve("skills/profile-review-skill/SKILL.md")),
                                "review skill projected into review profile home");
                        assertFalse(Files.exists(reviewHome.resolve("skills/profile-dev-skill")),
                                "dev skill does not leak into review profile home");
                        assertTrue(Files.isDirectory(devHome.resolve("agents/codex")),
                                "dev profile codex home scaffolded");
                        assertTrue(Files.isDirectory(reviewHome.resolve("agents/claude")),
                                "review profile claude home scaffolded");

                        SkillProjectLock devLock = new SkillProjectLockStore(h.store())
                                .read("profiled-project--dev")
                                .orElseThrow();
                        SkillProjectLock reviewLock = new SkillProjectLockStore(h.store())
                                .read("profiled-project--review")
                                .orElseThrow();
                        assertEquals("dev", devLock.profile(), "dev profile lock");
                        assertEquals("review", reviewLock.profile(), "review profile lock");
                        assertTrue(devLock.resolvedUnits().stream()
                                        .anyMatch(u -> u.name().equals("profile-dev-skill")),
                                "dev lock records dev skill");
                        assertFalse(devLock.resolvedUnits().stream()
                                        .anyMatch(u -> u.name().equals("profile-review-skill")),
                                "dev lock excludes review skill");

                        ChildHomeRegistry registry = new ChildHomeRegistry(h.store());
                        assertTrue(registry.exists("project:profiled-project:profile:dev"),
                                "parent registry records dev profile child");
                        assertTrue(registry.exists("project:profiled-project:profile:review"),
                                "parent registry records review profile child");
                    }
                })
                .test("plain remove is blocked while a project lock claims the unit", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-remove-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "claimed-skill", DepSpec.empty()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "claimed-project"

                                [skills.claimed]
                                source = "%s"
                                """.formatted(skill));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));

                        boolean blocked = false;
                        try {
                            RemoveUseCase.buildProgram(h.store(), null, "claimed-skill", java.util.List.of(), false);
                        } catch (java.io.IOException e) {
                            blocked = e.getMessage().contains("claimed-project");
                        }
                        assertTrue(blocked, "project lock blocks plain remove");
                    }
                })
                .test("re-resolve updates project child store and parent child-home claims", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-child-update-");
                        Path first = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "first-child-skill", DepSpec.empty()).sourcePath();
                        Path second = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "second-child-skill", DepSpec.empty()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "update-project"

                                [skills.first]
                                source = "%s"
                                """.formatted(first));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));
                        assertTrue(Files.exists(repoRoot.resolve(".codex/skills/first-child-skill")),
                                "first skill projected into project agent home");

                        project = project(repoRoot, """
                                [project]
                                name = "update-project"

                                [skills.second]
                                source = "%s"
                                """.formatted(second));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));

                        assertFalse(Files.exists(repoRoot.resolve(".skill-manager/skills/first-child-skill")),
                                "removed project dependency pruned from child store");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/skills/second-child-skill/SKILL.md")),
                                "new project dependency present in child store");
                        assertFalse(Files.exists(repoRoot.resolve(".codex/skills/first-child-skill")),
                                "removed project dependency pruned from Codex home");
                        assertTrue(Files.exists(repoRoot.resolve(".codex/skills/second-child-skill")),
                                "new project dependency projected into Codex home");
                        ChildHomeRegistry registry = new ChildHomeRegistry(h.store());
                        assertFalse(registry.childHomesClaiming("first-child-skill").contains("project:update-project"),
                                "old unit is no longer claimed by project child home");
                        assertTrue(registry.childHomesClaiming("second-child-skill").contains("project:update-project"),
                                "new unit is claimed by project child home");
                    }
                })
                .test("re-resolve prunes old project agent links when ledger is missing", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-reresolve-missing-ledger-");
                        Path first = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "missing-ledger-reresolve-first", DepSpec.empty())
                                .sourcePath();
                        Path second = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "missing-ledger-reresolve-second", DepSpec.empty())
                                .sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "reresolve-missing-ledger-project"

                                [skills.first]
                                source = "%s"
                                """.formatted(first));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));

                        new BindingStore(h.store()).delete("missing-ledger-reresolve-first");
                        Path replacedProjection = repoRoot.resolve(".codex/skills/missing-ledger-reresolve-first");
                        Files.delete(replacedProjection);
                        Files.createDirectories(replacedProjection);
                        Files.writeString(replacedProjection.resolve("user.txt"), "user content\n");

                        project = project(repoRoot, """
                                [project]
                                name = "reresolve-missing-ledger-project"

                                [skills.second]
                                source = "%s"
                                """.formatted(second));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));

                        assertFalse(Files.exists(repoRoot.resolve(".claude/skills/missing-ledger-reresolve-first")),
                                "removed dependency is pruned from Claude home without ledger");
                        assertTrue(Files.isRegularFile(replacedProjection.resolve("user.txt")),
                                "missing-ledger fallback does not delete user-owned replacement");
                        assertFalse(Files.exists(repoRoot.resolve(".gemini/skills/missing-ledger-reresolve-first")),
                                "removed dependency is pruned from Gemini home without ledger");
                        assertTrue(Files.exists(repoRoot.resolve(".codex/skills/missing-ledger-reresolve-second")),
                                "new dependency is projected into Codex home");
                    }
                })
                .test("placeholder project sync clears generated child store and resolves again", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-sync-placeholder-");
                        Path doc = scaffoldDocRepo(repoRoot.resolve("units"), "sync-prompts");
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "sync-project"

                                [docs.prompts]
                                source = "%s"
                                """.formatted(doc));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));
                        Path staleShim = repoRoot.resolve(".skill-manager/bin/cli/stale-tool");
                        Files.createDirectories(staleShim.getParent());
                        Files.writeString(staleShim, "stale\n");

                        ProjectSyncUseCase.Result result = new ProjectSyncUseCase(h.store(), null)
                                .sync(project, new ProjectDependencyResolver.Options(true, false));

                        assertEquals(1, result.bindingsRemoved(), "placeholder sync removes old project binding");
                        assertFalse(Files.exists(staleShim), "generated child-store bin directory is cleared");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/docs/sync-prompts/skill-manager.toml")),
                                "project child doc is reinstalled");
                        assertTrue(Files.isRegularFile(repoRoot.resolve("docs/agents/review.md")),
                                "project doc binding is re-materialized");
                        assertTrue(h.store().containsDocRepo("sync-prompts"),
                                "parent store dependency remains installed");
                        assertTrue(new ChildHomeRegistry(h.store()).exists("project:sync-project"),
                                "parent child-home registry is recreated");
                        assertEquals("sync-project", result.resolved().lock().projectName(),
                                "project lock is rewritten");
                    }
                })
                .test("local sync --from refreshes claiming project child homes when CLI deps change", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-sync-claiming-cli-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "project-cli-skill", DepSpec.empty()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "project-cli-refresh"

                                [skills.demo]
                                source = "%s"
                                """.formatted(skill));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));
                        assertFalse(Files.exists(repoRoot.resolve(".skill-manager/bin/cli/project-auto-cli")),
                                "project child home starts without the new CLI shim");

                        addSkillScriptCli(skill, "project-auto-cli");
                        var program = SyncUseCase.buildProgram(
                                h.store(),
                                null,
                                new SyncUseCase.Options(null, false, false, false, false, true),
                                java.util.List.of(new SyncUseCase.Target.FromDir("project-cli-skill", skill)),
                                java.util.List.of());
                        Executor.Outcome<SyncUseCase.Report> outcome =
                                new Executor(h.store(), null).runStaged(program);

                        assertFalse(outcome.rolledBack(), "parent sync does not roll back");
                        assertEquals(0, outcome.result().errorCount(), "project refresh has no errors");
                        assertTrue(Files.isExecutable(h.store().cliBinDir().resolve("project-auto-cli")),
                                "parent CLI shim installed");
                        assertTrue(Files.isExecutable(repoRoot.resolve(".skill-manager/bin/cli/project-auto-cli")),
                                "claiming project child home mirrors new CLI shim");
                    }
                })
                .test("global sync refreshes claiming project child homes when CLI deps change", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-global-sync-claiming-cli-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("git-skill"), "global-project-cli-skill", DepSpec.empty()).sourcePath();
                        gitInitCommit(skill);
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "project-global-cli-refresh"

                                [skills.demo]
                                source = "git+file://%s#main"
                                """.formatted(skill));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));
                        assertFalse(Files.exists(repoRoot.resolve(".skill-manager/bin/cli/project-global-auto-cli")),
                                "project child home starts without the new CLI shim");

                        addSkillScriptCli(skill, "project-global-auto-cli");
                        git(skill, "add", ".");
                        git(skill, "-c", "user.email=test@example.com", "-c", "user.name=Test",
                                "commit", "-m", "add cli");

                        SyncCommand cmd = new SyncCommand(h.store());
                        cmd.skipMcp = true;
                        cmd.skipAgents = true;
                        int rc = cmd.call();

                        assertEquals(0, rc, "global sync exits cleanly");
                        assertTrue(Files.isExecutable(h.store().cliBinDir().resolve("project-global-auto-cli")),
                                "parent CLI shim installed by global sync");
                        assertTrue(Files.isExecutable(repoRoot.resolve(".skill-manager/bin/cli/project-global-auto-cli")),
                                "claiming project child home mirrors new CLI shim after global sync");
                    }
                })
                .test("project sync failures are recorded only on units claiming the failed project", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-sync-error-scope-");
                        Path claimed = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "failing-claimed-skill", DepSpec.empty()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "failing-claimed-project"

                                [skills.demo]
                                source = "%s"
                                """.formatted(claimed));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));
                        h.seedUnit("unrelated-sync-skill", UnitKind.SKILL);
                        h.context().addError("unrelated-sync-skill",
                                InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED, "stale");
                        Files.delete(h.store().projectsDir()
                                .resolve("failing-claimed-project")
                                .resolve(SkillProjectRegistry.REGISTRATION_FILENAME));

                        var receipt = h.run(new SkillEffect.SyncClaimingProjects(
                                java.util.List.of("failing-claimed-skill", "unrelated-sync-skill"),
                                null,
                                false));

                        assertEquals(dev.skillmanager.effects.EffectStatus.PARTIAL, receipt.status(),
                                "missing project snapshot is reported as partial");
                        assertTrue(h.sourceOf("failing-claimed-skill").orElseThrow()
                                        .hasError(InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED),
                                "claiming unit records project sync failure");
                        assertFalse(h.sourceOf("unrelated-sync-skill").orElseThrow()
                                        .hasError(InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED),
                                "unrelated sync target has stale project sync failure cleared");
                    }
                })
                .test("successful project sync clears stale project errors for all project claimants", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-sync-error-clear-claimants-");
                        Path first = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "project-clear-first", DepSpec.empty()).sourcePath();
                        Path second = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "project-clear-second", DepSpec.empty()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "clear-claimants-project"

                                [skills.first]
                                source = "%s"

                                [skills.second]
                                source = "%s"
                                """.formatted(first, second));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));
                        h.context().addError("project-clear-first",
                                InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED, "stale first");
                        h.context().addError("project-clear-second",
                                InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED, "stale second");

                        var receipt = h.run(new SkillEffect.SyncClaimingProjects(
                                java.util.List.of("project-clear-first"),
                                null,
                                false));

                        assertEquals(dev.skillmanager.effects.EffectStatus.OK, receipt.status(),
                                "project refresh succeeds");
                        assertFalse(h.sourceOf("project-clear-first").orElseThrow()
                                        .hasError(InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED),
                                "synced claimant has stale project sync failure cleared");
                        assertFalse(h.sourceOf("project-clear-second").orElseThrow()
                                        .hasError(InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED),
                                "other project claimant has stale project sync failure cleared");
                    }
                })
                .test("automatic project sync rollback preserves existing realization on refresh failure", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-sync-rollback-");
                        Path stable = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "project-rollback-stable", DepSpec.empty()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "rollback-refresh-project"

                                [skills.stable]
                                source = "%s"
                                """.formatted(stable));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));

                        Path codexProjection = repoRoot.resolve(".codex/skills/project-rollback-stable");
                        Path childSkill = repoRoot.resolve(".skill-manager/skills/project-rollback-stable");
                        Path ledger = h.store().installedDir()
                                .resolve("project-rollback-stable.projections.json");
                        assertTrue(Files.exists(codexProjection, LinkOption.NOFOLLOW_LINKS),
                                "project Codex projection exists before sync");
                        assertTrue(Files.exists(childSkill, LinkOption.NOFOLLOW_LINKS),
                                "project child skill exists before sync");
                        assertTrue(Files.isRegularFile(ledger), "project binding ledger exists before sync");

                        Files.writeString(h.store().projectsDir()
                                .resolve("rollback-refresh-project")
                                .resolve("skill-project.toml"), """
                                [project]
                                name = "rollback-refresh-project"

                                [skills.stable]
                                source = "%s"

                                [skills.missing]
                                source = "%s"
                                """.formatted(stable, repoRoot.resolve("missing-skill")));

                        var receipt = h.run(new SkillEffect.SyncClaimingProjects(
                                java.util.List.of("project-rollback-stable"),
                                null,
                                false));

                        assertEquals(dev.skillmanager.effects.EffectStatus.PARTIAL, receipt.status(),
                                "invalid project snapshot reports partial project sync failure");
                        assertTrue(Files.exists(codexProjection, LinkOption.NOFOLLOW_LINKS),
                                "failed automatic refresh keeps previous Codex projection");
                        assertTrue(Files.exists(childSkill, LinkOption.NOFOLLOW_LINKS),
                                "failed automatic refresh keeps previous child-home skill");
                        assertTrue(pointsTo(codexProjection, childSkill),
                                "restored Codex projection still points at the child home");
                        assertTrue(Files.isRegularFile(ledger),
                                "failed automatic refresh keeps previous binding ledger");
                        assertTrue(new BindingStore(h.store()).read("project-rollback-stable")
                                        .findById("project:rollback-refresh-project:unit:project-rollback-stable")
                                        .isPresent(),
                                "failed automatic refresh keeps previous binding row");
                        assertTrue(new ChildHomeRegistry(h.store()).exists("project:rollback-refresh-project"),
                                "failed automatic refresh keeps child-home registry");
                    }
                })
                .test("project sync rollback restores prior registration snapshot on refresh failure", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-sync-registration-rollback-");
                        Path stable = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "project-registration-stable", DepSpec.empty()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "registration-rollback-project"

                                [skills.stable]
                                source = "%s"
                                """.formatted(stable));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));
                        Path registeredManifest = h.store().projectsDir()
                                .resolve("registration-rollback-project")
                                .resolve("skill-project.toml");
                        String previousRegistration = Files.readString(registeredManifest);

                        Files.writeString(repoRoot.resolve("skill-project.toml"), """
                                [project]
                                name = "registration-rollback-project"

                                [skills.stable]
                                source = "%s"

                                [skills.missing]
                                source = "%s"
                                """.formatted(stable, repoRoot.resolve("missing-skill")));
                        SkillProject broken = SkillProjectParser.load(repoRoot);

                        boolean failed = false;
                        try {
                            new ProjectSyncUseCase(h.store(), null)
                                    .sync(broken, new ProjectDependencyResolver.Options(true, false));
                        } catch (java.io.IOException io) {
                            failed = true;
                        }

                        assertTrue(failed, "broken project sync fails");
                        assertEquals(previousRegistration, Files.readString(registeredManifest),
                                "failed project sync restores previous registered manifest");
                        assertFalse(Files.readString(registeredManifest).contains("missing-skill"),
                                "failed project sync does not leave broken registration snapshot");
                        assertTrue(Files.exists(repoRoot.resolve(".codex/skills/project-registration-stable"),
                                        LinkOption.NOFOLLOW_LINKS),
                                "failed project sync keeps previous project projection");
                    }
                })
                .test("preinstalled unit with wrong kind is rejected before skip", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        h.seedUnit("kind-conflict", UnitKind.SKILL);
                        h.scaffoldUnitDir("kind-conflict", UnitKind.SKILL);
                        Path repoRoot = Files.createTempDirectory("project-resolve-kind-conflict-");
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "kind-conflict-project"

                                [plugins.conflict]
                                source = "plugin:kind-conflict"
                                """);

                        boolean rejected = false;
                        try {
                            resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));
                        } catch (java.io.IOException e) {
                            rejected = e.getMessage().contains("expected PLUGIN")
                                    && e.getMessage().contains("SKILL");
                        }
                        assertTrue(rejected, "existing wrong-kind unit is rejected");
                    }
                })
                .test("direct git dependency is recorded in the project lock", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-direct-git-");
                        Path gitSkill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("git-skill"), "git-locked-skill", DepSpec.empty()).sourcePath();
                        gitInitCommit(gitSkill);
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "git-project"

                                [skills.git]
                                source = "git+file://%s#main"
                                """.formatted(gitSkill));

                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));

                        Set<String> locked = result.lock().resolvedUnits().stream()
                                .map(SkillProjectLock.ResolvedUnit::name)
                                .collect(Collectors.toSet());
                        assertTrue(locked.contains("git-locked-skill"), "direct git unit is locked");
                        assertTrue(new SkillProjectLockStore(h.store()).projectsClaiming("git-locked-skill")
                                .contains("git-project"), "direct git unit is project-claimed");
                        assertTrue(Files.exists(repoRoot.resolve(".codex/skills/git-locked-skill")),
                                "direct git skill projected into project agent home");
                    }
                })
                .test("direct git transitive uses manifest identity in project lock and child homes", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-transitive-git-");
                        Path stagedChild = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("staging"), "acp-cdc-ai-python", DepSpec.empty()).sourcePath();
                        Path gitChild = repoRoot.resolve("acp-cdc-ai-python-skill");
                        Files.move(stagedChild, gitChild);
                        gitInitCommit(gitChild);

                        Path parent = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "hyper-experiments",
                                DepSpec.of()
                                        .ref("git+" + gitChild.toUri() + "#main")
                                        .build()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "transitive-git-project"

                                [skills.hyper]
                                source = "%s"
                                """.formatted(parent));

                        ProjectDependencyResolver.Result first = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));
                        ProjectDependencyResolver.Result second = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));

                        assertTrue(h.store().containsUnit("acp-cdc-ai-python"),
                                "transitive unit installed by manifest name");
                        assertFalse(h.store().containsUnit("acp-cdc-ai-python-skill"),
                                "repository basename is not treated as the unit name");
                        SkillProjectLock.ResolvedUnit child = second.lock().resolvedUnits().stream()
                                .filter(u -> u.name().equals("acp-cdc-ai-python"))
                                .findFirst()
                                .orElseThrow();
                        assertFalse(child.direct(), "transitive direct-git child is not marked direct");
                        assertTrue(first.lock().resolvedUnits().stream()
                                        .anyMatch(u -> u.name().equals("acp-cdc-ai-python")),
                                "first resolve locks transitive direct-git child");
                        assertTrue(Files.isRegularFile(repoRoot
                                        .resolve(".skill-manager/skills/acp-cdc-ai-python/SKILL.md")),
                                "transitive child copied into project child store");
                        assertTrue(pointsTo(
                                        repoRoot.resolve(".codex/skills/acp-cdc-ai-python"),
                                        repoRoot.resolve(".skill-manager/skills/acp-cdc-ai-python")),
                                "Codex projection uses manifest identity");
                        assertTrue(pointsTo(
                                        repoRoot.resolve(".gemini/skills/acp-cdc-ai-python"),
                                        repoRoot.resolve(".skill-manager/skills/acp-cdc-ai-python")),
                                "Gemini projection uses manifest identity");
                        assertTrue(new ChildHomeRegistry(h.store())
                                        .childHomesClaiming("acp-cdc-ai-python")
                                        .contains("project:transitive-git-project"),
                                "project child-home registry claims transitive unit");
                        assertEquals(0, second.installed().size(),
                                "second resolve reuses the complete installed closure");
                    }
                })
                .test("preinstalled parent cannot silently omit a missing direct git child", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-missing-transitive-git-");
                        Path missingChild = repoRoot.resolve("missing-child-repository");
                        Path parent = UnitFixtures.scaffoldSkill(
                                h.store().skillsDir(), "preinstalled-parent",
                                DepSpec.of()
                                        .ref("git+" + missingChild.toUri() + "#main")
                                        .build()).sourcePath();
                        h.seedUnit("preinstalled-parent", UnitKind.SKILL);
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "missing-transitive-git-project"

                                [skills.parent]
                                source = "%s"
                                """.formatted(parent));

                        boolean rejected = false;
                        try {
                            resolver(h).resolve(
                                    project,
                                    new ProjectDependencyResolver.Options(true, false));
                        } catch (java.io.IOException io) {
                            rejected = io.getMessage().contains("project dependency closure is missing")
                                    && io.getMessage().contains("preinstalled-parent")
                                    && io.getMessage().contains("missing-child-repository")
                                    && io.getMessage().contains(
                                            "skill-manager sync preinstalled-parent");
                        }
                        assertTrue(rejected,
                                "incomplete reused-parent closure fails with an actionable error");
                    }
                })
                .test("preinstalled direct git dependency is projected into project child home", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-preinstalled-git-");
                        Path gitSkill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("git-skill"), "preinstalled-git-skill", DepSpec.empty()).sourcePath();
                        gitInitCommit(gitSkill);

                        Path firstRoot = repoRoot.resolve("first-project");
                        Path secondRoot = repoRoot.resolve("second-project");
                        Files.createDirectories(firstRoot);
                        Files.createDirectories(secondRoot);
                        SkillProject first = project(firstRoot, """
                                [project]
                                name = "first-git-project"

                                [skills.git]
                                source = "git+file://%s"
                                """.formatted(gitSkill));
                        resolver(h).resolve(first, new ProjectDependencyResolver.Options(true, false));
                        assertTrue(h.store().containsUnit("preinstalled-git-skill"), "parent unit installed");

                        SkillProject second = project(secondRoot, """
                                [project]
                                name = "second-git-project"

                                [skills.git]
                                source = "git+file://%s"
                                """.formatted(gitSkill));
                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                second,
                                new ProjectDependencyResolver.Options(true, false));

                        assertEquals(0, result.installed().size(), "second project reuses parent install");
                        assertTrue(result.lock().resolvedUnits().stream()
                                        .anyMatch(u -> u.name().equals("preinstalled-git-skill")
                                                && u.direct()),
                                "second project lock records direct git skill");
                        assertTrue(Files.isRegularFile(secondRoot
                                        .resolve(".skill-manager/skills/preinstalled-git-skill/SKILL.md")),
                                "preinstalled git skill projected into child home");
                        assertTrue(Files.exists(secondRoot.resolve(".codex/skills/preinstalled-git-skill")),
                                "preinstalled git skill projected into project agent home");
                        assertTrue(new ChildHomeRegistry(h.store()).childHomesClaiming("preinstalled-git-skill")
                                        .contains("project:second-git-project"),
                                "parent child-home registry claims reused git skill");
                    }
                })
                .test("direct git doc and harness dependencies materialize project bindings", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-direct-git-bindings-");
                        Path gitDoc = scaffoldDocRepo(repoRoot.resolve("git-doc"), "git-prompts");
                        gitInitCommit(gitDoc);
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "git-harness-skill", DepSpec.empty()).sourcePath();
                        Path gitHarness = scaffoldHarness(
                                repoRoot.resolve("git-harness"), "git-project-harness", skill, null);
                        gitInitCommit(gitHarness);
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "git-binding-project"

                                [docs.review]
                                source = "git+file://%s"

                                [harnesses.default]
                                source = "git+file://%s"
                                """.formatted(gitDoc, gitHarness));

                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));

                        assertTrue(Files.isRegularFile(repoRoot.resolve("docs/agents/review.md")),
                                "direct git doc binding materialized");
                        assertTrue(Files.exists(repoRoot.resolve(".codex/skills/git-harness-skill")),
                                "direct git harness binding materialized");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/docs/git-prompts/skill-manager.toml")),
                                "direct git doc projected into project child store");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/harnesses/git-project-harness/harness.toml")),
                                "direct git harness projected into project child store");
                        assertTrue(result.bindingIds().stream()
                                        .anyMatch(id -> id.contains("git-prompts")),
                                "direct git doc binding id recorded");
                        assertTrue(result.bindingIds().stream()
                                        .anyMatch(id -> id.contains("git-harness-skill")),
                                "direct git harness binding id recorded");
                    }
                })
                .test("manifest revision overrides direct git default branch", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-revision-");
                        Path gitSkill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("revision-skill"), "revision-skill", DepSpec.empty()).sourcePath();
                        gitInitCommit(gitSkill);
                        git(gitSkill, "checkout", "-b", "release");
                        Files.writeString(gitSkill.resolve("skill-manager.toml"), """
                                [skill]
                                name = "revision-skill"
                                version = "9.9.9"
                                description = "revision fixture"
                                """);
                        git(gitSkill, "add", ".");
                        git(gitSkill, "-c", "user.email=test@example.com", "-c", "user.name=Test",
                                "commit", "-m", "release");
                        git(gitSkill, "checkout", "main");
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "revision-project"

                                [skills.rev]
                                source = "git+file://%s"
                                revision = "release"
                                """.formatted(gitSkill));

                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));

                        assertEquals("9.9.9", h.store().loadUnit("revision-skill").orElseThrow().version(),
                                "project dependency revision selects the declared git ref");
                    }
                })
                .test("project remove clears registration child home and bindings but keeps parent unit", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-remove-");
                        Path doc = scaffoldDocRepo(repoRoot.resolve("units"), "remove-prompts");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "remove-skill", DepSpec.empty()).sourcePath();
                        Path plugin = UnitFixtures.scaffoldPlugin(
                                repoRoot.resolve("units"), "remove-plugin", DepSpec.empty()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "remove-project"

                                [skills.remove]
                                source = "%s"

                                [plugins.remove]
                                source = "%s"

                                [docs.prompts]
                                source = "%s"
                                """.formatted(skill, plugin, doc));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));
                        assertTrue(Files.isRegularFile(repoRoot.resolve("docs/agents/review.md")),
                                "doc binding exists before remove");
                        assertTrue(Files.exists(repoRoot.resolve(".claude/skills/remove-skill")),
                                "Claude agent skill binding exists before remove");
                        assertTrue(Files.exists(repoRoot.resolve(".codex/skills/remove-skill")),
                                "Codex agent skill binding exists before remove");
                        assertTrue(Files.exists(repoRoot.resolve(".gemini/skills/remove-skill")),
                                "Gemini agent skill binding exists before remove");
                        assertTrue(Files.exists(repoRoot.resolve(".claude/plugins/remove-plugin")),
                                "Claude plugin binding exists before remove");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/docs/remove-prompts/skill-manager.toml")),
                                "child doc exists before remove");

                        ProjectRemoveUseCase.Result removed = new ProjectRemoveUseCase(h.store(), null)
                                .remove(project);

                        assertEquals(3, removed.bindingsRemoved(), "project bindings removed");
                        assertFalse(Files.isDirectory(h.store().projectsDir().resolve("remove-project")),
                                "project registration removed");
                        assertFalse(new ChildHomeRegistry(h.store()).exists("project:remove-project"),
                                "child-home registry removed");
                        assertFalse(Files.exists(repoRoot.resolve(".skill-manager/docs/remove-prompts")),
                                "child store doc projection removed");
                        assertFalse(Files.exists(repoRoot.resolve(".skill-manager/plugins/remove-plugin")),
                                "child store plugin projection removed");
                        assertFalse(Files.exists(repoRoot.resolve("docs/agents/review.md")),
                                "managed doc copy removed");
                        assertFalse(Files.exists(repoRoot.resolve(".claude/skills/remove-skill")),
                                "Claude agent skill projection removed");
                        assertFalse(Files.exists(repoRoot.resolve(".codex/skills/remove-skill")),
                                "Codex agent skill projection removed");
                        assertFalse(Files.exists(repoRoot.resolve(".gemini/skills/remove-skill")),
                                "Gemini agent skill projection removed");
                        assertFalse(Files.exists(repoRoot.resolve(".claude/plugins/remove-plugin")),
                                "Claude plugin projection removed");
                        assertFalse(Files.exists(repoRoot.resolve(".codex")),
                                "empty Codex project home removed");
                        assertTrue(h.store().containsDocRepo("remove-prompts"),
                                "parent doc repo remains installed");
                        assertTrue(h.store().containsPlugin("remove-plugin"),
                                "parent plugin remains installed");
                    }
                })
                .runAll();
    }

    private static ProjectDependencyResolver resolver(TestHarness h) {
        return new ProjectDependencyResolver(h.store(), null);
    }

    private static SkillProject project(Path root, String manifest) throws Exception {
        Files.writeString(root.resolve("skill-project.toml"), manifest);
        return SkillProjectParser.load(root);
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
        Files.writeString(dir.resolve("review.md"), "review guidance\n");
        return dir;
    }

    private static Path scaffoldHarness(Path root, String name, Path skill, Path doc) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        String docs = doc == null ? "" : "\ndocs = [\"" + doc + "\"]\n";
        Files.writeString(dir.resolve("harness.toml"), """
                [harness]
                name = "%s"
                version = "0.1.0"
                units = ["%s"]%s
                """.formatted(name, skill, docs));
        return dir;
    }

    private static boolean pointsTo(Path projection, Path expected) throws Exception {
        if (!Files.exists(projection)) return false;
        if (Files.isSymbolicLink(projection)) {
            Path link = Files.readSymbolicLink(projection);
            Path resolved = link.isAbsolute()
                    ? link.normalize()
                    : projection.getParent().resolve(link).normalize();
            return resolved.equals(expected.toAbsolutePath().normalize());
        }
        return projection.toRealPath().equals(expected.toRealPath());
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

    private static void gitInitCommit(Path repo) throws Exception {
        git(repo, "init");
        git(repo, "checkout", "-b", "main");
        git(repo, "add", ".");
        git(repo, "-c", "user.email=test@example.com", "-c", "user.name=Test", "commit", "-m", "initial");
    }

    private static void git(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repo.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new java.io.IOException("git " + String.join(" ", args)
                    + " failed with " + rc + ": " + output);
        }
    }
}
