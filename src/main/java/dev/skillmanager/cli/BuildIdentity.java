package dev.skillmanager.cli;

import dev.skillmanager.launch.RunningCli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * What {@code --version} prints, and why it is more than a number.
 *
 * <h2>The number alone cannot answer the question anyone is actually asking</h2>
 *
 * <p>Measured while fixing issue #61: the Homebrew build and this repository's
 * working build both answered {@code skill-manager 0.19.2}. They are not the
 * same program — one has an {@code exec} subcommand and one does not — and a
 * home whose launcher reached the wrong one failed with
 * {@code Unmatched arguments: 'exec'} while every diagnostic anyone could run
 * agreed on the version. release-please owns the release number and will keep
 * bumping it; the number is simply not a build identity, and no amount of
 * bumping makes it one.
 *
 * <p>So {@code --version} now prints three lines: the release number
 * (unchanged, still the first line, still what a parser looking for a semver
 * finds), the <b>build</b> that is answering, and the <b>path of the launcher
 * that started it</b>. The last one is what makes a wrong-CLI report
 * self-diagnosing: it is the same value {@code home shims} pins into a home, so
 * "which CLI is this home running" and "which CLI am I talking to" can be
 * compared directly instead of inferred.
 *
 * <h2>Where the build identity comes from</h2>
 *
 * <ol>
 *   <li><b>The git checkout it is running from</b>, when there is one —
 *       {@code SKILL_MANAGER_INSTALL_DIR} points at the repo root for a source
 *       run, so {@code .git/HEAD} is readable and the commit is exact. This is
 *       the case that matters for telling a development build apart from an
 *       installed release.</li>
 *   <li><b>The jar's own content digest and timestamp</b>, for an installed
 *       build with no git around it. Not a commit, but it is stable per
 *       artifact and different artifacts differ, which is the whole
 *       requirement.</li>
 *   <li>{@code unknown}, said plainly, when neither is available. A fabricated
 *       identity would be worse than none.</li>
 * </ol>
 *
 * <p>Nothing here requires a build step, which is deliberate: the CLI is run
 * from source via jbang as often as it is run from a jar, and an identity that
 * only existed in CI-built artifacts would be missing from exactly the builds
 * that get confused with each other.
 */
public final class BuildIdentity implements picocli.CommandLine.IVersionProvider {

    /** Public and no-arg because picocli constructs the version provider itself. */
    public BuildIdentity() {}

    /**
     * The released version, still the FIRST line of {@code --version} so
     * anything that scraped it for a semver keeps working. It lives on
     * {@link SkillManagerCli} because that is the file release-please is
     * configured to update.
     */
    public static final String RELEASE = SkillManagerCli.RELEASE;

    @Override
    public String[] getVersion() { return lines(); }

    public static String[] lines() {
        return new String[] { RELEASE, "build:  " + build(), "cli:    " + launcher() };
    }

    /** A commit, or an artifact digest, or {@code unknown} — never a guess. */
    public static String build() {
        String fromGit = fromGitCheckout();
        if (fromGit != null) return fromGit;
        String fromJar = fromJarArtifact();
        if (fromJar != null) return fromJar;
        return "unknown (no git checkout and no jar to fingerprint)";
    }

    /**
     * The launcher this process was started by, or the reason it is unknown.
     *
     * <p>Never throws: {@code --version} that fails is worse than
     * {@code --version} that says it does not know.
     */
    public static String launcher() {
        try {
            return RunningCli.locate().toString();
        } catch (RunningCli.UnknownLocationException e) {
            return "unresolved (" + firstLine(e.getMessage()) + ")";
        }
    }

    // --------------------------------------------------------------- sources

    private static String fromGitCheckout() {
        for (Path root : candidateRoots()) {
            // The checkout must be THIS program's checkout. Without that guard a
            // build installed under some unrelated git tree — /opt/homebrew is
            // itself a git repository — would confidently report that tree's
            // commit, which is worse than reporting nothing.
            if (!Files.isRegularFile(root.resolve("SkillManager.java"))) continue;
            String head = readHead(root);
            if (head != null) return head;
        }
        return null;
    }

    /**
     * Directories that might be the checkout this build came from: the launcher
     * exports its install directory, and for a source run that IS the repo root.
     * The jar's location is probed too, for a build run out of a working tree.
     */
    private static List<Path> candidateRoots() {
        List<Path> roots = new ArrayList<>();
        String installDir = System.getenv(RunningCli.INSTALL_DIR);
        if (installDir != null && !installDir.isBlank()) {
            Path dir = Path.of(installDir.trim()).toAbsolutePath().normalize();
            roots.add(dir);
            Path parent = dir.getParent();
            if (parent != null) roots.add(parent);
        }
        Path code = RunningCli.codeSource();
        while (code != null && roots.size() < 8) {
            roots.add(code);
            code = code.getParent();
        }
        return roots;
    }

    /**
     * The commit {@code root} is on, read from {@code .git} directly rather than
     * by shelling out to git: {@code --version} must not depend on git being
     * installed, and must not spawn a process to answer.
     */
    private static String readHead(Path root) {
        try {
            Path gitDir = root.resolve(".git");
            if (Files.isRegularFile(gitDir)) {
                // A worktree or submodule: `.git` is a file naming the real dir.
                String line = Files.readString(gitDir).trim();
                if (!line.startsWith("gitdir:")) return null;
                gitDir = root.resolve(line.substring("gitdir:".length()).trim()).normalize();
            }
            if (!Files.isDirectory(gitDir)) return null;
            String head = Files.readString(gitDir.resolve("HEAD")).trim();
            if (head.startsWith("ref:")) {
                String ref = head.substring(4).trim();
                Path refFile = gitDir.resolve(ref);
                if (Files.isRegularFile(refFile)) {
                    return shortSha(Files.readString(refFile).trim()) + " (" + ref + ")";
                }
                // Packed refs: the loose file is absent after a `git gc`.
                Path packed = gitDir.resolve("packed-refs");
                if (Files.isRegularFile(packed)) {
                    for (String l : Files.readAllLines(packed)) {
                        if (l.endsWith(" " + ref)) {
                            return shortSha(l.substring(0, l.indexOf(' '))) + " (" + ref + ")";
                        }
                    }
                }
                return null;
            }
            return shortSha(head) + " (detached)";
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String shortSha(String sha) {
        String trimmed = sha.trim();
        return trimmed.length() >= 12 ? trimmed.substring(0, 12) : trimmed;
    }

    /**
     * A fingerprint of the jar this class was loaded from: the first bytes of
     * its SHA-256 plus its modification time. Two different artifacts differ;
     * the same artifact reports the same thing on every machine.
     */
    private static String fromJarArtifact() {
        Path jar = RunningCli.codeSource();
        if (jar == null || !Files.isRegularFile(jar)) return null;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(Files.readAllBytes(jar));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) hex.append(String.format("%02x", digest[i]));
            String stamp = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
                    .format(Instant.ofEpochMilli(Files.getLastModifiedTime(jar).toMillis()));
            return "artifact " + hex + " built " + stamp + " (" + jar.getFileName() + ")";
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstLine(String s) {
        if (s == null) return "no detail";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
