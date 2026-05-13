package dev.skillmanager.commands;

import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingSource;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.HarnessInstantiator;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.HarnessParser;
import dev.skillmanager.model.HarnessUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager harness <subcmd>} — manage harness templates
 * (#47) and their instances. Templates land in
 * {@code <store>/harnesses/<name>/} via the regular install pipeline
 * (e.g. {@code skill-manager install harness:foo}); instantiation
 * materializes one instance into
 * {@code <store>/harnesses/instances/<instanceId>/} by fanning out
 * one {@link BindingSource#HARNESS} binding per referenced
 * skill / plugin / doc-repo source.
 */
@Command(name = "harness",
        description = "Manage harness templates and their instances.",
        subcommands = {
                HarnessCommand.InstantiateCmd.class,
                HarnessCommand.RmCmd.class,
                HarnessCommand.ListCmd.class,
                HarnessCommand.ShowCmd.class
        })
public final class HarnessCommand {

    /** Sub-directory under {@code <store>/harnesses/} where instances live. */
    public static final String INSTANCES_DIR = "instances";

    @Command(name = "instantiate",
            description = """
                    Materialize a harness template by symlinking its skills + plugins
                    into the agent-discoverable config dirs (CLAUDE_CONFIG_DIR / CODEX_HOME)
                    and writing its docs + CLAUDE.md / AGENTS.md into a project root.

                    Path resolution for each target (CLI > env var > sandbox fallback):
                      --claude-config-dir    CLAUDE_CONFIG_DIR    <sandbox>/<id>/claude
                      --codex-home           CODEX_HOME           <sandbox>/<id>/codex
                      --project-dir          (no env counterpart) <sandbox>/<id>

                    Re-runs are idempotent: bindings are replaced-by-id and projections
                    overwrite. Skills get two symlinks (claude+codex); plugins get one
                    (claude only — Codex doesn't load plugins).""")
    public static final class InstantiateCmd implements Callable<Integer> {

        @Parameters(index = "0", description = "Harness template name (must be installed)")
        String name;

        @Option(names = "--id",
                description = "Instance id (default: <name>). Drives the sandbox dir name "
                        + "and the stable binding-id prefix the instantiator stamps.")
        String instanceId;

        @Option(names = "--claude-config-dir",
                description = "Path to the .claude/ config dir Claude Code reads from. "
                        + "Skills land at <dir>/skills/<name>; plugins at <dir>/plugins/<name>. "
                        + "Defaults to env CLAUDE_CONFIG_DIR, then <sandbox>/<id>/claude.")
        String claudeConfigDir;

        @Option(names = "--codex-home",
                description = "Path to the Codex home dir (parent of skills/). Skills land at "
                        + "<dir>/skills/<name>. Defaults to env CODEX_HOME, then <sandbox>/<id>/codex.")
        String codexHome;

        @Option(names = "--project-dir",
                description = "Project root that receives CLAUDE.md / AGENTS.md import lines + "
                        + "tracked-copy docs under docs/agents/. Defaults to <sandbox>/<id>.")
        String projectDir;

        @Option(names = "--dry-run",
                description = "Print the effects without touching the filesystem or ledger.")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            UnitStore us = new UnitStore(store);
            var rec = us.read(name).orElse(null);
            if (rec == null) {
                Log.error("not installed: %s — `skill-manager install harness:%s` first", name, name);
                return 1;
            }
            if (rec.unitKind() != UnitKind.HARNESS) {
                Log.error("%s is not a harness template (kind=%s)", name, rec.unitKind());
                return 2;
            }
            String id = instanceId != null && !instanceId.isBlank() ? instanceId : name;

            HarnessUnit harness = HarnessParser.load(store.unitDir(name, UnitKind.HARNESS));
            Path sandboxRoot = store.harnessesDir().resolve(INSTANCES_DIR);
            Files.createDirectories(sandboxRoot);
            Path instanceSandbox = sandboxRoot.resolve(id);

            // Resolution order per option: explicit CLI arg, then env
            // var, then sandbox subdir fallback. The fallback keeps
            // tests / first-time users self-contained (no chance of
            // accidentally writing into the developer's real
            // ~/.claude/ when no env is set).
            Path resolvedClaude = resolveTargetDir(claudeConfigDir, "CLAUDE_CONFIG_DIR",
                    instanceSandbox.resolve("claude"));
            Path resolvedCodex = resolveTargetDir(codexHome, "CODEX_HOME",
                    instanceSandbox.resolve("codex"));
            Path resolvedProject = resolveTargetDir(projectDir, null, instanceSandbox);

            HarnessInstantiator.Plan plan = HarnessInstantiator.plan(
                    harness, id, resolvedClaude, resolvedCodex, resolvedProject, store);

            // Persist the resolved paths in a sandbox-side lock file so
            // `sync harness:<name>` can re-plan with the same layout
            // without re-deriving from env (which may drift after
            // instantiate). The lock lives inside <sandbox>/<id>/ even
            // when projectDir is elsewhere — `harness rm` cleans it up
            // with the rest of the sandbox dir.
            if (!dryRun) {
                new dev.skillmanager.bindings.HarnessInstanceLock(
                        name, id, resolvedClaude, resolvedCodex, resolvedProject,
                        dev.skillmanager.bindings.BindingStore.nowIso())
                        .write(sandboxRoot);
            }

            List<SkillEffect> effects = new ArrayList<>();
            for (Binding b : plan.bindings()) {
                for (Projection p : b.projections()) {
                    effects.add(new SkillEffect.MaterializeProjection(p, b.conflictPolicy()));
                }
                effects.add(new SkillEffect.CreateBinding(b));
            }

            Program<Void> program = new Program<>("harness-instantiate-" + id,
                    effects, receipts -> null);
            GatewayConfig gw = GatewayConfig.resolve(store, null);
            if (dryRun) {
                new DryRunInterpreter(store).run(program);
                return 0;
            }
            Executor.Outcome<Void> outcome = new Executor(store, gw).run(program);
            if (outcome.rolledBack()) {
                Log.error("harness instantiate rolled back %d effect(s) — sandbox state restored",
                        outcome.applied().size());
                return 4;
            }
            Log.ok("instantiated %s as %s (%d binding(s))",
                    name, id, plan.bindings().size());
            Log.info("  claude config dir: %s", resolvedClaude);
            Log.info("  codex home:        %s", resolvedCodex);
            Log.info("  project dir:       %s", resolvedProject);
            return 0;
        }

        /**
         * Resolve a target directory in CLI > env > fallback order.
         * Expands a leading {@code ~/} against {@code user.home}; passes
         * absolute and relative paths through unchanged (relative
         * resolves against the user's cwd via {@link Path#of}).
         */
        private static Path resolveTargetDir(String cliArg, String envVar, Path fallback) {
            if (cliArg != null && !cliArg.isBlank()) return expandHome(cliArg).toAbsolutePath().normalize();
            if (envVar != null) {
                String env = System.getenv(envVar);
                if (env != null && !env.isBlank()) return expandHome(env).toAbsolutePath().normalize();
            }
            return fallback;
        }

        private static Path expandHome(String s) {
            if (s.equals("~") || s.startsWith("~/")) {
                return Path.of(System.getProperty("user.home") + s.substring(1));
            }
            return Path.of(s);
        }
    }

    @Command(name = "rm",
            description = "Tear down a harness instance: walk every harness:<id>:* binding, "
                    + "reverse each projection, drop the ledger rows, and remove the sandbox dir.")
    public static final class RmCmd implements Callable<Integer> {

        @Parameters(index = "0", description = "Instance id (from `harness list`)")
        String instanceId;

        @Option(names = "--dry-run",
                description = "Print the effects without touching the filesystem or ledger.")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            BindingStore bs = new BindingStore(store);
            String prefix = "harness:" + instanceId + ":";
            List<Binding> mine = new ArrayList<>();
            for (Binding b : bs.listAll()) {
                if (b.bindingId().startsWith(prefix)) mine.add(b);
            }
            if (mine.isEmpty()) {
                Log.warn("no bindings for harness instance %s (checked ledger prefix '%s')",
                        instanceId, prefix);
                // Still try to remove the sandbox dir if it exists — covers
                // a partial instantiation where the ledger never landed.
                Path stale = store.harnessesDir().resolve(INSTANCES_DIR).resolve(instanceId);
                if (Files.isDirectory(stale)) {
                    if (!dryRun) Fs.deleteRecursive(stale);
                    Log.info("removed sandbox dir %s", stale);
                }
                return 0;
            }
            // LIFO across bindings, LIFO across projections inside each
            // — matches Unbind / RemoveUseCase behavior so RENAMED_ORIGINAL_
            // BACKUP rows restore last.
            List<SkillEffect> effects = new ArrayList<>();
            List<Binding> reversed = new ArrayList<>(mine);
            Collections.reverse(reversed);
            for (Binding b : reversed) {
                List<Projection> projs = new ArrayList<>(b.projections());
                Collections.reverse(projs);
                for (Projection p : projs) {
                    effects.add(new SkillEffect.UnmaterializeProjection(p));
                }
                effects.add(new SkillEffect.RemoveBinding(b.unitName(), b.bindingId()));
            }
            Program<Void> program = new Program<>("harness-rm-" + instanceId,
                    effects, receipts -> null);
            GatewayConfig gw = GatewayConfig.resolve(store, null);
            if (dryRun) {
                new DryRunInterpreter(store).run(program);
                return 0;
            }
            Executor.Outcome<Void> outcome = new Executor(store, gw).run(program);
            if (outcome.rolledBack()) {
                Log.error("harness rm rolled back %d effect(s)", outcome.applied().size());
                return 4;
            }
            // After ledger + projections are clean, drop the sandbox dir.
            Path sandbox = store.harnessesDir().resolve(INSTANCES_DIR).resolve(instanceId);
            if (Files.isDirectory(sandbox)) Fs.deleteRecursive(sandbox);
            Log.ok("tore down harness instance %s (%d binding(s))", instanceId, mine.size());
            return 0;
        }
    }

    @Command(name = "list", description = "List installed harness templates + live instances.")
    public static final class ListCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            SkillStore store = SkillStore.defaultStore();
            // Templates
            try {
                List<dev.skillmanager.source.InstalledUnit> templates = new ArrayList<>();
                for (var u : store.listInstalledUnits()) {
                    var rec = new UnitStore(store).read(u.name()).orElse(null);
                    if (rec != null && rec.unitKind() == UnitKind.HARNESS) templates.add(rec);
                }
                if (templates.isEmpty()) {
                    System.out.println("templates: (none installed)");
                } else {
                    System.out.println("templates:");
                    for (var t : templates) {
                        System.out.printf("  %-32s %s%n", t.name(),
                                t.version() == null ? "" : t.version());
                    }
                }
            } catch (IOException io) {
                Log.warn("could not list installed units: %s", io.getMessage());
            }
            // Instances
            Path instancesDir = store.harnessesDir().resolve(INSTANCES_DIR);
            BindingStore bs = new BindingStore(store);
            List<Binding> harnessBindings = bs.listAll().stream()
                    .filter(b -> b.source() == BindingSource.HARNESS)
                    .toList();
            java.util.Map<String, Integer> byInstance = new java.util.TreeMap<>();
            for (Binding b : harnessBindings) {
                String id = b.bindingId();
                // id shape: harness:<instanceId>:<unit>[:<source>]
                if (!id.startsWith("harness:")) continue;
                String rest = id.substring("harness:".length());
                int colon = rest.indexOf(':');
                String instanceId = colon < 0 ? rest : rest.substring(0, colon);
                byInstance.merge(instanceId, 1, Integer::sum);
            }
            System.out.println();
            if (byInstance.isEmpty()) {
                System.out.println("instances: (none)");
            } else {
                System.out.println("instances:");
                for (var e : byInstance.entrySet()) {
                    Path dir = instancesDir.resolve(e.getKey());
                    System.out.printf("  %-32s %d binding(s)  %s%n",
                            e.getKey(), e.getValue(), dir);
                }
            }
            return 0;
        }
    }

    @Command(name = "show", description = "Show one harness template's resolved manifest.")
    public static final class ShowCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "Harness template name")
        String name;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            UnitStore us = new UnitStore(store);
            var rec = us.read(name).orElse(null);
            if (rec == null || rec.unitKind() != UnitKind.HARNESS) {
                Log.error("not a harness template: %s", name);
                return 1;
            }
            HarnessUnit h = HarnessParser.load(store.unitDir(name, UnitKind.HARNESS));
            System.out.println("name:        " + h.name());
            System.out.println("version:     " + (h.version() == null ? "" : h.version()));
            System.out.println("description: " + h.description());
            System.out.println("units:");
            for (var u : h.units()) System.out.println("  - " + u.coord().raw());
            System.out.println("docs:");
            for (var d : h.docs()) System.out.println("  - " + d.coord().raw());
            System.out.println("mcp_tools:");
            for (var m : h.mcpTools()) {
                String tools = m.exposesAllTools() ? "*" : String.join(",", m.tools());
                System.out.printf("  - %s [%s]%n", m.server(), tools);
            }
            return 0;
        }
    }
}
