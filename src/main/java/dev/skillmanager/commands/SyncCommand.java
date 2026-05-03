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
            }
            targets = List.of(store.load(name).orElseThrow());
        } else {
            targets = store.listInstalled();
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
                return 4;
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
        return 0;
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
                printMergeInstructions(skillName, storeDir, src, srcIsGit);
                return 7;
            }
            if (merge) {
                if (!srcIsGit) {
                    Log.warn("--merge requested but %s is not a git repo; falling back "
                            + "to diff + overwrite (will refuse if dirty)", src);
                    if (dirty) {
                        printMergeInstructions(skillName, storeDir, src, false);
                        return 7;
                    }
                } else {
                    return runGitMerge(storeDir, src, skillName);
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
    private static void printMergeInstructions(String skillName, Path storeDir, Path src, boolean srcIsGit) {
        Log.error("%s has local changes (working tree dirty or commits ahead of installed baseline).",
                skillName);
        System.err.println();
        System.err.println("Aborting to avoid clobbering your edits. Resolve one of these ways:");
        System.err.println();
        if (srcIsGit) {
            System.err.println("  # Merge the source dir's HEAD into your local edits:");
            System.err.println("  cd " + storeDir);
            System.err.println("  git fetch " + src + " HEAD");
            System.err.println("  git merge FETCH_HEAD");
            System.err.println();
            System.err.println("  # Or have skill-manager attempt the merge for you:");
            System.err.println("  skill-manager sync " + skillName + " --from " + src + " --merge");
        } else {
            System.err.println("  # The source dir is not a git repo, so there's no upstream branch to merge.");
            System.err.println("  # Inspect the diff, copy the changes you want by hand, commit them, then re-run:");
            System.err.println("  git diff --no-index " + storeDir + " " + src);
            System.err.println("  # Or accept the overwrite (loses local changes):");
            System.err.println("  rm -rf " + storeDir + " && cp -R " + src + " " + storeDir);
        }
        System.err.println();
    }

    /**
     * Run {@code git fetch <src> HEAD} then {@code git merge FETCH_HEAD}
     * inside {@code storeDir}. On success, refresh the source-record
     * baseline to the new HEAD. On conflict, leave the working tree
     * conflicted, list the files, and exit non-zero so the caller can
     * resolve.
     */
    private int runGitMerge(Path storeDir, Path src, String skillName) {
        // Snapshot any working-tree changes onto HEAD so `git merge`
        // produces a real 3-way merge (and surfaces real conflicts in
        // the working tree) instead of refusing with "your local
        // changes would be overwritten by merge".
        String snapshot = GitOps.commitWorkingChanges(storeDir,
                "skill-manager: snapshot local edits before merge");
        if (snapshot != null) {
            Log.info("%s: snapshotted local edits as %s", skillName, snapshot.substring(0, 7));
        }

        Log.step("git fetch %s HEAD && git merge FETCH_HEAD (in %s)", src, storeDir);
        String fetchedHash = GitOps.fetchHead(storeDir, src.toString());
        if (fetchedHash == null) {
            Log.error("`git fetch %s HEAD` failed in %s", src, storeDir);
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
