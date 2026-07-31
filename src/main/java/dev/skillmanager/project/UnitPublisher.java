package dev.skillmanager.project;

import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.bindings.MaterializationMode;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sends the edits an agent made to a unit inside a Skill Manager home back to
 * the unit's own repository, as a branch and a pull request.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>There was no defined path from "an agent improved a skill" to "that
 * improvement reached the skill's repo". The improvement sat in one home until
 * that home was thrown away. Everything needed to move it already existed —
 * the unit is a git checkout with an origin — but nothing joined it up, so in
 * practice edits were either lost or copy-pasted by hand.
 *
 * <h2>Nothing here can lose work on either side</h2>
 *
 * <ul>
 *   <li>The commit lands on {@code skill/<ticket>-<unit>}, never on the trunk,
 *       and the push targets that branch. {@code --direct} is the only way to
 *       write to the trunk and it has to be asked for.</li>
 *   <li>The branch is entered with {@code git switch}, never {@code checkout -B}
 *       — see {@link GitOps#switchToBranch}. Re-publishing a ticket adds a
 *       commit; it does not reset the branch to HEAD and drop the first one.</li>
 *   <li>No push in this codebase is a force push, so a rejected
 *       non-fast-forward is reported rather than overruled.</li>
 *   <li>A frozen home refuses: committing into the home's checkout mutates it,
 *       and a frozen home is one whose contents are evidence.</li>
 * </ul>
 */
public final class UnitPublisher {

    /** Branch prefix, so a home's push-backs are recognizable in any repository. */
    public static final String BRANCH_PREFIX = "skill/";

    /** Env var read when {@code --ticket} is not passed. */
    public static final String TICKET_ENV = "SKILL_MANAGER_TICKET";

    private final SkillStore store;
    private final PullRequestOpener opener;

    public UnitPublisher(SkillStore store) { this(store, PullRequestOpener.gh()); }

    public UnitPublisher(SkillStore store, PullRequestOpener opener) {
        this.store = store;
        this.opener = opener == null ? PullRequestOpener.none() : opener;
    }

    /**
     * @param ticket the ticket this work belongs to; part of the branch name so a
     *        reviewer can tell what a branch is for without reading its diff
     * @param direct push straight to {@code baseBranch} instead of opening a PR
     * @param childHome a project child home to publish from, when the unit was
     *        materialized there as a {@code CHECKOUT}
     */
    public record Options(String ticket, String message, String baseBranch, String remote,
                          boolean direct, boolean dryRun, Path childHome) {
        public Options {
            remote = remote == null || remote.isBlank() ? "origin" : remote.trim();
            baseBranch = baseBranch == null || baseBranch.isBlank()
                    ? UnitTrunkPull.DEFAULT_TRUNK
                    : baseBranch.trim();
        }

        public static Options forTicket(String ticket) {
            return new Options(ticket, null, null, null, false, false, null);
        }
    }

    public enum Status {
        /** Committed, pushed, and a pull request is open. */
        PUBLISHED,
        /** Committed and pushed, but no pull request could be opened. */
        PUSHED_NO_PR,
        /** Committed and pushed straight to the trunk, as {@code --direct} asked. */
        PUSHED_DIRECT,
        /** The unit has no changes that are not already on the branch. */
        NOTHING_TO_PUBLISH,
        /** Nothing was done, because {@code --dry-run}. */
        PLANNED,
        /** The unit is not a git checkout, so there is nowhere to publish to. */
        NOT_GIT_TRACKED,
        /** Git-tracked but with no remote configured. */
        NO_REMOTE,
        /** The push was rejected. */
        PUSH_REJECTED
    }

    public record Result(
            String unitName,
            UnitKind unitKind,
            Path repo,
            String branch,
            String baseBranch,
            String remote,
            String commit,
            Status status,
            String pullRequestUrl,
            String detail
    ) {
        public boolean ok() {
            return status == Status.PUBLISHED || status == Status.PUSHED_NO_PR
                    || status == Status.PUSHED_DIRECT || status == Status.NOTHING_TO_PUBLISH
                    || status == Status.PLANNED;
        }
    }

    /**
     * Branch name for {@code ticket} and {@code unit}. Both are slugged, so a
     * ticket written as {@code "#8"} or {@code "T7 push-back"} still yields a
     * legal ref rather than a git error at push time.
     */
    public static String branchFor(String ticket, String unitName) {
        return BRANCH_PREFIX + slug(ticket) + "-" + slug(unitName);
    }

    public Result publish(String unitName, Options options) throws IOException {
        if (unitName == null || unitName.isBlank()) {
            throw new IOException("unit name is required");
        }
        Options opts = options == null ? Options.forTicket(null) : options;
        String ticket = opts.ticket() == null || opts.ticket().isBlank()
                ? System.getenv(TICKET_ENV)
                : opts.ticket();
        if (ticket == null || ticket.isBlank()) {
            throw new IOException("a ticket is required to publish a unit: pass --ticket <id> or "
                    + "set " + TICKET_ENV + ". The branch is named after it so a reviewer can tell "
                    + "what the change is for.");
        }
        // Committing into the home's checkout is an in-place mutation of the
        // home, so the same gate that stops `sync` stops this.
        HomePolicy.requireLive(store, "unit publish");

        Located located = locate(unitName, opts.childHome());
        Path repo = located.repo();
        UnitKind kind = located.kind();
        String branch = branchFor(ticket, unitName);

        if (!GitOps.isAvailable() || !GitOps.isGitRepo(repo)) {
            return new Result(unitName, kind, repo, branch, opts.baseBranch(), opts.remote(), null,
                    Status.NOT_GIT_TRACKED, null,
                    "not a git checkout at " + repo + " — reinstall the unit from a git source, or "
                            + "materialize it into the child home with --checkout, to publish from it");
        }
        String remoteUrl = resolveRemote(repo, unitName, opts.remote());
        if (remoteUrl == null) {
            return new Result(unitName, kind, repo, branch, opts.baseBranch(), opts.remote(), null,
                    Status.NO_REMOTE, null,
                    "no `" + opts.remote() + "` remote configured in " + repo);
        }

        boolean dirty = GitOps.hasWorktreeChanges(repo);
        if (opts.dryRun()) {
            return new Result(unitName, kind, repo, branch, opts.baseBranch(), opts.remote(),
                    GitOps.headHash(repo), Status.PLANNED, null,
                    (dirty ? "would commit local changes" : "would push the existing commits")
                            + " on " + branch + " and "
                            + (opts.direct() ? "push to " + opts.baseBranch()
                                             : "open a pull request against " + opts.baseBranch()));
        }

        if (!GitOps.switchToBranch(repo, branch)) {
            throw new IOException("could not switch " + repo + " onto " + branch);
        }
        String commit = GitOps.commitAll(repo, commitMessage(opts, ticket, unitName));
        String head = GitOps.headHash(repo);

        if (commit == null && !hasCommitsBeyondBase(repo, remoteUrl, opts.baseBranch(), head)) {
            return new Result(unitName, kind, repo, branch, opts.baseBranch(), opts.remote(), head,
                    Status.NOTHING_TO_PUBLISH, null,
                    "nothing to publish — " + repo + " matches " + opts.baseBranch());
        }

        String pushTarget = opts.direct() ? opts.baseBranch() : branch;
        GitOps.PushOutcome push = GitOps.push(repo, opts.remote(), "refs/heads/" + branch, pushTarget);
        if (!push.ok()) {
            return new Result(unitName, kind, repo, branch, opts.baseBranch(), opts.remote(), head,
                    Status.PUSH_REJECTED, null,
                    "push of " + branch + " to " + opts.remote() + "/" + pushTarget
                            + " was rejected: " + push.log().trim());
        }
        if (opts.direct()) {
            Log.warn("%s: pushed straight to %s — no review", unitName, opts.baseBranch());
            return new Result(unitName, kind, repo, branch, opts.baseBranch(), opts.remote(), head,
                    Status.PUSHED_DIRECT, null,
                    "pushed to " + opts.remote() + "/" + opts.baseBranch() + " without review");
        }

        Optional<String> url = opener.open(new PullRequestOpener.Request(
                repo, opts.remote(), branch, opts.baseBranch(),
                "skill(" + unitName + "): " + ticket,
                pullRequestBody(unitName, ticket, store.root())));
        return url
                .map(u -> new Result(unitName, kind, repo, branch, opts.baseBranch(), opts.remote(),
                        head, Status.PUBLISHED, u, "pull request open at " + u))
                .orElseGet(() -> new Result(unitName, kind, repo, branch, opts.baseBranch(),
                        opts.remote(), head, Status.PUSHED_NO_PR, null,
                        "pushed " + branch + " to " + opts.remote() + "; open a pull request "
                                + "against " + opts.baseBranch() + " to land it"));
    }

    // ------------------------------------------------------------- internals

    private record Located(Path repo, UnitKind kind) {}

    /**
     * Where the unit's edits are.
     *
     * <p>A child home is checked first when one is named, because that is where a
     * {@code CHECKOUT} unit's commits live. A child home unit materialized as a
     * COPY has no history, so publishing from it is refused with the message
     * explaining how to get one — publishing a copy would mean committing a whole
     * tree over the trunk with no shared ancestry.
     */
    private Located locate(String unitName, Path childHome) throws IOException {
        UnitKind kind = kindOf(unitName);
        if (childHome != null) {
            SkillStore childStore = new SkillStore(childHome.toAbsolutePath().normalize());
            Path childUnit = childStore.unitDir(unitName, kind).toAbsolutePath().normalize();
            // Returned whether or not it is a checkout: a COPY there fails the
            // git-tracked check below, whose message says how to get a checkout.
            // Silently falling back to the parent store would publish the wrong
            // tree — the one without the agent's edits.
            if (Files.isDirectory(childUnit)) return new Located(childUnit, kind);
        }
        return new Located(store.unitDir(unitName, kind).toAbsolutePath().normalize(), kind);
    }

    private UnitKind kindOf(String unitName) throws IOException {
        Optional<AgentUnit> unit = store.loadUnit(unitName);
        if (unit.isPresent()) return unit.get().kind();
        Optional<InstalledUnit> record = new UnitStore(store).read(unitName);
        if (record.isPresent() && record.get().unitKind() != null) return record.get().unitKind();
        return UnitKind.SKILL;
    }

    private static String resolveRemote(Path repo, String unitName, String remote) {
        return GitOps.remoteUrl(repo, remote);
    }

    /**
     * True when the branch holds commits the base does not, so a second publish
     * with no new edits still pushes work that was committed by an earlier one.
     */
    private static boolean hasCommitsBeyondBase(Path repo, String remoteUrl, String baseBranch,
                                                String head) {
        if (head == null) return false;
        String baseHash = GitOps.remoteBranchHash(repo, remoteUrl, baseBranch);
        if (baseHash == null) return true;
        if (baseHash.equals(head)) return false;
        // head is a descendant of base → there is something to propose.
        return GitOps.isAncestor(repo, baseHash, head);
    }

    private static String commitMessage(Options opts, String ticket, String unitName) {
        if (opts.message() != null && !opts.message().isBlank()) return opts.message().trim();
        return "skill(" + unitName + "): " + ticket + "\n\n"
                + "Edited in a Skill Manager home and published with `skill-manager unit publish`.\n";
    }

    private static String pullRequestBody(String unitName, String ticket, Path homeRoot) {
        return """
                Edits to `%s` made while working %s, published from a Skill Manager home.

                Home: `%s`

                Opened as a pull request rather than pushed to the trunk on purpose: whoever
                improved the unit and whoever decides it belongs on the trunk are different
                roles. Every home that pulls this trunk will inherit whatever lands here.
                """.formatted(unitName, ticket, homeRoot);
    }

    /** Lowercase, non-alphanumerics collapsed to single dashes, trimmed. */
    static String slug(String value) {
        if (value == null) return "unknown";
        String slug = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return slug.isBlank() ? "unknown" : slug;
    }
}
