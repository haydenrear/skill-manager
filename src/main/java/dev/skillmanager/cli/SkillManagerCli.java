package dev.skillmanager.cli;

import dev.skillmanager.commands.AdsCommand;
import dev.skillmanager.commands.CliCommand;
import dev.skillmanager.commands.CreateAccountCommand;
import dev.skillmanager.commands.CreateCommand;
import dev.skillmanager.commands.DepsCommand;
import dev.skillmanager.commands.GatewayCommand;
import dev.skillmanager.commands.InstallCommand;
import dev.skillmanager.commands.ListCommand;
import dev.skillmanager.commands.LoginCommand;
import dev.skillmanager.commands.OnboardCommand;
import dev.skillmanager.commands.PmCommand;
import dev.skillmanager.commands.PolicyCommand;
import dev.skillmanager.commands.PublishCommand;
import dev.skillmanager.commands.RegistryCommand;
import dev.skillmanager.commands.RemoveCommand;
import dev.skillmanager.commands.ResetPasswordCommand;
import dev.skillmanager.commands.SearchCommand;
import dev.skillmanager.commands.ShowCommand;
import dev.skillmanager.commands.SyncCommand;
import dev.skillmanager.commands.UninstallCommand;
import dev.skillmanager.commands.UpgradeCommand;
import dev.skillmanager.registry.AuthenticationRequiredException;
import dev.skillmanager.util.Log;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "skill-manager",
        mixinStandardHelpOptions = true,
        // x-release-please-start-version
        version = "skill-manager 0.7.0",
        // x-release-please-end
        description = "Build tool for agent skills: CLI deps, skill references, MCP servers.",
        subcommands = {
                ListCommand.class,
                InstallCommand.class,
                UninstallCommand.class,
                RemoveCommand.class,
                SyncCommand.class,
                UpgradeCommand.class,
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
                ResetPasswordCommand.class,
                OnboardCommand.class
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
            tryReconcile();
            int rc = new CommandLine.RunLast().execute(pr);
            tryPrintOutstandingErrors();
            return rc;
        });
        // Surface auth-expiry as a stable, agent-parseable banner so the
        // skill-manager-skill wrapper can relay it verbatim to the user.
        cmd.setExecutionExceptionHandler((ex, c, pr) -> {
            Throwable cause = unwrapAuth(ex);
            if (cause != null) return printAuthBanner(cause.getMessage());
            throw ex;
        });
        return cmd.execute(args);
    }

    private static Throwable unwrapAuth(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof AuthenticationRequiredException) return c;
        }
        return null;
    }

    private static void tryReconcile() {
        try {
            dev.skillmanager.store.SkillStore store = dev.skillmanager.store.SkillStore.defaultStore();
            store.init();
            dev.skillmanager.mcp.GatewayConfig gw = dev.skillmanager.mcp.GatewayConfig.resolve(store, null);
            dev.skillmanager.lifecycle.SkillReconciler.reconcile(store, gw);
        } catch (Throwable ignored) {}
    }

    private static void tryPrintOutstandingErrors() {
        try {
            dev.skillmanager.store.SkillStore store = dev.skillmanager.store.SkillStore.defaultStore();
            store.init();
            dev.skillmanager.lifecycle.SkillReconciler.printOutstandingErrors(store);
        } catch (Throwable ignored) {}
    }

    private static int printAuthBanner(String reason) {
        System.err.println();
        System.err.println("ACTION_REQUIRED: skill-manager login");
        System.err.println("Reason: " + (reason == null ? "registry credentials are no longer valid" : reason));
        System.err.println("Ask the user to run the following in their terminal, then retry the task:");
        System.err.println();
        System.err.println("    skill-manager login");
        System.err.println();
        System.err.println("A browser window will open for them to sign in. Tokens are refreshed automatically");
        System.err.println("after that — this banner only fires if the refresh token is also expired.");
        return AuthenticationRequiredException.EXIT_CODE;
    }
}
