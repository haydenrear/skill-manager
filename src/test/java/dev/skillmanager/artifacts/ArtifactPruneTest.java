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

        suite.test("a wrapper shim a registered child home links at is not an orphan", () -> {
            SkillStore store = ArtifactsFixture.seed();
            record(store);
            // HomeCloner's parentStoreShims: a child home's bin/cli entry is a
            // symlink AT THE PARENT'S by design, and `home verify` reports
            // those and refuses to count them. A parent that prunes without
            // reading its children breaks a home whose name appears nowhere in
            // the parent's installed set.
            //
            // This is the WRAPPER shape — the parent's own bin/cli entry is a
            // regular file — and it is the easy half. The case below is the
            // one this passed while getting wrong.
            Path childHome = ArtifactsFixture.newDir("prune-child-home-");
            Files.createDirectories(childHome.resolve("bin/cli"));
            Files.createSymbolicLink(childHome.resolve("bin/cli/alpha-script"),
                    store.cliBinDir().resolve("alpha-script"));
            registerChild(store, "child-1", childHome);
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

        // F5. The shape the ticket names and the shape the operator's home
        // actually has: the parent's own bin/cli entry is a SYMLINK into a
        // venvs/ tree, so the child's mirrored link resolves one hop PAST it.
        // `toRealPath()` on the child's entry returns
        // venvs/shared-venv/bin/shared-tool, which is neither the shim's output
        // (bin/cli/shared-tool) nor the tree's (venvs/shared-venv) — and an
        // exact-string claim test therefore matched neither, planned both for
        // removal, and left the child home's entry dangling.
        suite.test("a SYMLINK shim a child home links at keeps both it and its tree", () -> {
            SkillStore store = ArtifactsFixture.seed();
            ArtifactsFixture.withSharedVenvShim(store);
            record(store);
            Path childHome = ArtifactsFixture.newDir("prune-child-home-venv-");
            Files.createDirectories(childHome.resolve("bin/cli"));
            Files.createSymbolicLink(childHome.resolve("bin/cli/shared-tool"),
                    store.cliBinDir().resolve("shared-tool"));
            registerChild(store, "child-venv", childHome);
            uninstall(store, "alpha");

            ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());

            ArtifactPrune.Step shim = byId(plan, ArtifactIds.cliShim("pip", "shared-venv"));
            assertEquals(ArtifactPrune.Verdict.CLAIMED, shim.verdict(),
                    "the shim the child links at: " + shim.reason());
            assertContains(shim.reason(), "child home", "named as a child-home claim");

            // The tree is the half nothing in the registry mentions. It is
            // reached by the containment rule on the resolved chain and by the
            // reverse edge from the shim, and it is the half whose deletion is
            // not recoverable.
            ArtifactPrune.Step tree = byId(plan,
                    ArtifactIds.provisionedTree("venvs", "shared-venv"));
            assertEquals(ArtifactPrune.Verdict.CLAIMED, tree.verdict(),
                    "and the tree it runs out of: " + tree.reason());
            assertContains(tree.reason(), "child home", "for the same reason");

            ArtifactPrune.apply(store, plan);
            assertTrue(Files.exists(store.cliBinDir().resolve("shared-tool"),
                            LinkOption.NOFOLLOW_LINKS),
                    "the parent's shim survives");
            assertTrue(Files.isRegularFile(
                            store.root().resolve("venvs/shared-venv/bin/shared-tool")),
                    "and so does the tree, so the child home's entry still RESOLVES rather "
                            + "than merely existing");
        });

        suite.test("every hop of a child home's link chain is claimed, not just the last", () -> {
            SkillStore store = ArtifactsFixture.seed();
            ArtifactsFixture.withSharedVenvShim(store);
            Path childHome = ArtifactsFixture.newDir("prune-child-hops-");
            Files.createDirectories(childHome.resolve("bin/cli"));
            Files.createSymbolicLink(childHome.resolve("bin/cli/shared-tool"),
                    store.cliBinDir().resolve("shared-tool"));

            java.util.Set<String> hops = ArtifactPrune.claimedByChildHome(
                    childHome.resolve("bin/cli/shared-tool"), store.root());
            assertTrue(hops.contains("bin/cli/shared-tool"),
                    "the parent's own entry is on the chain: " + hops);
            assertTrue(hops.contains("venvs/shared-venv/bin/shared-tool"),
                    "and so is what it points into: " + hops);
        });

        // F6. The guard tested `target.startsWith(home)` on the UNRESOLVED
        // path while Fs.deleteRecursive operated on what the kernel resolved,
        // so an output crossing an intermediate directory symlink passed a
        // test about a string and deleted a tree outside the home.
        suite.test("an output that reaches outside the home through a directory symlink "
                + "is refused", () -> {
            SkillStore store = ArtifactsFixture.seed();
            uninstall(store, "alpha");
            Path outside = ArtifactsFixture.newDir("prune-outside-");
            Path victim = outside.resolve("subdir");
            Files.createDirectories(victim);
            Files.writeString(victim.resolve("precious.txt"), "not this home's to delete\n");
            Files.createSymbolicLink(store.root().resolve("cache/escape"), outside);
            Files.writeString(ArtifactLedger.file(store), """
                    schema = 1
                    recorded_at = "2026-01-01T00:00:00Z"

                    [[artifact]]
                    id = "provisioned-tree:cache/escapee"
                    kind = "provisioned-tree"
                    owner = "alpha"
                    outputs = ["cache/escape/subdir"]
                    """);

            ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());
            ArtifactPrune.Step step = byId(plan, "provisioned-tree:cache/escapee");
            assertEquals(ArtifactPrune.Verdict.REFUSED, step.verdict(),
                    "refused: " + step.reason());
            assertContains(step.reason(), "not inside this home",
                    "and says it on the path the kernel would use");

            ArtifactPrune.apply(store, plan);
            assertTrue(Files.isRegularFile(victim.resolve("precious.txt")),
                    "and the tree outside the home is untouched");
        });

        // F7. `visitFileFailed` returned CONTINUE, swallowing the per-entry
        // AccessDeniedException that the catch below it was written for — so
        // "an unreadable subtree is treated as containing a .git" was asserted
        // in three places and true in none.
        suite.test("a subtree this pass cannot read is treated as holding a .git", () -> {
            SkillStore store = ArtifactsFixture.seed();
            Path tree = store.root().resolve("cache/skill-script-alpha-alpha-script");
            Path locked = tree.resolve("locked");
            Files.createDirectories(locked.resolve(".git/refs"));
            Files.writeString(locked.resolve(".git/HEAD"), "ref: refs/heads/main\n");
            boolean sealed = seal(locked);
            if (!sealed) {
                // Running as root, or a filesystem that ignores mode bits. Say
                // so rather than passing on an assertion that never ran.
                System.out.println("    (skipped: this filesystem/user ignores mode bits)");
                return;
            }
            try {
                record(store);
                uninstall(store, "alpha");
                ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of("alpha"));
                ArtifactPrune.Step step = byId(plan, ArtifactIds.provisionedTree(
                        "cache", "skill-script-alpha-alpha-script"));
                assertEquals(ArtifactPrune.Verdict.REFUSED, step.verdict(),
                        "refused, not pruned: " + step.reason());
                assertContains(step.reason(), "could not", "and says it could not read it");
                assertContains(step.reason(), "#29", "with the issue that made it a rule");
                ArtifactPrune.apply(store, plan);
                assertTrue(Files.isDirectory(locked, LinkOption.NOFOLLOW_LINKS),
                        "and the tree holding it is still there");
            } finally {
                unseal(locked);
            }
        });

        // F9. The unit-store guard keyed on the DECLARED KIND, so a row
        // declaring `provisioned-tree` over skills/<unit> deleted the store of
        // every unit that has no .git — which is every file:- or
        // tarball-installed unit, the .git walk being the only thing in front
        // of them.
        suite.test("a row whose output is a unit store is refused, whatever kind it declares",
                () -> {
            SkillStore store = ArtifactsFixture.seed();
            Path unit = store.root().resolve("skills/notgit-unit");
            Files.createDirectories(unit);
            Files.writeString(unit.resolve("SKILL.md"),
                    "---\nname: notgit-unit\ndescription: installed from a tarball\n---\nbody\n");
            uninstall(store, "alpha");
            Files.writeString(ArtifactLedger.file(store), """
                    schema = 1
                    recorded_at = "2026-01-01T00:00:00Z"

                    [[artifact]]
                    id = "provisioned-tree:skills/notgit-unit"
                    kind = "provisioned-tree"
                    owner = "alpha"
                    outputs = ["skills/notgit-unit"]
                    """);

            ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());
            ArtifactPrune.Step step = byId(plan, "provisioned-tree:skills/notgit-unit");
            assertEquals(ArtifactPrune.Verdict.REFUSED, step.verdict(),
                    "refused: " + step.reason());
            assertContains(step.reason(), "unit content",
                    "and says which verb owns it instead");
            ArtifactPrune.apply(store, plan);
            assertTrue(Files.isRegularFile(unit.resolve("SKILL.md")),
                    "and the store of a unit with no .git survives a prune");
        });

        // The other edge of the same rule. `harnesses` IS one of HomeCloner's
        // CONTENT_ROOTS, so a literal top-segment test would have taken every
        // harness instance out of scope — and five of them are what this
        // ticket's own measured run removed from a clone of the operator's
        // home. `harnesses/instances` is a STATE_SUBTREE, which is why the
        // rule asks HomeCloner.classify rather than restating the set.
        suite.test("a harness instance is still in scope, though `harnesses` is a content root",
                () -> {
            SkillStore store = ArtifactsFixture.seed();
            record(store);
            Path instance = store.root().resolve("harnesses/instances/inst-1");
            assertTrue(Files.isDirectory(instance), "the instance the fixture wrote");

            ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());
            ArtifactPrune.Step step = byId(plan, ArtifactIds.harnessInstance("inst-1"));
            assertEquals(ArtifactPrune.Verdict.PRUNE, step.verdict(),
                    "its owning harness is not installed here: " + step.reason());
            ArtifactPrune.apply(store, plan);
            assertFalse(Files.exists(instance, LinkOption.NOFOLLOW_LINKS),
                    "and it goes, as it did before the unit-content rule existed");
        });

        // F8. Fs.deleteRecursive threw straight out through apply(), so every
        // step after it was skipped AND the ledger re-save never ran — leaving
        // the home partially pruned with a ledger naming what was gone.
        suite.test("one step that cannot be deleted does not abandon the rest, or the ledger",
                () -> {
            SkillStore store = ArtifactsFixture.seed();
            Path blocked = store.root().resolve("cache/blocked-tree");
            Files.createDirectories(blocked.resolve("inner"));
            Files.writeString(blocked.resolve("inner/file"), "x\n");
            uninstall(store, "alpha");
            Files.writeString(ArtifactLedger.file(store), """
                    schema = 1
                    recorded_at = "2026-01-01T00:00:00Z"

                    [[artifact]]
                    id = "provisioned-tree:cache/blocked-tree"
                    kind = "provisioned-tree"
                    owner = "alpha"
                    outputs = ["cache/blocked-tree"]

                    [[artifact]]
                    id = "provisioned-tree:cache/skill-script-alpha-alpha-script"
                    kind = "provisioned-tree"
                    owner = "alpha"
                    outputs = ["cache/skill-script-alpha-alpha-script"]
                    """);
            // Readable — so the `.git` walk sees inside it and does NOT refuse
            // it — but not writable, so removing what is inside fails. That
            // separates this case from the one above: F7 is about a tree the
            // scan cannot READ, this is about a tree apply() cannot REMOVE.
            boolean sealed = sealReadOnly(blocked.resolve("inner")) && sealReadOnly(blocked);
            if (!sealed) {
                System.out.println("    (skipped: this filesystem/user ignores mode bits)");
                return;
            }
            try {
                ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());
                List<String> pruned = ArtifactPrune.apply(store, plan);

                assertFalse(Files.exists(
                                store.root().resolve("cache/skill-script-alpha-alpha-script"),
                                LinkOption.NOFOLLOW_LINKS),
                        "the step after the failing one still ran");
                assertFalse(pruned.contains("provisioned-tree:cache/blocked-tree"),
                        "and the one that failed is not reported as pruned: " + pruned);
                // The serious half. `Fs.deleteRecursive` propagated out of
                // apply(), so this line never ran and the home was left
                // partially pruned with a ledger describing the moment before.
                assertFalse(ArtifactLedger.load(store).recordedAt()
                                .equals("2026-01-01T00:00:00Z"),
                        "and the ledger was re-recorded from what is on disk rather than left "
                                + "describing the moment before the pass");
            } finally {
                unseal(blocked);
            }
        });

        // F12. `list()` swallowed a per-file decode failure, so an unreadable
        // registry and an empty one were the same empty list — and an empty
        // list is what makes this command delete.
        suite.test("a child-home record this pass cannot decode stops the prune, by name", () -> {
            SkillStore store = ArtifactsFixture.seed();
            record(store);
            Path tree = store.root().resolve("cache/skill-script-alpha-alpha-script");
            Path registryFile = new ChildHomeRegistry(store).file("child-corrupt");
            Files.createDirectories(registryFile.getParent());
            Files.writeString(registryFile, "{ this is not json");
            uninstall(store, "alpha");

            ChildHomeRegistry.Listing listing = new ChildHomeRegistry(store).listing();
            assertTrue(listing.records().isEmpty(), "nothing decodes");
            assertEquals(1, listing.unreadable().size(),
                    "and the registry says so rather than returning a clean empty list");

            ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());
            assertTrue(plan.prunes().isEmpty(), "so nothing is planned: " + plan.prunes());
            assertFalse(plan.refusals().isEmpty(), "and each candidate is refused, by name");
            assertContains(plan.refusals().get(0).reason(), "child home",
                    "naming what could not be read");
            ArtifactPrune.apply(store, plan);
            assertTrue(Files.isDirectory(tree), "and nothing was removed");
        });

        // F13. The same rule as F12, one level down. `Files.isDirectory`
        // answers false for "not there" and for "could not be stat'd" alike,
        // and those are opposite instructions — so a registered child home
        // whose `bin/cli` sits behind an unreadable parent contributed ZERO
        // claims, silently, and the parent planned to delete the tree that
        // live home is running out of. The `catch (IOException)` the author
        // put on the readdir below it was never reached for this shape.
        suite.test("a child home's shim directory this pass cannot stat stops the prune", () -> {
            SkillStore store = ArtifactsFixture.seed();
            ArtifactsFixture.withSharedVenvShim(store);
            record(store);
            Path childHome = ArtifactsFixture.newDir("prune-child-unstattable-");
            Files.createDirectories(childHome.resolve("bin/cli"));
            Files.createSymbolicLink(childHome.resolve("bin/cli/shared-tool"),
                    store.cliBinDir().resolve("shared-tool"));
            registerChild(store, "child-sealed", childHome);
            uninstall(store, "alpha");

            // Sealing the PARENT is what makes the stat itself fail: mode 000
            // on `bin/cli` is a readdir failure, which the pass already had an
            // answer for. This is the dead-mount / detached-volume shape.
            Path sealed = childHome.resolve("bin");
            if (!seal(sealed)) {
                System.out.println("    (skipped: this filesystem/user ignores mode bits)");
                return;
            }
            try {
                Path tree = store.root().resolve("venvs/shared-venv");
                ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());

                String treeId = ArtifactIds.provisionedTree("venvs", "shared-venv");
                for (ArtifactPrune.Step step : plan.prunes()) {
                    assertFalse(treeId.equals(step.id()),
                            "the tree the sealed child home runs out of is not a deletion step");
                }
                assertTrue(plan.prunes().isEmpty(),
                        "and nothing at all is planned while a child home is unreadable: "
                                + plan.prunes());
                assertFalse(plan.refusals().isEmpty(), "the pass refuses instead");
                assertContains(plan.refusals().get(0).reason(), childHome.toString(),
                        "naming the child home whose links it could not read");

                ArtifactPrune.apply(store, plan);
                assertTrue(Files.isRegularFile(tree.resolve("bin/shared-tool")),
                        "so the child home's entry still RESOLVES rather than merely existing");
            } finally {
                unseal(sealed);
            }
        });

        // F14. F13's rule, one tier UP, where it does more damage. The registry
        // decided whether a record exists with `Files.isRegularFile`, which
        // answers the same false to "no such record" and "I could not stat the
        // record" — so a record directory behind a mode that bites was dropped
        // BEFORE `unreadable` was ever consulted, and the prune received a
        // clean, empty, confident answer about every child home at once. F13
        // silences one shim directory; this silences the whole registry.
        suite.test("a child-home record this pass cannot stat stops the prune, by name", () -> {
            SkillStore store = ArtifactsFixture.seed();
            ArtifactsFixture.withSharedVenvShim(store);
            record(store);
            Path childHome = ArtifactsFixture.newDir("prune-child-record-sealed-");
            Files.createDirectories(childHome.resolve("bin/cli"));
            Files.createSymbolicLink(childHome.resolve("bin/cli/shared-tool"),
                    store.cliBinDir().resolve("shared-tool"));
            registerChild(store, "child-record-sealed", childHome);
            uninstall(store, "alpha");

            // The child home itself is perfectly readable. What is sealed is
            // the PARENT's own record of it — a plain `chmod 000` on
            // `<home>/child-homes/<id>/`, which is all it takes to make the
            // stat of `child-home.json` inside it fail.
            Path sealed = new ChildHomeRegistry(store).file("child-record-sealed").getParent();
            if (!seal(sealed)) {
                System.out.println("    (skipped: this filesystem/user ignores mode bits)");
                return;
            }
            try {
                // Asserted on the PLAN first, because the plan is what the
                // reviewer measured: `provisioned-tree:venvs/shared-venv` was a
                // deletion step, and applying it took the venv tree out from
                // under a live registered child home.
                Path tree = store.root().resolve("venvs/shared-venv");
                ArtifactPrune.Plan plan = ArtifactPrune.of(store, List.of());

                String treeId = ArtifactIds.provisionedTree("venvs", "shared-venv");
                for (ArtifactPrune.Step step : plan.prunes()) {
                    assertFalse(treeId.equals(step.id()),
                            "the tree the live child home runs out of is not a deletion step");
                }
                assertTrue(plan.prunes().isEmpty(),
                        "and nothing at all is planned while a record is unreadable: "
                                + plan.prunes());
                assertFalse(plan.refusals().isEmpty(), "the pass refuses instead");
                assertContains(plan.refusals().get(0).reason(), "child home",
                        "naming what could not be read");

                ChildHomeRegistry.Listing listing = new ChildHomeRegistry(store).listing();
                assertTrue(listing.records().isEmpty(), "the record cannot be read");
                assertEquals(1, listing.unreadable().size(),
                        "and it is the REGISTRY that says so, rather than handing the pass a "
                                + "clean empty list: " + listing.unreadable());

                ArtifactPrune.apply(store, plan);
                assertTrue(Files.isRegularFile(tree.resolve("bin/shared-tool")),
                        "so the child home's entry still RESOLVES rather than merely existing");
            } finally {
                unseal(sealed);
            }
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

        // F10. The refusals and their reasons are `Log.warn`, so they go to
        // stderr as every warning in this program does. What stdout carried
        // was "0 artifact(s) would be removed" — the same sentence a home with
        // nothing to prune gets. An operator or a script reading the default
        // surface could not tell "clean" from "refused everything".
        suite.test("the plain-text verdict says how many it refused, not just how many it "
                + "removed", () -> {
            SkillStore store = ArtifactsFixture.seed();
            // Deliberately NOT recorded: the no-ledger home is the one the PR
            // body's claim was made about.
            uninstall(store, "alpha");

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
            java.io.PrintStream realOut = System.out;
            java.io.PrintStream realErr = System.err;
            int rc;
            try {
                System.setOut(new java.io.PrintStream(out, true));
                System.setErr(new java.io.PrintStream(err, true));
                var cmd = new dev.skillmanager.commands.ArtifactsCommand.PruneArtifacts();
                cmd.injectedStore = store;
                rc = new picocli.CommandLine(cmd).execute("--dry-run");
            } finally {
                System.setOut(realOut);
                System.setErr(realErr);
            }

            assertEquals(0, rc, "a report, not a failure");
            assertContains(out.toString(), "refused",
                    "the stdout verdict names the refusals: " + out);
            assertContains(err.toString(), "no ledger row",
                    "and the per-artifact reason is still there in full");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------------ helpers

    private static void registerChild(SkillStore store, String id, Path childHome)
            throws Exception {
        new ChildHomeRegistry(store).write(new ChildHomeRegistry.ChildHomeRecord(
                id, store.root().toString(), childHome.toString(), null,
                List.of(), "2026-01-01T00:00:00Z"));
    }

    /**
     * Make {@code dir} unreadable, or report that this filesystem/user will not
     * allow it. Verified by actually opening it: running as root, mode 000 is
     * not a refusal, and a test that assumed it was would assert nothing.
     */
    private static boolean seal(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, java.util.Set.of());
        } catch (Exception unsupported) {
            return false;
        }
        try (var entries = Files.newDirectoryStream(dir)) {
            entries.iterator();
            return false;
        } catch (java.io.IOException refused) {
            return true;
        }
    }

    /** Readable and traversable, but nothing inside it may be unlinked. */
    private static boolean sealReadOnly(Path dir) {
        try {
            Files.setPosixFilePermissions(dir,
                    java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (Exception unsupported) {
            return false;
        }
        return !Files.isWritable(dir);
    }

    private static void unseal(Path dir) {
        try {
            Files.setPosixFilePermissions(dir,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                try (var entries = Files.newDirectoryStream(dir)) {
                    for (Path child : entries) {
                        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) unseal(child);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

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
