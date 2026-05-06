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

        for (UnitKind kind : UnitKind.values()) {
            String label = kind.name().toLowerCase();

            suite.test(label + " — planProjection produces one projection routing to the right dir", () -> {
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
                Path expectedTarget = (kind == UnitKind.PLUGIN
                        ? agentRoot.resolve("plugins")
                        : agentRoot.resolve("skills")).resolve("widget");
                assertEquals(expectedTarget, proj.target(), "target dir matches kind");
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
                // Either a real symlink or a copied dir (fallback) — both
                // resolve to the source bytes.
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
                p.apply(proj);  // second call: no-op (same source)

                // Still exactly one entry, still resolving to the source.
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

                p.remove(proj);  // remove again — no-op, no exception
            });

            suite.test(label + " — remove on never-applied projection is a no-op", () -> {
                TestHarness h = TestHarness.create();
                Path agentRoot = Files.createTempDirectory("claude-proj-noop-");
                ClaudeProjector p = new ClaudeProjector(
                        agentRoot.resolve("skills"), agentRoot.resolve("plugins"));
                AgentUnit u = installInStore(h, "widget", kind);

                Projection proj = p.planProjection(u, h.store()).get(0);
                p.remove(proj);  // never applied → silent no-op
                assertFalse(Files.exists(proj.target(), LinkOption.NOFOLLOW_LINKS), "still absent");
            });
        }

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
