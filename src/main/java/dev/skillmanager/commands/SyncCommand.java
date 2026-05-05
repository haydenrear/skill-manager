package dev.skillmanager.commands;

import dev.skillmanager.app.SyncUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.Skill;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code sync [name] [--from <dir>] [--git-latest] [--merge]} — pull
 * upstream changes for git-tracked installs and re-run the install
 * side-effects pipeline (tools, CLI deps, MCP register, agent symlinks).
 *
 * <p>Every side effect (the merge, the prompt + diff for {@code --from},
 * the post-update tail, orphan-detection) lives in the {@link SyncUseCase}
 * program — the command just resolves targets and hands off.
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
            description = "Allow a 3-way merge against the resolved upstream when local edits exist. "
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

        GatewayConfig gw = GatewayConfig.resolve(store, null);

        // A user-named skill that isn't installed is an error (exit 3),
        // distinct from the empty-store case (no targets, exit 0 with a
        // warning).
        if (name != null && !name.isBlank() && !store.contains(name)) {
            Log.error("not installed: %s", name);
            return 3;
        }

        List<SyncUseCase.Target> targets = resolveTargets(store);
        if (targets.isEmpty()) {
            Log.warn("no skills installed");
            return 0;
        }

        SyncUseCase.Options opts = new SyncUseCase.Options(
                registryUrl, gitLatest, merge, !skipMcp, !skipAgents, yes);
        Program<SyncUseCase.Report> program = SyncUseCase.buildProgram(store, gw, opts, targets);
        ProgramInterpreter interpreter = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store, gw);
        SyncUseCase.Report report = interpreter.run(program);
        return report.worstRc();
    }

    private List<SyncUseCase.Target> resolveTargets(SkillStore store) throws IOException {
        if (name != null && !name.isBlank()) {
            return fromDir != null
                    ? List.of(new SyncUseCase.Target.FromDir(name, fromDir))
                    : List.of(new SyncUseCase.Target.Git(name));
        }
        List<SyncUseCase.Target> out = new ArrayList<>();
        for (Skill s : store.listInstalled()) out.add(new SyncUseCase.Target.Git(s.name()));
        return out;
    }
}
