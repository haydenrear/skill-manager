package dev.skillmanager.commands;

import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(
        name = "registry",
        description = "Inspect or configure the skill registry.",
        subcommands = {
                RegistryCommand.Set.class,
                RegistryCommand.Status.class,
        })
public final class RegistryCommand implements Runnable {

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }

    @Command(name = "set", description = "Persist the registry URL.")
    public static final class Set implements Callable<Integer> {
        @Parameters(index = "0", description = "Base URL, e.g. http://127.0.0.1:8090") String url;

        @picocli.CommandLine.Option(names = "--dry-run",
                description = "Print the effect that would persist the URL without writing.")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            dev.skillmanager.effects.Program<Integer> program =
                    new dev.skillmanager.effects.Program<>(
                            "registry-set-" + java.util.UUID.randomUUID(),
                            java.util.List.of(new dev.skillmanager.effects.SkillEffect.ConfigureRegistry(url)),
                            receipts -> 0);
            dev.skillmanager.effects.ProgramInterpreter interp = dryRun
                    ? new dev.skillmanager.effects.DryRunInterpreter()
                    : new dev.skillmanager.effects.LiveInterpreter(store, null);
            interp.run(program);
            return 0;
        }
    }

    @Command(name = "status", description = "Show the configured registry + reachability.")
    public static final class Status implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            RegistryConfig cfg = RegistryConfig.resolve(store, null);
            RegistryClient client = RegistryClient.authenticated(store, cfg);
            System.out.println("base:    " + cfg.baseUrl());
            boolean reachable = client.ping();
            System.out.println("status:  " + (reachable ? "reachable" : "unreachable"));
            return reachable ? 0 : 2;
        }
    }
}
