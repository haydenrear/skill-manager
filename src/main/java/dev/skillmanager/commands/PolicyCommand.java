package dev.skillmanager.commands;

import dev.skillmanager.policy.Policy;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "policy",
        description = "Inspect or initialize the skill-manager policy.",
        subcommands = {PolicyCommand.Show.class, PolicyCommand.Init.class, PolicyCommand.Where.class})
public final class PolicyCommand implements Runnable {
    @Override public void run() { new picocli.CommandLine(this).usage(System.out); }

    @Command(name = "show", description = "Print the current policy.")
    public static final class Show implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            Policy p = Policy.load(store);
            java.nio.file.Path f = store.root().resolve(Policy.FILENAME);
            System.out.println("policy file:          " + f + (Files.exists(f) ? "" : "  (using defaults)"));
            System.out.println("allowed_backends:     " + p.allowedBackends());
            System.out.println("require_hash:         " + p.requireHash());
            System.out.println("allow_init_scripts:   " + p.allowInitScripts());
            System.out.println("allow_docker:         " + p.allowDocker());
            System.out.println("allowed_registries:   " + (p.allowedRegistries().isEmpty() ? "(any)" : p.allowedRegistries()));
            System.out.println("allowed_docker_prefs: " + (p.allowedDockerPrefixes().isEmpty() ? "(any)" : p.allowedDockerPrefixes()));
            System.out.println("require_confirmation: " + p.requireConfirmation());
            return 0;
        }
    }

    @Command(name = "init", description = "Write the default policy file if missing.")
    public static final class Init implements Callable<Integer> {
        @picocli.CommandLine.Option(names = "--dry-run",
                description = "Print the effect that would initialize the policy without writing.")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            dev.skillmanager.effects.Program<Integer> program =
                    new dev.skillmanager.effects.Program<>(
                            "policy-init-" + java.util.UUID.randomUUID(),
                            java.util.List.of(new dev.skillmanager.effects.SkillEffect.InitializePolicy()),
                            receipts -> 0);
            dev.skillmanager.effects.ProgramInterpreter interp = dryRun
                    ? new dev.skillmanager.effects.DryRunInterpreter()
                    : new dev.skillmanager.effects.LiveInterpreter(store, null);
            interp.run(program);
            if (!dryRun) Log.ok("policy file: %s", store.root().resolve(Policy.FILENAME));
            return 0;
        }
    }

    @Command(name = "path", description = "Print the path to the policy file.")
    public static final class Where implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            System.out.println(store.root().resolve(Policy.FILENAME));
            return 0;
        }
    }
}
