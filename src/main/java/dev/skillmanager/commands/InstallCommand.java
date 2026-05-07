package dev.skillmanager.commands;

import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.resolve.Resolver;
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

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        Policy.writeDefaultIfMissing(store);

        // Apply --registry BEFORE resolution so the graph is built against
        // the same registry the program's ConfigureRegistry effect will
        // persist. Otherwise the override only takes effect for the next
        // command and the current install resolves against the old URL.
        // Skipped on dry-run to avoid persisting state during a no-op.
        if (!dryRun && registryUrl != null && !registryUrl.isBlank()) {
            dev.skillmanager.registry.RegistryConfig.resolve(store, registryUrl);
        }

        Log.step("resolving %s", source);
        Resolver resolver = new Resolver(store);
        ResolvedGraph graph = resolver.resolve(source, version);

        // Resolve gateway URL — the Program's EnsureGateway effect handles
        // the actual probe + start + persist; gateway resolution itself
        // doesn't side-effect.
        GatewayConfig gw = GatewayConfig.resolve(store, null);

        // Build a tentative plan + categorize so policy.install gates can
        // decide whether --yes is acceptable BEFORE the program runs. The
        // BuildInstallPlan effect will run the same plan-build inside the
        // program; the cost of doing it twice (once here, once there) is
        // a few ms of toml parsing — acceptable for the cleaner separation
        // (no prompting from inside an effect).
        if (!dryRun) {
            int gateRc = checkPolicyGate(store, graph);
            if (gateRc != 0) return gateRc;
        }

        Program<InstallUseCase.Report> program = InstallUseCase.buildProgram(
                store, gw, registryUrl, graph, dryRun);
        InstallUseCase.Report report;
        if (dryRun) {
            report = new DryRunInterpreter().run(program);
            return 0;
        }
        // Live install runs through Executor: each successful effect
        // records its compensation, and a FAILED downstream effect (e.g.
        // gateway register fails after a clean commit) walks the journal
        // back so no half-applied state survives. Outcome.rolledBack
        // surfaces in the warn line; the report's committed list is
        // already empty after rollback (DeleteUnitDir compensations
        // deleted what CommitUnitsToStore put down), so the existing
        // exit-code path naturally returns 4.
        dev.skillmanager.effects.Executor.Outcome<InstallUseCase.Report> outcome =
                new dev.skillmanager.effects.Executor(store, gw).run(program);
        report = outcome.result();
        if (outcome.rolledBack()) {
            Log.warn("install rolled back %d effect(s) — no partial state retained",
                    outcome.applied().size());
        }
        // Renderer printed every user-facing line; the only command-level
        // decision left is the exit code. Install succeeds when the skill
        // committed — post-commit failures (MCP register, transitive install,
        // agent sync) are tracked as outstanding errors on the source record
        // for the reconciler to retry. Exit 4 is reserved for "nothing
        // committed" (commit failed or program halted before commit).
        return report.committed().isEmpty() ? 4 : 0;
    }

    /**
     * Build the plan, categorize, and gate against {@link Policy#install}.
     * Returns 0 if the run can proceed, non-zero if it should abort
     * (either rejected --yes or user said no at the prompt).
     */
    private int checkPolicyGate(SkillStore store, ResolvedGraph graph) throws Exception {
        Policy policy = Policy.load(store);
        dev.skillmanager.plan.InstallPlan plan = InstallUseCase.buildPlan(store, graph);
        java.util.List<String> categorization =
                dev.skillmanager.plan.PlanBuilder.categorize(graph.units(), plan);
        java.util.List<dev.skillmanager.policy.PolicyGate.Category> violations =
                dev.skillmanager.policy.PolicyGate.violations(categorization, policy.install());
        if (violations.isEmpty()) return 0;

        if (yes) {
            Log.error("%s",
                    dev.skillmanager.policy.PolicyGate.formatViolationMessage(violations));
            return 5;
        }
        // Non-interactive context (no controlling TTY — pipe / CI / test
        // harness). A prompt would block on EOF; fail fast with the same
        // remediation message --yes gets so automation gets a clear
        // exit instead of a 60s timeout.
        if (System.console() == null) {
            Log.error("install needs interactive confirmation but no TTY is attached");
            Log.error("%s",
                    dev.skillmanager.policy.PolicyGate.formatViolationMessage(violations));
            return 5;
        }
        // Interactive confirmation. Print the categorization (so the user
        // sees what they're approving), then prompt once. A more granular
        // per-category prompt is overkill for v1 — single y/n covers the
        // common case and the policy file is the way to flip individual
        // categories off long-term.
        System.out.println();
        System.out.println("install will perform actions in these gated categories:");
        for (var c : violations) System.out.println("  ! " + c.name());
        System.out.println();
        System.out.print("proceed? [y/N] ");
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in));
        String line = r.readLine();
        if (line == null || !line.trim().equalsIgnoreCase("y")) {
            Log.warn("install aborted at policy gate");
            return 6;
        }
        return 0;
    }
}
