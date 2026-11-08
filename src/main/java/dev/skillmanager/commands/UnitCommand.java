package dev.skillmanager.commands;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.DocRepoParser;
import dev.skillmanager.model.HarnessParser;
import dev.skillmanager.model.PluginParser;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.project.PullRequestOpener;
import dev.skillmanager.project.UnitPublisher;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import dev.skillmanager.validation.MarkdownImportValidator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Operations on one unit as a piece of source that belongs to somebody else's
 * repository, as distinct from one that happens to be installed here.
 */
@Command(name = "unit",
        description = "Work with a unit as source: validate a unit directory, or publish home "
                + "edits back to its repo.",
        subcommands = {UnitCommand.PublishCmd.class, UnitCommand.ValidateCmd.class})
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

    /**
     * {@code skill-manager unit validate <dir>} — the local, registry-free check
     * that {@code skill-manager publish --dry-run} used to be documented as.
     *
     * <p>#427 removed {@code publish} with the registry it talked to. Its
     * dry-run was the documented markdown-import validator, so this keeps that
     * check: parse the unit's manifest (plugin, harness, doc-repo or skill, in
     * the resolver's precedence) and run
     * {@link MarkdownImportValidator#validateSource} over it — the same call
     * {@code publish} made. Unlike {@code publish --dry-run}, a violation fails
     * the command ({@link MarkdownImportValidator#EXIT_CODE}), and an
     * unparseable manifest exits 1.
     */
    @Command(name = "validate",
            description = "Parse a unit directory's manifest and check its markdown skill-imports. "
                    + "Local only; exits non-zero on any violation.")
    public static final class ValidateCmd implements Callable<Integer> {

        @Parameters(index = "0", arity = "0..1",
                description = "Unit source directory (defaults to the current directory).")
        Path dir;

        @Option(names = "--home",
                description = "Skill Manager home imports are resolved against. "
                        + "Defaults to $SKILL_MANAGER_HOME.")
        Path home;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        private final SkillStore injectedStore;

        public ValidateCmd() { this(null); }

        public ValidateCmd(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            Path src = (dir == null ? Path.of(System.getProperty("user.dir")) : dir)
                    .toAbsolutePath().normalize();
            SkillStore store = injectedStore != null
                    ? injectedStore
                    : home != null
                        ? new SkillStore(home.toAbsolutePath().normalize())
                        : SkillStore.defaultStore();
            AgentUnit unit;
            try {
                unit = load(src);
            } catch (Exception ex) {
                String reason = "not a parseable unit directory (need "
                        + PluginParser.PLUGIN_JSON_PATH + ", harness.toml, a [doc-repo] "
                        + "skill-manager.toml or " + SkillParser.SKILL_FILENAME + "): " + src
                        + (ex.getMessage() == null ? "" : " — " + ex.getMessage());
                if (json) {
                    System.out.println("{\"dir\":\"" + esc(src.toString())
                            + "\",\"ok\":false,\"error\":\"" + esc(reason) + "\",\"violations\":[]}");
                } else {
                    Log.error("%s", reason);
                }
                return 1;
            }
            List<MarkdownImportValidator.Violation> violations =
                    MarkdownImportValidator.validateSource(store, unit);
            if (json) {
                StringBuilder out = new StringBuilder();
                out.append("{\"dir\":\"").append(esc(src.toString()))
                        .append("\",\"unit\":\"").append(esc(unit.name()))
                        .append("\",\"kind\":\"").append(unit.kind().name().toLowerCase())
                        .append("\",\"ok\":").append(violations.isEmpty())
                        .append(",\"violations\":[");
                for (int i = 0; i < violations.size(); i++) {
                    var v = violations.get(i);
                    if (i > 0) out.append(',');
                    out.append("{\"file\":\"").append(esc(String.valueOf(v.file())))
                            .append("\",\"message\":\"").append(esc(v.message())).append("\"}");
                }
                out.append("]}");
                System.out.println(out);
            } else if (violations.isEmpty()) {
                Log.ok("%s (%s): manifest parsed, no markdown skill-import violations",
                        unit.name(), unit.kind().name().toLowerCase());
            } else {
                for (var v : violations) Log.error("%s", v.render());
                Log.error("%d markdown skill-import violation(s)", violations.size());
            }
            return violations.isEmpty() ? 0 : MarkdownImportValidator.EXIT_CODE;
        }

        /** The resolver's kind precedence (see {@code Resolver.resolveAll}). */
        static AgentUnit load(Path src) throws Exception {
            if (PluginParser.looksLikePlugin(src)) return PluginParser.load(src);
            if (HarnessParser.looksLikeHarness(src)) return HarnessParser.load(src);
            if (DocRepoParser.looksLikeDocRepo(src)) return DocRepoParser.load(src);
            if (!java.nio.file.Files.isRegularFile(src.resolve(SkillParser.SKILL_FILENAME))) {
                throw new java.io.IOException("no unit manifest found");
            }
            return SkillParser.load(src).asUnit();
        }
    }

    /**
     * {@code skill-manager publish} — REMOVED with the registry (#427). Kept as
     * a hidden stub so the old documented validator invocation
     * ({@code publish --dry-run}) fails loudly with a pointer instead of
     * parsing as an unknown command.
     */
    @Command(name = "publish", hidden = true,
            description = "Removed with the registry; see `skill-manager unit validate`.")
    public static final class RemovedPublishCmd implements Callable<Integer> {
        @picocli.CommandLine.Unmatched
        List<String> ignored;

        @Override
        public Integer call() {
            System.err.println("skill-manager publish was removed with the registry (#427): "
                    + "validate a unit's imports with `skill-manager unit validate <dir>`, "
                    + "publish a home edit with `skill-manager unit publish <name>`.");
            return 2;
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
