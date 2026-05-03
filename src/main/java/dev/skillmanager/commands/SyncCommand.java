package dev.skillmanager.commands;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.SkillSource;
import dev.skillmanager.source.SkillSourceStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.sync.SkillSync;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager sync [name] [--from <dir>]} — re-run the
 * install-time side effects for already-installed skills, and
 * optionally pull fresh content from a local working directory before
 * doing so.
 *
 * <p>Two motivating cases:
 *
 * <ul>
 *   <li>A skill declared an MCP server with a required env var that
 *       wasn't set during {@code install}, so the gateway registered
 *       the server but never deployed it. After exporting the env
 *       var, {@code sync} re-registers and tries to deploy. Also
 *       re-syncs agent symlinks and re-asserts the {@code
 *       virtual-mcp-gateway} entry in each agent's MCP config.</li>
 *   <li>You're iterating on a skill from a local git checkout and
 *       want to apply your in-flight edits to the installed copy
 *       without going through publish + install. {@code --from
 *       <dir>} runs {@code diff -urN --no-index} between the
 *       store entry and the source dir, prints it for review,
 *       prompts before overwriting (skip with {@code --yes}), and
 *       then continues into the normal sync.</li>
 * </ul>
 *
 * <p>With no argument, syncs every installed skill. With a name, syncs just
 * that skill (but the agent's MCP-config entry is rewritten the same way —
 * it's a single shared entry, not per-skill). {@code --from} requires a
 * name, since it operates on exactly one skill.
 */
@Command(name = "sync",
        description = "Re-run install side effects (MCP deploy, agent symlinks) for installed skills, "
                + "optionally pulling content from a local source directory first.")
public final class SyncCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Skill name to sync (default: all installed)")
    String name;

    @Option(names = "--from",
            description = "Local directory to pull skill content from (must contain SKILL.md). "
                    + "Lists changed files via `git diff --no-index --name-status` (re-run "
                    + "without --name-status to inspect the full diff), prompts for approval, "
                    + "then overwrites the store with the source dir's contents. "
                    + "Requires <name>. Does not contact the registry.")
    Path fromDir;

    @Option(names = {"-y", "--yes"},
            description = "Skip the approval prompt for --from and apply the diff unconditionally.")
    boolean yes;

    @Option(names = "--merge",
            description = "When the target skill is git-tracked and --from is also a git repo, "
                    + "fetch its HEAD and `git merge` it onto the installed copy instead of "
                    + "overwriting. On conflict, leaves the working tree in the conflicted "
                    + "state for you (or the agent) to resolve.")
    boolean merge;

    @Option(names = "--skip-agents",
            description = "Don't refresh agent symlinks or MCP-config entries.")
    boolean skipAgents;

    @Option(names = "--skip-mcp",
            description = "Don't re-register MCP servers with the gateway.")
    boolean skipMcp;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();

        if (fromDir != null && (name == null || name.isBlank())) {
            Log.error("--from requires a skill name (got --from %s with no <name>)", fromDir);
            return 2;
        }

        // Track the worst per-skill git-sync exit code across the run.
        // We don't fail-fast in all-skills mode — we want one
        // aggregate summary at the end naming every skill that needs
        // attention, then we still run the MCP / agent refresh.
        int gitSyncRc = 0;

        List<Skill> targets;
        if (name != null && !name.isBlank()) {
            if (!store.contains(name)) {
                if (fromDir != null) {
                    Log.error("not installed: %s — install it first with `skill-manager install file:%s`",
                            name, fromDir.toAbsolutePath());
                } else {
                    Log.error("not installed: %s", name);
                }
                return 1;
            }
            if (fromDir != null) {
                int rc = applyFromLocalDir(store, name, fromDir);
                if (rc != 0) return rc;
            } else {
                int rc = applyFromImplicitOrigin(store, name);
                if (rc != 0) return rc;
            }
            targets = List.of(store.load(name).orElseThrow());
        } else {
            targets = store.listInstalled();
            if (!targets.isEmpty()) {
                gitSyncRc = syncAllGit(store, targets);
            }
        }
        if (targets.isEmpty()) {
            Log.warn("no skills installed");
            return 0;
        }

        GatewayConfig gw = GatewayConfig.resolve(store, null);

        if (!skipMcp) {
            if (!InstallCommand.ensureGatewayRunning(store, gw)) {
                Log.error("gateway at %s is unreachable and could not be started — "
                        + "start it manually (`skill-manager gateway up`) and rerun",
                        gw.baseUrl());
                // Surface the more user-actionable code if git also flagged things.
                return Math.max(4, gitSyncRc);
            }
            McpWriter writer = new McpWriter(gw);
            var results = writer.registerAll(targets);
            writer.printInstallResults(results);
        }

        if (!skipAgents) {
            // Symlink + MCP-config refresh always operates on the full
            // installed set, since the gateway entry is shared. When the
            // user named a single skill, only its symlink gets rebuilt.
            List<Skill> linkSet = (name != null && !name.isBlank())
                    ? targets
                    : store.listInstalled();
            McpWriter writer = new McpWriter(gw);
            for (Agent agent : Agent.all()) {
                try {
                    new SkillSync(store).sync(agent, linkSet, true);
                } catch (Exception e) {
                    Log.warn("%s: skill sync failed — %s", agent.id(), e.getMessage());
                }
                try {
                    writer.writeAgentEntry(agent);
                } catch (Exception e) {
                    Log.warn("%s: mcp config update failed — %s", agent.id(), e.getMessage());
                }
            }
        }
        return gitSyncRc;
    }

    /**
     * All-skills git sync. For every installed skill that has a
     * pinned upstream (git-tracked with origin set on the source
     * record), run the same implicit-origin pull as {@code sync
     * <name>} would. Don't bail on the first refusal — collect
     * per-skill outcomes and emit one aggregate summary at the end
     * so the user sees the whole picture in one pass.
     *
     * <p>Returns the worst exit code observed: {@code 8} (any
     * conflict) > {@code 7} (any extra-local-changes refusal) >
     * {@code 0} (clean). Non-git skills and git skills with no
     * configured upstream contribute {@code 0} silently.
     */
    private int syncAllGit(SkillStore store, List<Skill> targets) {
        java.util.List<String> refused = new java.util.ArrayList<>();
        java.util.List<String> conflicted = new java.util.ArrayList<>();
        int worstRc = 0;
        for (Skill s : targets) {
            int rc;
            try {
                rc = applyFromImplicitOrigin(store, s.name());
            } catch (Exception e) {
                Log.warn("%s: git sync failed — %s", s.name(), e.getMessage());
                continue;
            }
            if (rc == 7) refused.add(s.name());
            else if (rc == 8) conflicted.add(s.name());
            if (rc > worstRc) worstRc = rc;
        }
        if (!refused.isEmpty() || !conflicted.isEmpty()) {
            printSyncAllSummary(refused, conflicted);
        }
        return worstRc;
    }

    /**
     * One block listing every skill that needs follow-up, with the
     * exact {@code skill-manager sync … --merge} command per row so
     * an agent / human can copy-paste resolve.
     */
    private static void printSyncAllSummary(java.util.List<String> refused,
                                            java.util.List<String> conflicted) {
        System.err.println();
        int total = refused.size() + conflicted.size();
        System.err.println("sync summary: " + total + " skill(s) need attention");
        if (!refused.isEmpty()) {
            System.err.println();
            System.err.println("  Extra local changes — re-run with --merge to bring upstream in:");
            for (String n : refused) {
                System.err.println("    skill-manager sync " + n + " --merge");
            }
        }
        if (!conflicted.isEmpty()) {
            System.err.println();
            System.err.println("  Conflicted — resolve in the store dir, then `git commit` "
                    + "or `git merge --abort`:");
            for (String n : conflicted) {
                System.err.println("    " + n);
            }
        }
        System.err.println();
    }

    /**
     * Pull upstream changes for a git-tracked skill, using whatever
     * origin was recorded at install time (the {@link SkillSource}
     * record's {@code origin} field, or {@code git remote get-url
     * origin} as a fallback). This is what runs when the user types
     * {@code skill-manager sync <name>} without {@code --from}: the
     * install already opened the git repo and pinned the upstream, so
     * sync should just use it.
     *
     * <p>Behaviour:
     *
     * <ul>
     *   <li>Skill not git-tracked, or no origin configured → no-op,
     *       returns 0 so the caller continues into the MCP / agent
     *       refresh.</li>
     *   <li>Working tree clean → fetch + merge (typically a no-op or
     *       fast-forward). Refreshes the source-record baseline on
     *       success.</li>
     *   <li>Dirty (working tree edits or commits ahead of baseline)
     *       and {@code --merge} not set → prints structured refusal
     *       with the exact {@code skill-manager sync … --merge} +
     *       by-hand git recipe and returns 7.</li>
     *   <li>Dirty and {@code --merge} set → snapshot local edits,
     *       fetch, merge. Conflict → returns 8 with conflict files
     *       listed, working tree left for the user to resolve.</li>
     * </ul>
     */
    private int applyFromImplicitOrigin(SkillStore store, String skillName) {
        Path storeDir = store.skillDir(skillName);
        if (!GitOps.isGitRepo(storeDir) || !GitOps.isAvailable()) return 0;

        SkillSourceStore sources = new SkillSourceStore(store);
        SkillSource src = sources.read(skillName).orElse(null);
        String upstream = src != null && src.origin() != null && !src.origin().isBlank()
                ? src.origin()
                : GitOps.originUrl(storeDir);
        if (upstream == null || upstream.isBlank()) {
            Log.info("%s: git-tracked but no upstream origin configured — skipping git pull", skillName);
            return 0;
        }

        String baseline = src != null ? src.gitHash() : null;
        boolean dirty = GitOps.isDirty(storeDir, baseline);
        if (dirty && !merge) {
            printMergeInstructions(skillName, storeDir, upstream, true, false);
            return 7;
        }
        return runGitMerge(storeDir, upstream, skillName);
    }

    /**
     * Diff {@code <store>/<name>} against {@code fromDir} and, on
     * approval, replace the store contents. Returns {@code 0} on
     * success or no-op (no diff / user-aborted), nonzero on validation
     * failure.
     *
     * <p>For git-tracked skills (a {@code .git/} dir under the store),
     * detects local edits (working tree dirty, commits ahead of the
     * recorded baseline) before any overwrite. Without {@code --merge}
     * the dirty state aborts with structured merge instructions. With
     * {@code --merge} and a git-backed source dir, attempts a {@code
     * git fetch + git merge FETCH_HEAD} from the source's HEAD onto
     * the installed copy.
     */
    private int applyFromLocalDir(SkillStore store, String skillName, Path fromDir) throws Exception {
        Path src = fromDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(src)) {
            Log.error("--from is not a directory: %s", src);
            return 1;
        }
        if (!Files.isRegularFile(src.resolve(SkillParser.SKILL_FILENAME))) {
            Log.error("--from %s is not a skill directory (missing %s)", src, SkillParser.SKILL_FILENAME);
            return 1;
        }
        Path storeDir = store.skillDir(skillName);

        // Source-tracking-aware path: if the installed copy is git-backed,
        // protect user edits and offer a real merge instead of an
        // overwrite. Falls through to the plain diff+overwrite flow when
        // either side isn't git.
        SkillSourceStore sources = new SkillSourceStore(store);
        SkillSource src0 = sources.read(skillName).orElse(null);
        boolean storeIsGit = GitOps.isGitRepo(storeDir);
        boolean srcIsGit = GitOps.isGitRepo(src);
        if (storeIsGit && GitOps.isAvailable()) {
            String baseline = src0 != null ? src0.gitHash() : null;
            boolean dirty = GitOps.isDirty(storeDir, baseline);
            if (dirty && !merge) {
                printMergeInstructions(skillName, storeDir, src.toString(), srcIsGit, true);
                return 7;
            }
            if (merge) {
                if (!srcIsGit) {
                    Log.warn("--merge requested but %s is not a git repo; falling back "
                            + "to diff + overwrite (will refuse if dirty)", src);
                    if (dirty) {
                        printMergeInstructions(skillName, storeDir, src.toString(), false, true);
                        return 7;
                    }
                } else {
                    return runGitMerge(storeDir, src.toString(), skillName);
                }
            }
            // Clean working tree + no --merge → fall through to diff+overwrite.
        }

        // List changed files only — full content diff is verbose for
        // bigger skills and the user can re-run the same git command
        // without --name-status when they actually want to read it.
        // `git diff --no-index` works on arbitrary paths outside any
        // repo; BSD `diff` (macOS default) doesn't support --no-index.
        // Exit codes: 0 = identical, 1 = differences found, 128 = git error.
        Log.step("git diff --no-index --name-status %s %s", storeDir, src);
        StringBuilder summary = new StringBuilder();
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--no-index", "--name-status",
                "--", storeDir.toString(), src.toString())
                .redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            Log.error("`git` not available on PATH: %s", e.getMessage());
            return 1;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println(line);
                summary.append(line).append('\n');
            }
        }
        int rc = p.waitFor();
        if (rc == 0 || summary.length() == 0) {
            Log.ok("%s: store and %s are identical — nothing to apply", skillName, src);
            return 0;
        }
        if (rc != 1) {
            Log.error("`git diff --no-index --name-status` exited %d (output above)", rc);
            return 1;
        }

        System.out.println();
        System.out.println("To inspect the full diff before deciding, run:");
        System.out.println();
        System.out.println("    git diff --no-index " + storeDir + " " + src);
        System.out.println();

        if (!yes) {
            System.out.println();
            System.out.print("Apply these changes to " + storeDir + "? [y/N] ");
            System.out.flush();
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
            String input = stdin.readLine();
            if (input == null || !input.trim().toLowerCase().startsWith("y")) {
                Log.warn("aborted; no changes applied");
                return 0;
            }
        }

        Fs.deleteRecursive(storeDir);
        Fs.copyRecursive(src, storeDir);
        Log.ok("%s: applied changes from %s", skillName, src);
        return 0;
    }

    /**
     * Print exit-code-7 banner with the exact git commands an operator
     * (or the calling agent) needs to merge upstream into the local
     * working tree. Format is stable so harnesses can match on it.
     */
    private static void printMergeInstructions(String skillName, Path storeDir,
                                                String upstream, boolean upstreamIsGit,
                                                boolean explicitFrom) {
        Log.error("%s has extra local changes (working tree edits or commits ahead of the installed baseline).",
                skillName);
        System.err.println();
        if (upstreamIsGit) {
            System.err.println("Sync would overwrite them. Re-run with --merge to bring upstream in via a real");
            System.err.println("3-way merge instead:");
            System.err.println();
            String fromArg = explicitFrom ? " --from " + upstream : "";
            System.err.println("    skill-manager sync " + skillName + fromArg + " --merge");
            System.err.println();
            System.err.println("Or merge by hand with git:");
            System.err.println();
            System.err.println("    cd " + storeDir);
            System.err.println("    git fetch " + upstream + " HEAD");
            System.err.println("    git merge FETCH_HEAD");
        } else {
            System.err.println("The source dir is not a git repo, so there's no upstream branch to merge.");
            System.err.println("Inspect the diff, copy the changes you want by hand, commit them, then re-run:");
            System.err.println();
            System.err.println("    git diff --no-index " + storeDir + " " + upstream);
            System.err.println();
            System.err.println("Or accept the overwrite (loses local changes):");
            System.err.println();
            System.err.println("    rm -rf " + storeDir + " && cp -R " + upstream + " " + storeDir);
        }
        System.err.println();
    }

    /**
     * Run {@code git fetch <upstream> HEAD} then {@code git merge
     * FETCH_HEAD} inside {@code storeDir}. {@code upstream} can be a
     * URL, a configured remote name, or a path on disk — whatever
     * {@code git fetch} accepts.
     *
     * <p>On success, refresh the source-record baseline to the new
     * HEAD. On conflict, leave the working tree conflicted, list the
     * files, and exit non-zero so the caller can resolve.
     */
    private int runGitMerge(Path storeDir, String upstream, String skillName) {
        // Snapshot any working-tree changes onto HEAD so `git merge`
        // produces a real 3-way merge (and surfaces real conflicts in
        // the working tree) instead of refusing with "your local
        // changes would be overwritten by merge".
        String snapshot = GitOps.commitWorkingChanges(storeDir,
                "skill-manager: snapshot local edits before merge");
        if (snapshot != null) {
            Log.info("%s: snapshotted local edits as %s", skillName, snapshot.substring(0, 7));
        }

        Log.step("git fetch %s HEAD && git merge FETCH_HEAD (in %s)", upstream, storeDir);
        String fetchedHash = GitOps.fetchHead(storeDir, upstream);
        if (fetchedHash == null) {
            Log.error("`git fetch %s HEAD` failed in %s", upstream, storeDir);
            return 1;
        }
        GitOps.MergeOutcome outcome = GitOps.mergeFetchHead(storeDir);
        if (outcome.ok()) {
            Log.ok("%s: merged %s into %s", skillName, fetchedHash.substring(0, 7), storeDir);
            // Pin the new baseline so the next sync's dirty-check uses it.
            try {
                SkillSourceStore sources = new SkillSourceStore(SkillStore.defaultStore());
                SkillSource old = sources.read(skillName).orElse(null);
                if (old != null) {
                    sources.write(new SkillSource(
                            old.name(), old.version(), old.kind(), old.origin(),
                            GitOps.headHash(storeDir),
                            java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()));
                }
            } catch (Exception e) {
                Log.warn("merged ok but could not refresh source record: %s", e.getMessage());
            }
            return 0;
        }
        if (!outcome.conflictedFiles().isEmpty()) {
            Log.error("%s: merge has conflicts in %d file(s):", skillName, outcome.conflictedFiles().size());
            for (String f : outcome.conflictedFiles()) {
                System.err.println("    " + f);
            }
            System.err.println();
            System.err.println("Resolve in " + storeDir + ", then `git commit` to finish, or");
            System.err.println("`git merge --abort` to back out.");
            return 8;
        }
        Log.error("git merge failed (no conflict files reported): %s", outcome.log());
        return 1;
    }
}
