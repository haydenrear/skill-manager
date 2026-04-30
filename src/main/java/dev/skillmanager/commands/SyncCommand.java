package dev.skillmanager.commands;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.shared.util.Fs;
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
}
