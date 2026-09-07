package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * A clone re-anchors the source home by every spelling that reaches it, not
 * only by the one the caller typed.
 *
 * <h2>The defect, #330</h2>
 *
 * <p>{@code HomeCloner.reanchorProvisioned} rewrites generated files by byte
 * substitution, and the needle was {@code srcRoot.toString()} — <b>one</b>
 * spelling. A home provisioned while it was addressed one way and cloned while
 * it is addressed another holds shims the needle cannot find, so they are
 * copied through unchanged and still exec the source home's files. Verification
 * then resolves them properly and reports {@code FOREIGN_PATH_IN_SHIM}, so the
 * clone fails — correctly, about a leak the clone itself was supposed to have
 * removed.
 *
 * <p>Measured on macOS, where {@code /tmp} is a symlink to {@code /private/tmp}:
 * a home whose shims said {@code /tmp/…} cloned with {@code srcRoot} spelled
 * {@code /private/tmp/…}, and {@code skt ticket new} rolled back with "home
 * bootstrap failed" two layers away from the spelling that caused it.
 *
 * <h2>Why this is not the limit {@code HomeVerifyPathSpellingTest} pins</h2>
 *
 * <p>That test records that a spelling the caller did not name cannot be
 * DERIVED from the one it did: a symlink cannot be inverted, so from
 * {@code <s>/realdir/home} there is no way to compute {@code <s>/link/home}.
 * True, and it is why the fix here does not try. Both spellings are already
 * present in the copy's own bytes — the clone reads the shim, resolves the
 * absolute path it names, and asks whether that lands inside the source home.
 * Resolution answers what string derivation cannot, and it is the same question
 * {@link HomeCloner#foreignHomeReachedBy} already asks one step later, which is
 * exactly why the two used to disagree: the rewriter compared spellings and the
 * verifier compared files.
 *
 * <p>The fixture is a real directory, a sibling symlink at it, and the home
 * provisioned through the symlink — deliberately NOT {@code /var} vs
 * {@code /private/var}, because those are substrings of one another and a
 * byte-needle test built on them passes without the fix.
 */
public final class CloneReanchorsEveryAliasTest {

    public static int run() throws Exception {
        return Tests.suite("CloneReanchorsEveryAliasTest")

                .test("a shim holding an alias spelling of the source is re-anchored, not leaked", () -> {
                    Aliased a = Aliased.build();
                    // Provisioned while the home was addressed through the link:
                    // that is the spelling a generated wrapper records.
                    a.shim(a.viaLink.resolve("skills/alpha/scripts/run.py"));
                    Path dest = Files.createTempDirectory("clone-alias-dest-").toRealPath()
                            .resolve("home");

                    // Cloned while it is addressed by its real path.
                    HomeCloner.Report report = HomeCloner.cloneHome(a.viaReal, dest);

                    assertTrue(report.clean(),
                            "the alias spelling must be re-anchored like any other; leaks: "
                                    + report.leaks());
                    String shim = Files.readString(dest.resolve("bin/cli/probe"));
                    assertContains(shim, dest.toString(),
                            "the copy's shim runs the COPY's file");
                    assertTrue(!shim.contains(a.viaLink.toString())
                                    && !shim.contains(a.viaReal.toString()),
                            "and names the source home by neither spelling: " + shim);
                })

                .test("the spelling the caller DID name is still re-anchored", () -> {
                    // Non-vacuity in the other direction: a fix that only ever
                    // consulted the resolved form would break the ordinary case.
                    Aliased a = Aliased.build();
                    a.shim(a.viaReal.resolve("skills/alpha/scripts/run.py"));
                    Path dest = Files.createTempDirectory("clone-alias-plain-").toRealPath()
                            .resolve("home");

                    HomeCloner.Report report = HomeCloner.cloneHome(a.viaReal, dest);

                    assertTrue(report.clean(), "leaks: " + report.leaks());
                    assertContains(Files.readString(dest.resolve("bin/cli/probe")),
                            dest.toString(), "re-anchored to the copy");
                })

                .test("a path that merely LOOKS like the source home is left alone", () -> {
                    // The rule is "resolves inside the source home", not "starts
                    // with a string that resembles it". A sibling directory whose
                    // name extends the home's is the classic prefix bug.
                    Aliased a = Aliased.build();
                    Path decoy = a.scratch.resolve("realdir/home-notes");
                    Files.createDirectories(decoy);
                    Files.writeString(decoy.resolve("run.py"), "print(1)\n");
                    a.shim(decoy.resolve("run.py"));
                    Path dest = Files.createTempDirectory("clone-alias-decoy-").toRealPath()
                            .resolve("home");

                    HomeCloner.cloneHome(a.viaReal, dest);

                    assertContains(Files.readString(dest.resolve("bin/cli/probe")),
                            decoy.resolve("run.py").toString(),
                            "a neighbour of the home is not part of it and is not rewritten");
                })

                .runAll();
    }

    /**
     * {@code <scratch>/realdir/home} and {@code <scratch>/link/home}, where
     * {@code link -> realdir}: two spellings of one home, neither a substring
     * of the other. The home holds the one skill its shim runs.
     */
    private record Aliased(Path scratch, Path viaReal, Path viaLink) {

        static Aliased build() throws Exception {
            Path scratch = Files.createTempDirectory("clone-alias-").toRealPath();
            Path realDir = Files.createDirectories(scratch.resolve("realdir"));
            Path home = realDir.resolve("home");
            new SkillStore(home).init();
            Files.createDirectories(home.resolve("skills/alpha/scripts"));
            Files.writeString(home.resolve("skills/alpha/SKILL.md"),
                    "---\nname: alpha\ndescription: fixture\n---\nbody\n");
            Files.writeString(home.resolve("skills/alpha/scripts/run.py"), "print(1)\n");
            Files.writeString(home.resolve("units.lock.toml"), "version = 1\n");
            Files.createDirectories(home.resolve("bin/cli"));
            Files.createSymbolicLink(scratch.resolve("link"), realDir);
            return new Aliased(scratch, home, scratch.resolve("link/home"));
        }

        /** A generated wrapper that execs {@code target}, as a real one does. */
        void shim(Path target) throws Exception {
            Path probe = viaReal.resolve("bin/cli/probe");
            Files.writeString(probe, "#!/bin/sh\nexec python3 \"" + target + "\" \"$@\"\n");
            dev.skillmanager.shared.util.Fs.makeExecutable(probe);
        }
    }
}
