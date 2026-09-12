package dev.skillmanager.lifecycle;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.app.SyncUseCase;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

            // The home must be CONSISTENT afterwards, not merely missing a
            // directory. home.membership.law reads exactly this pair, and an
            // installed/ record naming a tree the home does not hold is its
            // definition of a LOST unit — "a unit nobody removed".
            assertFalse(Files.exists(h.store().root().resolve("installed/" + MOVED + ".json")),
                    "the installed record goes with the tree");
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

            assertEquals(0, UnitSupersession.dueInThisHome(h.store()).size(),
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
                    (int) UnitSupersession.dueInThisHome(h.store()).stream()
                            .filter(r -> MOVED.equals(r.unit())).count(),
                    "no contained copy on disk yet, so nothing is due. This is now the ONLY "
                            + "thing standing between the home and a deleted unit: the sync "
                            + "path no longer waits to be told the carrier is involved");
        });

        // THIS CASE USED TO ASSERT THE OPPOSITE, and the assertion was the
        // defect written down: keyed on the target list, `sync skill-manager`
        // named the retired unit but not its carrier and fired nothing, so the
        // one command a person runs after being told about this migration was
        // the one command that did not perform it.
        suite.test("a home that is DUE is due whatever is being synced", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir(OBSOLETE, UnitKind.SKILL);

            assertEquals(1,
                    (int) UnitSupersession.dueInThisHome(h.store()).stream()
                            .filter(r -> OBSOLETE.equals(r.unit())).count(),
                    "the home holds a unit that was deleted upstream; which unit somebody "
                            + "happens to be syncing does not change that");
        });

        suite.test("the target list decides mandatory vs reported, not what is due", () -> {
            UnitSupersession.Retirement moved = UnitSupersession.TABLE.stream()
                    .filter(r -> MOVED.equals(r.unit())).findFirst().orElseThrow();

            assertTrue(UnitSupersession.isMandatory(moved, java.util.List.of(CARRIER)),
                    "the carrier is what is being synced, so proceeding without the "
                            + "retirement produces the two-copies state on purpose");
            assertFalse(UnitSupersession.isMandatory(moved, java.util.List.of("deploy-helm")),
                    "an unrelated sync must not be held hostage by a migration it did not "
                            + "ask for — it reports and moves on");
            assertFalse(UnitSupersession.isMandatory(moved, null),
                    "and no list at all is not a licence to halt");
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

        // ---------------------------------------------- THE SYNC PATH

        // The path OUN-5 declared (its test_graph key is "home-sync") and did
        // not exercise. Install is how a home ACQUIRES the carrier; sync is how
        // every project home that already has it reaches the new shape on its
        // own, which is the case that actually happens to people.

        suite.test("a plain sync retires what the home is due", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir(OBSOLETE, UnitKind.SKILL);
            h.scaffoldUnitDir("acme-tool", UnitKind.SKILL);

            sync(h.store(), List.of("acme-tool", OBSOLETE));

            assertFalse(Files.exists(h.store().skillDir(OBSOLETE)),
                    "the unit deleted upstream is gone after an ordinary sync — nobody had "
                            + "to know it needed retiring");
            assertTrue(Files.isDirectory(h.store().skillDir("acme-tool")),
                    "and the unit that was actually being synced is untouched");
        });

        suite.test("syncing the RETIRED unit by name performs its retirement", () -> {
            // The regression this whole follow-up exists for. Keyed on the
            // target list, `sync skill-manager` named the retired unit but not
            // its carrier, so nothing fired — and that is the exact command
            // someone runs after being told this migration exists.
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir(MOVED, UnitKind.SKILL);
            installCarrier(h.store());

            sync(h.store(), List.of(MOVED));

            assertFalse(Files.exists(h.store().skillDir(MOVED)),
                    "naming the retired unit is enough; the home is what decides what is due");
        });

        suite.test("the sync plans the retirement before it commits units", () -> {
            TestHarness h = TestHarness.create();
            var program = SyncUseCase.buildProgram(h.store(), null,
                    new SyncUseCase.Options(null, false, false, false, false, false, false, false),
                    List.of(new SyncUseCase.Target.Git("acme-tool")), List.of());

            // STAGE 2, and deliberately: stage 1 is where the git pulls
            // happen, so the carrier on disk is only current once stage 1 has
            // run. Asking "what does the carrier contain" any earlier reads
            // the copy the sync is about to replace.
            var tail = program.stage2().apply(
                    new dev.skillmanager.effects.EffectContext(h.store(), null));
            int retire = indexOf(tail, SkillEffect.RetireSupersededUnits.class);
            int commit = indexOf(tail, SkillEffect.CommitUnitsToStore.class);
            int validate = indexOf(tail, SkillEffect.ValidateMarkdownImports.class);

            assertTrue(retire >= 0, "the retirement is planned on the sync path at all");
            assertTrue(commit < 0 || retire < commit,
                    "and before the commit — after it, the home has held two copies of one "
                            + "name for the length of an operation");
            assertTrue(validate < 0 || retire < validate,
                    "and before import validation, which would otherwise be resolving names "
                            + "against a home with two answers for one of them");
            assertEquals(-1, indexOf(program.stage1(), SkillEffect.RetireSupersededUnits.class),
                    "and NOT in stage 1, where the pulls have not happened yet");
        });

        suite.test("an unrelated sync is not held hostage by a blocked retirement", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir(OBSOLETE, UnitKind.SKILL);
            h.scaffoldUnitDir("acme-tool", UnitKind.SKILL);
            gitInitWithACommit(h.store().skillDir(OBSOLETE));   // unpublished work

            sync(h.store(), List.of("acme-tool"));

            assertTrue(Files.isDirectory(h.store().skillDir(OBSOLETE)),
                    "the retirement is refused, as it must be — that checkout holds history "
                            + "no other copy has");
            assertTrue(Files.isDirectory(h.store().skillDir("acme-tool")),
                    "and the sync the operator actually asked for still happened. A migration "
                            + "that halts `sync deploy-helm` over somebody else's uncommitted "
                            + "edit is a migration holding the whole home hostage");
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

    /** A sync over the named units, as SyncCommand builds one. */
    private static void sync(SkillStore store, java.util.List<String> unitNames) throws Exception {
        java.util.List<SyncUseCase.Target> targets = new java.util.ArrayList<>();
        for (String name : unitNames) targets.add(new SyncUseCase.Target.Git(name));
        var program = SyncUseCase.buildProgram(store, null,
                new SyncUseCase.Options(null, false, false, false, false, false, false, false),
                targets, java.util.List.of());
        new Executor(store, null).runStaged(program);
    }

    /** Put the carrier in the home, carrying the moved skill, as OUN-6 will. */
    private static void installCarrier(SkillStore store) throws Exception {
        install(store, pluginCarrying(CARRIER, MOVED));
    }

    private static int indexOf(dev.skillmanager.effects.Program<?> program, Class<?> type) {
        java.util.List<SkillEffect> effects = program.effects();
        for (int i = 0; i < effects.size(); i++) {
            if (type.isInstance(effects.get(i))) return i;
        }
        return -1;
    }

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
