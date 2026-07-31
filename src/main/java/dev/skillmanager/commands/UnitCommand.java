package dev.skillmanager.commands;

import dev.skillmanager.project.PullRequestOpener;
import dev.skillmanager.project.UnitPublisher;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Operations on one unit as a piece of source that belongs to somebody else's
 * repository, as distinct from one that happens to be installed here.
 */
@Command(name = "unit",
        description = "Work with an installed unit as source: publish home edits back to its repo.",
        subcommands = {UnitCommand.PublishCmd.class})
public final class UnitCommand {

    /**
     * {@code skill-manager unit publish <name>} — the return path for an edit an
     * agent made inside a home.
     *
     * <p>A pull request by default, on a branch named after the ticket. See
     * {@link UnitPublisher} for why proposing rather than pushing is the default
     * and what guarantees the branch mechanics carry.
     */
    @Command(name = "publish",
            description = "Commit this home's edits to a unit on skill/<ticket>-<unit>, push it, "
                    + "and open a pull request against the unit's trunk.")
    public static final class PublishCmd implements Callable<Integer> {

        @Parameters(index = "0", description = "Installed unit name.")
        String name;

        @Option(names = "--ticket",
                description = "Ticket this work belongs to; becomes part of the branch name. "
                        + "Defaults to $" + UnitPublisher.TICKET_ENV + ".")
        String ticket;

        @Option(names = "--message", description = "Commit message. Defaults to a generated one.")
        String message;

        @Option(names = "--base",
                description = "Branch to propose against. Defaults to main.")
        String base;

        @Option(names = "--remote", description = "Remote to push to. Defaults to origin.")
        String remote;

        @Option(names = "--child-home",
                description = "Project child home to publish from, when the unit was materialized "
                        + "there as a checkout.")
        Path childHome;

        @Option(names = "--direct",
                description = "Push straight to the base branch instead of opening a pull "
                        + "request. No review; use when you are also the reviewer.")
        boolean direct;

        @Option(names = "--no-pr",
                description = "Push the branch but do not open a pull request.")
        boolean noPr;

        @Option(names = "--dry-run", description = "Report what would happen and change nothing.")
        boolean dryRun;

        @Option(names = "--home",
                description = "Skill Manager home. Defaults to $SKILL_MANAGER_HOME.")
        Path home;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        private final SkillStore injectedStore;
        private final PullRequestOpener injectedOpener;

        public PublishCmd() { this(null, null); }

        public PublishCmd(SkillStore injectedStore, PullRequestOpener injectedOpener) {
            this.injectedStore = injectedStore;
            this.injectedOpener = injectedOpener;
        }

        @Override
        public Integer call() throws Exception {
            SkillStore store = injectedStore != null
                    ? injectedStore
                    : home != null
                        ? new SkillStore(home.toAbsolutePath().normalize())
                        : SkillStore.defaultStore();
            store.init();
            PullRequestOpener opener = injectedOpener != null
                    ? injectedOpener
                    : noPr ? PullRequestOpener.none() : PullRequestOpener.gh();
            UnitPublisher.Result result = new UnitPublisher(store, opener).publish(name,
                    new UnitPublisher.Options(ticket, message, base, remote, direct, dryRun,
                            childHome));
            if (json) {
                System.out.println("""
                        {"unit":"%s","kind":"%s","repo":"%s","branch":"%s","base":"%s",\
                        "remote":"%s","commit":"%s","status":"%s","pullRequest":"%s"}"""
                        .formatted(esc(result.unitName()), result.unitKind().name().toLowerCase(),
                                esc(String.valueOf(result.repo())), esc(result.branch()),
                                esc(result.baseBranch()), esc(result.remote()),
                                esc(result.commit() == null ? "" : result.commit()),
                                result.status().name().toLowerCase(),
                                esc(result.pullRequestUrl() == null ? "" : result.pullRequestUrl())));
                return result.ok() ? 0 : 1;
            }
            if (result.ok()) {
                Log.ok("%s: %s", result.unitName(), result.detail());
            } else {
                Log.error("%s: %s", result.unitName(), result.detail());
            }
            Log.info("  repo:   %s", result.repo());
            Log.info("  branch: %s → %s/%s", result.branch(), result.remote(), result.baseBranch());
            if (result.pullRequestUrl() != null) Log.info("  pr:     %s", result.pullRequestUrl());
            return result.ok() ? 0 : 1;
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
