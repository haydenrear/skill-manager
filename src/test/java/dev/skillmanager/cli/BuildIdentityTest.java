package dev.skillmanager.cli;

import dev.skillmanager._lib.test.Tests;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The first line of {@code --version} has to distinguish two builds that
 * carry the same release number.
 *
 * <h2>Why the number and the second line were not enough</h2>
 *
 * <p>A Homebrew 0.19.2 with no {@code exec} subcommand and a working-tree
 * 0.19.2 that has one both answered {@code skill-manager 0.19.2}. #61 added a
 * {@code build:} line that does tell them apart, and it works — but the first
 * line is the one people read, quote in an issue and paste into a report, and
 * it stayed ambiguous. Issue #133 exists partly because of that: transcripts
 * attributed to the merged build record behaviour that does not reproduce on
 * it, and which binary answered cannot be recovered, because the line both
 * would print is the same line.
 *
 * <p>The discriminator is SemVer build metadata, so it is additive: anything
 * scraping {@code \d+\.\d+\.\d+} still finds the release. The rule it encodes
 * is "say more only when there is more to say" — never a fabricated identity,
 * and never a mark on a build that really is the release.
 */
public final class BuildIdentityTest {

    private static final String SHA = "0784412e579d1c2b3a4d5e6f708192a3b4c5d6e7";

    public static int run() throws Exception {
        return Tests.suite("BuildIdentityTest")

                .test("a checkout that is not on the release tag carries its commit", () -> {
                    Path git = checkout("untagged");
                    branch(git, "refs/heads/fix/133-home-verify", SHA);

                    assertEquals(BuildIdentity.RELEASE + "+g" + SHA.substring(0, 12),
                            BuildIdentity.releaseLine(git),
                            "the working build names the commit that is answering");
                })

                .test("a checkout standing exactly on v<release> reports the bare release", () -> {
                    Path git = checkout("tagged");
                    branch(git, "refs/heads/main", SHA);
                    Files.createDirectories(git.resolve("refs/tags"));
                    // DERIVED, never literal. The assertion below compares against
                    // BuildIdentity.RELEASE, which reads version.txt. A literal tag
                    // here stops describing a released build the moment
                    // release-please bumps the version, and the suite then fails for
                    // a reason that has nothing to do with the code under test.
                    // That is exactly what `chore(main): release 0.20.0` did.
                    Files.writeString(git.resolve("refs/tags/" + BuildIdentity.releaseTag()), SHA + "\n");

                    assertEquals(BuildIdentity.RELEASE, BuildIdentity.releaseLine(git),
                            "a tagged build IS the release and must not look unreleased");
                })

                .test("an annotated tag is compared by its peeled commit, not its tag object", () -> {
                    // The failure this catches is silent and total: an
                    // annotated tag's own sha never equals HEAD, so every
                    // released build would report itself as a working build.
                    Path git = checkout("annotated");
                    branch(git, "refs/heads/main", SHA);
                    // Tag name derived from BuildIdentity.RELEASE for the same
                    // reason as the case above.
                    Files.writeString(git.resolve("packed-refs"), """
                            # pack-refs with: peeled fully-peeled sorted
                            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa refs/tags/%s
                            ^%s
                            """.formatted(BuildIdentity.releaseTag(), SHA));

                    assertEquals(BuildIdentity.RELEASE, BuildIdentity.releaseLine(git),
                            "the peeled line is the commit the tag names");
                })

                .test("a linked worktree resolves its branch through the common git dir", () -> {
                    // A worktree's HEAD is in its own git dir and its refs are
                    // in the shared one. Reading only the first resolved
                    // nothing, which is how the #61 build: line fell back to a
                    // jar fingerprint for exactly the builds under review.
                    Path common = checkout("wt-common");
                    branch(common, "refs/heads/fix/133", SHA);
                    Path linked = Files.createDirectories(
                            common.resolve("worktrees/sm-wt-133"));
                    Files.writeString(linked.resolve("HEAD"), "ref: refs/heads/fix/133\n");
                    Files.writeString(linked.resolve("commondir"), "../..\n");

                    assertEquals(BuildIdentity.RELEASE + "+g" + SHA.substring(0, 12),
                            BuildIdentity.releaseLine(linked),
                            "the worktree finds its commit in the shared refs");
                })

                .test("no checkout means no discriminator rather than a guess", () -> {
                    assertEquals(BuildIdentity.RELEASE, BuildIdentity.releaseLine(null),
                            "an installed artifact reports the release it claims to be");

                    Path empty = Files.createTempDirectory("build-id-empty-");
                    assertEquals(BuildIdentity.RELEASE, BuildIdentity.releaseLine(empty),
                            "and an unreadable git dir invents nothing");
                })

                .test("the release is still the first thing on the first line", () -> {
                    Path git = checkout("shape");
                    branch(git, "refs/heads/main", SHA);
                    String line = BuildIdentity.releaseLine(git);

                    assertTrue(line.startsWith(BuildIdentity.RELEASE),
                            "additive, so anything that scraped the semver keeps working: " + line);
                    assertTrue(line.indexOf('+') > 0 && line.indexOf('+') > line.indexOf("0.19.2"),
                            "and the discriminator is SemVer build metadata: " + line);
                })

                .runAll();
    }

    /** A bare {@code .git}-shaped directory; {@code releaseLine} reads it directly. */
    private static Path checkout(String label) throws Exception {
        return Files.createDirectories(
                Files.createTempDirectory("build-id-" + label + "-").resolve(".git"));
    }

    private static void branch(Path gitDir, String ref, String sha) throws Exception {
        Files.writeString(gitDir.resolve("HEAD"), "ref: " + ref + "\n");
        Path loose = gitDir.resolve(ref);
        Files.createDirectories(loose.getParent());
        Files.writeString(loose, sha + "\n");
    }
}
