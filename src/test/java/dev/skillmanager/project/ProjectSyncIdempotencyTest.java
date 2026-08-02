package dev.skillmanager.project;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * {@code project sync} must be runnable twice against a project that declares
 * CLI dependencies. Issue #144.
 *
 * <h2>The state that broke it</h2>
 *
 * <p>{@code <home>/bin/cli/<dep>} is a <em>regular file</em> whenever the dep
 * uses the {@code skill-script} backend — the backend generates a shell shim,
 * not a symlink. A project child home is a home in its own right: an agent
 * launches with it and {@code bootstrap-home.sh} tells the operator to
 * re-provision its tools with {@code sync --force-scripts}, which writes those
 * shims. So a child home holding a real file at {@code bin/cli/computeq} is
 * the ordinary state, not an anomaly.
 *
 * <p>The shim projection then treated it as a hard error —
 * {@code child home path already exists: <home>/bin/cli/computeq} — aborting
 * the entire realization. Nothing about that state ever cleared, so the
 * project could not be synced again, ever.
 *
 * <h2>Why the assertion is "kept", not "replaced"</h2>
 *
 * <p>Replacing the child's own shim with a symlink at the parent store's entry
 * would make the sync pass and would be wrong: it deletes a tool this home
 * provisioned, and it puts a live path into another Skill Manager home inside
 * the directory whose entire purpose is to reach none. That is the leak
 * {@code home verify} refuses.
 */
public final class ProjectSyncIdempotencyTest {

    private static final String CHILD_SHIM = "#!/usr/bin/env bash\necho child-provisioned\n";
    private static final String PARENT_SHIM = "#!/usr/bin/env bash\necho parent-provisioned\n";

    public static int run() throws Exception {
        return Tests.suite("ProjectSyncIdempotencyTest")

                .test("a project home that declares CLI deps syncs twice, and the second run "
                        + "is clean", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("sync-idempotent-");
                        SkillProject project = cliDepProject(h, repoRoot, "sync-idempotent");
                        Path childShim = repoRoot.resolve(".skill-manager/bin/cli/probe-tool");

                        // Provision: the first realization of the project.
                        new ProjectDependencyResolver(h.store(), null)
                                .resolve(project, new ProjectDependencyResolver.Options(true, false));
                        assertTrue(Files.exists(childShim, LinkOption.NOFOLLOW_LINKS),
                                "provisioning mirrors the CLI dep's shim into the child home");

                        // The child home provisions its own copy of the tool —
                        // what `sync --force-scripts` does in any home, and what
                        // the reporting operator's home actually held.
                        Files.delete(childShim);
                        Files.writeString(childShim, CHILD_SHIM);

                        // sync #1
                        new ProjectSyncUseCase(h.store(), null).sync(
                                project,
                                new ProjectDependencyResolver.Options(true, false),
                                new ProjectSyncUseCase.Options(false, false, null));
                        assertEquals(CHILD_SHIM, Files.readString(childShim),
                                "sync keeps the shim the child home provisioned for itself");

                        // sync #2 — the run the issue says is impossible today.
                        new ProjectSyncUseCase(h.store(), null).sync(
                                project,
                                new ProjectDependencyResolver.Options(true, false),
                                new ProjectSyncUseCase.Options(false, false, null));

                        assertFalse(Files.isSymbolicLink(childShim),
                                "the kept shim was not replaced with a link into the parent home");
                        assertEquals(CHILD_SHIM, Files.readString(childShim),
                                "and its content is untouched after a second sync");
                    }
                })

                .test("mirroring a shim over the child home's own file keeps it instead of "
                        + "throwing", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path childRoot = Files.createTempDirectory("shim-mirror-child-");
                        SkillStore child = new SkillStore(childRoot.resolve(".skill-manager"));
                        child.init();
                        ChildHomeMaterializer materializer =
                                new ChildHomeMaterializer(h.store(), child);

                        Path source = h.store().cliBinDir().resolve("probe-tool");
                        Files.createDirectories(source.getParent());
                        Files.writeString(source, PARENT_SHIM);
                        Path dest = child.cliBinDir().resolve("probe-tool");
                        Files.createDirectories(dest.getParent());

                        assertEquals(ChildHomeMaterializer.ShimOutcome.MIRRORED,
                                materializer.mirrorExistingShim(source, dest),
                                "an empty destination is mirrored");
                        // Already a symlink at the parent's entry, so it now
                        // resolves to the same file: the degenerate-layout
                        // guard answers first, and nothing is rewritten.
                        assertEquals(ChildHomeMaterializer.ShimOutcome.SAME_ENTRY,
                                materializer.mirrorExistingShim(source, dest),
                                "re-mirroring an already-mirrored link is not an error");
                        assertTrue(Files.isSymbolicLink(dest),
                                "and it is still the mirrored link");

                        Files.delete(dest);
                        Files.writeString(dest, CHILD_SHIM);
                        assertEquals(ChildHomeMaterializer.ShimOutcome.KEPT_LOCAL,
                                materializer.mirrorExistingShim(source, dest),
                                "the child home's own regular-file shim is kept, not refused");
                        assertEquals(CHILD_SHIM, Files.readString(dest),
                                "and it is left byte-for-byte alone");

                        Files.delete(dest);
                        Files.writeString(dest, PARENT_SHIM);
                        assertEquals(ChildHomeMaterializer.ShimOutcome.UNCHANGED,
                                materializer.mirrorExistingShim(source, dest),
                                "a byte-identical copy needs no reconciliation and no warning");

                        assertEquals(ChildHomeMaterializer.ShimOutcome.NO_SOURCE,
                                materializer.mirrorExistingShim(
                                        h.store().cliBinDir().resolve("absent-tool"),
                                        child.cliBinDir().resolve("absent-tool")),
                                "a dep the parent never installed is nothing to mirror");
                        assertEquals(ChildHomeMaterializer.ShimOutcome.SAME_ENTRY,
                                materializer.mirrorExistingShim(source, source),
                                "the degenerate same-home layout still declines");
                    }
                })
                .runAll();
    }

    // ------------------------------------------------------------- helpers

    /**
     * A project with one skill declaring a {@code skill-script} CLI dep, and a
     * parent-store shim for it written the way that backend writes one: a
     * regular file.
     */
    private static SkillProject cliDepProject(TestHarness h, Path repoRoot, String name)
            throws Exception {
        Path unitSource = UnitFixtures.scaffoldSkill(
                repoRoot.resolve("units"), name + "-skill",
                DepSpec.of().cli("skill-script:probe-tool").build()).sourcePath();
        Files.writeString(repoRoot.resolve("skill-project.toml"), """
                [project]
                name = "%s"

                [skills.demo]
                source = "%s"
                """.formatted(name, unitSource));
        Path parentShim = h.store().cliBinDir().resolve("probe-tool");
        Files.createDirectories(parentShim.getParent());
        Files.writeString(parentShim, PARENT_SHIM);
        return SkillProjectParser.load(repoRoot);
    }
}
