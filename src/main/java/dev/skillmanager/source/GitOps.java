package dev.skillmanager.source;

import dev.skillmanager.util.Log;

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

    /**
     * Whether {@code dir} is ITSELF a git repository — not whether it happens to
     * sit inside somebody else's checkout.
     *
     * <p><b>This is the boundary that keeps skill-manager out of repositories it
     * does not own.</b> It used to ask {@code rev-parse --is-inside-work-tree},
     * which WALKS UP: a Skill Manager home resolved inside a git working tree
     * (a run-scoped store under a repository's {@code build/} dir, a per-agent
     * home under a run directory) made every unit dir in it answer "yes" for the
     * ENCLOSING repository. Every git command downstream of that answer then ran
     * against the enclosing repository too. The measured consequence was
     * {@link #setOrigin} rewriting a real repository's {@code remote.origin.url}
     * to a vendored source directory — silently, once per curated unit, with the
     * last unit installed left behind as the value. Worktrees share
     * {@code .git/config}, so the primary checkout went with it.
     *
     * <p>Every caller in this codebase means "is this directory its own
     * repository": a unit checkout, a store dir, a clone source. None of them
     * wants an answer about an enclosing tree, and
     * {@code ChildHomeMaterializer.carriesGitDirectory} had already worked
     * around the walk-up locally before this was fixed at the source.
     *
     * <p>{@code rev-parse --show-toplevel} compared against {@code dir} rather
     * than a {@code .git} probe: a LINKED WORKTREE is its own repository root but
     * carries {@code .git} as a FILE, and a {@code Files.isDirectory(.git)} test
     * would wrongly reject it.
     */
    public static boolean isGitRepo(Path dir) {
        return isRepoRoot(dir);
    }

    /**
     * {@code dir} is the top level of a working tree.
     *
     * <p>Deliberately NOT {@code git config --local --get ...}, which reads like
     * a scoping mechanism and is not one: git DISCOVERS the repository by
     * walking up, and {@code --local} then names THAT repository's config file.
     * Measured on git 2.50.1 — from a plain directory nested inside a checkout,
     * {@code git -C <nested> --no-optional-locks config --local --get
     * remote.origin.url} prints the enclosing repository's URL, exactly as
     * {@code git remote get-url origin} does. The scope has to come from asking
     * where the top level IS.
     */
    private static boolean isRepoRoot(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        Result r = run(dir, List.of("git", "rev-parse", "--show-toplevel"));
        if (r.exit != 0) return false;
        String top = r.stdout.trim();
        if (top.isBlank()) return false;
        Path toplevel = Path.of(top);
        try {
            // isSameFile, not equals: /tmp vs /private/tmp and any other
            // symlinked path would otherwise read as a different directory and
            // turn every root into a non-root.
            return Files.isSameFile(toplevel, dir);
        } catch (IOException e) {
            return toplevel.toAbsolutePath().normalize()
                    .equals(dir.toAbsolutePath().normalize());
        }
    }

    public static String headHash(Path dir) {
        Result r = run(dir, List.of("git", "rev-parse", "HEAD"));
        return r.exit == 0 ? r.stdout.trim() : null;
    }

    public static String originUrl(Path dir) {
        return remoteUrl(dir, "origin");
    }

    /**
     * The URL of a named remote of the repository AT {@code dir}, or null when
     * {@code dir} is not itself a repository or has no such remote.
     *
     * <p>The root gate is the point: without it a plain directory inside
     * somebody's checkout reports that checkout's remote, and callers that then
     * WRITE (see {@link #setOrigin}) write to that checkout.
     */
    public static String remoteUrl(Path dir, String remote) {
        if (remote == null || remote.isBlank()) return null;
        if (!isRepoRoot(dir)) return null;
        Result r = run(dir, List.of("git", "remote", "get-url", remote.trim()));
        return r.exit == 0 && !r.stdout.trim().isBlank() ? r.stdout.trim() : null;
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

    /**
     * Point {@code dir}'s own {@code origin} at {@code url}.
     *
     * <p>Refuses — loudly, and without writing anything — when {@code dir} is not
     * a repository root. Both branches below are destructive against the wrong
     * repository: {@code set-url} repoints an existing remote, and {@code add}
     * gives a repository that had no origin one pointing at a source directory.
     * A caller that reaches here with a non-root has already made a mistake, so
     * this says so rather than failing silently.
     */
    public static boolean setOrigin(Path dir, String url) {
        if (!isRepoRoot(dir)) {
            Log.warn("refusing to set origin on %s: not a git repository root"
                    + " (a nested directory's git commands would write to the enclosing checkout)",
                    dir);
            return false;
        }
        if (originUrl(dir) != null) {
            return run(dir, List.of("git", "remote", "set-url", "origin", url)).exit == 0;
        }
        return run(dir, List.of("git", "remote", "add", "origin", url)).exit == 0;
    }

    public static boolean initLocalSnapshot(Path dir, String originUrl) {
        if (!Files.isDirectory(dir) || originUrl == null || originUrl.isBlank()) return false;
        Result init = run(dir, List.of("git", "init", "-b", "main", "--quiet"));
        if (init.exit != 0) {
            init = run(dir, List.of("git", "init", "--quiet"));
            if (init.exit != 0) return false;
            run(dir, List.of("git", "symbolic-ref", "HEAD", "refs/heads/main"));
        }
        if (!setOrigin(dir, originUrl)) return false;
        if (run(dir, List.of("git", "add", "-A")).exit != 0) return false;
        Result commit = run(dir, List.of("git",
                "-c", "user.email=skill-manager@localhost",
                "-c", "user.name=skill-manager",
                "commit", "--quiet", "-m", "skill-manager local onboard snapshot"));
        return commit.exit == 0 || headHash(dir) != null;
    }

    /**
     * Every ref in the repository as {@code <objectname> <refname>} lines,
     * sorted by refname — or null when git cannot answer.
     *
     * <p><b>HEAD is not a summary of a git repository.</b> This exists because
     * asking only {@code rev-parse HEAD} treats "I cannot see inside
     * {@code .git}" as "nothing is in there": a commit on a side branch the agent
     * switched away from, a {@code git stash}, a tag, a note and a fetched ref
     * all leave HEAD exactly where it was. Reconciling a home on that answer
     * destroys every one of them — measured, on a real unit: a side-branch commit
     * and a stash both came back {@code cat-file -e} exit 128 after a plain
     * downward sync that reported "the destination held no local work".
     *
     * <p>{@code for-each-ref refs/} rather than {@code show-ref}: it names the
     * scope explicitly, it includes {@code refs/stash} (which is what makes a
     * stash visible at all), and for an ANNOTATED tag {@code %(objectname)} is
     * the tag object, so a tag that adds no commit is still a change. HEAD is
     * appended by the caller, because it is not a ref under {@code refs/}.
     *
     * <p>What it deliberately does NOT do is ask about reachability. A ref
     * DELETION and an annotated tag are both invisible to
     * {@code rev-list --all --not <rev>}, and the whole point here is to stop
     * converting "cannot see" into "nothing there". The cost is named where it is
     * read: a fetched remote-tracking ref and a deleted merged branch both read
     * as "moved on", which is a conflict a human resolves rather than an edit
     * nobody sees again.
     */
    public static String refListing(Path dir) {
        Result r = run(dir, List.of("git", "for-each-ref",
                "--format=%(objectname) %(refname)", "refs/"));
        return r.exit == 0 ? r.stdout : null;
    }

    /**
     * Is this checkout's HEAD reachable from a <b>remote-tracking</b> ref?
     *
     * <h2>Why "unmodified" was not enough — MAJOR 1 of PR #255's review</h2>
     *
     * <p>{@code home close-out}'s DEF-101 exemption cleared a unit on the
     * strength of "the destination declares it" plus "the worktree has not
     * modified it since its materialization record was written". The reviewer
     * built the case that defeats it with the real CLI: a home whose
     * clone-time baseline was stamped OVER commits that were never pushed. The
     * gate printed <em>"nothing in this worktree's copy exists only here"</em>
     * in the same document where the CLI printed
     * {@code NO_GIT_REMOTE: git-tracked but no origin remote configured}. Two
     * readers, one wrong answer, inside a gate whose entire job is to refuse
     * when work would be destroyed.
     *
     * <p>The precondition is ordinary rather than exotic:
     * {@code recordCloneBaselines} stamps {@code gitStateOf(dir)} over whatever
     * is in the tree, and {@code home clone} is how {@code bootstrap-home.sh}
     * makes every ticket worktree home in this epic.
     *
     * <p>So this asks for POSITIVE EVIDENCE OF PUBLICATION instead: is HEAD an
     * ancestor of, or equal to, some {@code refs/remotes/**} ref. That is a
     * local question with a local answer — no fetch, no network, no
     * availability assumption — and it is the strongest thing that can honestly
     * be claimed offline. It deliberately does NOT prove the remote is
     * reachable or that the declared coordinate resolves; a gate cannot promise
     * that without a network call, and the remedy text says only what was
     * checked.
     *
     * @return the remote-tracking ref that contains HEAD, or {@code null} when
     *         nothing does — including when this is not a git checkout at all,
     *         which is "cannot show it is published" and never "it is fine"
     */
    public static String publishedRefContaining(Path dir) {
        if (!isGitRepo(dir)) return null;
        String head = headHash(dir);
        if (head == null || head.isBlank()) return null;
        Result r = run(dir, List.of("git", "for-each-ref",
                "--format=%(objectname) %(refname)", "refs/remotes/"));
        if (r.exit != 0 || r.stdout == null || r.stdout.isBlank()) return null;
        for (String line : r.stdout.split("\n")) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length != 2) continue;
            // isAncestor is reflexive here by design: HEAD == the remote tip is
            // the commonest published case, and `git merge-base --is-ancestor X X`
            // answers true.
            if (isAncestor(dir, head, parts[0])) return parts[1];
        }
        return null;
    }

    public static String porcelainStatus(Path dir) {
        Result r = run(dir, List.of("git", "status", "--porcelain"));
        return r.exit == 0 ? r.stdout : "";
    }

    public static boolean hasWorktreeChanges(Path dir) {
        return !porcelainStatus(dir).isBlank();
    }

    /**
     * {@link #porcelainStatus} without the opportunistic index refresh.
     *
     * <p>A plain {@code git status} rewrites {@code .git/index} whenever stat
     * information is stale, which is nearly always after a copy. Readers that
     * must not change a byte of the tree they are asked about -- a dry-run
     * reconcile, a teardown gate -- ask this instead: {@code --no-optional-locks}
     * makes git skip that write. Same output, same exit code.
     */
    public static String porcelainStatusNoLock(Path dir) {
        Result r = run(dir, List.of("git", "--no-optional-locks", "status", "--porcelain"));
        return r.exit == 0 ? r.stdout : null;
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

    /**
     * Whether every object in {@code objects} is reachable from some ref of
     * {@code dir} -- {@code refs/*} or HEAD, so a side branch, a remote-tracking
     * ref, a tag or a stash in {@code dir} all count as "holding" it.
     *
     * <p>One {@code rev-list --stdin --not --all} rather than one
     * {@code merge-base --is-ancestor} per object per ref: a real store copy
     * carries a few dozen refs on each side, and asking about ancestry of the
     * HEAD alone was measured false on every one of them (feature branches and
     * remote-tracking refs are not ancestors of {@code main} in either home,
     * while being identical in both). An object {@code dir} does not have at
     * all makes rev-list exit non-zero, which reads as "not contained" -- the
     * conservative answer.
     */
    public static boolean containsAll(Path dir, java.util.Collection<String> objects) {
        if (objects == null || objects.isEmpty()) return true;
        ProcessBuilder pb = new ProcessBuilder(List.of("git", "rev-list", "--stdin", "--not", "--all"))
                .redirectErrorStream(true);
        pb.directory(dir.toFile());
        try {
            Process p = pb.start();
            try (java.io.Writer w = new java.io.OutputStreamWriter(p.getOutputStream(),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                for (String object : objects) {
                    if (object == null || object.isBlank()) continue;
                    w.write(object.trim());
                    w.write('\n');
                }
            }
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            return p.waitFor() == 0 && out.toString().isBlank();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Files left in unmerged ({@code UU}) state after a failed merge or stash pop. */
    public static List<String> unmergedFiles(Path dir) {
        Result r = run(dir, List.of("git", "diff", "--name-only", "--diff-filter=U"));
        if (r.exit != 0 || r.stdout.isBlank()) return List.of();
        return List.of(r.stdout.trim().split("\\r?\\n"));
    }

    /** One row of {@code git ls-files --stage}: the tracked mode, object and path. */
    public record IndexEntry(String mode, String objectId, String path) {}

    /**
     * Every tracked entry in {@code dir}'s index, with the MODE git records for
     * it.
     *
     * <p>The mode is the point. {@code git status} tells you a path changed; it
     * does not tell you that git holds it at {@code 120000} while the working
     * tree holds a directory, which is the shape a dereferenced store link
     * makes and the one no commit can reconcile. See
     * {@link DereferencedStoreLinks}.
     *
     * <p>Stage-0 rows only. An unmerged path appears at stages 1-3 and is a
     * different question, asked by {@link #unmergedFiles}; folding the two
     * together would let a conflicted index masquerade as a materialization.
     */
    public static List<IndexEntry> indexEntries(Path dir) {
        Result r = run(dir, List.of("git", "ls-files", "--stage"));
        if (r.exit != 0 || r.stdout.isBlank()) return List.of();
        List<IndexEntry> out = new java.util.ArrayList<>();
        for (String line : r.stdout.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            String[] meta = line.substring(0, tab).trim().split("\\s+");
            if (meta.length < 3) continue;
            if (!"0".equals(meta[2])) continue;
            out.add(new IndexEntry(meta[0], meta[1], line.substring(tab + 1)));
        }
        return List.copyOf(out);
    }

    /**
     * The contents of a blob as text, or {@code null} when it cannot be read.
     * Used to recover the TARGET of a symlink git still tracks after the
     * working tree stopped holding one.
     */
    public static String blobText(Path dir, String objectId) {
        if (objectId == null || objectId.isBlank()) return null;
        Result r = run(dir, List.of("git", "cat-file", "-p", objectId));
        return r.exit == 0 ? r.stdout : null;
    }

    /**
     * Whether {@code dir} is in the middle of a merge — {@code MERGE_HEAD}
     * exists.
     *
     * <p>Distinct from "are there unmerged paths", and the difference is the
     * whole of HIS-4's link 2. The state measured on the operator's home had
     * unmerged index stages and <b>no {@code MERGE_HEAD}</b>: residue of a
     * failed {@code git stash pop}, not a merge in progress. The remedy printed
     * for it — {@code git add} then {@code git commit} — is the remedy for a
     * merge in progress, and it cannot clear a stash-pop residue.
     */
    public static boolean isMidMerge(Path dir) {
        return Files.isRegularFile(gitDir(dir).resolve("MERGE_HEAD"));
    }

    /**
     * {@code git checkout -- <paths>}: restore these paths in the working tree
     * from the index. Best-effort; a path the index does not hold is simply not
     * restored.
     */
    public static boolean checkoutPaths(Path dir, java.util.Collection<String> paths) {
        if (paths == null || paths.isEmpty()) return true;
        List<String> argv = new java.util.ArrayList<>(List.of("git", "checkout", "--"));
        argv.addAll(paths);
        return run(dir, argv).exit == 0;
    }

    /** Whether {@code dir} carries at least one stash entry. */
    public static boolean hasStash(Path dir) {
        Result r = run(dir, List.of("git", "stash", "list"));
        return r.exit == 0 && !r.stdout.isBlank();
    }

    /**
     * The repository's git directory. Usually {@code dir/.git}, but a store
     * copy that was materialized from a worktree can carry a {@code .git} FILE
     * naming somewhere else, and asking git is the only way to be right.
     */
    private static Path gitDir(Path dir) {
        Result r = run(dir, List.of("git", "rev-parse", "--absolute-git-dir"));
        if (r.exit == 0 && !r.stdout.isBlank()) return Path.of(r.stdout.trim());
        return dir.resolve(".git");
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
        return mergeFetchHead(dir, false);
    }

    public static MergeOutcome mergeFetchHead(Path dir, boolean allowUnrelatedHistories) {
        // Identity must be supplied via `-c` overrides — fresh clones under
        // SKILL_MANAGER_HOME inherit no global git identity, and on
        // ephemeral runners (CI, containers) `user.email`/`user.name` are
        // unset globally. Without them a non-fast-forward merge fails to
        // create the merge commit with `fatal: empty ident name not allowed`
        // and rc=1, even though there are no conflicts. Mirrors `stashAll`.
        java.util.ArrayList<String> argv = new java.util.ArrayList<>(List.of("git",
                "-c", "user.email=skill-manager@localhost",
                "-c", "user.name=skill-manager",
                "merge", "--no-edit"));
        if (allowUnrelatedHistories) argv.add("--allow-unrelated-histories");
        argv.add("FETCH_HEAD");
        Result merge = run(dir, argv);
        if (merge.exit == 0) return new MergeOutcome(true, List.of(), merge.stdout);
        List<String> conflicted = unmergedFiles(dir);
        if (conflicted.isEmpty()) mergeAbort(dir);
        return new MergeOutcome(false, conflicted, merge.stdout);
    }

    public record MergeOutcome(boolean ok, List<String> conflictedFiles, String log) {}

    // ------------------------------------------------------------ push-back
    //
    // Everything below supports `unit publish`: taking the edits an agent made
    // to a unit inside a Skill Manager home and getting them back to the unit's
    // own repository. All of it is deliberately additive — nothing here moves,
    // resets, or force-updates a branch, because the whole point is that a
    // push-back must not be able to lose work on either side.

    /** The branch currently checked out, or null on a detached HEAD. */
    public static String currentBranch(Path dir) {
        Result r = run(dir, List.of("git", "symbolic-ref", "--short", "--quiet", "HEAD"));
        return r.exit == 0 && !r.stdout.trim().isBlank() ? r.stdout.trim() : null;
    }

    public static boolean branchExists(Path dir, String branch) {
        if (branch == null || branch.isBlank()) return false;
        return run(dir, List.of("git", "rev-parse", "--verify", "--quiet",
                "refs/heads/" + branch)).exit == 0;
    }

    /**
     * Move onto {@code branch}, creating it at the current HEAD when it does not
     * exist yet.
     *
     * <p>Deliberately {@code switch -c} / {@code switch} rather than
     * {@code checkout -B}: {@code -B} <em>resets</em> an existing branch to
     * HEAD, so re-running a publish after adding a second commit would silently
     * throw away the first one.
     */
    public static boolean switchToBranch(Path dir, String branch) {
        if (branch == null || branch.isBlank()) return false;
        if (branch.equals(currentBranch(dir))) return true;
        if (branchExists(dir, branch)) {
            return run(dir, List.of("git", "switch", "--quiet", branch)).exit == 0;
        }
        return run(dir, List.of("git", "switch", "--quiet", "-c", branch)).exit == 0;
    }

    /**
     * Stage everything and commit. Returns the new commit's hash, or null when
     * there was nothing to commit (which is not an error — an idempotent
     * re-publish reaches this).
     */
    public static String commitAll(Path dir, String message) {
        if (run(dir, List.of("git", "add", "-A")).exit != 0) return null;
        if (run(dir, List.of("git", "diff", "--cached", "--quiet")).exit == 0) return null;
        Result commit = run(dir, List.of("git",
                "-c", "user.email=skill-manager@localhost",
                "-c", "user.name=skill-manager",
                "commit", "--quiet", "-m", message));
        return commit.exit == 0 ? headHash(dir) : null;
    }

    /**
     * {@code git push <remote> <local>:<remote-ref>}. No {@code --force} and no
     * {@code +} refspec anywhere in this class: a rejected non-fast-forward push
     * is information the caller must surface, never something to overrule.
     */
    public static PushOutcome push(Path dir, String remote, String localRef, String remoteBranch) {
        Result r = run(dir, List.of("git", "push", remote,
                localRef + ":refs/heads/" + remoteBranch));
        return new PushOutcome(r.exit == 0, r.stdout);
    }

    public record PushOutcome(boolean ok, String log) {}

    /** The hash {@code remote}'s {@code branch} currently points at, or null. */
    public static String remoteBranchHash(Path dir, String remote, String branch) {
        Result r = run(dir, List.of("git", "ls-remote", "--heads", remote, branch));
        if (r.exit != 0 || r.stdout.isBlank()) return null;
        String first = r.stdout.trim().split("\\r?\\n")[0];
        int tab = first.indexOf('\t');
        return tab > 0 ? first.substring(0, tab).trim() : null;
    }

    /**
     * Clone {@code source} (a path or URL) into {@code dest}, optionally at
     * {@code ref}. Used by {@code CHECKOUT} materialization, where the source is
     * normally the parent store's own checkout — so the clone is local and needs
     * no network.
     */
    public static boolean clone(Path dest, String source, String ref) {
        if (source == null || source.isBlank() || dest == null) return false;
        Path parent = dest.getParent();
        java.util.ArrayList<String> argv = new java.util.ArrayList<>(List.of(
                "git", "clone", "--quiet", source, dest.toString()));
        if (run(parent, argv).exit != 0) return false;
        if (ref == null || ref.isBlank()) return true;
        return run(dest, List.of("git", "checkout", "--quiet", ref)).exit == 0
                || headHash(dest) != null;
    }

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
