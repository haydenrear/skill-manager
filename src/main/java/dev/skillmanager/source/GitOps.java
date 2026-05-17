package dev.skillmanager.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Shell-out wrapper around {@code git} for the source-tracking flow.
 * ProcessBuilder rather than JGit because we already shell out for
 * {@code git diff --no-index} elsewhere and JGit has quirks around
 * filesystem-path remotes and stash semantics.
 */
public final class GitOps {

    private GitOps() {}

    public static boolean isAvailable() {
        return run(null, List.of("git", "--version")).exit == 0;
    }

    public static boolean isGitRepo(Path dir) {
        return Files.isDirectory(dir.resolve(".git"));
    }

    public static String headHash(Path dir) {
        Result r = run(dir, List.of("git", "rev-parse", "HEAD"));
        return r.exit == 0 ? r.stdout.trim() : null;
    }

    public static String originUrl(Path dir) {
        Result r = run(dir, List.of("git", "remote", "get-url", "origin"));
        return r.exit == 0 ? r.stdout.trim() : null;
    }

    /**
     * Branch name (named branch), tag name (detached HEAD on a tag), or null
     * (detached HEAD on a sha). Drives {@code sync --git-latest}.
     */
    public static String detectInstallRef(Path dir) {
        Result branch = run(dir, List.of("git", "symbolic-ref", "--short", "--quiet", "HEAD"));
        if (branch.exit == 0 && !branch.stdout.trim().isBlank()) return branch.stdout.trim();
        Result tag = run(dir, List.of("git", "describe", "--tags", "--exact-match", "HEAD"));
        if (tag.exit == 0 && !tag.stdout.trim().isBlank()) return tag.stdout.trim();
        return null;
    }

    public static boolean setOrigin(Path dir, String url) {
        if (originUrl(dir) != null) {
            return run(dir, List.of("git", "remote", "set-url", "origin", url)).exit == 0;
        }
        return run(dir, List.of("git", "remote", "add", "origin", url)).exit == 0;
    }

    public static String porcelainStatus(Path dir) {
        Result r = run(dir, List.of("git", "status", "--porcelain"));
        return r.exit == 0 ? r.stdout : "";
    }

    public static boolean hasWorktreeChanges(Path dir) {
        return !porcelainStatus(dir).isBlank();
    }

    public static boolean isDirty(Path dir, String baselineHash) {
        if (hasWorktreeChanges(dir)) return true;
        if (baselineHash == null || baselineHash.isBlank()) return false;
        String head = headHash(dir);
        return head != null && !head.equals(baselineHash);
    }

    public static boolean isAncestor(Path dir, String ancestor, String descendant) {
        if (ancestor == null || ancestor.isBlank() || descendant == null || descendant.isBlank()) {
            return false;
        }
        return run(dir, List.of("git", "merge-base", "--is-ancestor", ancestor, descendant)).exit == 0;
    }

    /** Files left in unmerged ({@code UU}) state after a failed merge or stash pop. */
    public static List<String> unmergedFiles(Path dir) {
        Result r = run(dir, List.of("git", "diff", "--name-only", "--diff-filter=U"));
        if (r.exit != 0 || r.stdout.isBlank()) return List.of();
        return List.of(r.stdout.trim().split("\\r?\\n"));
    }

    /**
     * {@code git stash push --include-untracked}. Returns true if anything was
     * stashed (working tree had changes), false if the tree was clean (nothing
     * to do) or the stash failed.
     */
    public static boolean stashAll(Path dir, String message) {
        if (porcelainStatus(dir).isBlank()) return false;
        Result r = run(dir, List.of("git",
                "-c", "user.email=skill-manager@localhost",
                "-c", "user.name=skill-manager",
                "stash", "push", "--include-untracked", "-m", message));
        return r.exit == 0;
    }

    /**
     * {@code git stash pop}. Returns true on clean pop, false on conflict (the
     * stash entry is preserved at {@code stash@{0}} so the user can resolve and
     * re-pop manually, which is the expected UX for local-vs-upstream collisions).
     */
    public static boolean stashPop(Path dir) {
        return run(dir, List.of("git", "stash", "pop")).exit == 0;
    }

    public static boolean resetHard(Path dir, String ref) {
        return run(dir, List.of("git", "reset", "--hard", "--quiet", ref)).exit == 0;
    }

    public static boolean mergeAbort(Path dir) {
        return run(dir, List.of("git", "merge", "--abort")).exit == 0;
    }

    public static String fetchHead(Path dir, String remote) {
        return fetchRef(dir, remote, "HEAD");
    }

    /**
     * Fetches {@code ref} from {@code remote} into FETCH_HEAD. Falls back to a
     * full fetch + rev-parse when the remote rejects fetch-by-sha (older git
     * servers without {@code uploadpack.allowAnySHA1InWant}).
     */
    public static String fetchRef(Path dir, String remote, String ref) {
        Result fetch = run(dir, List.of("git", "fetch", "--no-tags", "--quiet", remote, ref));
        if (fetch.exit != 0) {
            Result fullFetch = run(dir, List.of("git", "fetch", "--no-tags", "--quiet", remote));
            if (fullFetch.exit != 0) return null;
            // Branch refs ("main", "develop") must resolve to the just-fetched
            // remote-tracking branch (refs/remotes/<remote>/<ref>) — falling
            // back to plain "rev-parse <ref>" would resolve the stale local
            // branch and return the wrong sha. Try remote-tracking first;
            // tags and full shas resolve identically either way, so the bare
            // <ref> fallback only fires for those.
            Result remoteRev = run(dir, List.of("git", "rev-parse", remote + "/" + ref));
            if (remoteRev.exit == 0 && !remoteRev.stdout.trim().isBlank()) {
                return remoteRev.stdout.trim();
            }
            Result rev = run(dir, List.of("git", "rev-parse", ref));
            return rev.exit == 0 ? rev.stdout.trim() : null;
        }
        Result rev = run(dir, List.of("git", "rev-parse", "FETCH_HEAD"));
        return rev.exit == 0 ? rev.stdout.trim() : null;
    }

    /**
     * {@code git ls-remote --symref <remote> HEAD} → the remote's default branch
     * name (e.g. {@code "main"}). Used as the implicit ref when an install was
     * sha-detached and didn't record a {@code gitRef}.
     */
    public static String remoteDefaultBranch(Path dir, String remote) {
        Result r = run(dir, List.of("git", "ls-remote", "--symref", remote, "HEAD"));
        if (r.exit != 0 || r.stdout.isBlank()) return null;
        for (String line : r.stdout.split("\\r?\\n")) {
            if (line.startsWith("ref: ")) {
                int tab = line.indexOf('\t');
                String full = (tab > 0 ? line.substring("ref: ".length(), tab) : line.substring("ref: ".length())).trim();
                if (full.startsWith("refs/heads/")) return full.substring("refs/heads/".length());
                return full;
            }
        }
        return null;
    }

    public static MergeOutcome mergeFetchHead(Path dir) {
        // Identity must be supplied via `-c` overrides — fresh clones under
        // SKILL_MANAGER_HOME inherit no global git identity, and on
        // ephemeral runners (CI, containers) `user.email`/`user.name` are
        // unset globally. Without them a non-fast-forward merge fails to
        // create the merge commit with `fatal: empty ident name not allowed`
        // and rc=1, even though there are no conflicts. Mirrors `stashAll`.
        Result merge = run(dir, List.of("git",
                "-c", "user.email=skill-manager@localhost",
                "-c", "user.name=skill-manager",
                "merge", "--no-edit", "FETCH_HEAD"));
        if (merge.exit == 0) return new MergeOutcome(true, List.of(), merge.stdout);
        List<String> conflicted = unmergedFiles(dir);
        if (conflicted.isEmpty()) mergeAbort(dir);
        return new MergeOutcome(false, conflicted, merge.stdout);
    }

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
