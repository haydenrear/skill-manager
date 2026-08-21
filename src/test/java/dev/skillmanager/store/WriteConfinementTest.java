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

                    WriteConfinement.Scope previous = WriteConfinement.declare(fx.scope());
                    try {
                        WriteConfinement.checkDelete(mirror, "the pruner");
                    } finally {
                        WriteConfinement.restore(previous);
                    }
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
                    Path entry = binCli.resolve("tool");

                    String refusal = refused(fx, () -> WriteConfinement.checkDelete(entry, "the pruner"));

                    assertTrue(refusal.contains("outside the home"),
                            "the escaping component is the DIRECTORY, and resolving the parent "
                                    + "is what catches it; got:\n" + refusal);
                })

                .test("a declared extra root is permitted, and it is the ONLY way one is", () -> {
                    // An exception that is listed can be argued with; one that is
                    // implicit is the bug. This is the listing mechanism, and the
                    // second half of the case is that nothing else gets in.
                    Fixture fx = Fixture.build("extra-root");
                    Path shared = fx.elsewhere.resolve("shared-cache/thing");
                    Path unlisted = fx.base.resolve("third/thing");
                    Files.createDirectories(shared.getParent());
                    Files.createDirectories(unlisted.getParent());

                    WriteConfinement.Scope widened = WriteConfinement.forHome(
                            fx.home, "an effect with a second root", fx.elsewhere.resolve("shared-cache"));
                    WriteConfinement.Scope previous = WriteConfinement.declare(widened);
                    boolean listedRefused = false;
                    boolean unlistedRefused = false;
                    try {
                        try {
                            WriteConfinement.checkWrite(shared, "a listed write");
                        } catch (WriteOutsideHomeException e) {
                            listedRefused = true;
                        }
                        try {
                            WriteConfinement.checkWrite(unlisted, "an unlisted write");
                        } catch (WriteOutsideHomeException e) {
                            unlistedRefused = true;
                        }
                    } finally {
                        WriteConfinement.restore(previous);
                    }

                    assertTrue(!listedRefused, "a root the effect declared is permitted");
                    assertTrue(unlistedRefused, "and one it did not declare is not");
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
                    WriteConfinement.checkDelete(Path.of("/etc/anywhere-at-all"), "unscoped");
                })

                .test("a declaration does not outlive its scope", () -> {
                    // LiveInterpreter declares per effect and restores in a
                    // finally; a leak here would confine every later effect in
                    // the process to whichever home ran first.
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
                        WriteConfinement.checkDelete(throughAlias, "a delete through an alias");
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
