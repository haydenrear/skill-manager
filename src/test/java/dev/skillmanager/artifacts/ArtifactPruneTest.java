package dev.skillmanager.artifacts;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeRegistry;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * ARTI-08: <b>teardown is a reverse walk of the edges, and the ledger decides
 * what may be deleted.</b>
 *
 * <p>The cases split in two, and the second half is the important one. The
 * first half is that the walk reaches what {@code PruneCliIfOrphan} misses —
 * the {@code cache/skill-script-<unit>-<tool>/} tree, which has outlived every
 * uninstall this program has ever run. The second half is every way this
 * command must REFUSE, because a prune is the one operation in a home whose
 * mistakes are not re-derivable.
 */
public final class ArtifactPruneTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ArtifactPruneTest");

        // ------------------------------------------------------- the reverse walk

        suite.test("the skill-script cache tree goes with its unit", () -> {
            SkillStore store = ArtifactsFixture.seed();
            record(store);
            Path tree = store.root().resolve("cache/skill-script-alpha-alpha-script");
            assertTrue(Files.isDirectory(tree), "the tree the install wrote is there");

            uninstall(store, "alpha");
            ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of("alpha"));
            List<String> pruned = ArtifactPrune.apply(store, plan);

            assertFalse(Files.exists(tree, LinkOption.NOFOLLOW_LINKS),
                    "and it does not outlive the unit that asked for it — the class"
                            + " CliDependencyCleaner.removeArtifacts has no branch for");
            assertTrue(pruned.contains(ArtifactIds.provisionedTree(
                            "cache", "skill-script-alpha-alpha-script")),
                    "reported by id: " + pruned);
        });

        suite.test("pruning a shim drops its cli-lock.toml row", () -> {
            SkillStore store = ArtifactsFixture.seed();
            record(store);
            uninstall(store, "alpha");

            ArtifactPrune.apply(store, ArtifactPrune.of(store, List.of()));

            // The live homes carry three rows in exactly this state: declared
            // by no installed unit, left by a removal that had no caller to ask
            // about them.
            assertTrue(CliLock.load(store).get("skill-script", "alpha-script") == null,
                    "the row went with the artifact it describes");
        });

        suite.test("a whole-home prune finds what past removals left behind", () -> {
            SkillStore store = ArtifactsFixture.seed();
            record(store);
            uninstall(store, "alpha");

            ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());
            assertFalse(plan.prunes().isEmpty(), "there is something to catch up on");
            for (ArtifactPrune.Step step : plan.prunes()) {
                assertTrue(step.owner() != null, "and every one of them names an owner");
                assertFalse(step.paths().isEmpty(), "and the paths it would remove");
            }
        });

        // --------------------------------------------------------- the refusals

        suite.test("a home with no ledger deletes nothing", () -> {
            SkillStore store = ArtifactsFixture.seed();
            uninstall(store, "alpha");
            // Deliberately NOT recorded. The rule is that the ledger decides,
            // so a home that never wrote one has nothing this command may act
            // on — including the tree it can plainly see on disk.
            ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());
            assertFalse(plan.ledgerPresent(), "no ledger");
            assertTrue(plan.prunes().isEmpty(),
                    "and nothing is planned: " + plan.prunes());
            assertFalse(plan.refusals().isEmpty(), "each candidate is refused, by name");
            assertContains(plan.refusals().get(0).reason(), "no ledger row",
                    "and the refusal says what would make it actionable");
            assertTrue(Files.isDirectory(
                            store.root().resolve("cache/skill-script-alpha-alpha-script")),
                    "the tree is still there");
        });

        suite.test("a target containing a .git is refused, by name", () -> {
            SkillStore store = ArtifactsFixture.seed();
            // The #29 shape. `Rederivable`'s heading is a standing instruction
            // that no disposal decision may step over a `.git`, and a prune is
            // the sharpest disposal decision in this program: the commits in
            // there exist nowhere else, and the directory looks exactly like
            // every cache around it.
            Path tree = store.root().resolve("cache/skill-script-alpha-alpha-script");
            Files.createDirectories(tree.resolve("checkout/.git/refs"));
            Files.writeString(tree.resolve("checkout/.git/HEAD"), "ref: refs/heads/main\n");
            record(store);
            uninstall(store, "alpha");

            ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of("alpha"));
            ArtifactPrune.Step step = byId(plan,
                    ArtifactIds.provisionedTree("cache", "skill-script-alpha-alpha-script"));
            assertEquals(ArtifactPrune.Verdict.REFUSED, step.verdict(), "refused, not pruned");
            assertContains(step.reason(), ".git", "and the refusal names what stopped it");
            assertContains(step.reason(), "#29", "with the issue that made it a rule");

            ArtifactPrune.apply(store, plan);
            assertTrue(Files.isDirectory(tree.resolve("checkout/.git")),
                    "and the repository is still there afterwards");
        });

        suite.test("an unattributed tree is not an orphan", () -> {
            SkillStore store = ArtifactsFixture.seed();
            record(store);
            uninstall(store, "alpha");

            ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());
            // `cache/uv-tools` is the shared uv root, credited to nobody
            // because the only shim naming it does so through a broken link.
            // "Nothing claims it" is not "delete it": the shared
            // package-manager roots are permanently in that state (#122).
            for (ArtifactPrune.Step step : plan.steps()) {
                assertFalse(step.id().equals(ArtifactIds.provisionedTree("cache", "uv-tools")),
                        "the shared root is not even a candidate");
            }
            assertTrue(Files.isDirectory(store.root().resolve("cache/uv-tools")),
                    "and it survives");
        });

        suite.test("a surviving consumer keeps the tree", () -> {
            SkillStore store = ArtifactsFixture.seed();
            record(store);
            // alpha is gone; a second unit still declares the same tool, so the
            // tree it runs out of has a live claimant. Decided by the reverse
            // edges ARTI-05 drew, not by a rule about names.
            uninstall(store, "alpha");
            installBeta(store);

            ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());
            for (ArtifactPrune.Step step : plan.prunes()) {
                assertFalse(step.id().contains("skill-script-alpha-alpha-script"),
                        "the tree beta still runs out of is not planned for removal");
            }
            ArtifactPrune.apply(store, plan);
            assertTrue(Files.isDirectory(
                            store.root().resolve("cache/skill-script-alpha-alpha-script")),
                    "and it is still there");
        });

        suite.test("a shim a registered child home links at is not an orphan", () -> {
            SkillStore store = ArtifactsFixture.seed();
            record(store);
            // HomeCloner's parentStoreShims: a child home's bin/cli entry is a
            // symlink AT THE PARENT'S by design, and `home verify` reports
            // those and refuses to count them. A parent that prunes without
            // reading its children breaks a home whose name appears nowhere in
            // the parent's installed set.
            Path childHome = ArtifactsFixture.newDir("prune-child-home-");
            Files.createDirectories(childHome.resolve("bin/cli"));
            Files.createSymbolicLink(childHome.resolve("bin/cli/alpha-script"),
                    store.cliBinDir().resolve("alpha-script"));
            new ChildHomeRegistry(store).write(new ChildHomeRegistry.ChildHomeRecord(
                    "child-1", store.root().toString(), childHome.toString(), null,
                    List.of(), "2026-01-01T00:00:00Z"));
            uninstall(store, "alpha");

            ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());
            ArtifactPrune.Step step = byId(plan, ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertEquals(ArtifactPrune.Verdict.CLAIMED, step.verdict(),
                    "claimed by the child home: " + step.reason());
            assertContains(step.reason(), "child home", "and the reason says which relationship");
            ArtifactPrune.apply(store, plan);
            assertTrue(Files.exists(store.cliBinDir().resolve("alpha-script")),
                    "so the child home's entry still resolves");
        });

        suite.test("a recorded output that escapes the home is refused", () -> {
            SkillStore store = ArtifactsFixture.seed();
            uninstall(store, "alpha");
            // The ledger refuses to WRITE such a row, so this shape can only
            // arrive by a hand edit or a future writer — which is exactly when
            // a deleter needs its own opinion rather than a shared assumption.
            Files.writeString(ArtifactLedger.file(store), """
                    schema = 1
                    recorded_at = "2026-01-01T00:00:00Z"

                    [[artifact]]
                    id = "provisioned-tree:cache/escapee"
                    kind = "provisioned-tree"
                    owner = "alpha"
                    outputs = ["cache/../../elsewhere"]
                    """);

            ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());
            ArtifactPrune.Step step = byId(plan, "provisioned-tree:cache/escapee");
            assertEquals(ArtifactPrune.Verdict.REFUSED, step.verdict(), "refused");
            assertContains(step.reason(), "not inside this home",
                    "and says why it will not touch it");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------------ helpers

    private static void record(SkillStore store) throws Exception {
        ArtifactLedger.of(ArtifactIndex.of(store).artifacts()).save(store);
    }

    /** What a removal leaves behind today: the unit is gone, its artifacts are not. */
    private static void uninstall(SkillStore store, String unit) throws Exception {
        dev.skillmanager.shared.util.Fs.deleteRecursive(store.root().resolve("skills/" + unit));
        Files.deleteIfExists(store.root().resolve("installed/" + unit + ".json"));
    }

    /** A second installed unit that declares the same skill-script tool. */
    private static void installBeta(SkillStore store) throws Exception {
        Path beta = store.root().resolve("skills/beta");
        Files.createDirectories(beta);
        Files.writeString(beta.resolve("SKILL.md"),
                "---\nname: beta\ndescription: fixture\n---\nbody\n");
        Files.writeString(beta.resolve("skill-manager.toml"), """
                [skill]
                name = "beta"
                version = "0.1.0"
                description = "beta fixture"

                [[cli_dependencies]]
                name = "alpha-script"
                spec = "skill-script:alpha-script"
                on_path = "alpha-script"

                [cli_dependencies.install.any]
                script = "install.sh"
                binary = "alpha-script"
                """);
        new dev.skillmanager.source.UnitStore(store).write(
                new dev.skillmanager.source.InstalledUnit("beta", "0.1.0",
                        dev.skillmanager.source.InstalledUnit.Kind.LOCAL_DIR,
                        dev.skillmanager.source.InstalledUnit.InstallSource.LOCAL_FILE,
                        null, null, null, "2026-01-01T00:00:00Z", List.of(),
                        dev.skillmanager.model.UnitKind.SKILL));
        CliLock lock = CliLock.load(store);
        lock.put(new CliLock.Entry("skill-script", "alpha-script", "1.0.0",
                "skill-script:alpha-script", null, List.of("beta"),
                "2026-01-01T00:00:00Z", "fingerprint-abc"));
        lock.save(store);
    }

    private static ArtifactPrune.Step byId(ArtifactPrune.Plan plan, String id) {
        for (ArtifactPrune.Step step : plan.steps()) {
            if (step.id().equals(id)) return step;
        }
        throw new AssertionError("no step for " + id + " in "
                + plan.steps().stream().map(ArtifactPrune.Step::id).toList());
    }
}
