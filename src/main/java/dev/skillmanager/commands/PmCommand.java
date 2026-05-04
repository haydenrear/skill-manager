package dev.skillmanager.commands;

import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.pm.PackageManager;
import dev.skillmanager.pm.PackageManagerRuntime;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.tools.ToolDependency;
import dev.skillmanager.tools.ToolRegistry;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.UUID;
import java.util.concurrent.Callable;

@Command(
        name = "pm",
        description = "Manage bundled, version-pinned package managers (uv, node/npm).",
        subcommands = {PmCommand.Install.class, PmCommand.List.class, PmCommand.Which.class, PmCommand.Setup.class})
public final class PmCommand implements Runnable {
    @Override public void run() { new picocli.CommandLine(this).usage(System.out); }

    @Command(name = "install", description = "Download and install uv or node (→ npm) at a pinned version.")
    public static final class Install implements Callable<Integer> {
        @Parameters(index = "0", description = "Tool: uv | node") String tool;
        @Option(names = "--version", description = "Version (default: package manager's pinned version)") String version;
        @Option(names = "--dry-run",
                description = "Print the effect that would run without executing it.")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            PackageManager pm = PackageManager.byId(tool);
            Program<Integer> program = new Program<>(
                    "pm-install-" + UUID.randomUUID(),
                    java.util.List.of(new SkillEffect.InstallPackageManager(pm, version)),
                    receipts -> {
                        for (var r : receipts) {
                            if (r.status() == dev.skillmanager.effects.EffectStatus.FAILED) return 1;
                        }
                        return 0;
                    });
            ProgramInterpreter interp = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store, null);
            return interp.run(program);
        }
    }

    @Command(name = "list", description = "List installed package managers and their versions.")
    public static final class List implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            var installed = new PackageManagerRuntime(store).list();
            if (installed.isEmpty()) {
                System.out.println("(no bundled package managers installed)");
                System.out.println("try: skill-manager pm install uv");
                return 0;
            }
            System.out.printf("%-8s %-10s %s%n", "TOOL", "CURRENT", "VERSIONS");
            for (var i : installed) {
                System.out.printf("%-8s %-10s %s%n",
                        i.pm().id,
                        i.current() == null ? "-" : i.current(),
                        String.join(", ", i.versions()));
            }
            return 0;
        }
    }

    @Command(name = "setup",
            description = "Validate / bootstrap the bundled package managers via the SetupPackageManagerRuntime effect.")
    public static final class Setup implements Callable<Integer> {
        @Parameters(arity = "0..*",
                description = "Tool ids to ensure (e.g. uv, npx, docker). Default: every bundleable tool.")
        java.util.List<String> tools;

        @Option(names = "--dry-run",
                description = "Print the effect that would run without executing it.")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            java.util.List<ToolDependency> deps = new java.util.ArrayList<>();
            java.util.List<String> requested = (tools == null || tools.isEmpty())
                    ? java.util.List.of("uv", "node", "npm", "npx")
                    : tools;
            for (String id : requested) {
                ToolDependency dep = ToolRegistry.byId(id);
                if (dep != null) deps.add(dep);
                else Log.warn("unknown tool id: %s", id);
            }
            Program<Integer> program = new Program<>(
                    "pm-setup-" + UUID.randomUUID(),
                    java.util.List.of(new SkillEffect.SetupPackageManagerRuntime(deps)),
                    receipts -> {
                        int errs = 0;
                        for (var r : receipts) {
                            if (r.status() == dev.skillmanager.effects.EffectStatus.FAILED
                                    || r.status() == dev.skillmanager.effects.EffectStatus.PARTIAL) errs++;
                        }
                        return errs;
                    });
            ProgramInterpreter interp = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store, null);
            int rc = interp.run(program);
            return rc == 0 ? 0 : 1;
        }
    }

    @Command(name = "which", description = "Print the path to a given tool (bundled preferred).")
    public static final class Which implements Callable<Integer> {
        @Parameters(index = "0", description = "Tool name (uv, npm, node, pip, …)") String tool;

        @Option(names = "--bundled-only", description = "Only look at bundled package managers under pm/") boolean bundledOnly;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            PackageManagerRuntime rt = new PackageManagerRuntime(store);
            String path = bundledOnly ? rt.bundledPath(tool) : rt.executablePath(tool);
            if (path == null) {
                Log.warn("%s: not found %s", tool, bundledOnly ? "(bundled)" : "(bundled or on PATH)");
                return 1;
            }
            System.out.println(path);
            return 0;
        }
    }
}
