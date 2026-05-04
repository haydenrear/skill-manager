package dev.skillmanager.commands;

import dev.skillmanager.app.PostUpdateUseCase;
import dev.skillmanager.app.SyncUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.EffectContext;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.effects.SyncGitHandler;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.store.SkillStore;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code sync [name] [--from <dir>] [--git-latest] [--merge]} — pull upstream
 * changes for git-tracked installs and re-run the install side-effects
 * pipeline (tools, CLI deps, MCP register, agent symlinks).
 *
 * <p>The no-{@code --from} path drives a single {@link SyncUseCase} program of
 * {@link dev.skillmanager.effects.SkillEffect.SyncGit} per target plus the
 * post-update tail. The {@code --from} path stays inline because it has an
 * interactive prompt; for the {@code --merge} variant of {@code --from} it
 * still calls {@link SyncGitHandler#runMerge} so the stash / fetch / merge /
 * pop bookkeeping is shared.
 */
@Command(name = "sync",
        description = "Pull upstream + re-run install side effects for git-tracked skills.")
public final class SyncCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Skill name to sync (default: all installed)")
    public String name;

    @Option(names = "--from",
            description = "Local directory to pull skill content from (must contain SKILL.md). "
                    + "Without --merge: shows diff and prompts before overwriting. "
                    + "With --merge and a git-backed source: 3-way merge against the source's HEAD. "
                    + "Requires <name>.")
    public Path fromDir;

    @Option(names = {"-y", "--yes"},
            description = "Skip the approval prompt for --from.")
    public boolean yes;

    @Option(names = "--merge",
            description = "Allow a real 3-way merge against the resolved upstream when local edits exist. "
                    + "Conflicts leave the working tree in conflicted state and set MERGE_CONFLICT until resolved.")
    public boolean merge;

    @Option(names = "--git-latest",
            description = "Skip the registry; fetch the install-time gitRef (branch / tag) instead of the "
                    + "server-published version's git_sha.")
    public boolean gitLatest;

    @Option(names = "--registry",
            description = "Registry URL override for this invocation (persisted).")
    public String registryUrl;

    @Option(names = "--skip-agents",
            description = "Don't refresh agent symlinks or MCP-config entries.")
    public boolean skipAgents;

    @Option(names = "--skip-mcp",
            description = "Don't re-register MCP servers with the gateway.")
    public boolean skipMcp;

    @Option(names = "--dry-run",
            description = "Print the effects the program would run without mutating filesystem, "
                    + "gateway, or registry.")
    public boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();

        if (fromDir != null && (name == null || name.isBlank())) {
            Log.error("--from requires a skill name");
            return 2;
        }

        // ConfigureRegistry + EnsureGateway are emitted by SyncUseCase as
        // effects; we just resolve the gateway URL here so the program can
        // target it (resolution doesn't side-effect, only persists on a
        // user-supplied override which is also done via ConfigureRegistry).
        GatewayConfig gw = GatewayConfig.resolve(store, null);

        Map<String, Set<String>> preMcpDeps = PostUpdateUseCase.snapshotMcpDeps(store);

        if (fromDir != null) {
            if (!store.contains(name)) {
                Log.error("not installed: %s", name);
                return 1;
            }
            int rc = applyFromLocalDir(store, gw, name, fromDir);
            if (rc != 0 && rc != 7 && rc != 8) return rc;
            // After a --from apply, run the post-update tail through the same
            // effect pipeline so MCP / CLI / agents converge.
            Program<PostUpdateUseCase.Report> tail = PostUpdateUseCase.buildProgram(
                    store, gw, preMcpDeps, !skipMcp, !skipAgents);
            ProgramInterpreter interp = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store, gw);
            PostUpdateUseCase.Report tailReport = interp.run(tail);
            PostUpdateUseCase.printAgentConfigSummary(tailReport, gw.mcpEndpoint().toString());
            return rc;
        }

        List<String> targetNames = resolveTargetNames(store);
        if (targetNames.isEmpty()) {
            Log.warn("no skills installed");
            return 0;
        }

        Program<SyncUseCase.Report> program = SyncUseCase.buildProgram(
                store, gw, registryUrl, targetNames, gitLatest, merge,
                !skipMcp, !skipAgents, preMcpDeps);
        ProgramInterpreter interpreter = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store, gw);
        SyncUseCase.Report report = interpreter.run(program);
        SyncUseCase.printSyncSummary(report);
        if (!report.agentConfigChanges().isEmpty()) {
            PostUpdateUseCase.printAgentConfigSummary(
                    new PostUpdateUseCase.Report(report.errorCount(),
                            report.agentConfigChanges(), report.orphansUnregistered()),
                    gw.mcpEndpoint().toString());
        }
        return report.worstRc();
    }

    private List<String> resolveTargetNames(SkillStore store) throws IOException {
        if (name != null && !name.isBlank()) {
            if (!store.contains(name)) {
                Log.error("not installed: %s", name);
                return List.of();
            }
            return List.of(name);
        }
        return store.listInstalled().stream().map(Skill::name).toList();
    }

    /**
     * --from path: copy or 3-way-merge content from a local directory.
     * Stays inline because the non-merge route prompts on stdin. The merge
     * route delegates to {@link SyncGitHandler#runMerge}.
     */
    private int applyFromLocalDir(SkillStore store, GatewayConfig gw, String skillName, Path fromDir)
            throws Exception {
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
        boolean storeIsGit = GitOps.isGitRepo(storeDir);
        boolean srcIsGit = GitOps.isGitRepo(src);

        if (storeIsGit && GitOps.isAvailable() && merge && srcIsGit) {
            EffectContext ctx = new EffectContext(store, gw);
            return SyncGitHandler.runMerge(ctx, storeDir, src.toString(), "HEAD", skillName).rc();
        }
        if (storeIsGit && GitOps.isAvailable()) {
            String baseline = ctxSourceHash(store, skillName);
            boolean dirty = GitOps.isDirty(storeDir, baseline);
            if (dirty && !merge) {
                printMergeInstructions(skillName, storeDir, src.toString(), srcIsGit, true);
                return 7;
            }
            if (merge && !srcIsGit && dirty) {
                printMergeInstructions(skillName, storeDir, src.toString(), false, true);
                return 7;
            }
        }

        Log.step("git diff --no-index --name-status %s %s", storeDir, src);
        StringBuilder summary = new StringBuilder();
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--no-index", "--name-status",
                "--", storeDir.toString(), src.toString())
                .redirectErrorStream(true);
        Process p;
        try { p = pb.start(); }
        catch (IOException e) {
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
            Log.error("`git diff --no-index --name-status` exited %d", rc);
            return 1;
        }

        System.out.println();
        System.out.println("To inspect the full diff, run:");
        System.out.println();
        System.out.println("    git diff --no-index " + storeDir + " " + src);
        System.out.println();

        if (!yes) {
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

    private static String ctxSourceHash(SkillStore store, String skillName) {
        return new dev.skillmanager.source.SkillSourceStore(store).read(skillName)
                .map(s -> s.gitHash()).orElse(null);
    }

    private void printMergeInstructions(String skillName, Path storeDir, String upstream,
                                        boolean upstreamIsGit, boolean explicitFrom) {
        Log.error("%s has extra local changes (working tree edits or commits ahead of installed baseline).",
                skillName);
        System.err.println();
        if (upstreamIsGit) {
            System.err.println("Sync would overwrite them. Re-run with --merge:");
            System.err.println();
            String flags = (explicitFrom ? " --from " + upstream : "")
                    + (gitLatest ? " --git-latest" : "")
                    + " --merge";
            System.err.println("    skill-manager sync " + skillName + flags);
            System.err.println();
            System.err.println("Or merge by hand:");
            System.err.println();
            System.err.println("    cd " + storeDir);
            System.err.println("    git fetch " + upstream + " HEAD");
            System.err.println("    git merge FETCH_HEAD");
        } else {
            System.err.println("Source dir is not a git repo — no upstream branch to merge.");
            System.err.println("Inspect the diff and apply changes by hand:");
            System.err.println();
            System.err.println("    git diff --no-index " + storeDir + " " + upstream);
        }
        System.err.println();
    }
}
