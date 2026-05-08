package dev.skillmanager.cli;

import dev.skillmanager.commands.AdsCommand;
import dev.skillmanager.commands.CliCommand;
import dev.skillmanager.commands.CreateAccountCommand;
import dev.skillmanager.commands.CreateCommand;
import dev.skillmanager.commands.DepsCommand;
import dev.skillmanager.commands.GatewayCommand;
import dev.skillmanager.commands.InstallCommand;
import dev.skillmanager.commands.ListCommand;
import dev.skillmanager.commands.LockCommand;
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
import dev.skillmanager.registry.RegistryUnavailableException;
import dev.skillmanager.util.Log;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "skill-manager",
        mixinStandardHelpOptions = true,
        // x-release-please-start-version
        version = "skill-manager 0.9.0",
        // x-release-please-end
        description = "Build tool for agent skills: CLI deps, skill references, MCP servers.",
        subcommands = {
                ListCommand.class,
                LockCommand.class,
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
        // Surface auth-expiry + registry-unreachable as stable,
        // agent-parseable banners so the skill-manager-skill wrapper can
        // relay them verbatim to the user. Anything else falls through
        // to picocli's default handler (full stack trace), which is the
        // right diagnostic for unexpected failures.
        cmd.setExecutionExceptionHandler((ex, c, pr) -> {
            AuthenticationRequiredException auth = unwrapCause(ex, AuthenticationRequiredException.class);
            if (auth != null) return printAuthBanner(auth.getMessage());
            RegistryUnavailableException unreachable =
                    unwrapCause(ex, RegistryUnavailableException.class);
            if (unreachable != null) return printRegistryUnreachableBanner(unreachable);
            throw ex;
        });
        return cmd.execute(args);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T unwrapCause(Throwable t, Class<T> kind) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (kind.isInstance(c)) return (T) c;
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
        // The closing report-as-program runs through ConsoleProgramRenderer:
        // OutstandingError facts are emitted by LoadOutstandingErrors and
        // the renderer's onComplete prints the banner. No extra printer call
        // needed here.
        try {
            dev.skillmanager.store.SkillStore store = dev.skillmanager.store.SkillStore.defaultStore();
            store.init();
            dev.skillmanager.mcp.GatewayConfig gw =
                    dev.skillmanager.mcp.GatewayConfig.resolve(store, null);
            new dev.skillmanager.effects.LiveInterpreter(store, gw)
                    .run(dev.skillmanager.app.ReportUseCase.buildProgram());
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

    /**
     * Render a friendly, actionable banner when the registry server
     * isn't reachable. Replaces the raw {@code java.net.ConnectException}
     * stack trace users used to see when running {@code create-account},
     * {@code login}, {@code search}, {@code install <name>}, etc. with
     * the registry server down.
     */
    private static int printRegistryUnreachableBanner(RegistryUnavailableException ex) {
        System.err.println();
        System.err.println("ERROR: registry unreachable");
        System.err.println("URL:    " + ex.baseUrl());
        Throwable cause = ex.getCause();
        if (cause != null) {
            String msg = cause.getMessage();
            System.err.println("Cause:  " + cause.getClass().getSimpleName()
                    + (msg == null || msg.isBlank() ? "" : " — " + msg));
        }
        System.err.println();
        System.err.println("Likely fixes:");
        System.err.println("  - The registry server isn't running at that URL. Start it (or wait for it");
        System.err.println("    to come up) and retry the command.");
        System.err.println("  - You're on the wrong URL. Override per-command with:");
        System.err.println("        --registry <url>");
        System.err.println("    or set persistently with:");
        System.err.println("        skill-manager registry set <url>");
        System.err.println("    or via env var:");
        System.err.println("        SKILL_MANAGER_REGISTRY_URL=<url>");
        System.err.println("  - For installing a unit from local disk (skipping the registry entirely):");
        System.err.println("        skill-manager install ./path/to/dir");
        System.err.println("        skill-manager install file:/abs/path");
        System.err.println();
        return RegistryUnavailableException.EXIT_CODE;
    }
}
