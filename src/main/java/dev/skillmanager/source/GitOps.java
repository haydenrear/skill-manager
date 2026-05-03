package dev.skillmanager.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Thin shell-out wrapper around {@code git} for the operations the
 * skill-manager source-tracking flow needs. Uses ProcessBuilder rather
 * than JGit because (a) we already shell out for {@code git diff
 * --no-index} elsewhere, (b) JGit has quirks around fetching from a
 * filesystem path with no upstream branch, and (c) the porcelain
 * commands here are stable across git versions.
 *
 * <p>Every method takes the working directory of the git repo as its
 * first argument. Callers should check {@link #isAvailable()} once at
 * startup and {@link #isGitRepo(Path)} per-skill before any other call.
 */
public final class GitOps {

    private GitOps() {}

    public static boolean isAvailable() {
        return run(null, List.of("git", "--version")).exit == 0;
    }

    public static boolean isGitRepo(Path dir) {
        return Files.isDirectory(dir.resolve(".git"));
    }

    /** Current HEAD commit, or null if the repo is empty / read fails. */
    public static String headHash(Path dir) {
        Result r = run(dir, List.of("git", "rev-parse", "HEAD"));
        return r.exit == 0 ? r.stdout.trim() : null;
    }

    /** Origin URL, or null if no origin remote is configured. */
    public static String originUrl(Path dir) {
        Result r = run(dir, List.of("git", "remote", "get-url", "origin"));
        return r.exit == 0 ? r.stdout.trim() : null;
    }

    /**
     * Detect the install-time ref to track for {@code sync --git-latest}.
     *
     * <ul>
     *   <li>On a named branch (the common case for an unspecified-ref
     *       install or a {@code --branch} clone): returns the branch
     *       name (e.g. {@code "main"}).</li>
     *   <li>Detached HEAD on a tagged commit: returns the tag name
     *       (e.g. {@code "v1.0.0"}). Sync against a tag is a no-op,
     *       which preserves version pinning.</li>
     *   <li>Detached HEAD on a sha with no matching tag: returns null
     *       — the caller can decide whether to fall back to
     *       {@code "HEAD"} (the remote's default branch) or refuse.</li>
     * </ul>
     */
    public static String detectInstallRef(Path dir) {
        // Branch: succeeds when on a real branch, fails on detached HEAD.
        Result branch = run(dir, List.of("git", "symbolic-ref", "--short", "--quiet", "HEAD"));
        if (branch.exit == 0) {
            String b = branch.stdout.trim();
            if (!b.isBlank()) return b;
        }
        // Tag: only an exact-match tag points at HEAD (annotated or lightweight).
        Result tag = run(dir, List.of("git", "describe", "--tags", "--exact-match", "HEAD"));
        if (tag.exit == 0) {
            String t = tag.stdout.trim();
            if (!t.isBlank()) return t;
        }
        return null;
    }

    /**
     * Set origin to {@code url}. Adds the remote if missing, updates it
     * if present. Idempotent.
     */
    public static boolean setOrigin(Path dir, String url) {
        if (originUrl(dir) != null) {
            return run(dir, List.of("git", "remote", "set-url", "origin", url)).exit == 0;
        }
        return run(dir, List.of("git", "remote", "add", "origin", url)).exit == 0;
    }

    /**
     * Commit any uncommitted (staged + unstaged + untracked) changes
     * onto HEAD as a single commit with {@code message}. Returns the
     * new HEAD on success, null on failure or if there was nothing to
     * commit. Configures user.name / user.email for this one invocation
     * so empty environments (CI runners with no global git identity)
     * still get a valid commit.
     */
    public static String commitWorkingChanges(Path dir, String message) {
        if (porcelainStatus(dir).isBlank()) return null;
        if (run(dir, List.of("git", "add", "-A")).exit != 0) return null;
        Result commit = run(dir, List.of("git",
                "-c", "user.email=skill-manager@localhost",
                "-c", "user.name=skill-manager",
                "commit", "--quiet", "-m", message));
        return commit.exit == 0 ? headHash(dir) : null;
    }

    /**
     * {@code git status --porcelain} output. Empty string means the
     * working tree is clean (no staged, unstaged, or untracked changes).
     */
    public static String porcelainStatus(Path dir) {
        Result r = run(dir, List.of("git", "status", "--porcelain"));
        return r.exit == 0 ? r.stdout : "";
    }

    /**
     * {@code true} when the working tree has staged / unstaged / untracked
     * changes <i>or</i> HEAD has advanced past {@code baselineHash}.
     * Either signal indicates user-side edits a sync would clobber.
     */
    public static boolean isDirty(Path dir, String baselineHash) {
        if (!porcelainStatus(dir).isBlank()) return true;
        if (baselineHash == null || baselineHash.isBlank()) return false;
        String head = headHash(dir);
        return head != null && !head.equals(baselineHash);
    }

    /**
     * Fetch the {@code HEAD} of {@code remote} (a URL, a path, or a
     * configured remote name) into {@code FETCH_HEAD}. Returns the
     * fetched commit hash, or null on failure.
     */
    public static String fetchHead(Path dir, String remote) {
        return fetchRef(dir, remote, "HEAD");
    }

    /**
     * Fetch a specific {@code ref} (HEAD, a branch, a tag, or a sha)
     * from {@code remote} into {@code FETCH_HEAD}. Returns the
     * resolved commit hash, or null on failure.
     *
     * <p>Modern git supports fetching by sha when the server has
     * {@code uploadpack.allowAnySHA1InWant=true} (github does); for
     * other servers a branch/tag name is the safer ref.
     */
    public static String fetchRef(Path dir, String remote, String ref) {
        Result fetch = run(dir, List.of("git", "fetch", "--no-tags", "--quiet", remote, ref));
        if (fetch.exit != 0) {
            // Fall back to a full fetch then resolve — useful when the
            // remote rejects fetch-by-sha and the sha is reachable from
            // the default branch we'd otherwise get on a plain fetch.
            Result fullFetch = run(dir, List.of("git", "fetch", "--no-tags", "--quiet", remote));
            if (fullFetch.exit != 0) return null;
            Result rev = run(dir, List.of("git", "rev-parse", ref));
            return rev.exit == 0 ? rev.stdout.trim() : null;
        }
        Result rev = run(dir, List.of("git", "rev-parse", "FETCH_HEAD"));
        return rev.exit == 0 ? rev.stdout.trim() : null;
    }

    /**
     * Attempt a {@code git merge --no-edit FETCH_HEAD}. On success,
     * {@link MergeOutcome#ok} is true. On a conflict, {@code ok} is
     * false and {@link MergeOutcome#conflictedFiles} lists the files
     * with merge markers; the working tree is left in the conflict
     * state for the user to resolve. On any other failure (no upstream,
     * detached HEAD, etc.) we abort with {@code git merge --abort}.
     */
    public static MergeOutcome mergeFetchHead(Path dir) {
        Result merge = run(dir, List.of("git", "merge", "--no-edit", "FETCH_HEAD"));
        if (merge.exit == 0) {
            return new MergeOutcome(true, List.of(), merge.stdout + merge.stderr);
        }
        Result conflicts = run(dir, List.of("git", "diff", "--name-only", "--diff-filter=U"));
        List<String> conflicted = conflicts.exit == 0 && !conflicts.stdout.isBlank()
                ? List.of(conflicts.stdout.trim().split("\\r?\\n"))
                : List.of();
        if (conflicted.isEmpty()) {
            // Non-conflict failure (e.g. dirty working tree, no upstream).
            // Leave nothing in a half-merged state.
            run(dir, List.of("git", "merge", "--abort"));
        }
        return new MergeOutcome(false, conflicted, merge.stdout + merge.stderr);
    }

    /** Result of {@link #mergeFetchHead}. */
    public record MergeOutcome(boolean ok, List<String> conflictedFiles, String log) {}

    private record Result(int exit, String stdout, String stderr) {}

    private static Result run(Path workdir, List<String> argv) {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        if (workdir != null) pb.directory(workdir.toFile());
        try {
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            return new Result(p.waitFor(), out.toString(), "");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(-1, "", e.getMessage() == null ? "" : e.getMessage());
        }
    }
}
