package dev.skillmanager.project;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-11 contract: {@link ClaudeProjector} routes SKILL units to
 * {@code skillsDir} and PLUGIN units to {@code pluginsDir}. Both arms
 * land as one {@link Projection} per unit; {@code apply} symlinks (or
 * copies as fallback); {@code remove} reverses idempotently.
 *
 * <p>Sweep: {@code (UnitKind × pre-state with/without conflicting
 * target)}.
 */
public final class ClaudeProjectorTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ClaudeProjectorTest");

        // Skill arm: full projection lifecycle (plan → apply → remove),
        // identical to the pre-marketplace contract. Plugins now skip the
        // projector entirely (handled by
        // {@link dev.skillmanager.effects.SkillEffect.RefreshHarnessPlugins}),
        // so the plugin arm only asserts "planProjection returns empty".
        UnitKind kind = UnitKind.SKILL;
        String label = kind.name().toLowerCase();

        suite.test(label + " — planProjection produces one projection routing to skillsDir", () -> {
            TestHarness h = TestHarness.create();
            Path agentRoot = Files.createTempDirectory("claude-proj-plan-");
            ClaudeProjector p = new ClaudeProjector(
                    agentRoot.resolve("skills"), agentRoot.resolve("plugins"));
            AgentUnit u = installInStore(h, "widget", kind);

            List<Projection> projs = p.planProjection(u, h.store());
            assertEquals(1, projs.size(), "exactly one projection");
            Projection proj = projs.get(0);
            assertEquals("claude", proj.agentId(), "agent");
            assertEquals(kind, proj.kind(), "kind preserved");
            Path expectedTarget = agentRoot.resolve("skills").resolve("widget");
            assertEquals(expectedTarget, proj.target(), "target dir is skillsDir");
            assertEquals(h.store().unitDir("widget", kind), proj.source(), "source from store.unitDir");
        });

        suite.test(label + " — apply creates a symlink target → source", () -> {
            TestHarness h = TestHarness.create();
            Path agentRoot = Files.createTempDirectory("claude-proj-apply-");
            ClaudeProjector p = new ClaudeProjector(
                    agentRoot.resolve("skills"), agentRoot.resolve("plugins"));
            AgentUnit u = installInStore(h, "widget", kind);

            Projection proj = p.planProjection(u, h.store()).get(0);
            p.apply(proj);

            assertTrue(Files.exists(proj.target(), LinkOption.NOFOLLOW_LINKS),
                    "target exists");
            assertTrue(Files.isSymbolicLink(proj.target()) || Files.isDirectory(proj.target()),
                    "target is a symlink or copied dir");
        });

        suite.test(label + " — apply replaces a pre-existing target cleanly (idempotent)", () -> {
            TestHarness h = TestHarness.create();
            Path agentRoot = Files.createTempDirectory("claude-proj-replace-");
            ClaudeProjector p = new ClaudeProjector(
                    agentRoot.resolve("skills"), agentRoot.resolve("plugins"));
            AgentUnit u = installInStore(h, "widget", kind);

            Projection proj = p.planProjection(u, h.store()).get(0);
            p.apply(proj);
            p.apply(proj);

            assertTrue(Files.exists(proj.target(), LinkOption.NOFOLLOW_LINKS), "target still there");
        });

        suite.test(label + " — remove deletes the target; idempotent", () -> {
            TestHarness h = TestHarness.create();
            Path agentRoot = Files.createTempDirectory("claude-proj-remove-");
            ClaudeProjector p = new ClaudeProjector(
                    agentRoot.resolve("skills"), agentRoot.resolve("plugins"));
            AgentUnit u = installInStore(h, "widget", kind);

            Projection proj = p.planProjection(u, h.store()).get(0);
            p.apply(proj);
            assertTrue(Files.exists(proj.target(), LinkOption.NOFOLLOW_LINKS), "applied");

            p.remove(proj);
            assertFalse(Files.exists(proj.target(), LinkOption.NOFOLLOW_LINKS), "removed");

            p.remove(proj);
        });

        suite.test(label + " — remove on never-applied projection is a no-op", () -> {
            TestHarness h = TestHarness.create();
            Path agentRoot = Files.createTempDirectory("claude-proj-noop-");
            ClaudeProjector p = new ClaudeProjector(
                    agentRoot.resolve("skills"), agentRoot.resolve("plugins"));
            AgentUnit u = installInStore(h, "widget", kind);

            Projection proj = p.planProjection(u, h.store()).get(0);
            p.remove(proj);
            assertFalse(Files.exists(proj.target(), LinkOption.NOFOLLOW_LINKS), "still absent");
        });

        // CHM-16. `<home>/.claude/skills/<name>` is not a skill-manager-only
        // namespace — it is where a human writes skills by hand. apply()
        // deleted whatever was there before linking, so installing a unit whose
        // name collided destroyed the hand-written directory with no warning,
        // no backup and no record of whose bytes those were.
        suite.test(label + " — apply does not destroy a hand-authored directory at the target", () -> {
            TestHarness h = TestHarness.create();
            Path agentRoot = Files.createTempDirectory("claude-proj-authored-");
            ClaudeProjector p = new ClaudeProjector(
                    agentRoot.resolve("skills"), agentRoot.resolve("plugins"));
            AgentUnit u = installInStore(h, "widget", kind);
            Projection proj = p.planProjection(u, h.store()).get(0);

            Fs.ensureDir(proj.target());
            Files.writeString(proj.target().resolve("private-notes.md"), "AUTHORED BY HAND\n");

            boolean applied = p.apply(proj);

            assertFalse(applied, "apply reports the projection was held back");
            assertFalse(Files.isSymbolicLink(proj.target()),
                    "the authored directory is not replaced by a symlink");
            assertEquals("AUTHORED BY HAND\n",
                    Files.readString(proj.target().resolve("private-notes.md")),
                    "the authored bytes are still there");
        });

        suite.test(label + " — remove does not delete a hand-authored directory at the target", () -> {
            TestHarness h = TestHarness.create();
            Path agentRoot = Files.createTempDirectory("claude-proj-authored-rm-");
            ClaudeProjector p = new ClaudeProjector(
                    agentRoot.resolve("skills"), agentRoot.resolve("plugins"));
            AgentUnit u = installInStore(h, "widget", kind);
            Projection proj = p.planProjection(u, h.store()).get(0);

            Fs.ensureDir(proj.target());
            Files.writeString(proj.target().resolve("private-notes.md"), "AUTHORED BY HAND\n");

            boolean removed = p.remove(proj);

            assertFalse(removed, "remove reports it left something in place");
            assertEquals("AUTHORED BY HAND\n",
                    Files.readString(proj.target().resolve("private-notes.md")),
                    "an uninstall does not take the human's skill with it");
        });

        suite.test(label + " — apply still replaces the projector's own copy-fallback output", () -> {
            TestHarness h = TestHarness.create();
            Path agentRoot = Files.createTempDirectory("claude-proj-copyshape-");
            ClaudeProjector p = new ClaudeProjector(
                    agentRoot.resolve("skills"), agentRoot.resolve("plugins"));
            AgentUnit u = installInStore(h, "widget", kind);
            Projection proj = p.planProjection(u, h.store()).get(0);

            // Exactly what the copy fallback writes: a directory byte-identical
            // to the source. Nothing is lost by rewriting it, so the guard must
            // not refuse the happy path on a filesystem without symlinks.
            Fs.copyRecursive(proj.source(), proj.target());

            assertTrue(p.apply(proj), "a byte-identical copy is this projector's own output");
            assertTrue(Files.isSymbolicLink(proj.target()), "and is replaced by the link");
        });

        suite.test("plugin — planProjection returns empty (handled by RefreshHarnessPlugins)", () -> {
            TestHarness h = TestHarness.create();
            Path agentRoot = Files.createTempDirectory("claude-proj-plugin-");
            ClaudeProjector p = new ClaudeProjector(
                    agentRoot.resolve("skills"), agentRoot.resolve("plugins"));
            AgentUnit u = installInStore(h, "widget", UnitKind.PLUGIN);

            List<Projection> projs = p.planProjection(u, h.store());
            assertEquals(0, projs.size(),
                    "plugin arm yields no projection — marketplace flow owns it");
        });

        return suite.runAll();
    }

    /**
     * Materialize a unit on disk so the projector has a real source to
     * symlink. Mirrors how the install pipeline lands files: SKILL
     * units under {@code skills/<name>/}; PLUGIN units under
     * {@code plugins/<name>/}.
     */
    private static AgentUnit installInStore(TestHarness h, String name, UnitKind kind) throws Exception {
        Path tmp = Files.createTempDirectory("projector-fixture-");
        AgentUnit u = UnitFixtures.buildEquivalent(kind, tmp, name, DepSpec.empty());
        Path src = u.sourcePath();
        Path dst = h.store().unitDir(name, kind);
        if (kind == UnitKind.SKILL) Fs.ensureDir(h.store().skillsDir());
        else Fs.ensureDir(h.store().pluginsDir());
        Fs.copyRecursive(src, dst);
        return u;
    }
}
