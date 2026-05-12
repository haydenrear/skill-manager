package dev.skillmanager.commands;

import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * {@code skill-manager install <source>} resolves and stages the dep
 * graph, then drives an {@link InstallUseCase} program. Every side
 * effect — registry override, gateway start, pre-condition checks,
 * plan build, commit, audit, source provenance, transitive resolution,
 * tool/CLI/MCP per-action effects, agent sync, orphan unregister, and
 * staged-graph cleanup — is its own effect with a receipt.
 */
@Command(
        name = "install",
        description = """
                Install a unit (skill or plugin) and everything it depends on.

                Sources:
                  - `name[@version]` — registry lookup
                  - `skill:<name>` / `plugin:<name>` — kind-pinned registry lookup
                  - `github:user/repo[@ref]` — direct git
                  - `git+https://...` — direct git URL
                  - `./path`, `/abs/path`, `file:<path>` — local directory
                Local-directory installs do not contact the registry — useful
                for iterating from a working tree without publishing first.

                Plugin vs skill detection (at parse time, after fetch):
                  - PLUGIN if the unit's root contains `.claude-plugin/plugin.json`
                    (Claude Code's runtime manifest). An optional sidecar
                    `skill-manager-plugin.toml` adds skill-manager metadata
                    (CLI deps, MCP deps, references); a plugin without the
                    sidecar still installs.
                  - SKILL if the unit's root contains `SKILL.md` (and is not
                    nested in a plugin).
                Resolver errors out if the source matches neither shape.

                What an install does:
                  1. Fetch + stage the unit (and every transitive reference).
                  2. Plan + show CLI / MCP / hooks the install will register
                     (gated by `policy.install.*`; `--yes` is blocked when a
                     `!`-marked category still requires confirmation).
                  3. Commit bytes — skills land at
                     `$SKILL_MANAGER_HOME/skills/<name>/`; plugins at
                     `plugins/<name>/`.
                  4. Install CLI deps (`bin/cli/`) + register MCP servers
                     with the gateway. For plugins, the install pipeline
                     walks the plugin's `skill-manager-plugin.toml` AND
                     every contained skill's `skill-manager.toml` and
                     unions the deps — no need to declare twice.
                  5. Project units into each agent's tree:
                     - Skills → symlinks at `<agentHome>/skills/<name>/`.
                     - Plugins → entries in the skill-manager-owned
                       marketplace at `<store>/plugin-marketplace/`, then
                       `claude plugin install <name>@skill-manager` (and
                       `codex plugin marketplace add` if either CLI is on
                       PATH; otherwise the plugin records a
                       `HARNESS_CLI_UNAVAILABLE` error with a brew-install
                       hint that self-clears on the next sync).
                  6. Atomically flip `units.lock.toml` at commit.

                Use `skill-manager sync <name> --from <dir>` to refresh an
                already-installed unit from the same directory.
                """
)
public final class InstallCommand implements Callable<Integer> {

    @Parameters(index = "0",
            description = "Source: name[@version] (registry), skill:<name> / plugin:<name> "
                    + "(kind-pinned), github:user/repo, git+https://..., or a local directory "
                    + "(./path, /abs/path, file:<path>) — local sources do not contact the "
                    + "registry. Plugins detected by the presence of `.claude-plugin/plugin.json` "
                    + "at the unit root.")
    public String source;

    @Option(names = {"--version", "--ref"}, description = "Registry version / git ref")
    public String version;

    @Option(names = "--registry",
            description = "Registry URL override for this invocation (persisted so `search` and "
                    + "`install` stay consistent). Can also be set via SKILL_MANAGER_REGISTRY_URL.")
    public String registryUrl;

    @Option(names = "--dry-run",
            description = "Resolve, plan, and print the install effects without committing to the "
                    + "store, contacting the gateway, or running side effects.")
    public boolean dryRun;

    @Option(names = {"-y", "--yes"},
            description = "Skip interactive confirmation. Blocked by policy.install gates when "
                    + "the plan emits a `!`-marked category that policy still requires "
                    + "confirmation for — error names the specific policy flag to flip.")
    public boolean yes;

    @Option(names = "--no-bind-default", negatable = false,
            description = "Skip the default-agent projection — install the unit into the store "
                    + "(and write the lock) but don't symlink it into any agent's dir or record a "
                    + "DEFAULT_AGENT binding. Useful for harness instantiation and profile sync, "
                    + "which create their own bindings.")
    public boolean noBindDefault;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        Policy.writeDefaultIfMissing(store);

        // Apply --registry BEFORE the program starts so the in-program
        // resolve sees the override — the program's ConfigureRegistry
        // effect also persists it, but the resolve happens later in the
        // same program and needs the override active immediately.
        // Skipped on dry-run to avoid persisting state during a no-op.
        if (!dryRun && registryUrl != null && !registryUrl.isBlank()) {
            dev.skillmanager.registry.RegistryConfig.resolve(store, registryUrl);
        }

        // Gateway resolution doesn't side-effect — the Program's
        // EnsureGateway effect does the actual probe + start + persist.
        GatewayConfig gw = GatewayConfig.resolve(store, null);

        // Everything else lives in the program now:
        //   1. BuildResolveGraphFromSource(source, version) — the
        //      resolver call. Halts with HaltWithExitCode on any failure
        //      so transitive auth/fetch errors still surface the typed
        //      exit code the old pre-Program code returned.
        //   2. RejectIfTopLevelInstalled — read top-level from ctx after
        //      the resolve. Halts if it's already installed.
        //   3. BuildInstallPlan + CheckInstallPolicyGate(yes) — gate
        //      against policy.install. Halts with HaltWithExitCode 5/6
        //      on user-blocking violations / declined prompt.
        //   4. Commit + audit + provenance + summary + run + tail.
        dev.skillmanager.effects.StagedProgram<InstallUseCase.Report> program =
                InstallUseCase.buildProgram(store, gw, registryUrl, source, version, yes, dryRun,
                        !dryRun, !noBindDefault);

        InstallUseCase.Report report;
        if (dryRun) {
            report = new DryRunInterpreter(store).runStaged(program);
            return report.exitCode() != 0 ? report.exitCode() : 0;
        }
        // Live install runs through Executor: each successful effect
        // records its compensation, and a FAILED downstream effect (e.g.
        // gateway register fails after a clean commit) walks the journal
        // back so no half-applied state survives. The Report's exitCode
        // wins over the default — typed halts (resolve failure, policy
        // gate) carry their own exit code via HaltWithExitCode facts.
        dev.skillmanager.effects.Executor.Outcome<InstallUseCase.Report> outcome =
                new dev.skillmanager.effects.Executor(store, gw).runStaged(program);
        report = outcome.result();
        if (outcome.rolledBack()) {
            Log.warn("install rolled back %d effect(s) — no partial state retained",
                    outcome.applied().size());
        }
        if (report.exitCode() != 0) return report.exitCode();
        // Renderer printed every user-facing line; the only command-level
        // decision left is the exit code. Install succeeds when the skill
        // committed — post-commit failures (MCP register, transitive
        // install, agent sync) are tracked as outstanding errors on the
        // source record for the reconciler to retry. Exit 4 is reserved
        // for "nothing committed" (commit failed or program halted
        // before commit).
        return report.committed().isEmpty() ? 4 : 0;
    }
}
