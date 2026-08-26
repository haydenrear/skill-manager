package dev.skillmanager.store;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * DEF-101 — <b>"the destination lacks this and cannot get it" is not the same
 * sentence as "the destination lacks this and its own manifest declares it".</b>
 *
 * <h2>The measurement</h2>
 *
 * <p>HIS-20's close-out: {@code exit 1}, {@code safe: false}, two blockers —
 * {@code skill:skill-manager} and {@code plugin:skt}, both {@code new}, both
 * "not in the destination home". Both were unmodified checkouts at published
 * {@code main} SHAs, and the destination's own {@code skill-project.toml}
 * declares both. <b>Nothing would have been destroyed.</b> The gate had no way
 * to say so, so it blocked — and the operator's only route past it was to sync
 * bytes the destination can fetch, out of a home about to be deleted.
 *
 * <h2>Why the guard case matters more than the fix case</h2>
 *
 * <p>The cheapest way to make {@code CASE 1} pass is to stop blocking on
 * {@code NEW}, which would delete the gate's entire reason to exist. So
 * {@code CASE 2} and {@code CASE 3} pin the two halves that must not move: an
 * undeclared new unit still blocks, and a declared unit the worktree has
 * <em>edited</em> still blocks. Between them they say the exemption is about
 * provenance, not about the word {@code NEW}.
 */
public final class HomeCloseOutSelfObtainableTest {

    private static final String DECLARED = "declared-unit";
    private static final String AUTHORED = "authored-unit";

    public static int run() throws Exception {
        return Tests.suite("HomeCloseOutSelfObtainableTest")

                // ------------------------------------------------------- CASE 1: the fix

                .test("a new unit the destination's own manifest declares does not block teardown", () -> {
                    Fixture f = Fixture.create("obtainable");
                    f.declare(DECLARED, "skills", "github:example/declared-unit");
                    f.putInWorktreeOnly(DECLARED);

                    // PRECONDITIONS. Both asserted, because either one being
                    // false makes CASE 1 pass for the wrong reason.
                    assertFalse(Files.isDirectory(f.dest.skillDir(DECLARED)),
                            "fixture precondition: the destination really does not hold it");
                    assertTrue(Files.isRegularFile(f.repoRoot.resolve("skill-project.toml")),
                            "fixture precondition: the destination's manifest exists beside its home");

                    HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.source, f.dest);

                    assertTrue(verdict.safe(),
                            "a unit the destination declares at a published source, unmodified in "
                                    + "the worktree, is not work that exists nowhere else");
                    assertEquals(0, verdict.exitCode(), "so the teardown is not refused");
                    assertEquals(0, verdict.blockers().size(),
                            "and it is not on the fix list: " + HomeCloseOut.render(verdict));

                    // NOT SILENT. A fix that simply stopped reporting the unit
                    // would pass every assertion above and be a regression.
                    assertEquals(1, verdict.selfObtainable().size(),
                            "the decision is reported, not swallowed");
                    HomeCloseOut.SelfObtainable obtainable = verdict.selfObtainable().get(0);
                    assertEquals("skill:" + DECLARED, obtainable.label(), "naming the unit");
                    assertEquals("github:example/declared-unit", obtainable.source(),
                            "and where the destination said it comes from");
                    assertContains(obtainable.remedy(), "project resolve",
                            "and the command that obtains it");
                    assertContains(String.join("\n", HomeCloseOut.render(verdict)), "obtainable",
                            "and the human rendering says so too");

                    // And it still WROTE NOTHING: close-out is a read-only gate,
                    // and an exemption must not become a quiet copy.
                    assertFalse(Files.isDirectory(f.dest.skillDir(DECLARED)),
                            "clearing the gate did not materialize anything into the destination");
                })

                // ----------------------------------- CASE 2: guard — undeclared still blocks

                .test("a new unit nothing declares still blocks, with the same remedy", () -> {
                    Fixture f = Fixture.create("undeclared");
                    f.declare(DECLARED, "skills", "github:example/declared-unit");
                    f.putInWorktreeOnly(AUTHORED);

                    HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.source, f.dest);

                    assertFalse(verdict.safe(),
                            "the exemption is about the manifest, not about the word NEW — a unit "
                                    + "the agent authored in the worktree exists nowhere else");
                    assertEquals(1, verdict.blockers().size(), "exactly the unit at risk");
                    assertEquals("skill:" + AUTHORED, verdict.blockers().get(0).label(),
                            "naming it");
                    assertContains(verdict.blockers().get(0).remedy(), "home sync",
                            "with the unchanged remedy");
                    assertEquals(0, verdict.selfObtainable().size(),
                            "and nothing was exempted");
                })

                // ------------------------- CASE 3: guard — declared but locally edited blocks

                .test("a declared unit the worktree has edited is not obtainable and still blocks", () -> {
                    Fixture f = Fixture.create("edited");
                    f.declare(DECLARED, "skills", "github:example/declared-unit");
                    f.putInWorktreeOnly(DECLARED);
                    f.markLocallyModified(DECLARED);

                    HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.source, f.dest);

                    assertFalse(verdict.safe(),
                            "'the destination can fetch this' is false the moment the worktree's "
                                    + "copy stops being what the published ref holds");
                    assertEquals(0, verdict.selfObtainable().size(),
                            "so it is not exempted");
                    assertEquals(1, verdict.blockers().size(), "and it blocks");
                    assertEquals("skill:" + DECLARED, verdict.blockers().get(0).label(),
                            "naming the unit whose edit would be lost");
                })

                // ---- CASE 3b: guard - MAJOR 1 of PR #255. Pristine is not published.

                .test("a declared unit whose commits are on no remote still blocks", () -> {
                    Fixture f = Fixture.create("unpublished");
                    f.declare(DECLARED, "skills", "github:example/declared-unit");
                    f.unpublishedCheckout(DECLARED);

                    HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.source, f.dest);

                    assertFalse(verdict.safe(),
                            "the reviewer of #255 built exactly this and the first version of the "
                                    + "gate cleared it, printing \"nothing in this worktree's copy "
                                    + "exists only here\" in the same document where the CLI "
                                    + "printed NO_GIT_REMOTE for the same unit. A materialization "
                                    + "record says nobody TOUCHED the unit; it says nothing about "
                                    + "whether what arrived exists anywhere else");
                    assertEquals(0, verdict.selfObtainable().size(), "so it is not exempted");
                    assertEquals(1, verdict.blockers().size(),
                            "and the commits that exist nowhere else are named");
                    assertEquals("skill:" + DECLARED, verdict.blockers().get(0).label(),
                            "naming the unit");
                })

                .test("the exemption states only what it verified, and names the ref", () -> {
                    Fixture f = Fixture.create("honest-remedy");
                    f.declare(DECLARED, "skills", "github:example/declared-unit");
                    f.putInWorktreeOnly(DECLARED);

                    HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.source, f.dest);
                    assertEquals(1, verdict.selfObtainable().size(), "precondition: it is exempt");
                    HomeCloseOut.SelfObtainable obtainable = verdict.selfObtainable().get(0);

                    assertContains(obtainable.publishedAt(), "refs/remotes/",
                            "the verdict carries the remote-tracking ref the claim rests on");
                    assertContains(obtainable.remedy(), "published at",
                            "and the remedy says what was actually established");
                    // #142: a remedy that does not work is worse than none, and
                    // that applies to the sentence beside the command too. The
                    // gate cannot verify that the DECLARED COORDINATE is
                    // fetchable -- `github:example/declared-unit` does not exist,
                    // as the reviewer pointed out -- so it must not imply it did.
                    assertFalse(obtainable.remedy().contains("exists only here"),
                            "and no longer asserts the unverifiable claim it used to: "
                                    + obtainable.remedy());
                    assertContains(obtainable.remedy(), "not one this gate verified",
                            "the declared source is named as the DESTINATION's claim, not as "
                                    + "something checked here");
                })

                // ------------------- CASE 4: guard — no manifest beside the destination

                .test("a destination with no manifest of its own exempts nothing", () -> {
                    Fixture f = Fixture.create("nomanifest");
                    Files.deleteIfExists(f.repoRoot.resolve("skill-project.toml"));
                    f.putInWorktreeOnly(DECLARED);

                    HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.source, f.dest);

                    assertFalse(verdict.safe(),
                            "with nothing declaring it, a root home destination blocks exactly as "
                                    + "it always did — the claim has to come from the destination");
                    assertEquals(0, verdict.selfObtainable().size(), "and nothing is exempted");
                })

                .runAll();
    }

    // ----------------------------------------------------------------- fixture

    /**
     * A worktree home and a destination "project" home laid out the way the
     * product lays them out: the destination home is {@code <repoRoot>/.skill-manager},
     * beside {@code <repoRoot>/skill-project.toml}. That geometry is the whole
     * mechanism under test, so the fixture builds it rather than stubbing it.
     */
    private static final class Fixture {
        final Path root;
        final Path repoRoot;
        final SkillStore source;
        final SkillStore dest;

        private Fixture(Path root, Path repoRoot, SkillStore source, SkillStore dest) {
            this.root = root;
            this.repoRoot = repoRoot;
            this.source = source;
            this.dest = dest;
        }

        static Fixture create(String label) throws IOException {
            Path root = Files.createTempDirectory("close-out-obtainable-" + label + "-");
            Path repoRoot = Files.createDirectories(root.resolve("repo"));
            SkillStore source = init(root.resolve("worktree/.skill-manager"));
            SkillStore dest = init(repoRoot.resolve(".skill-manager"));
            Files.writeString(repoRoot.resolve("skill-project.toml"), """
                    [project]
                    name = "close-out-obtainable"
                    """);
            return new Fixture(root, repoRoot, source, dest);
        }

        void declare(String unit, String table, String source) throws IOException {
            Files.writeString(repoRoot.resolve("skill-project.toml"), """
                    [project]
                    name = "close-out-obtainable"

                    [%s.%s]
                    source = "%s"
                    """.formatted(table, unit, source));
        }

        /**
         * A unit the worktree home has and the destination does not: status NEW,
         * as a checkout whose HEAD really is on a remote-tracking ref.
         *
         * <p>Three things are true of it, and all three are asserted rather than
         * assumed, because each one being false would make a case below green
         * for the wrong reason (mechanism B):
         *
         * <ul>
         *   <li>the clone baseline is recorded, which is what {@code home clone}
         *       does when a ticket worktree's home is created;</li>
         *   <li>the copy reads as pristine against that record;</li>
         *   <li><b>its HEAD is reachable from a real {@code refs/remotes/} ref</b>,
         *       built by cloning from a bare repository on disk rather than by
         *       writing a ref by hand. MAJOR 1 of PR #255's review is that
         *       "pristine" and "published" are different properties, so a fixture
         *       that conflated them could not tell the two apart.</li>
         * </ul>
         */
        void putInWorktreeOnly(String unit) throws Exception {
            publishedCheckout(unit);
            dev.skillmanager.bindings.ChildHomeMaterializer.recordCloneBaselines(source);
            assertFalse(new dev.skillmanager.bindings.ChildHomeMaterializer(dest, source)
                            .isLocallyModified(unit, dev.skillmanager.model.UnitKind.SKILL),
                    "fixture precondition: with a baseline recorded the worktree copy reads as "
                            + "pristine, so the only variable left is what the manifest declares");
            assertTrue(dev.skillmanager.source.GitOps.publishedRefContaining(
                            source.skillDir(unit)) != null,
                    "fixture precondition: the worktree copy's HEAD really is on a "
                            + "remote-tracking ref");
        }

        /**
         * The shape the reviewer of #255 built to defeat the first version of the
         * gate: a checkout carrying commits that exist on NO remote, over which a
         * clone baseline has been stamped. It reads as pristine, and it is the one
         * thing the exemption must never clear.
         */
        void unpublishedCheckout(String unit) throws Exception {
            publishedCheckout(unit);
            Path unitDir = source.skillDir(unit);
            Files.writeString(unitDir.resolve("SKILL.md"),
                    Files.readString(unitDir.resolve("SKILL.md"))
                            + "\nWORK THAT WAS NEVER PUSHED\n");
            git(unitDir, "add", ".");
            git(unitDir, "-c", "user.email=t@e.com", "-c", "user.name=T",
                    "commit", "-m", "unpushed");
            // The stamp is the defect's precondition, not a contrivance:
            // recordCloneBaselines writes gitStateOf(dir) over whatever is in the
            // tree, and `home clone` is how bootstrap-home.sh makes every ticket
            // worktree home in this epic.
            dev.skillmanager.bindings.ChildHomeMaterializer.recordCloneBaselines(source);
            assertFalse(new dev.skillmanager.bindings.ChildHomeMaterializer(dest, source)
                            .isLocallyModified(unit, dev.skillmanager.model.UnitKind.SKILL),
                    "fixture precondition: the stamped baseline makes unpushed work read as "
                            + "PRISTINE. That is the whole defect, and if this were false the "
                            + "case below would be testing nothing");
            assertTrue(dev.skillmanager.source.GitOps.publishedRefContaining(unitDir) == null,
                    "fixture precondition: and its HEAD really is on no remote-tracking ref");
        }

        /** A unit whose history exists on a real remote: a bare repo on disk. */
        private void publishedCheckout(String unit) throws IOException {
            Path origin = root.resolve("origins").resolve(unit + ".git");
            Path staging = root.resolve("staging").resolve(unit);
            try {
                Files.createDirectories(staging.getParent());
                UnitFixtures.scaffoldSkill(staging.getParent(), unit, DepSpec.empty());
                git(staging, "init", "-b", "main");
                git(staging, "add", ".");
                git(staging, "-c", "user.email=t@e.com", "-c", "user.name=T",
                        "commit", "-m", "published");
                Files.createDirectories(origin.getParent());
                git(staging, "clone", "--bare", staging.toString(), origin.toString());
                Files.createDirectories(source.skillsDir());
                dev.skillmanager.source.GitOps.clone(
                        source.skillDir(unit), origin.toString(), "main");
            } catch (IOException io) {
                throw io;
            } catch (Exception e) {
                throw new IOException("fixture: could not build a published checkout", e);
            }
        }

        private static void git(Path repo, String... args) throws Exception {
            String[] command = new String[args.length + 3];
            command[0] = "git";
            command[1] = "-C";
            command[2] = repo.toString();
            System.arraycopy(args, 0, command, 3, args.length);
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + ": " + out);
            }
        }

        /**
         * Make the worktree's copy carry work of its own.
         *
         * <p>Written through the materialization record rather than by editing
         * bytes at random, because {@code isLocallyModified} is defined against
         * that record: a fixture that edited the tree without a record would
         * report "not modified" and the case would pass vacuously — mechanism B.
         * The record is asserted to take effect below.
         */
        void markLocallyModified(String unit) throws IOException {
            dev.skillmanager.bindings.ChildHomeMaterializer worktreeSide =
                    new dev.skillmanager.bindings.ChildHomeMaterializer(dest, source);
            Path unitDir = source.skillDir(unit);
            Files.writeString(unitDir.resolve("SKILL.md"),
                    Files.readString(unitDir.resolve("SKILL.md")) + "\nAGENT EDIT\n");
            assertTrue(worktreeSide.isLocallyModified(unit, dev.skillmanager.model.UnitKind.SKILL),
                    "fixture precondition: the worktree copy really does read as locally modified");
        }

        private static SkillStore init(Path root) throws IOException {
            SkillStore store = new SkillStore(root);
            store.init();
            return store;
        }
    }
}
