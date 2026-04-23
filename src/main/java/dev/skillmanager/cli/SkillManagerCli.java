package dev.skillmanager.cli;

import dev.skillmanager.commands.AddCommand;
import dev.skillmanager.commands.AdsCommand;
import dev.skillmanager.commands.CliCommand;
import dev.skillmanager.commands.CreateAccountCommand;
import dev.skillmanager.commands.CreateCommand;
import dev.skillmanager.commands.DepsCommand;
import dev.skillmanager.commands.GatewayCommand;
import dev.skillmanager.commands.InstallCommand;
import dev.skillmanager.commands.ListCommand;
import dev.skillmanager.commands.LoginCommand;
import dev.skillmanager.commands.PmCommand;
import dev.skillmanager.commands.PolicyCommand;
import dev.skillmanager.commands.PublishCommand;
import dev.skillmanager.commands.RegistryCommand;
import dev.skillmanager.commands.RemoveCommand;
import dev.skillmanager.commands.ResetPasswordCommand;
import dev.skillmanager.commands.SearchCommand;
import dev.skillmanager.commands.ShowCommand;
import dev.skillmanager.commands.SyncCommand;
import dev.skillmanager.util.Log;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "skill-manager",
        mixinStandardHelpOptions = true,
        version = "skill-manager 0.1.0",
        description = "Build tool for agent skills: CLI deps, skill references, MCP servers.",
        subcommands = {
                ListCommand.class,
                AddCommand.class,
                RemoveCommand.class,
                InstallCommand.class,
                SyncCommand.class,
                ShowCommand.class,
                DepsCommand.class,
                GatewayCommand.class,
                RegistryCommand.class,
                PublishCommand.class,
                SearchCommand.class,
                PolicyCommand.class,
                PmCommand.class,
                CliCommand.class,
                CreateCommand.class,
                AdsCommand.class,
                LoginCommand.class,
                CreateAccountCommand.class,
                ResetPasswordCommand.class
        })
public final class SkillManagerCli implements Runnable {

    @Option(names = {"-v", "--verbose"}, description = "Verbose output", scope = CommandLine.ScopeType.INHERIT)
    public boolean verbose;

    @Override
    public void run() {
        Log.setVerbose(verbose);
        new CommandLine(this).usage(System.out);
    }

    public static int run(String[] args) {
        CommandLine cmd = new CommandLine(new SkillManagerCli());
        cmd.setExecutionStrategy(pr -> {
            SkillManagerCli root = pr.commandSpec().root().userObject() instanceof SkillManagerCli c ? c : null;
            if (root != null) Log.setVerbose(root.verbose);
            return new CommandLine.RunLast().execute(pr);
        });
        return cmd.execute(args);
    }
}
