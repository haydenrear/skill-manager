package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The same home, reached two ways, gets the same verdict.
 *
 * <h2>The defect, #206</h2>
 *
 * <p>{@link HomeCloner#verifyRoots} resolved the destination root once, for the
 * SYMLINK branch ({@code realOrSame(dstRoot)}), and handed the UNRESOLVED
 * spelling to the provisioned-file branch. That branch ends in a literal
 * {@code text.indexOf(dstRoot.toString())}. So a home addressed through a
 * symlink was scanned for a string its own generated wrappers do not contain,
 * and it reported <b>"✓ every reference resolves"</b> without having checked
 * anything. {@code HomeFixpointLaw} is the post-condition of 22 graphs and was
 * blind wherever that happened.
 *
 * <h2>THE FIXTURE THIS DELIBERATELY DOES NOT USE, AND WHY</h2>
 *
 * <p>The obvious way to write this on macOS is {@code /var -> /private/var} or
 * a {@code Files.createTempDirectory} path, since every temp path there is
 * symlinked. <b>That fixture passes before AND after the fix.</b> Measured at
 * the epic tip: {@code /private/var/folders/x} literally CONTAINS the substring
 * {@code /var/folders/x}, so {@code indexOf} finds the reference by accident.
 * The 22 graphs are saved by that coincidence, not by correctness — and a test
 * built on it would be this epic's third vacuous assertion.
 *
 * <p>So the fixture below builds two spellings that are NOT substrings of one
 * another: a real directory, a sibling symlink at it, and the home addressed
 * through the symlink. That is the shape a symlinked {@code $HOME}, a symlinked
 * checkout or a {@code /Volumes/…} mount produces, and it is the shape the
 * defect actually reproduces in.
 *
 * <h2>Both spellings, which is why the fix is not "resolve once"</h2>
 *
 * <p>A clone re-anchors its generated files to the destination spelling it was
 * GIVEN. A home created at {@code <s>/link/home} therefore holds
 * {@code <s>/link/home/cache/…} in its wrappers, and a check that resolved the
 * root once and scanned only for {@code <s>/realdir/home} would be exactly as
 * blind, in the other direction. Both spellings are real; both are scanned, and
 * a finding is reported under the root the caller named.
 *
 * <p><b>The limit, asserted below rather than assumed away.</b> The scan finds
 * candidates BY the root string, so it can only use spellings it can derive. A
 * symlink cannot be inverted: addressed as {@code <s>/realdir/home} there is no
 * way to learn that {@code <s>/link/home} names the same directory. A home
 * created through one alias and verified through a DIFFERENT one is therefore
 * still partly unchecked. Closing that needs the home to record the spelling it
 * was created at, which HIS-10 does not add.
 */
public final class HomeVerifyPathSpellingTest {

    public static int run() throws Exception {
        return Tests.suite("HomeVerifyPathSpellingTest")

                .test("a missing reference is found through the symlinked spelling too", () -> {
                    Spellings s = Spellings.build("real-target");
                    // The wrapper names the RESOLVED spelling of a tree the home
                    // does not hold — what a home provisioned at its real path
                    // and later reached through a link looks like.
                    s.wrapper(s.viaReal.resolve("venvs/nope/bin/probe"));

                    List<String> viaReal = unresolved(s.viaReal);
                    List<String> viaLink = unresolved(s.viaLink);

                    assertTrue(!viaReal.isEmpty(),
                            "control: addressed by its real path the check finds it — if this "
                                    + "is empty the fixture is not exercising the scan at all");
                    assertEquals(viaReal.size(), viaLink.size(),
                            "the same home reached through a symlink must reach the same "
                                    + "verdict; real=" + viaReal + " link=" + viaLink);
                })

                .test("THE STATED LIMIT: an alias spelling the caller did not name is not recovered", () -> {
                    // Pinned rather than left to be rediscovered. The scan finds
                    // candidates BY the root string, so it can only use spellings
                    // it can derive: the one the caller passed, and that one
                    // resolved. A symlink cannot be inverted -- from
                    // <s>/realdir/home there is no way to learn that
                    // <s>/link/home names the same directory -- so a file holding
                    // the LINKED spelling is invisible when the home is addressed
                    // by its real path.
                    //
                    // Small in practice and deliberately not widened here: a
                    // clone re-anchors generated files to the spelling it was
                    // GIVEN, so a home created at <s>/link/home holds that
                    // spelling and is checked correctly whenever it is addressed
                    // that way, and the resolved spelling is now covered from
                    // either address. What is left is "created through one alias,
                    // verified through a different one", which needs the home to
                    // record its own creation spelling -- evidence this ticket
                    // does not add. Recorded in HIS-10's goal contribution as
                    // what was cut.
                    Spellings s = Spellings.build("linked-target");
                    s.wrapper(s.viaLink.resolve("venvs/nope/bin/probe"));

                    List<String> viaLink = unresolved(s.viaLink);
                    List<String> viaReal = unresolved(s.viaReal);

                    assertTrue(!viaLink.isEmpty(),
                            "the spelling the caller named is always scanned");
                    assertTrue(viaReal.isEmpty(),
                            "and an alias it could not derive is not; if this ever starts "
                                    + "finding it, the limit above has been closed and this "
                                    + "test should become the equality it used to assert. got "
                                    + viaReal);
                })

                .test("a reference that DOES resolve is reported by neither spelling", () -> {
                    // The other half of non-vacuity: a scan wired to "always
                    // report something" would satisfy both tests above.
                    Spellings s = Spellings.build("healthy");
                    Path target = s.viaReal.resolve("venvs/real/bin/probe");
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, "#!/usr/bin/env sh\nexit 0\n");
                    s.wrapper(target);

                    assertTrue(unresolved(s.viaReal).isEmpty(),
                            "a present tree is not an unresolved reference");
                    assertTrue(unresolved(s.viaLink).isEmpty(), "through either spelling");
                })

                .test("the recovered path is reported in the spelling the caller asked about", () -> {
                    // Callers relativize these against the root they passed in
                    // (ArtifactBuild joins a finding to a remedy that way), so a
                    // finding recovered under the other spelling must be
                    // rewritten before it is handed back or the join silently
                    // produces ../../.. nonsense.
                    Spellings s = Spellings.build("spelling-out");
                    s.wrapper(s.viaReal.resolve("venvs/nope/bin/probe"));

                    List<String> viaLink = unresolved(s.viaLink);
                    assertTrue(!viaLink.isEmpty(), "control");
                    // Findings are reported as "<home-relative entry> -> <path>";
                    // it is the right-hand side whose spelling is under test.
                    assertTrue(viaLink.get(0).contains(s.viaLink + "/venvs/nope/bin/probe"),
                            "reported under the root the caller named, not the one the bytes "
                                    + "happened to hold; got " + viaLink.get(0));
                })

                .runAll();
    }

    /**
     * {@code <scratch>/realdir/home} and {@code <scratch>/link/home}, where
     * {@code link -> realdir}. Two spellings of one home, neither a substring
     * of the other.
     */
    private record Spellings(Path viaReal, Path viaLink) {

        static Spellings build(String label) throws Exception {
            // Under a resolved temp root, so the ONLY difference between the two
            // spellings below is the link this fixture makes on purpose.
            Path scratch = Files.createTempDirectory("home-spelling-" + label + "-")
                    .toRealPath();
            Path realDir = Files.createDirectories(scratch.resolve("realdir"));
            Path home = realDir.resolve("home");
            // A minimal home: installed/ + skills/ is what LaunchEnv
            // .looksLikeStoreRoot recognises, and nothing here needs more.
            Files.createDirectories(home.resolve("installed"));
            Files.createDirectories(home.resolve("skills"));
            Files.createDirectories(home.resolve("bin/cli"));
            Files.createSymbolicLink(scratch.resolve("link"), realDir);
            return new Spellings(home, scratch.resolve("link/home"));
        }

        /** A generated wrapper that execs {@code target}, as a real one does. */
        void wrapper(Path target) throws Exception {
            Path probe = viaReal.resolve("bin/cli/probe");
            Files.writeString(probe, "#!/bin/sh\nexec \"" + target + "\" \"$@\"\n");
            probe.toFile().setExecutable(true, false);
        }
    }

    private static List<String> unresolved(Path home) throws Exception {
        return HomeCloner.verify(home, false).unresolvedReferences();
    }
}
