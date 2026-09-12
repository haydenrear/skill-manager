package dev.skillmanager.lifecycle;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * OUN-5: an old-shape home takes the upgrade that fixes it.
 *
 * <h2>The bind this ticket exists to undo</h2>
 *
 * <p>From OUN-1 a plugin's contained skills are addressable by name, and from
 * OUN-2 installing a plugin whose contained name is already claimed is
 * refused outright, with no flag past it. A home holding the standalone
 * {@code skill-manager} skill is in exactly that state with respect to the
 * {@code skt} that carries it now — so the upgrade that would fix the home is
 * the upgrade the home rejects, and every existing home is stuck.
 *
 * <h2>What must NOT be the fix</h2>
 *
 * <p>Weakening the gate. The ticket says so in as many words, and it is the
 * reason half of these cases are about collisions that still get refused: a
 * migration that works by disabling the guard has produced the state the guard
 * exists to prevent, and would have passed a suite that only checked the
 * upgrade succeeded.
 */
public final class MigrationSatisfiesTheGateTest {

    private static final String CARRIER = "skt";
    private static final String MOVED = "skill-manager";
    private static final String OBSOLETE = "skill-dev-skill";

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("MigrationSatisfiesTheGateTest");

        // ------------------------------------------------- the whole upgrade

        suite.test("an old-shape home installs the carrier that would have collided", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir(MOVED, UnitKind.SKILL);
            h.scaffoldUnitDir(OBSOLETE, UnitKind.SKILL);

            InstallUseCase.Report report = install(h.store(), pluginCarrying(CARRIER, MOVED));

            assertEquals(0, report.exitCode(),
                    "the upgrade succeeds — before OUN-5 this was exit 3, refused by the "
                            + "gate for a collision the upgrade itself resolves");
            assertTrue(Files.isDirectory(h.store().pluginsDir().resolve(CARRIER)),
                    "the carrier landed");
            assertFalse(Files.exists(h.store().skillDir(MOVED)),
                    "and the standalone copy it supersedes is gone");
            assertTrue(Files.isDirectory(
                            h.store().pluginsDir().resolve(CARRIER).resolve("skills").resolve(MOVED)),
                    "the name still resolves — to the copy the carrier holds. That is what "
                            + "makes retiring it safe rather than destructive");
        });

        suite.test("the obsolete unit goes too, without needing a name collision", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir(OBSOLETE, UnitKind.SKILL);

            install(h.store(), pluginCarrying(CARRIER, "something-else"));

            assertFalse(Files.exists(h.store().skillDir(OBSOLETE)),
                    "skill-dev-skill was deleted upstream at OUN-4; nothing publishes it and "
                            + "nothing collides with it — it is simply occupying a name");
        });

        suite.test("running it again changes nothing", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir(MOVED, UnitKind.SKILL);
            install(h.store(), pluginCarrying(CARRIER, MOVED));

            assertEquals(0, UnitSupersession.due(h.store(), java.util.List.of(CARRIER)).size(),
                    "a retirement of something that is not installed is not an operation — "
                            + "which is what makes a migrated home safe to migrate again");
        });

        // ------------------------------------ the gate, still doing its job

        // OUN-13 CHANGED THIS TEST'S OBSERVABLE, NOT ITS SUBJECT. It used to
        // prove "the migration satisfies the gate rather than disabling it" by
        // showing an ordinary same-name plugin was STILL refused afterwards.
        // Same-name is no longer refused by anyone — a contained skill is
        // `plugin:skill` and does not collide with a standalone — so the
        // subject is now proved directly: the migration retires exactly the
        // unit the table names, and touches nothing else, whatever installs
        // after it.
        suite.test("the migration retires what the table names and nothing else", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir(MOVED, UnitKind.SKILL);
            h.scaffoldUnitDir("acme-tool", UnitKind.SKILL);

            InstallUseCase.Report migrated = install(h.store(), pluginCarrying(CARRIER, MOVED));
            assertEquals(0, migrated.exitCode(), "the migration itself still goes through");
            assertFalse(Files.isDirectory(h.store().skillDir(MOVED)),
                    "the unit the table names is retired");

            InstallUseCase.Report ordinary =
                    install(h.store(), pluginCarrying("acme-plugin", "acme-tool"));

            assertEquals(0, ordinary.exitCode(),
                    "a plugin sharing a name with a standalone unit installs — they are "
                            + "addressed `acme-tool` and `acme-plugin:acme-tool`");
            assertTrue(Files.isDirectory(h.store().skillDir("acme-tool")),
                    "and the standalone it shares a name with is UNTOUCHED — the migration "
                            + "did not hand later installs a licence to evict");
        });

        suite.test("a name not in the table is never retired, whoever carries it", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("acme-tool", UnitKind.SKILL);

            // A plugin that IS the carrier, carrying a name the table does not
            // mention. If the retirement were keyed on "the carrier contains
            // it" alone, this would delete a unit nobody agreed to retire.
            InstallUseCase.Report report = install(h.store(), pluginCarrying(CARRIER, "acme-tool"));

            assertEquals(0, report.exitCode(), "the install itself is fine — sharing a name "
                    + "with a standalone unit is legal under OUN-13");
            assertTrue(Files.isDirectory(h.store().skillDir("acme-tool")),
                    "and the unit is still there — the table is a list of two historical "
                            + "facts, not a licence for a carrier to evict whatever it likes");
        });

        // ---------------------------------------------- the decision itself

        suite.test("the moved unit is retired only by a carrier that actually holds it", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir(MOVED, UnitKind.SKILL);

            // OUN-6 is what moves skill-manager into skt, and it lands AFTER
            // this ticket. Until it does, an skt that does not carry the skill
            // must not retire the only copy in the home.
            assertEquals(0,
                    (int) UnitSupersession.due(h.store(), java.util.List.of(CARRIER)).stream()
                            .filter(r -> MOVED.equals(r.unit())).count(),
                    "no contained copy on disk yet, so nothing is due");
        });

        suite.test("nothing is due when the carrier is not the unit being installed", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir(MOVED, UnitKind.SKILL);
            h.scaffoldUnitDir(OBSOLETE, UnitKind.SKILL);

            assertEquals(0,
                    UnitSupersession.due(h.store(), java.util.List.of("some-other-unit")).size(),
                    "installing an unrelated unit does not trigger somebody else's migration");
        });

        // ------------------------------------- the destructive half, guarded

        suite.test("a retirement will not eat unpublished work", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir(MOVED, UnitKind.SKILL);
            gitInitWithACommit(h.store().skillDir(MOVED));

            String blocked = UnitSupersession.blockedFrom(h.store(), MOVED);
            assertTrue(blocked != null, "a checkout whose commits are on no remote is blocked");

            InstallUseCase.Report report = install(h.store(), pluginCarrying(CARRIER, MOVED));
            assertTrue(report.exitCode() != 0, "and the upgrade stops rather than deleting it");
            assertTrue(Files.isDirectory(h.store().skillDir(MOVED)),
                    "the unit is still there, with its history — a home is the only place a "
                            + "unit's history lives until it is published");
        });

        suite.test("uncommitted changes block it too", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir(MOVED, UnitKind.SKILL);
            Path dir = h.store().skillDir(MOVED);
            gitInitWithACommit(dir);
            pushToABareRemote(dir);
            assertEquals(null, UnitSupersession.blockedFrom(h.store(), MOVED),
                    "CONTROL: published, and therefore not blocked — without this the case "
                            + "below passes against a guard that blocks everything");

            Files.writeString(dir.resolve("SKILL.md"), "edited, not committed\n");
            assertTrue(UnitSupersession.blockedFrom(h.store(), MOVED) != null,
                    "an uncommitted edit is work too");
        });

        suite.test("a unit that is not a checkout is not blocked by the guard", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir(MOVED, UnitKind.SKILL);

            assertEquals(null, UnitSupersession.blockedFrom(h.store(), MOVED),
                    "there is no history to lose, so there is nothing to refuse over");
        });

        suite.test("every row names a reason an operator can act on", () -> {
            for (UnitSupersession.Retirement r : UnitSupersession.TABLE) {
                assertTrue(r.reason() != null && r.reason().length() > 30,
                        "row " + r.unit() + " states why");
                assertTrue(r.carrier() != null && !r.carrier().isBlank(),
                        "row " + r.unit() + " names what retires it");
            }
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- fixtures

    private static InstallUseCase.Report install(SkillStore store, Path unitDir) {
        var program = InstallUseCase.buildProgram(
                store, null, null, unitDir.toString(), null, true, false, false, true);
        return new Executor(store, null).runStaged(program).result();
    }

    private static void gitInitWithACommit(Path dir) throws Exception {
        run(dir, "git", "init", "-q");
        run(dir, "git", "config", "user.email", "test@example.invalid");
        run(dir, "git", "config", "user.name", "test");
        run(dir, "git", "add", "-A");
        run(dir, "git", "commit", "-q", "-m", "unit content");
    }

    /** A real remote, so "published" is proved rather than assumed. */
    private static void pushToABareRemote(Path dir) throws Exception {
        Path remote = Files.createTempDirectory("migration-remote-").resolve("origin.git");
        run(dir.getParent(), "git", "init", "--bare", "-q", remote.toString());
        run(dir, "git", "remote", "add", "origin", remote.toString());
        run(dir, "git", "push", "-q", "origin", "HEAD");
        run(dir, "git", "fetch", "-q", "origin");
    }

    private static void run(Path cwd, String... command) throws Exception {
        Process p = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IllegalStateException(String.join(" ", command) + " -> " + rc + ": " + out);
        }
    }

    private static Path pluginCarrying(String pluginName, String skillName) throws Exception {
        Path root = Files.createTempDirectory("migration-src-").resolve(pluginName);
        Files.createDirectories(root.resolve(".claude-plugin"));
        Files.writeString(root.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"" + pluginName + "\",\"version\":\"0.1.0\","
                        + "\"description\":\"migration fixture\"}\n");
        Files.writeString(root.resolve("skill-manager-plugin.toml"), """
                [plugin]
                name = "%s"
                version = "0.1.0"
                description = "migration fixture"
                """.formatted(pluginName));
        Path contained = root.resolve("skills").resolve(skillName);
        Files.createDirectories(contained);
        Files.writeString(contained.resolve("SKILL.md"),
                "---\nname: " + skillName + "\ndescription: contained fixture\n---\n\nbody\n");
        Files.writeString(contained.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "contained fixture"
                """.formatted(skillName));
        return root;
    }
}
