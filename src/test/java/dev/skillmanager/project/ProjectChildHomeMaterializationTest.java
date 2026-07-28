package dev.skillmanager.project;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeHarnessInstaller;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.bindings.MaterializationMode;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Covers how parent-store units are materialized into a child Skill Manager
 * home — the project child home written by {@code project resolve}, and the
 * one written by {@code harness instantiate --child-home-dir}, which share the
 * same {@code <dir>/.skill-manager} layout.
 *
 * <p>The regressions under test: a symlinked child home means an agent editing
 * {@code <project>/.skill-manager/skills/<unit>} (or the agent-home projection
 * pointing at it) writes straight through into the shared parent store; and a
 * refresh that overwrites the child home destroys whatever the agent did there.
 */
public final class ProjectChildHomeMaterializationTest {

    public static int run() throws Exception {
        return Tests.suite("ProjectChildHomeMaterializationTest")
                .test("default project resolve copies units into the child home", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-copy-default-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "copy-default-skill", DepSpec.empty())
                                .sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "copy-default-project"

                                [skills.demo]
                                source = "%s"
                                """.formatted(skill));

                        new ProjectDependencyResolver(h.store(), null)
                                .resolve(project, new ProjectDependencyResolver.Options(true, false));

                        Path childUnit = repoRoot.resolve(".skill-manager/skills/copy-default-skill");
                        assertFalse(Files.isSymbolicLink(childUnit),
                                "child home unit is not a symlink into the parent store");
                        assertTrue(Files.isDirectory(childUnit, LinkOption.NOFOLLOW_LINKS),
                                "child home unit is a real directory");
                        assertTrue(Files.isRegularFile(childUnit.resolve("SKILL.md")),
                                "child home unit carries its own SKILL.md");
                        assertFalse(childUnit.toRealPath()
                                        .equals(h.store().skillDir("copy-default-skill").toRealPath()),
                                "child home unit is a distinct path from the parent unit");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(
                                        ".skill-manager/.materialization/skill/copy-default-skill.json")),
                                "materialization record written next to the child home");
                        assertEquals(MaterializationMode.COPY,
                                ProjectChildHomeScaffolder.DEFAULT_MODE,
                                "project child homes default to COPY");
                    }
                })
                .test("editing a child home unit leaves the parent store unchanged", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-isolation-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "isolated-skill", DepSpec.empty())
                                .sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "isolation-project"

                                [skills.demo]
                                source = "%s"
                                """.formatted(skill));
                        new ProjectDependencyResolver(h.store(), null)
                                .resolve(project, new ProjectDependencyResolver.Options(true, false));

                        Path parentUnit = h.store().skillDir("isolated-skill");
                        Path parentSkillMd = parentUnit.resolve("SKILL.md");
                        String parentBefore = Files.readString(parentSkillMd);

                        // 1. Direct edit inside the project child home.
                        Path childSkillMd = repoRoot
                                .resolve(".skill-manager/skills/isolated-skill/SKILL.md");
                        Files.writeString(childSkillMd, "AGENT LOCAL EDIT\n");
                        Files.writeString(repoRoot
                                .resolve(".skill-manager/skills/isolated-skill/scratch.md"),
                                "agent scratch\n");

                        // 2. Edit through the agent-home projection an agent
                        //    actually sees (.claude/skills/<unit> -> child home).
                        Path claudeProjection = repoRoot.resolve(".claude/skills/isolated-skill");
                        assertTrue(Files.exists(claudeProjection), "Claude projection exists");
                        Files.writeString(claudeProjection.resolve("via-projection.md"),
                                "written through the agent projection\n");

                        assertEquals(parentBefore, Files.readString(parentSkillMd),
                                "parent store SKILL.md is unchanged by the child home edit");
                        assertFalse(Files.exists(parentUnit.resolve("scratch.md")),
                                "child home addition does not appear in the parent store");
                        assertFalse(Files.exists(parentUnit.resolve("via-projection.md")),
                                "agent-projection write does not reach the parent store");
                        assertEquals("AGENT LOCAL EDIT\n", Files.readString(childSkillMd),
                                "the child home keeps the agent's edit");
                    }
                })
                .test("re-resolve keeps local child home edits and reports them as held back", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-hold-back-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "held-back-skill", DepSpec.empty())
                                .sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "hold-back-project"

                                [skills.demo]
                                source = "%s"
                                """.formatted(skill));
                        ProjectDependencyResolver resolver =
                                new ProjectDependencyResolver(h.store(), null);
                        resolver.resolve(project, new ProjectDependencyResolver.Options(true, false));

                        Path childUnit = repoRoot.resolve(".skill-manager/skills/held-back-skill");
                        Files.writeString(childUnit.resolve("SKILL.md"), "AGENT WORK IN PROGRESS\n");
                        Files.writeString(childUnit.resolve("notes.md"), "agent notes\n");
                        // Parent moves on too, so a refresh would be visible.
                        Files.writeString(h.store().skillDir("held-back-skill").resolve("SKILL.md"),
                                "NEW PARENT CONTENT\n");

                        ProjectDependencyResolver.Result second = resolver.resolve(
                                project, new ProjectDependencyResolver.Options(true, false));

                        assertEquals("AGENT WORK IN PROGRESS\n",
                                Files.readString(childUnit.resolve("SKILL.md")),
                                "re-resolve does not overwrite the agent's edit");
                        assertTrue(Files.isRegularFile(childUnit.resolve("notes.md")),
                                "re-resolve does not delete files the agent added");
                        assertEquals(1, second.childHome().heldBack().size(),
                                "the modified unit is reported as held back");
                        ChildHomeMaterializer.UnitOutcome outcome =
                                second.childHome().heldBack().get(0);
                        assertEquals("held-back-skill", outcome.unitName(), "held-back unit name");
                        assertEquals(ChildHomeMaterializer.Status.SKIPPED_LOCAL_CHANGES,
                                outcome.status(), "held-back status");
                        assertEquals("skill:held-back-skill", outcome.label(), "held-back label");
                    }
                })
                .test("re-resolve refreshes an unmodified child home unit from the parent", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-refresh-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "refresh-skill", DepSpec.empty())
                                .sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "refresh-project"

                                [skills.demo]
                                source = "%s"
                                """.formatted(skill));
                        ProjectDependencyResolver resolver =
                                new ProjectDependencyResolver(h.store(), null);
                        resolver.resolve(project, new ProjectDependencyResolver.Options(true, false));

                        Files.writeString(h.store().skillDir("refresh-skill").resolve("SKILL.md"),
                                "UPGRADED PARENT CONTENT\n");
                        Files.writeString(h.store().skillDir("refresh-skill").resolve("added.md"),
                                "new parent file\n");

                        ProjectDependencyResolver.Result second = resolver.resolve(
                                project, new ProjectDependencyResolver.Options(true, false));

                        Path childUnit = repoRoot.resolve(".skill-manager/skills/refresh-skill");
                        assertEquals("UPGRADED PARENT CONTENT\n",
                                Files.readString(childUnit.resolve("SKILL.md")),
                                "untouched child unit is refreshed from the parent store");
                        assertTrue(Files.isRegularFile(childUnit.resolve("added.md")),
                                "refresh picks up files added in the parent store");
                        assertTrue(second.childHome().heldBack().isEmpty(),
                                "nothing is held back when the child home is untouched");
                    }
                })
                // CHM-15. The three-tier flow leaves a project home holding a
                // tree that is pristine BY ITS OWN RECORD and is a wholesale
                // copy of no store: `home sync --merge` from a worktree writes
                // a record naming the WORKTREE. The downward materialization
                // read only "does the destination still hold what its record
                // says", so it read that record as a licence to refresh from
                // the parent store — and deleted a ticket's work while
                // reporting MATERIALIZED, "copied from the parent store".
                //
                // Asserted on BYTES. The status alone cannot tell a refresh of
                // something stale from a deletion of the only copy.
                .test("a merge from a worktree survives the next parent-store materialization", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path base = Files.createTempDirectory("chm15-seam-");
                        SkillStore project = new SkillStore(base.resolve("project/.skill-manager"));
                        SkillStore worktree = new SkillStore(base.resolve("worktree/.skill-manager"));
                        project.init();
                        worktree.init();

                        Path rootUnit = h.store().unitDir("seam-skill", UnitKind.SKILL);
                        Fs.ensureDir(rootUnit);
                        Files.writeString(rootUnit.resolve("SKILL.md"), "ROOT STORE v1\n");

                        ChildHomeMaterializer intoProject =
                                new ChildHomeMaterializer(h.store(), project);
                        intoProject.materializeUnit("seam-skill", UnitKind.SKILL,
                                MaterializationMode.COPY);
                        new ChildHomeMaterializer(project, worktree)
                                .materializeUnit("seam-skill", UnitKind.SKILL,
                                        MaterializationMode.COPY);

                        Path inWorktree = worktree.unitDir("seam-skill", UnitKind.SKILL);
                        Files.writeString(inWorktree.resolve("SKILL.md"), "THE TICKET'S WORK\n");
                        new ChildHomeMaterializer(worktree, project)
                                .applySync("seam-skill", UnitKind.SKILL, true);
                        Path inProject = project.unitDir("seam-skill", UnitKind.SKILL);
                        assertEquals("THE TICKET'S WORK\n",
                                Files.readString(inProject.resolve("SKILL.md")),
                                "precondition: the merge reached the project home");

                        assertTrue(intoProject.isLocallyModified("seam-skill", UnitKind.SKILL),
                                "a tree merged up from a worktree is not the parent store's to delete");

                        ChildHomeMaterializer.UnitOutcome outcome = intoProject.materializeUnit(
                                "seam-skill", UnitKind.SKILL, MaterializationMode.COPY);
                        assertEquals("THE TICKET'S WORK\n",
                                Files.readString(inProject.resolve("SKILL.md")),
                                "the worktree's merged work survives the next materialization");
                        assertTrue(outcome.heldBack(),
                                "and the unit is reported as held back, not as materialized");
                    }
                })
                // The same rule for the LINK arm: linkPath deletes a real
                // directory to put a symlink where it stood, so a COPY child
                // home somebody edited must not be replaced by a later pass
                // that merely asked for LINK.
                .test("LINK mode does not delete an edited child unit to install a symlink", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path base = Files.createTempDirectory("chm15-link-");
                        SkillStore child = new SkillStore(base.resolve("child/.skill-manager"));
                        child.init();
                        Path rootUnit = h.store().unitDir("link-guard-skill", UnitKind.SKILL);
                        Fs.ensureDir(rootUnit);
                        Files.writeString(rootUnit.resolve("SKILL.md"), "ROOT STORE v1\n");

                        ChildHomeMaterializer m = new ChildHomeMaterializer(h.store(), child);
                        m.materializeUnit("link-guard-skill", UnitKind.SKILL,
                                MaterializationMode.COPY);
                        Path childUnit = child.unitDir("link-guard-skill", UnitKind.SKILL);
                        Files.writeString(childUnit.resolve("SKILL.md"), "AGENT EDIT\n");

                        ChildHomeMaterializer.UnitOutcome outcome = m.materializeUnit(
                                "link-guard-skill", UnitKind.SKILL, MaterializationMode.LINK);
                        assertFalse(Files.isSymbolicLink(childUnit),
                                "the edited directory is not replaced by a symlink");
                        assertEquals("AGENT EDIT\n",
                                Files.readString(childUnit.resolve("SKILL.md")),
                                "LINK mode leaves the agent's edit alone");
                        assertTrue(outcome.heldBack(), "and reports it");
                    }
                })
                .test("project sync keeps local child home edits and reports them", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-sync-hold-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "sync-held-skill", DepSpec.empty())
                                .sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "sync-hold-project"

                                [skills.demo]
                                source = "%s"
                                """.formatted(skill));
                        new ProjectDependencyResolver(h.store(), null)
                                .resolve(project, new ProjectDependencyResolver.Options(true, false));

                        Path childUnit = repoRoot.resolve(".skill-manager/skills/sync-held-skill");
                        Files.writeString(childUnit.resolve("SKILL.md"), "AGENT WORK IN PROGRESS\n");
                        Files.writeString(childUnit.resolve("notes.md"), "agent notes\n");

                        ProjectSyncUseCase.Result synced = new ProjectSyncUseCase(h.store(), null)
                                .sync(project, new ProjectDependencyResolver.Options(true, false));

                        assertEquals("AGENT WORK IN PROGRESS\n",
                                Files.readString(childUnit.resolve("SKILL.md")),
                                "project sync does not overwrite the agent's edit");
                        assertTrue(Files.isRegularFile(childUnit.resolve("notes.md")),
                                "project sync does not delete files the agent added");
                        assertEquals(1, synced.resolved().childHome().heldBack().size(),
                                "project sync reports the unit it held back");
                        assertEquals("skill:sync-held-skill",
                                synced.resolved().childHome().heldBack().get(0).label(),
                                "held-back label from sync");
                        // The skills directory survived to hold the preserved
                        // unit, so sync must not report it as cleared. (Other
                        // generated dirs are legitimately listed: they really
                        // were removed, even though the rebuild recreates them.)
                        Path childSkills = repoRoot.resolve(".skill-manager/skills")
                                .toAbsolutePath().normalize();
                        assertFalse(synced.clearedPaths().stream()
                                        .map(p -> p.toAbsolutePath().normalize())
                                        .anyMatch(childSkills::equals),
                                "a directory kept for a held-back unit is not counted as cleared");
                    }
                })
                .test("project sync keeps local edits when the record is missing or corrupt", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-sync-no-record-");
                        Path missing = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "no-record-skill", DepSpec.empty())
                                .sourcePath();
                        Path corrupt = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "bad-record-skill", DepSpec.empty())
                                .sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "no-record-project"

                                [skills.missing]
                                source = "%s"

                                [skills.corrupt]
                                source = "%s"
                                """.formatted(missing, corrupt));
                        new ProjectDependencyResolver(h.store(), null)
                                .resolve(project, new ProjectDependencyResolver.Options(true, false));

                        Path missingChild = repoRoot.resolve(".skill-manager/skills/no-record-skill");
                        Path corruptChild = repoRoot.resolve(".skill-manager/skills/bad-record-skill");
                        Files.writeString(missingChild.resolve("agent.md"), "agent work\n");
                        Files.writeString(corruptChild.resolve("agent.md"), "agent work\n");
                        // A child home from before records existed, and one whose
                        // record write was interrupted.
                        Files.delete(repoRoot.resolve(
                                ".skill-manager/.materialization/skill/no-record-skill.json"));
                        Files.writeString(repoRoot.resolve(
                                ".skill-manager/.materialization/skill/bad-record-skill.json"),
                                "{\"schemaVersion\":1,\"unitName\":\"bad-rec");

                        ProjectSyncUseCase.Result synced = new ProjectSyncUseCase(h.store(), null)
                                .sync(project, new ProjectDependencyResolver.Options(true, false));

                        assertTrue(Files.isRegularFile(missingChild.resolve("agent.md")),
                                "sync keeps a unit whose record is missing");
                        assertTrue(Files.isRegularFile(corruptChild.resolve("agent.md")),
                                "sync keeps a unit whose record is corrupt");
                        assertEquals(2, synced.resolved().childHome().heldBack().size(),
                                "sync reports both provenance-less units it held back");
                    }
                })
                .test("project sync still rebuilds an unmodified child home unit", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-sync-rebuild-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "sync-rebuild-skill", DepSpec.empty())
                                .sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "sync-rebuild-project"

                                [skills.demo]
                                source = "%s"
                                """.formatted(skill));
                        new ProjectDependencyResolver(h.store(), null)
                                .resolve(project, new ProjectDependencyResolver.Options(true, false));
                        Files.writeString(h.store().skillDir("sync-rebuild-skill").resolve("SKILL.md"),
                                "PARENT CONTENT AFTER SYNC\n");

                        ProjectSyncUseCase.Result synced = new ProjectSyncUseCase(h.store(), null)
                                .sync(project, new ProjectDependencyResolver.Options(true, false));

                        Path childUnit = repoRoot.resolve(".skill-manager/skills/sync-rebuild-skill");
                        assertEquals("PARENT CONTENT AFTER SYNC\n",
                                Files.readString(childUnit.resolve("SKILL.md")),
                                "an untouched unit is still rebuilt from the parent by sync");
                        assertTrue(synced.resolved().childHome().heldBack().isEmpty(),
                                "nothing held back when the child home is untouched");
                    }
                })
                .test("dropping a dependency keeps an edited child unit but prunes clean ones", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-prune-");
                        Path edited = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "prune-edited-skill", DepSpec.empty())
                                .sourcePath();
                        Path clean = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "prune-clean-skill", DepSpec.empty())
                                .sourcePath();
                        Path keep = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "prune-keep-skill", DepSpec.empty())
                                .sourcePath();
                        ProjectDependencyResolver resolver =
                                new ProjectDependencyResolver(h.store(), null);
                        resolver.resolve(project(repoRoot, """
                                [project]
                                name = "prune-project"

                                [skills.edited]
                                source = "%s"

                                [skills.clean]
                                source = "%s"

                                [skills.keep]
                                source = "%s"
                                """.formatted(edited, clean, keep)),
                                new ProjectDependencyResolver.Options(true, false));

                        Path editedChild = repoRoot.resolve(".skill-manager/skills/prune-edited-skill");
                        Files.writeString(editedChild.resolve("SKILL.md"), "AGENT EDIT\n");

                        ProjectDependencyResolver.Result second = resolver.resolve(
                                project(repoRoot, """
                                        [project]
                                        name = "prune-project"

                                        [skills.keep]
                                        source = "%s"
                                        """.formatted(keep)),
                                new ProjectDependencyResolver.Options(true, false));

                        assertTrue(Files.isDirectory(editedChild, LinkOption.NOFOLLOW_LINKS),
                                "a dropped dependency with local edits is not deleted");
                        assertEquals("AGENT EDIT\n", Files.readString(editedChild.resolve("SKILL.md")),
                                "the agent's edit survives the prune");
                        assertFalse(Files.exists(repoRoot.resolve(
                                        ".skill-manager/skills/prune-clean-skill")),
                                "a dropped dependency with no local edits is still pruned");
                        assertEquals(1, second.childHome().heldBack().size(),
                                "the retained dropped dependency is reported");
                        assertEquals("skill:prune-edited-skill",
                                second.childHome().heldBack().get(0).label(),
                                "held-back label from prune");
                    }
                })
                .test("upgrading a unit behind a dereferenced link refreshes the child copy", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-deref-refresh-");
                        SkillProject project = seedParentUnit(h, repoRoot,
                                "deref-refresh-skill", "deref-refresh-project");
                        // A second parent-store unit, linked into the first —
                        // the shape that makes `deploy-helm` embed `test-graph`.
                        UnitFixtures.scaffoldSkill(h.store().skillsDir(), "linked-sdk", DepSpec.empty());
                        Path sdk = h.store().skillDir("linked-sdk");
                        Files.writeString(sdk.resolve("sdk.txt"), "SDK V1\n");
                        Files.createSymbolicLink(
                                h.store().skillDir("deref-refresh-skill").resolve("sdk"), sdk);

                        ProjectChildHomeScaffolder scaffolder =
                                new ProjectChildHomeScaffolder(h.store());
                        scaffolder.scaffold(project, resolved("deref-refresh-skill"),
                                MaterializationMode.COPY);
                        Path childSdk = repoRoot
                                .resolve(".skill-manager/skills/deref-refresh-skill/sdk/sdk.txt");
                        assertEquals("SDK V1\n", Files.readString(childSdk),
                                "the linked unit's content is dereferenced into the child home");

                        // Upgrade only the pointed-at unit; the linking unit's
                        // own files do not change at all.
                        Files.writeString(sdk.resolve("sdk.txt"), "SDK V2\n");
                        scaffolder.scaffold(project, resolved("deref-refresh-skill"),
                                MaterializationMode.COPY);

                        assertEquals("SDK V2\n", Files.readString(childSdk),
                                "upgrading the linked unit refreshes the dereferenced copy");
                    }
                })
                .test("a displaced tree never lands in the child store's unit directories", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-orphan-");
                        SkillProject project = seedParentUnit(h, repoRoot,
                                "orphan-skill", "orphan-project");
                        // Part of the unit's real content, so the child copy has
                        // it too (see the permission trick below).
                        Path parentSticky = h.store().skillDir("orphan-skill").resolve("sticky");
                        Files.createDirectories(parentSticky);
                        Files.writeString(parentSticky.resolve("pinned.txt"), "pinned\n");
                        ProjectChildHomeScaffolder scaffolder =
                                new ProjectChildHomeScaffolder(h.store());
                        scaffolder.scaffold(project, resolved("orphan-skill"), MaterializationMode.COPY);

                        // Force a real swap: the parent moves on, the child is untouched.
                        Files.writeString(h.store().skillDir("orphan-skill").resolve("SKILL.md"),
                                "PARENT V2\n");
                        scaffolder.scaffold(project, resolved("orphan-skill"), MaterializationMode.COPY);

                        Path childSkills = repoRoot.resolve(".skill-manager/skills");
                        Path childHome = repoRoot.resolve(".skill-manager");
                        Path staging = childHome.resolve(".materialization/tmp");
                        SkillStore childStore = new SkillStore(childHome);
                        assertEquals(List.of("orphan-skill"), childEntries(childSkills),
                                "a completed swap leaves nothing beside the unit it replaced");
                        assertEquals(1, childStore.listInstalledUnits().units().size(),
                                "the child store lists the unit exactly once");

                        // Now make the displaced tree survive the swap, which is
                        // what a crash between the two moves would do: an
                        // undeletable subtree inside the child unit means the
                        // post-swap cleanup cannot remove what it parked. Where
                        // the tree is parked then becomes observable.
                        //
                        // Directory permissions are not part of the tree digest,
                        // so this does not make the child unit look modified —
                        // the refresh still runs and still swaps.
                        Path childUnit = childSkills.resolve("orphan-skill");
                        Path sticky = childUnit.resolve("sticky");
                        if (denyWrites(sticky)) {
                            Files.writeString(h.store().skillDir("orphan-skill").resolve("SKILL.md"),
                                    "PARENT V3\n");
                            scaffolder.scaffold(project, resolved("orphan-skill"),
                                    MaterializationMode.COPY);

                            assertEquals(List.of("orphan-skill"), childEntries(childSkills),
                                    "a surviving displaced tree is not parked inside skills/");
                            assertEquals(1, childStore.listInstalledUnits().units().size(),
                                    "a surviving displaced tree is not loaded as a second unit");
                            assertTrue(Files.isDirectory(staging), "it is parked in staging instead");
                            assertTrue(childEntries(staging).stream()
                                            .anyMatch(name -> name.startsWith("replaced-")),
                                    "staging holds the displaced tree");
                            assertEquals("PARENT V3\n",
                                    Files.readString(childUnit.resolve("SKILL.md")),
                                    "the swap itself still completed");

                            // And the next run sweeps it.
                            for (Path parked : Files.list(staging).toList()) {
                                allowWrites(parked.resolve("sticky"));
                            }
                            scaffolder.scaffold(project, resolved("orphan-skill"),
                                    MaterializationMode.COPY);
                            assertFalse(Files.exists(staging),
                                    "the next run sweeps the displaced tree and its staging dir");
                        }
                        allowWrites(sticky);
                    }
                })
                .test("LINK mode still symlinks the child home unit at the parent store", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-link-mode-");
                        SkillProject project = seedParentUnit(h, repoRoot,
                                "link-mode-skill", "link-mode-project");

                        new ProjectChildHomeScaffolder(h.store()).scaffold(
                                project, resolved("link-mode-skill"), MaterializationMode.LINK);

                        Path childUnit = repoRoot.resolve(".skill-manager/skills/link-mode-skill");
                        Path parentUnit = h.store().skillDir("link-mode-skill")
                                .toAbsolutePath().normalize();
                        assertTrue(Files.isSymbolicLink(childUnit),
                                "LINK mode keeps the pre-existing symlink behavior");
                        assertEquals(parentUnit, Files.readSymbolicLink(childUnit),
                                "LINK mode points the child home at the parent unit");
                        // Sensitivity check: with LINK, a child-home write DOES
                        // reach the parent store — exactly the hazard COPY removes.
                        Files.writeString(childUnit.resolve("written-through.md"), "x\n");
                        assertTrue(Files.exists(parentUnit.resolve("written-through.md")),
                                "LINK mode writes through to the parent store");

                        new ProjectChildHomeScaffolder(h.store()).scaffold(
                                project, resolved("link-mode-skill"), MaterializationMode.LINK);
                        assertTrue(Files.isSymbolicLink(childUnit),
                                "re-scaffolding in LINK mode keeps the symlink");
                    }
                })
                .test("upgrading a LINK-mode child home to COPY converges", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-upgrade-");
                        SkillProject project = seedParentUnit(h, repoRoot,
                                "upgrade-skill", "upgrade-project");
                        ProjectChildHomeScaffolder scaffolder =
                                new ProjectChildHomeScaffolder(h.store());

                        scaffolder.scaffold(project, resolved("upgrade-skill"),
                                MaterializationMode.LINK);
                        Path childUnit = repoRoot.resolve(".skill-manager/skills/upgrade-skill");
                        assertTrue(Files.isSymbolicLink(childUnit), "starts as a LINK-mode child home");

                        // The upgrade case: must not fail with "already exists".
                        ProjectChildHomeScaffolder.Result upgraded = scaffolder.scaffold(
                                project, resolved("upgrade-skill"), MaterializationMode.COPY);
                        assertFalse(Files.isSymbolicLink(childUnit),
                                "COPY replaces the inherited symlink");
                        assertTrue(Files.isRegularFile(childUnit.resolve("SKILL.md")),
                                "upgraded child home carries real content");
                        assertTrue(upgraded.heldBack().isEmpty(),
                                "a symlinked child home is upgraded, not held back");

                        scaffolder.scaffold(project, resolved("upgrade-skill"),
                                MaterializationMode.COPY);
                        assertFalse(Files.isSymbolicLink(childUnit),
                                "re-scaffolding in COPY mode stays a real directory");
                        assertEquals(
                                Files.readString(h.store().skillDir("upgrade-skill").resolve("SKILL.md")),
                                Files.readString(childUnit.resolve("SKILL.md")),
                                "re-scaffolded copy converges on the parent content");

                        Files.writeString(childUnit.resolve("SKILL.md"), "local\n");
                        assertFalse(Files.readString(h.store().skillDir("upgrade-skill")
                                        .resolve("SKILL.md")).equals("local\n"),
                                "upgraded child home no longer writes through");
                    }
                })
                .test("tool shims stay linked at the parent bin dir under COPY", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-shims-");
                        SkillProject project = seedParentUnit(h, repoRoot,
                                "shim-skill", "shim-project", """

                                [[cli_dependencies]]
                                name = "demo-shim"
                                spec = "brew:demo-shim"
                                """);
                        Path parentShim = h.store().cliBinDir().resolve("demo-shim");
                        Files.createDirectories(parentShim.getParent());
                        Files.writeString(parentShim, "#!/usr/bin/env sh\necho demo\n");
                        dev.skillmanager.shared.util.Fs.makeExecutable(parentShim);

                        new ProjectChildHomeScaffolder(h.store()).scaffold(
                                project, resolved("shim-skill"), MaterializationMode.COPY);

                        Path childShim = repoRoot.resolve(".skill-manager/bin/cli/demo-shim");
                        assertTrue(Files.isSymbolicLink(childShim),
                                "CLI shims are linked, not copied, even in COPY mode");
                        assertEquals(parentShim.toAbsolutePath().normalize(),
                                Files.readSymbolicLink(childShim),
                                "child shim points at the parent bin entry");
                        assertTrue(Files.isExecutable(childShim), "mirrored shim is executable");
                        assertFalse(Files.isSymbolicLink(
                                        repoRoot.resolve(".skill-manager/skills/shim-skill")),
                                "the unit itself is still copied");
                    }
                })
                .test("harness instantiate into a child home copies units instead of symlinking", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        UnitFixtures.scaffoldSkill(h.store().skillsDir(), "harness-child-skill",
                                DepSpec.empty());
                        h.seedUnit("harness-child-skill", UnitKind.SKILL);
                        Path harnessDir = h.store().unitDir("child-harness", UnitKind.HARNESS);
                        Files.createDirectories(harnessDir);
                        Files.writeString(harnessDir.resolve("harness.toml"), """
                                [harness]
                                name = "child-harness"
                                version = "0.1.0"
                                description = "child home fixture"

                                units = ["skill:harness-child-skill"]
                                """);
                        new UnitStore(h.store()).write(new InstalledUnit(
                                "child-harness", "0.1.0",
                                InstalledUnit.Kind.LOCAL_DIR,
                                InstalledUnit.InstallSource.LOCAL_FILE,
                                "fixture", null, null,
                                "2026-05-12T00:00:00Z", List.of(), UnitKind.HARNESS));

                        Path target = Files.createTempDirectory("child-home-harness-");
                        new ChildHomeHarnessInstaller(h.store())
                                .instantiate("child-harness", "child-inst", target, null, false);

                        Path childSkill = target.resolve(".skill-manager/skills/harness-child-skill");
                        Path childHarness = target.resolve(".skill-manager/harnesses/child-harness");
                        assertFalse(Files.isSymbolicLink(childSkill),
                                "harness child home copies the skill instead of symlinking it");
                        assertFalse(Files.isSymbolicLink(childHarness),
                                "harness child home copies the harness template too");
                        assertTrue(Files.isRegularFile(childSkill.resolve("SKILL.md")),
                                "copied harness child skill has real content");

                        Files.writeString(childSkill.resolve("SKILL.md"), "HARNESS CHILD EDIT\n");
                        assertFalse(Files.readString(h.store().skillDir("harness-child-skill")
                                        .resolve("SKILL.md")).contains("HARNESS CHILD EDIT"),
                                "editing the harness child home does not reach the parent store");

                        // Stale-refresh: an untouched harness child home picks up
                        // parent changes on re-instantiate (the old code returned
                        // early for any existing directory and never refreshed).
                        Files.writeString(childSkill.resolve("SKILL.md"),
                                Files.readString(h.store().skillDir("harness-child-skill")
                                        .resolve("SKILL.md")));
                        Files.writeString(h.store().skillDir("harness-child-skill").resolve("SKILL.md"),
                                "REFRESHED PARENT CONTENT\n");
                        new ChildHomeHarnessInstaller(h.store())
                                .instantiate("child-harness", "child-inst", target, null, false);
                        assertEquals("REFRESHED PARENT CONTENT\n",
                                Files.readString(childSkill.resolve("SKILL.md")),
                                "unmodified harness child unit refreshes from the parent");
                    }
                })
                .test("symlinks into the parent store are dereferenced by COPY", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-deref-");
                        SkillProject project = seedParentUnit(h, repoRoot,
                                "deref-skill", "deref-project");
                        Path parentUnit = h.store().skillDir("deref-skill");

                        // (a) absolute link at another location in the parent store
                        Path shared = h.store().root().resolve("shared/data.txt");
                        Files.createDirectories(shared.getParent());
                        Files.writeString(shared, "PARENT DATA\n");
                        Files.createSymbolicLink(parentUnit.resolve("store-link.txt"), shared);
                        // (b) absolute link at a directory in the parent store
                        Path sharedDir = h.store().root().resolve("shared/tree");
                        Files.createDirectories(sharedDir);
                        Files.writeString(sharedDir.resolve("nested.txt"), "NESTED PARENT\n");
                        Files.createSymbolicLink(parentUnit.resolve("store-tree"), sharedDir);
                        // (c) relative link that stays inside the unit
                        Files.createSymbolicLink(parentUnit.resolve("self-link.md"),
                                Path.of("SKILL.md"));
                        // (d) link that leaves the store entirely
                        Path outside = Files.createTempDirectory("child-home-outside-")
                                .resolve("tool.txt");
                        Files.writeString(outside, "OUTSIDE\n");
                        Files.createSymbolicLink(parentUnit.resolve("outside-link.txt"), outside);

                        new ProjectChildHomeScaffolder(h.store()).scaffold(
                                project, resolved("deref-skill"), MaterializationMode.COPY);

                        Path childUnit = repoRoot.resolve(".skill-manager/skills/deref-skill");
                        assertFalse(Files.isSymbolicLink(childUnit.resolve("store-link.txt")),
                                "a link into the parent store is dereferenced");
                        assertEquals("PARENT DATA\n",
                                Files.readString(childUnit.resolve("store-link.txt")),
                                "dereferenced link carries the content");
                        assertFalse(Files.isSymbolicLink(childUnit.resolve("store-tree")),
                                "a directory link into the parent store is dereferenced");
                        assertTrue(Files.isRegularFile(childUnit.resolve("store-tree/nested.txt")),
                                "dereferenced directory link carries its subtree");
                        assertTrue(Files.isSymbolicLink(childUnit.resolve("self-link.md")),
                                "a relative unit-internal link is preserved");
                        assertTrue(Files.isSymbolicLink(childUnit.resolve("outside-link.txt")),
                                "a link outside the parent store is preserved");

                        // The point of (a): writing through it must not reach the store.
                        Files.writeString(childUnit.resolve("store-link.txt"), "CHILD EDIT\n");
                        assertEquals("PARENT DATA\n", Files.readString(shared),
                                "writing through the copied link does not reach the parent store");
                        Files.writeString(childUnit.resolve("store-tree/nested.txt"), "CHILD NESTED\n");
                        assertEquals("NESTED PARENT\n", Files.readString(sharedDir.resolve("nested.txt")),
                                "writing into the copied directory link does not reach the parent store");
                        // And the preserved internal link resolves inside the copy.
                        Files.writeString(childUnit.resolve("self-link.md"), "VIA SELF LINK\n");
                        assertEquals("VIA SELF LINK\n", Files.readString(childUnit.resolve("SKILL.md")),
                                "the unit-internal link resolves inside the child copy");
                        assertFalse(Files.readString(parentUnit.resolve("SKILL.md"))
                                        .contains("VIA SELF LINK"),
                                "the unit-internal link does not reach the parent store");
                    }
                })
                .test("a failed materialization leaves the previous child home unit intact", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("child-home-atomic-");
                        SkillProject project = seedParentUnit(h, repoRoot,
                                "atomic-skill", "atomic-project");
                        ProjectChildHomeScaffolder scaffolder =
                                new ProjectChildHomeScaffolder(h.store());
                        scaffolder.scaffold(project, resolved("atomic-skill"), MaterializationMode.COPY);

                        Path childUnit = repoRoot.resolve(".skill-manager/skills/atomic-skill");
                        String goodSkillMd = Files.readString(childUnit.resolve("SKILL.md"));

                        Path parentUnit = h.store().skillDir("atomic-skill");

                        // (a) Failure at the swap, after the new copy is fully
                        //     staged: the destination's parent is read-only, so
                        //     the unit cannot be moved aside.
                        Files.writeString(parentUnit.resolve("SKILL.md"), "PARENT MOVED ON\n");
                        Path childSkills = repoRoot.resolve(".skill-manager/skills");
                        if (denyWrites(childSkills)) {
                            boolean swapFailed = false;
                            try {
                                scaffolder.scaffold(project, resolved("atomic-skill"),
                                        MaterializationMode.COPY);
                            } catch (java.io.IOException expected) {
                                swapFailed = true;
                            } finally {
                                allowWrites(childSkills);
                            }
                            assertTrue(swapFailed, "a read-only destination fails the refresh");
                            assertTrue(Files.isDirectory(childUnit, LinkOption.NOFOLLOW_LINKS),
                                    "the previous child unit still exists after the swap failure");
                            assertEquals(goodSkillMd, Files.readString(childUnit.resolve("SKILL.md")),
                                    "the previous child unit content is intact after the swap failure");
                            assertEquals(List.of("atomic-skill"), childEntries(childSkills),
                                    "a failed swap leaves no displaced tree inside skills/");
                        }

                        // (b) Failure while reading the parent unit, before a
                        //     single byte of the new copy exists.
                        Path blocked = parentUnit.resolve("blocked");
                        Files.createDirectories(blocked);
                        Files.writeString(blocked.resolve("inner.txt"), "inner\n");
                        if (!denyReads(blocked)) return; // running as root: no denial to test

                        boolean readFailed = false;
                        try {
                            scaffolder.scaffold(project, resolved("atomic-skill"),
                                    MaterializationMode.COPY);
                        } catch (java.io.IOException expected) {
                            readFailed = true;
                        } finally {
                            allowReads(blocked);
                        }

                        assertTrue(readFailed, "an unreadable parent subtree fails the refresh");
                        assertEquals(goodSkillMd, Files.readString(childUnit.resolve("SKILL.md")),
                                "the previous child unit content is intact after the read failure");
                        assertFalse(Files.exists(childUnit.resolve("blocked")),
                                "no half-copied content was swapped into place");
                    }
                })
                .runAll();
    }

    // ------------------------------------------------------------- helpers

    private static SkillProject project(Path root, String manifest) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("skill-project.toml"), manifest);
        return SkillProjectParser.load(root);
    }

    private static List<SkillProjectLock.ResolvedUnit> resolved(String name) {
        return List.of(new SkillProjectLock.ResolvedUnit(
                name, UnitKind.SKILL, "0.1.0", null, true));
    }

    private static SkillProject seedParentUnit(TestHarness h, Path repoRoot,
                                               String unitName, String projectName)
            throws Exception {
        return seedParentUnit(h, repoRoot, unitName, projectName, null);
    }

    /**
     * Installs {@code unitName} straight into the parent store (unit directory
     * plus installed record) and returns a project rooted at {@code repoRoot},
     * so a test can drive {@link ProjectChildHomeScaffolder} directly with an
     * explicit materialization mode.
     */
    private static SkillProject seedParentUnit(TestHarness h, Path repoRoot,
                                               String unitName, String projectName,
                                               String extraToml)
            throws Exception {
        UnitFixtures.scaffoldSkill(h.store().skillsDir(), unitName, DepSpec.empty());
        if (extraToml != null) {
            Path toml = h.store().skillDir(unitName).resolve("skill-manager.toml");
            Files.writeString(toml, Files.readString(toml) + extraToml);
        }
        h.seedUnit(unitName, UnitKind.SKILL);
        return project(repoRoot, """
                [project]
                name = "%s"
                """.formatted(projectName));
    }

    private static List<String> childEntries(Path dir) throws Exception {
        try (var entries = Files.list(dir)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** @return true when the OS actually refuses to create entries in {@code dir}. */
    private static boolean denyWrites(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions
                    .fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return false;
        }
        Path probe = dir.resolve(".write-probe");
        try {
            Files.createFile(probe);
            Files.deleteIfExists(probe);
            return false;
        } catch (java.io.IOException denied) {
            return true;
        }
    }

    private static void allowWrites(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions
                    .fromString("rwxr-xr-x"));
        } catch (Exception ignored) {
            // best effort: the temp tree is disposable
        }
    }

    /** @return true when the OS actually refuses to list {@code dir} afterwards. */
    private static boolean denyReads(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, java.util.Set.of());
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return false;
        }
        try (var entries = Files.list(dir)) {
            entries.findAny();
            return false;
        } catch (java.io.IOException denied) {
            return true;
        }
    }

    private static void allowReads(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions
                    .fromString("rwxr-xr-x"));
        } catch (Exception ignored) {
            // best effort: the temp tree is disposable
        }
    }
}
