package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.HomeCommand;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * What {@code home verify} does with a CHILD home's link at its parent store.
 *
 * <h2>The two designs that contradicted each other</h2>
 *
 * <p>{@code ChildHomeMaterializer.mirrorExistingShim} symlinks a child home's
 * {@code bin/cli/<dep>} at the PARENT store's entry on purpose, so the child
 * shares the toolchain the parent provisioned. {@code home verify}'s isolation
 * rule (#49) forbids any path resolving into another home. Measured on
 * {@code harness-smoke}: every child home carrying one CLI dep failed its own
 * verify with {@code ✗ FOREIGN_HOME bin/cli/pycowsay … resolves into the home
 * at <parent>}, and printed no remedy at all.
 *
 * <p>The rule is over-broad rather than wrong, so the exception it grows is
 * NARROW, and these tests are mostly about the narrowness: the same link shape
 * with no evidence behind it, in the wrong directory, or naming a different
 * entry, is still a leak. A sanction that fires on shape alone would sanction
 * exactly the stale copied link the rule exists for.
 *
 * @see dev.skillmanager.bindings.ChildHomeLink
 */
public final class ChildHomeShimIsolationTest {

    public static int run() throws Exception {
        return Tests.suite("ChildHomeShimIsolationTest")

                .test("a child home's shim at its parent's entry passes, on the PARENT's claim", () -> {
                    Fixture fx = Fixture.build("claimed");
                    fx.mirrorShim("tool");
                    fx.writeParentClaim();

                    Result r = verify(fx.child);

                    assertEquals(0, r.rc, "a sanctioned parent-store shim is not a leak; got:\n"
                            + r.err);
                    assertContains(r.out, "link at its parent store",
                            "and it is REPORTED, because a home that is not self-contained "
                                    + "is a fact the reader needs");
                    assertContains(r.out, "except the 1 sanctioned parent-store shim(s)",
                            "the ✓ verdict names its own exception rather than claiming "
                                    + "the home reaches no other home");
                })

                .test("and on the CHILD's own provenance, which outlives the parent's claim", () -> {
                    // harness rm deletes the child-home record and keeps the
                    // child store — both asserted by the harness-smoke graph.
                    // This is the state the fixpoint law actually caught.
                    Fixture fx = Fixture.build("provenance");
                    fx.mirrorShim("tool");
                    fx.writeChildMaterializationRecord();

                    Result r = verify(fx.child);

                    assertEquals(0, r.rc, "the child's own materialization record is evidence "
                            + "enough after teardown; got:\n" + r.err);
                    assertContains(r.out, "link at its parent store", "and it is reported");
                })

                .test("with NO evidence on either side, the identical link is still a leak", () -> {
                    Fixture fx = Fixture.build("unclaimed");
                    fx.mirrorShim("tool");

                    Result r = verify(fx.child);

                    assertEquals(1, r.rc, "shape alone never sanctions — that is all a stale "
                            + "copied link has too");
                    assertContains(r.err, "FOREIGN_HOME bin/cli/tool",
                            "and it is reported as the isolation failure it is");
                })

                .test("a COPY of a sanctioned child inherits the sanction — root -> project -> worktree", () -> {
                    // HIS-7 / #223, and this is the case that blocked every
                    // ticket worktree in the repository.
                    //
                    // The sanction asks "is the DESTINATION a child of the home
                    // this shim points into". During a clone the destination is
                    // a home that DOES NOT EXIST YET: nothing claims it, and it
                    // was materialized from the SOURCE, not from the home the
                    // shim names. The real chain is root -> project -> worktree,
                    // so a clone of a child is a GRANDCHILD and the one-level
                    // question could never answer yes.
                    //
                    // Measured before the fix: bootstrap-home.sh reported
                    // "clone verification FAILED — 5 path(s) reach outside this
                    // copy" and "not usable", so neither `wt new` nor
                    // `skt ticket new` could produce a ticket home at all.
                    Fixture fx = Fixture.build("inherited");
                    fx.mirrorShim("tool");
                    fx.writeParentClaim();
                    assertEquals(0, verify(fx.child).rc, "precondition: the child is sanctioned");

                    Path copy = copyHome(fx.child, "worktree");

                    Result r = verifyAgainst(copy, fx.child);

                    assertEquals(0, r.rc, "a copy inherits the reason its source's shim was "
                            + "allowed; copying bytes changes nothing about whose artifact "
                            + "it is. got:\n" + r.err);
                    assertContains(r.out, "sanctioned parent-store shim",
                            "and the copy still REPORTS it, rather than claiming to reach "
                                    + "no other home");
                })

                .test("inheritance needs a source that was itself sanctioned, not merely a source", () -> {
                    // The guard that keeps this from being a widening. An
                    // UNCLAIMED child's shim is a leak (asserted above); a copy
                    // of that child must inherit the leak, not launder it.
                    // Without this, "clone it once more" would become a way to
                    // turn any foreign path into a sanctioned one.
                    Fixture fx = Fixture.build("laundering");
                    fx.mirrorShim("tool");
                    // deliberately NO parent claim and NO child record
                    assertEquals(1, verify(fx.child).rc, "precondition: the child is a leak");

                    Path copy = copyHome(fx.child, "worktree");

                    Result r = verifyAgainst(copy, fx.child);

                    assertEquals(1, r.rc, "a copy of an unsanctioned home is still unsanctioned "
                            + "— cloning is not a laundering step");
                    assertContains(r.err, "FOREIGN_HOME bin/cli/tool",
                            "and it is reported as the isolation failure it is");
                })

                .test("the sanction does not extend past bin/cli and bin/mcp", () -> {
                    Fixture fx = Fixture.build("scope");
                    fx.writeParentClaim();
                    Path unit = Files.createDirectories(fx.child.resolve("skills/demo"));
                    Files.createDirectories(fx.parent.resolve("skills/demo/nodes"));
                    Files.createSymbolicLink(unit.resolve("nodes"),
                            fx.parent.resolve("skills/demo/nodes"));

                    Result r = verify(fx.child);

                    assertEquals(1, r.rc, "only the two shim directories are sanctioned");
                    assertContains(r.err, "FOREIGN_HOME skills/demo/nodes",
                            "a unit tree linked at the parent is not a mirrored shim");
                })

                .test("a shim naming a DIFFERENT entry in the parent is not a mirror", () -> {
                    Fixture fx = Fixture.build("mismatch");
                    fx.writeParentClaim();
                    fx.parentEntry("bin/cli", "other");
                    Files.createDirectories(fx.child.resolve("bin/cli"));
                    Files.createSymbolicLink(fx.child.resolve("bin/cli/tool"),
                            fx.parent.resolve("bin/cli/other"));

                    Result r = verify(fx.child);

                    assertEquals(1, r.rc, "a mirror is the SAME entry in the parent, not any "
                            + "entry that happens to live there");
                    assertContains(r.err, "FOREIGN_HOME bin/cli/tool", "and it is named");
                })

                .test("an isolation refusal prints a remedy, in the spelling every caller parses", () -> {
                    Fixture fx = Fixture.build("remedy");
                    fx.mirrorShim("tool");

                    Result r = verify(fx.child);

                    assertEquals(1, r.rc, "still refused");
                    // The single defect this half of the release exists to
                    // close: a refusal with no remedy. The marker is the one
                    // HomeFixpointLaw parses out of stdout and runs verbatim.
                    String remedy = remedyFrom(r.err + "\n" + r.out);
                    assertTrue(remedy != null,
                            "the isolation verdict must print a runnable remedy; got:\n" + r.err);
                    assertContains(remedy, "sync --force-scripts",
                            "and it is the pass that prunes the foreign link and re-provisions");
                    assertContains(remedy, "SKILL_MANAGER_HOME=" + fx.child,
                            "pinned at THIS home, both axes — see homeEnvPrefix");
                    assertContains(remedy, "CLAUDE_CONFIG_DIR=",
                            "including the agent axis, or the remedy writes the operator's "
                                    + "global config (#145)");
                })

                .test("a leaking launcher pin gets home shims, which is the command that repairs it", () -> {
                    Fixture fx = Fixture.build("launcher");
                    fx.parentEntry("bin/cli", "skill-manager");
                    Files.createDirectories(fx.child.resolve("bin/cli"));
                    Files.createSymbolicLink(fx.child.resolve("bin/cli/skill-manager"),
                            fx.parent.resolve("bin/cli/skill-manager"));

                    Result r = verify(fx.child);

                    assertEquals(1, r.rc, "the CLI pin reaching into another home is a leak");
                    String remedy = remedyFrom(r.err + "\n" + r.out);
                    assertTrue(remedy != null && remedy.contains("home shims"),
                            "sync does not write the pin and CliShimPruner will not touch it, "
                                    + "so naming sync here would be a remedy that repairs "
                                    + "nothing; got: " + remedy);
                })

                .runAll();
    }

    // ------------------------------------------------------------- fixture

    /** A parent store and a child home laid out the way a real project is. */
    private record Fixture(Path parent, Path child) {

        static Fixture build(String label) throws Exception {
            Path root = Files.createTempDirectory("child-shim-" + label + "-");
            Path parent = newHome(root.resolve("parent"));
            Path child = newHome(root.resolve("proj/.skill-manager"));
            return new Fixture(parent, child);
        }

        /** An executable entry in the parent, and the child's symlink at it. */
        void mirrorShim(String name) throws Exception {
            parentEntry("bin/cli", name);
            Files.createDirectories(child.resolve("bin/cli"));
            Files.createSymbolicLink(child.resolve("bin/cli/" + name),
                    parent.resolve("bin/cli/" + name));
        }

        Path parentEntry(String dir, String name) throws Exception {
            Path entry = Files.createDirectories(parent.resolve(dir)).resolve(name);
            Files.writeString(entry, "#!/usr/bin/env sh\nexit 0\n");
            entry.toFile().setExecutable(true, false);
            return entry;
        }

        /** {@code <parent>/child-homes/<id>/child-home.json}, the live claim. */
        void writeParentClaim() throws Exception {
            Path dir = Files.createDirectories(parent.resolve("child-homes/proj"));
            Files.writeString(dir.resolve("child-home.json"), """
                    {
                      "id" : "proj",
                      "parentHome" : "%s",
                      "childHome" : "%s",
                      "units" : [ "demo" ],
                      "createdAt" : "2026-01-01T00:00:00Z"
                    }
                    """.formatted(parent, child));
        }

        /** What the child itself records about where its units came from. */
        void writeChildMaterializationRecord() throws Exception {
            Path dir = Files.createDirectories(child.resolve(".materialization/skill"));
            Files.writeString(dir.resolve("demo.json"), """
                    {
                      "schemaVersion" : 2,
                      "unitName" : "demo",
                      "unitKind" : "SKILL",
                      "mode" : "COPY",
                      "source" : "%s/skills/demo",
                      "materializedAt" : "2026-01-01T00:00:00Z",
                      "reconcileKind" : "copy"
                    }
                    """.formatted(parent));
        }
    }

    private static Path newHome(Path root) throws Exception {
        SkillStore store = new SkillStore(root);
        store.init();
        return root;
    }

    // ------------------------------------------------------------ plumbing

    private record Result(int rc, String out, String err) {}

    /** Verify {@code home} as a copy OF {@code source} — the clone's question. */
    private static Result verifyAgainst(Path home, Path source) {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            int rc = new CommandLine(new HomeCommand.VerifyCmd())
                    .execute("--home", home.toString(), "--against", source.toString());
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }

    /**
     * A byte copy of {@code home} beside it, symlinks preserved — what a clone
     * produces before anything re-anchors it.
     */
    private static Path copyHome(Path home, String name) throws Exception {
        Path dest = home.getParent().getParent().resolve(name + "/.skill-manager");
        Files.createDirectories(dest.getParent());
        try (var walk = Files.walk(home)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                Path rel = home.relativize(src);
                Path to = dest.resolve(rel.toString());
                if (Files.isSymbolicLink(src)) {
                    Files.createDirectories(to.getParent());
                    Files.createSymbolicLink(to, Files.readSymbolicLink(src));
                } else if (Files.isDirectory(src)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(src, to);
                    to.toFile().setExecutable(src.toFile().canExecute(), false);
                }
            }
        }
        return dest;
    }

    private static Result verify(Path home) {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            int rc = new CommandLine(new HomeCommand.VerifyCmd())
                    .execute("--home", home.toString());
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }

    /**
     * The remedy exactly as printed — the same extraction {@code HomeFixpointLaw}
     * performs, deliberately, rather than rebuilding the command from parts. A
     * test that rebuilds it asserts against a COPY of the production logic and
     * passes while the real sentence is un-runnable (see 69ad2ac).
     */
    private static String remedyFrom(String output) {
        for (String raw : output.split("\n")) {
            int at = raw.indexOf("complete it with: ");
            if (at < 0) continue;
            String rest = raw.substring(at + "complete it with: ".length()).trim();
            int tail = rest.indexOf(", then re-run this check");
            if (tail >= 0) rest = rest.substring(0, tail);
            if (!rest.isBlank()) return rest.trim();
        }
        return null;
    }
}
