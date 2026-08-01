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
        return new String[] { releaseLine(), "build:  " + build(), "cli:    " + launcher() };
    }

    /**
     * The first line: the release number, plus a build discriminator when this
     * build is <em>not</em> the released artifact.
     *
     * <h2>Why the number alone was still not enough after #61</h2>
     *
     * <p>#61 added the {@code build:} line and it works — but the first line is
     * the one people read, quote in an issue, and paste into a report, and it
     * stayed identical between a Homebrew 0.19.2 (which has no {@code exec}
     * subcommand) and every working-tree build also called 0.19.2. During the
     * epic #2 pilot that ambiguity was not hypothetical: {@code home verify}
     * transcripts were attributed to the merged build and the behaviour they
     * record does not reproduce on it. Which binary answered cannot be
     * recovered afterwards, because the line both of them print is the same
     * line.
     *
     * <h2>What is appended, and when</h2>
     *
     * <p>{@code +g<short sha>}, SemVer build metadata: {@code 0.19.2+gabc123}
     * is a valid SemVer with the same precedence as {@code 0.19.2}, and any
     * reader scraping {@code \d+\.\d+\.\d+} still finds the release. It is
     * appended only when this program is running out of its own git checkout
     * AND {@code HEAD} is not the commit tagged for this release:
     *
     * <ul>
     *   <li>an installed jar with no checkout around it — plain {@code RELEASE};
     *       nothing is known to append, and a fabricated discriminator would be
     *       worse than none (the same rule {@link #build()} follows);</li>
     *   <li>a checkout standing exactly on {@code v<release>} — plain
     *       {@code RELEASE}, because it IS that release, and marking it
     *       otherwise would make every tagged build look unreleased;</li>
     *   <li>anything else — {@code RELEASE+g<sha>}, which is exactly the case
     *       that used to be indistinguishable from the first.</li>
     * </ul>
     *
     * <p>A dirty working tree is deliberately NOT flagged. It would cost an
     * index read and a status walk on every {@code --version}, and it does not
     * address the confusion this exists to remove, which is two different
     * <em>commits</em> reporting one number. Issue #133.
     */
    public static String releaseLine() {
        return releaseLine(ownGitDir());
    }

    /** {@link #releaseLine()} against a given git directory; the seam tests use. */
    static String releaseLine(Path gitDir) {
        if (gitDir == null) return RELEASE;               // no checkout: nothing to add
        String head = resolveRef(gitDir, null);
        if (head == null) return RELEASE;
        String tagged = resolveRef(gitDir, "refs/tags/" + releaseTag());
        if (head.equals(tagged)) return RELEASE;          // this IS the release
        return RELEASE + "+g" + shortSha(head);
    }

    /** The tag this release would be cut as: {@code v0.19.2} from "skill-manager 0.19.2". */
    static String releaseTag() {
        String trimmed = RELEASE.trim();
        int space = trimmed.lastIndexOf(' ');
        return "v" + (space < 0 ? trimmed : trimmed.substring(space + 1));
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
     * The {@code .git} directory of this program's own checkout, or null when
     * it is not running out of one. Same guard as {@link #fromGitCheckout}:
     * the tree must carry {@code SkillManager.java}, so an install that merely
     * sits under some unrelated repository is not mistaken for a source build.
     */
    private static Path ownGitDir() {
        for (Path root : candidateRoots()) {
            if (!Files.isRegularFile(root.resolve("SkillManager.java"))) continue;
            Path resolved = gitDir(root);
            if (resolved != null) return resolved;
        }
        return null;
    }

    /**
     * Resolve {@code ref} — or {@code HEAD} when it is null — to a full sha,
     * reading {@code .git} directly.
     *
     * <p>Loose ref first, then {@code packed-refs}, whose peeled {@code ^{}}
     * line wins for an annotated tag because that line carries the commit and
     * the other carries the tag object.
     *
     * <p><b>Both directories are searched.</b> In a linked worktree, {@code
     * HEAD} lives in the per-worktree git dir while {@code refs/} and {@code
     * packed-refs} live in the common one, so looking only in the first
     * resolves nothing — which is why the {@code build:} line #61 added
     * reported an artifact fingerprint instead of a commit for every build run
     * from a worktree, the exact place where knowing the commit matters most.
     */
    private static String resolveRef(Path gitDir, String ref) {
        try {
            if (ref == null) {
                String head = Files.readString(gitDir.resolve("HEAD")).trim();
                if (!head.startsWith("ref:")) return head;
                ref = head.substring(4).trim();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        for (Path dir : new Path[] { gitDir, commonDir(gitDir) }) {
            if (dir == null) continue;
            String sha = lookupRef(dir, ref);
            if (sha != null) return sha;
        }
        return null;
    }

    private static String lookupRef(Path gitDir, String ref) {
        try {
            Path loose = gitDir.resolve(ref);
            if (Files.isRegularFile(loose)) return Files.readString(loose).trim();
            Path packed = gitDir.resolve("packed-refs");
            if (!Files.isRegularFile(packed)) return null;
            // packed-refs writes an annotated tag as two lines: the tag object,
            // then `^<commit>`. The second is the one a release comparison
            // wants — an annotated v0.19.2 whose tag-object sha was compared
            // against HEAD would never match, and every tagged build would
            // report itself as unreleased.
            List<String> lines = Files.readAllLines(packed);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.endsWith(" " + ref)) continue;
                if (i + 1 < lines.size() && lines.get(i + 1).startsWith("^")) {
                    return lines.get(i + 1).substring(1).trim();
                }
                return line.substring(0, line.indexOf(' '));
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The shared git dir behind a linked worktree's, or null when there is none. */
    private static Path commonDir(Path gitDir) {
        try {
            Path marker = gitDir.resolve("commondir");
            if (!Files.isRegularFile(marker)) return null;
            Path common = gitDir.resolve(Files.readString(marker).trim()).normalize();
            return Files.isDirectory(common) && !common.equals(gitDir) ? common : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** {@code root}'s git directory, following a {@code .git} file. */
    private static Path gitDir(Path root) {
        try {
            Path gitDir = root.resolve(".git");
            if (Files.isRegularFile(gitDir)) {
                // A worktree or submodule: `.git` is a file naming the real dir.
                String line = Files.readString(gitDir).trim();
                if (!line.startsWith("gitdir:")) return null;
                gitDir = root.resolve(line.substring("gitdir:".length()).trim()).normalize();
            }
            return Files.isDirectory(gitDir) ? gitDir : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
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
        Path gitDir = gitDir(root);
        if (gitDir == null) return null;
        String head;
        try {
            head = Files.readString(gitDir.resolve("HEAD")).trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (!head.startsWith("ref:")) return shortSha(head) + " (detached)";
        String ref = head.substring(4).trim();
        String sha = resolveRef(gitDir, ref);
        return sha == null ? null : shortSha(sha) + " (" + ref + ")";
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
