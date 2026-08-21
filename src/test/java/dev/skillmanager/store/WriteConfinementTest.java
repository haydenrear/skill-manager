package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The confinement predicate itself: what counts as "outside", and the four
 * shapes that decided its design.
 *
 * <h2>Why writes and deletes are asked different questions</h2>
 *
 * <p>They do not act on the same thing when the last component is a link, and
 * one rule would have got one of them wrong in the destructive direction:
 *
 * <ul>
 *   <li>Following the leaf for a DELETE would refuse every sanctioned mirror
 *       shim in every child home — the link lives here, points there, and
 *       removing it removes something that is ours. That is the sharing design
 *       this epic exists to keep.</li>
 *   <li>NOT following the leaf for a WRITE would miss the HIS-7 escape
 *       entirely, which is a producer's {@code cat >} landing in the parent
 *       store's file. Measured twice on the operator's root home.</li>
 * </ul>
 *
 * <p>The cases below assert both directions, so a future simplification that
 * collapses them has to redden something.
 */
public final class WriteConfinementTest {

    public static int run() throws Exception {
        return Tests.suite("WriteConfinementTest")

                .test("a write through a link that leaves the home is refused, "
                        + "naming path AND home", () -> {
                    Fixture fx = Fixture.build("write-escape");
                    Path escape = fx.home.resolve("bin/cli/tool");
                    Files.createSymbolicLink(escape, fx.elsewhere.resolve("tool"));

                    String refusal = refused(fx, () -> WriteConfinement.checkWrite(escape, "a producer"));

                    assertTrue(refusal.contains(escape.toString()),
                            "the path as SPELLED, which is what the caller sees; got:\n" + refusal);
                    assertTrue(refusal.contains(fx.elsewhere.resolve("tool").toString())
                                    || refusal.contains(real(fx.elsewhere).resolve("tool").toString()),
                            "and where it RESOLVES, which is the whole bug; got:\n" + refusal);
                    assertTrue(refusal.contains(fx.home.toString())
                                    || refusal.contains(real(fx.home).toString()),
                            "and the home it escaped; got:\n" + refusal);
                })

                .test("a DELETE of that same link is permitted — it removes what lives here", () -> {
                    // The sanctioned-mirror shape. A rule that followed the leaf
                    // for deletes would refuse this, and refusing it breaks
                    // CliShimPruner and takeOwnershipOfShim in every child home.
                    Fixture fx = Fixture.build("delete-link");
                    Path mirror = fx.home.resolve("bin/cli/tool");
                    Files.createSymbolicLink(mirror, fx.elsewhere.resolve("tool"));

                    // requireInside is the LIVE delete rule -- the one
                    // takeOwnershipOfShim calls. There used to be a scoped
                    // checkDelete beside it with no production caller, and this
                    // case pointed at that one, so it was reddening a predicate
                    // the product never reached. It is gone; this asks the
                    // method that actually runs.
                    WriteConfinement.requireInside(mirror, fx.home, "the pruner");
                    // no exception is the assertion; make it explicit so the
                    // case cannot pass by not running.
                    assertTrue(Files.isSymbolicLink(mirror), "and the fixture really was a link");
                })

                .test("a delete through a DIRECTORY that leaves the home is refused — DEF-007",
                        () -> {
                    Fixture fx = Fixture.build("delete-through-dir");
                    Path binCli = fx.home.resolve("bin/cli");
                    dev.skillmanager.shared.util.Fs.deleteRecursive(binCli);
                    Files.createSymbolicLink(binCli, fx.elsewhere);

                    String refusal = refused(fx,
                            () -> WriteConfinement.requireContainerInside(binCli, fx.home, "the pruner"));

                    assertTrue(refusal.contains("outside the home"),
                            "the escaping component is the DIRECTORY, so the container rule -- "
                                    + "which resolves the leaf -- is what catches it; got:\n"
                                    + refusal);
                })

                .test("a scope has exactly ONE root, and there is no escape hatch", () -> {
                    // WHAT THIS REPLACED, AND WHY IT IS BETTER AS AN ABSENCE.
                    //
                    // forHome once took an `alsoUnder` varargs -- the
                    // reviewable-exemption seam this ticket's acceptance asks
                    // for -- and the case here passed one and watched it be
                    // permitted. NO PRODUCTION CALLER EVER PASSED ONE, so the
                    // case was exercising a parameter only it used.
                    //
                    // The two exemptions the guard really needs are not
                    // root-shaped: a sanctioned mirror, and a path outside every
                    // home. Both are decided by asking
                    // HomeCloner.unsanctionedForeignHome -- the predicate
                    // `home verify` itself uses -- so they cannot drift from
                    // what the gate refuses. That is asserted where it lives, in
                    // ProducerStaysInsideItsHomeTest.
                    //
                    // So what is pinned here is the ABSENCE: a scope admits its
                    // home and nothing else, and no caller can widen it.
                    Fixture fx = Fixture.build("one-root");
                    Path outside = fx.base.resolve("third/thing");
                    Files.createDirectories(outside.getParent());

                    WriteConfinement.Scope scope = fx.scope();
                    assertEquals(1, scope.roots().size(),
                            "one home, one root: " + scope.roots());

                    String refusal = refused(fx,
                            () -> WriteConfinement.checkWrite(outside, "an unlisted write"));
                    assertTrue(refusal.contains("outside the home"),
                            "and anything else is refused; got:\n" + refusal);
                })

                .test("the default gates nothing, because a dozen effects write outside on purpose",
                        () -> {
                    // MaterializeProjection writes ~/.claude, SyncClaimingProjects
                    // writes a project checkout, every package backend writes a
                    // shared cache. A default of "inside the home or refuse"
                    // would be an outage, not a guard. Asserted rather than
                    // assumed, because a later change that flipped the default
                    // would break the product in a way no other case here sees.
                    WriteConfinement.reset();
                    assertTrue(WriteConfinement.declared().unconfined(),
                            "nothing declared, nothing gated");
                    WriteConfinement.checkWrite(Path.of("/etc/anywhere-at-all"), "unscoped");
                })

                .test("a declaration does not outlive its scope", () -> {
                    // InstallerRegistry.installOne declares for the duration of
                    // one install and restores in a finally; a leak here would
                    // confine every later install in the process to whichever
                    // home ran first.
                    Fixture fx = Fixture.build("restore");
                    WriteConfinement.reset();
                    WriteConfinement.Scope previous = WriteConfinement.declare(fx.scope());
                    assertTrue(!WriteConfinement.declared().unconfined(), "in force inside");
                    WriteConfinement.restore(previous);
                    assertTrue(WriteConfinement.declared().unconfined(), "and gone outside");
                })

                .test("two spellings of one home are the same home", () -> {
                    // /var vs /private/var, and a symlinked alias. This codebase
                    // has been defeated by a path spelling five times; Fs.
                    // realOrNormalized's javadoc lists them.
                    Fixture fx = Fixture.build("spelling");
                    Path alias = fx.base.resolve("alias");
                    Files.createSymbolicLink(alias, fx.home);
                    Path throughAlias = alias.resolve("bin/cli/tool");
                    Files.createDirectories(fx.home.resolve("bin/cli"));
                    Files.writeString(fx.home.resolve("bin/cli/tool"), "x");

                    WriteConfinement.Scope previous = WriteConfinement.declare(fx.scope());
                    try {
                        WriteConfinement.checkWrite(throughAlias, "a write through an alias");
                        WriteConfinement.requireInside(throughAlias, fx.home,
                                "a delete through an alias");
                        WriteConfinement.requireContainerInside(alias.resolve("bin/cli"),
                                fx.home, "a container reached through an alias");
                    } finally {
                        WriteConfinement.restore(previous);
                    }
                    assertEquals(real(fx.home.resolve("bin/cli/tool")),
                            real(throughAlias),
                            "precondition: the two spellings really are one file");
                })

                .runAll();
    }

    // ------------------------------------------------------------- fixture

    private record Fixture(Path base, Path home, Path elsewhere) {

        static Fixture build(String label) throws Exception {
            Path base = Files.createTempDirectory("write-confinement-" + label + "-");
            Path home = base.resolve("home");
            new SkillStore(home).init();
            Files.createDirectories(home.resolve("bin/cli"));
            Path elsewhere = Files.createDirectories(base.resolve("elsewhere"));
            Files.writeString(elsewhere.resolve("tool"), "the other home's bytes\n");
            return new Fixture(base, home, elsewhere);
        }

        WriteConfinement.Scope scope() {
            return WriteConfinement.forHome(home, "a fixture operation");
        }
    }

    private static Path real(Path p) {
        return dev.skillmanager.shared.util.Fs.realOrNormalized(p);
    }

    private static String refused(Fixture fx, Runnable body) {
        WriteConfinement.Scope previous = WriteConfinement.declare(fx.scope());
        try {
            body.run();
        } catch (WriteOutsideHomeException refusal) {
            return refusal.getMessage();
        } finally {
            WriteConfinement.restore(previous);
        }
        throw new AssertionError("expected a write-confinement refusal, and nothing was refused");
    }
}
