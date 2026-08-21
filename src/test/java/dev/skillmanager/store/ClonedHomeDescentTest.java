package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.installer.CliShimPruner;
import dev.skillmanager.commands.HomeCommand;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * FOUR READERS, ONE ANSWER, on one cloned home, with no {@code --against}.
 *
 * <h2>The measurement this replaces</h2>
 *
 * <p>HIS-10 / issue #227. On epic tip {@code 23e35c7}, cloning this
 * repository's project home produced a home that four readers described three
 * different ways:
 *
 * <pre>
 *   home clone                 clean — "no path in it reaches another home"
 *   home verify --home &lt;clone&gt;  exit 1 — 5x FOREIGN_HOME on bin/cli/{computeq,
 *                              helm-deploy,monitoring,tla-spec-dev,tlc2}
 *   home verify … --against    exit 0 — "5 sanctioned parent-store shim(s)"
 *   sync (CliShimPruner)       PRUNED all five, then spent ~90s re-provisioning
 *                              five toolchains nobody had changed
 * </pre>
 *
 * <p>Every one of those five shims was a legitimately inherited artifact of the
 * operator's root store. The only thing that made the sanction visible was an
 * operator typing {@code --against}, which is a flag, not a fact.
 *
 * <h2>What each test here is for</h2>
 *
 * <p>The first drives all four readers over ONE home produced by the real
 * {@link HomeCloner#cloneHome} and asserts they agree. The second is the
 * vacuity control and it is the more important of the two: it deletes the
 * provenance record from that same home and asserts the readers go back to
 * DISAGREEING. Without it, "all four agree" would also pass on a build where
 * the sanction came from somewhere else entirely — and this epic has already
 * shipped two assertions that passed without their fix.
 *
 * <p>The third is the laundering guard restated over a real clone rather than a
 * byte copy: {@code ChildHomeShimIsolationTest} asserts it for
 * {@code --against}, and it has to hold for the recorded descent too, or
 * "clone it once more" becomes a way to sanction any foreign path.
 *
 * @see HomeProvenance
 * @see dev.skillmanager.store.ChildHomeShimIsolationTest
 */
public final class ClonedHomeDescentTest {

    /** The inherited tool, standing in for the five real ones. */
    private static final String TOOL = "tool";

    public static int run() throws Exception {
        return Tests.suite("ClonedHomeDescentTest")

                .test("all four readers agree about a cloned home's inherited shim, with no --against", () -> {
                    Fixture fx = Fixture.build("agree", true);
                    Path clone = fx.clone("worktree");

                    // READER 1 — the clone itself.
                    assertTrue(fx.report.clean(),
                            "home clone must not report a leak on a shim its own descent "
                                    + "record sanctions; got: " + fx.report.leaks());

                    // The record it left, which is what the other three read.
                    HomeProvenance.Descent descent = HomeProvenance.read(clone);
                    assertTrue(descent != null, "the clone recorded no descent at all");
                    assertEquals(fx.child.toString(), descent.clonedFrom(),
                            "the copy records the home it was made from");
                    assertTrue(HomeProvenance.sanctions(clone, fx.parent),
                            "and it records the PARENT STORE, which is the fact the shim's "
                                    + "sanction stands on — the chain is root -> project -> "
                                    + "worktree and the copy is a grandchild; recorded: "
                                    + descent.parentStores());

                    // READER 2 — home verify, no flag. This is the one that
                    // exited 1 with 5x FOREIGN_HOME before HIS-10.
                    Result bare = verify(clone);
                    assertEquals(0, bare.rc,
                            "`home verify --home <clone>` must reach the same verdict `home "
                                    + "clone` just did, WITHOUT an operator supplying a source; "
                                    + "got:\n" + bare.err);
                    assertContains(bare.out, "sanctioned parent-store shim",
                            "and it says WHY it passed, rather than claiming the home reaches "
                                    + "no other home");
                    assertContains(bare.out, "cloned from",
                            "and it declares the descent it read that from — the 'and DECLARES "
                                    + "it' half of the lazy contract");

                    // READER 3 — the same command with the flag. It may still
                    // explain the answer; it no longer decides it.
                    Result against = verifyAgainst(clone, fx.child);
                    assertEquals(bare.rc, against.rc,
                            "--against must not change the isolation verdict any more; bare="
                                    + bare.rc + " against=" + against.rc + "\n" + against.err);

                    // READER 4 — sync's shim pruner, which is the expensive one.
                    Path shim = clone.resolve("bin/cli").resolve(TOOL);
                    List<CliShimPruner.Pruned> pruned =
                            CliShimPruner.prune(new SkillStore(clone));
                    assertTrue(pruned.stream().noneMatch(p -> p.path().equals(shim)),
                            "the first sync in a clone must KEEP the toolchain it inherited — "
                                    + "pruning it costs a full re-provision of a tool nobody "
                                    + "changed; pruned: " + pruned);
                    assertTrue(Files.isSymbolicLink(shim),
                            "and it is still the inherited link, not a local rebuild");
                    assertEquals(fx.parentShim().toRealPath(), shim.toRealPath(),
                            "still resolving at the parent's own entry");
                })

                .test("VACUITY CONTROL: delete the record and the same four readers disagree again", () -> {
                    // The assertion above is worth exactly what this one is. If
                    // removing the evidence leaves every reader green, the
                    // evidence was not what made them green.
                    Fixture fx = Fixture.build("vacuity", true);
                    Path clone = fx.clone("worktree");
                    assertEquals(0, verify(clone).rc, "precondition: the clone verifies clean");

                    Files.delete(clone.resolve(HomeProvenance.FILENAME));

                    Result bare = verify(clone);
                    assertEquals(1, bare.rc,
                            "with no recorded descent the inherited shim is a foreign path "
                                    + "again — a home that cannot say where it came from "
                                    + "sanctions nothing");
                    assertContains(bare.err, "FOREIGN_HOME bin/cli/" + TOOL,
                            "and it is the exact finding HIS-10 was measured on");

                    Path shim = clone.resolve("bin/cli").resolve(TOOL);
                    List<CliShimPruner.Pruned> pruned =
                            CliShimPruner.prune(new SkillStore(clone));
                    assertTrue(pruned.stream().anyMatch(p -> p.path().equals(shim)),
                            "and the pruner deletes it, which is the ~90s re-provision this "
                                    + "ticket exists to stop; pruned: " + pruned);
                    assertTrue(!Files.exists(shim, LinkOption.NOFOLLOW_LINKS),
                            "the shim really is gone, so the two readers really did disagree");
                })

                .test("a clone of an UNSANCTIONED home records nothing and stays unsanctioned", () -> {
                    // Cloning is not a laundering step. ChildHomeShimIsolationTest
                    // asserts this for the --against path; it has to hold for the
                    // recorded one too, or the record becomes a way to mint a
                    // sanction the source never had.
                    Fixture fx = Fixture.build("laundering", false);   // no parent claim
                    assertEquals(1, verify(fx.child).rc, "precondition: the source is a leak");

                    Path clone = fx.clone("worktree");

                    HomeProvenance.Descent descent = HomeProvenance.read(clone);
                    assertTrue(descent != null, "a clone always records SOMETHING about itself");
                    assertTrue(descent.parentStores().isEmpty(),
                            "but it may only record a store its source was genuinely a child "
                                    + "of; got " + descent.parentStores());
                    assertTrue(!HomeProvenance.sanctions(clone, fx.parent),
                            "so the copy sanctions nothing the source did not");
                    assertEquals(1, verify(clone).rc,
                            "and every reader still refuses it");
                    assertEquals(1, verifyAgainst(clone, fx.child).rc,
                            "including with --against, unchanged from HIS-7");
                })

                .test("descent is transitive: a clone of a clone still shares the root store", () -> {
                    // root -> project -> wt1 -> wt2. The one-level question could
                    // never answer this, and HIS-7's srcRoot inheritance answers
                    // it only for wt1: wt2's source is wt1, which is not a
                    // registered child of anything. The record carries it because
                    // each copy inherits what its source recorded.
                    Fixture fx = Fixture.build("transitive", true);
                    Path wt1 = fx.clone("wt1");
                    Path wt2 = fx.root.resolve("wt2/.skill-manager");
                    HomeCloner.cloneHome(wt1, wt2, false, false);

                    assertTrue(HomeProvenance.sanctions(wt2, fx.parent),
                            "the second copy still records the root store; got "
                                    + HomeProvenance.read(wt2).parentStores());
                    assertEquals(0, verify(wt2).rc,
                            "so it verifies clean with no flag, two tiers down");
                })

                .runAll();
    }

    // ------------------------------------------------------------- fixture

    /**
     * {@code root -> project} laid out the way this repository really is: the
     * tool lives in ONE home, the middle home holds an absolute symlink at it,
     * and the copy under test inherits that link.
     */
    private static final class Fixture {
        private final Path root;
        private final Path parent;
        private final Path child;
        private HomeCloner.Report report;

        private Fixture(Path root, Path parent, Path child) {
            this.root = root;
            this.parent = parent;
            this.child = child;
        }

        static Fixture build(String label, boolean claimed) throws Exception {
            Path root = Files.createTempDirectory("clone-descent-" + label + "-");
            Path parent = newHome(root.resolve("parent"));
            Path child = newHome(root.resolve("proj/.skill-manager"));

            Path entry = Files.createDirectories(parent.resolve("bin/cli")).resolve(TOOL);
            Files.writeString(entry, "#!/usr/bin/env sh\nexit 0\n");
            entry.toFile().setExecutable(true, false);
            Files.createDirectories(child.resolve("bin/cli"));
            Files.createSymbolicLink(child.resolve("bin/cli/" + TOOL), entry);

            if (claimed) {
                Path dir = Files.createDirectories(parent.resolve("child-homes/proj"));
                Files.writeString(dir.resolve("child-home.json"), """
                        {
                          "id" : "proj",
                          "parentHome" : "%s",
                          "childHome" : "%s",
                          "units" : [ ],
                          "createdAt" : "2026-01-01T00:00:00Z"
                        }
                        """.formatted(parent, child));
            }
            return new Fixture(root, parent, child);
        }

        Path parentShim() { return parent.resolve("bin/cli").resolve(TOOL); }

        /** A REAL clone — {@code lazyArtifacts} pinned so the tier cannot decide it. */
        Path clone(String name) throws Exception {
            Path dest = root.resolve(name + "/.skill-manager");
            report = HomeCloner.cloneHome(child, dest, false, false);
            return dest;
        }
    }

    private static Path newHome(Path root) throws Exception {
        SkillStore store = new SkillStore(root);
        store.init();
        return root;
    }

    // ------------------------------------------------------------ plumbing

    private record Result(int rc, String out, String err) {}

    private static Result verify(Path home) {
        return run("--home", home.toString());
    }

    private static Result verifyAgainst(Path home, Path source) {
        return run("--home", home.toString(), "--against", source.toString());
    }

    private static Result run(String... args) {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            int rc = new CommandLine(new HomeCommand.VerifyCmd()).execute(args);
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }
}
