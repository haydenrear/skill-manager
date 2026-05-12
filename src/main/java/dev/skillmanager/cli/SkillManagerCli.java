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
import dev.skillmanager.store.GitCloneAuthException;
import dev.skillmanager.store.GitFetcherException;
import dev.skillmanager.util.Log;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "skill-manager",
        mixinStandardHelpOptions = true,
        // x-release-please-start-version
        version = "skill-manager 0.11.1",
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
            // Match the auth subclass FIRST so its specific banner
            // wins; the generic GitFetcherException catch below is
            // the fall-through for every other git-clone failure
            // (subprocess non-zero, missing git on PATH for SSH,
            // checkout failure, JGit transport not in the auth set).
            GitCloneAuthException gitAuth = unwrapCause(ex, GitCloneAuthException.class);
            if (gitAuth != null) return printGitAuthBanner(gitAuth);
            GitFetcherException gitErr = unwrapCause(ex, GitFetcherException.class);
            if (gitErr != null) return printGitFetcherBanner(gitErr);
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

    /**
     * Print the {@code ACTION_REQUIRED: skill-manager login} banner.
     * Public so {@link dev.skillmanager.resolve.TransitiveFailures} can
     * call it for resolve-time failures that don't escape as
     * exceptions — keeps a single canonical banner per failure mode
     * regardless of which surface emitted it.
     */
    public static int printAuthBanner(String reason) {
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
    /**
     * Banner for a {@code git clone} that the remote refused because we
     * couldn't (or didn't) authenticate. Mirrors the registry's auth
     * banner but with remediation focused on git/SSH config: the user
     * fixes this by configuring credentials on their machine, not by
     * running {@code skill-manager login}.
     */
    public static int printGitAuthBanner(GitCloneAuthException ex) {
        System.err.println();
        System.err.println("ERROR: git clone refused — authentication required");
        System.err.println("URL:    " + ex.url());
        String detail = ex.getMessage();
        if (detail != null && !detail.isBlank()) {
            System.err.println("Detail: " + detail);
        }
        System.err.println();
        System.err.println("Likely fixes:");
        System.err.println("  - The repo is private. Make sure your git client can clone it directly:");
        System.err.println("        git clone " + ex.url());
        System.err.println("    If that fails too, configure credentials first (ssh-agent, gh auth, or a");
        System.err.println("    credential helper) and retry.");
        System.err.println("  - For an SSH URL: confirm `ssh -T git@<host>` succeeds and that your key is");
        System.err.println("    loaded (`ssh-add -l`).");
        System.err.println("  - For an HTTPS URL on github: `gh auth login` configures the credential");
        System.err.println("    helper that git then uses automatically.");
        System.err.println("  - To use SSH for a github source instead of HTTPS, install via:");
        System.err.println("        skill-manager install git@github.com:owner/repo.git");
        System.err.println();
        return GitCloneAuthException.EXIT_CODE;
    }

    /**
     * Banner for a {@code git clone} that failed for a reason other
     * than authentication — subprocess non-zero, missing host git for
     * an SSH URL, JGit transport error, checkout failed. Keeps the
     * full stderr in scope (the operator usually needs it to diagnose)
     * but wraps it in a header that names the URL up front.
     */
    public static int printGitFetcherBanner(GitFetcherException ex) {
        System.err.println();
        System.err.println("ERROR: git clone failed");
        System.err.println("URL:    " + ex.url());
        String detail = ex.getMessage();
        if (detail != null && !detail.isBlank()) {
            System.err.println("Detail:");
            for (String line : detail.split("\\r?\\n")) {
                System.err.println("  " + line);
            }
        }
        System.err.println();
        System.err.println("Likely fixes:");
        System.err.println("  - Reproduce locally with: `git clone " + ex.url() + "` — the same error");
        System.err.println("    should surface, and your normal git config (proxy, certs, network) is");
        System.err.println("    the right place to fix it.");
        System.err.println("  - For a github source, try the SSH form if HTTPS hits a wall (or vice versa):");
        System.err.println("        skill-manager install git@github.com:owner/repo.git");
        System.err.println();
        return GitFetcherException.EXIT_CODE;
    }

    public static int printRegistryUnreachableBanner(RegistryUnavailableException ex) {
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
