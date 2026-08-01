package dev.skillmanager.cli;

import dev.skillmanager.commands.AdsCommand;
import dev.skillmanager.commands.BindCommand;
import dev.skillmanager.commands.BindingsCommand;
import dev.skillmanager.commands.CliCommand;
import dev.skillmanager.commands.HarnessCommand;
import dev.skillmanager.commands.HomeCommand;
import dev.skillmanager.commands.CreateAccountCommand;
import dev.skillmanager.commands.CreateCommand;
import dev.skillmanager.commands.DepsCommand;
import dev.skillmanager.commands.EnvCommand;
import dev.skillmanager.commands.ExecCommand;
import dev.skillmanager.commands.GatewayCommand;
import dev.skillmanager.commands.InstallCommand;
import dev.skillmanager.commands.ListCommand;
import dev.skillmanager.commands.LockCommand;
import dev.skillmanager.commands.LoginCommand;
import dev.skillmanager.commands.OnboardCommand;
import dev.skillmanager.commands.PmCommand;
import dev.skillmanager.commands.PolicyCommand;
import dev.skillmanager.commands.ProjectCommand;
import dev.skillmanager.commands.PublishCommand;
import dev.skillmanager.commands.RebindCommand;
import dev.skillmanager.commands.RegistryCommand;
import dev.skillmanager.commands.RemoveCommand;
import dev.skillmanager.commands.ResetPasswordCommand;
import dev.skillmanager.commands.SearchCommand;
import dev.skillmanager.commands.ShowCommand;
import dev.skillmanager.commands.SyncCommand;
import dev.skillmanager.commands.UnbindCommand;
import dev.skillmanager.commands.UninstallCommand;
import dev.skillmanager.commands.UnitCommand;
import dev.skillmanager.commands.UpgradeCommand;
import dev.skillmanager.registry.AuthenticationRequiredException;
import dev.skillmanager.registry.RegistryUnavailableException;
import dev.skillmanager.observability.CliObservability;
import dev.skillmanager.store.GitCloneAuthException;
import dev.skillmanager.store.GitFetcherException;
import dev.skillmanager.util.Log;
import io.opentelemetry.context.Scope;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "skill-manager",
        // The release number alone cannot say WHICH build answered — two
        // different builds reported `skill-manager 0.19.2` while only one of
        // them had an `exec` subcommand (issue #61). BuildIdentity keeps that
        // number as its first line and adds the commit (or artifact
        // fingerprint) and the launcher path underneath. release-please still
        // owns the number: it stays in this file, between its markers, in the
        // same `skill-manager <semver>` shape its generic updater matched
        // before — only the annotation attribute that consumes it changed.
        versionProvider = BuildIdentity.class,
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
                EnvCommand.class,
                ExecCommand.class,
                CreateCommand.class,
                AdsCommand.class,
                LoginCommand.class,
                CreateAccountCommand.class,
                ResetPasswordCommand.class,
                OnboardCommand.class,
                BindCommand.class,
                UnbindCommand.class,
                RebindCommand.class,
                BindingsCommand.class,
                HarnessCommand.class,
                HomeCommand.class,
                ProjectCommand.class,
                UnitCommand.class
        })
public final class SkillManagerCli implements Runnable {

    /**
     * The released version, owned by release-please (see
     * {@code release-please-config.json}, which lists this file as an
     * extra-file). Read by {@link BuildIdentity}, which prints it as the first
     * line of {@code --version} exactly as the annotation used to.
     */
    // x-release-please-start-version
    public static final String RELEASE = "skill-manager 0.20.0";
    // x-release-please-end

    @Option(names = {"-h", "--help"}, usageHelp = true,
            description = "Show this help message and exit.",
            scope = CommandLine.ScopeType.INHERIT)
    public boolean help;

    @Option(names = {"-V", "--version"}, versionHelp = true,
            description = "Print version information and exit.",
            scope = CommandLine.ScopeType.LOCAL)
    public boolean versionHelp;

    @Option(names = {"-v", "--verbose"}, description = "Verbose output", scope = CommandLine.ScopeType.INHERIT)
    public boolean verbose;

    @Option(names = "--agent-context",
            description = "Emit a bounded agent-facing context block to stderr after the command.",
            scope = CommandLine.ScopeType.INHERIT)
    public boolean agentContext;

    @Override
    public void run() {
        Log.setVerbose(verbose);
        new CommandLine(this).usage(System.out);
    }

    public static int run(String[] args) {
        CliObservability observability = CliObservability.configure();
        int[] exitCode = {1};
        try {
            try (Scope ignored = observability.makeCurrent()) {
                try {
                    exitCode[0] = execute(args);
                    return exitCode[0];
                } finally {
                    // Most paths complete with the parsed command below. This
                    // idempotent fallback also covers picocli startup failures.
                    observability.complete("skill-manager", exitCode[0]);
                }
            }
        } finally {
            observability.flushAndClose(CliObservability.DEFAULT_FLUSH_TIMEOUT_MILLIS);
        }
    }

    private static int execute(String[] args) {
        dev.skillmanager.effects.UnitReadProblemReporter.reset();
        CommandLine cmd = new CommandLine(new SkillManagerCli());
        // The mode this invocation displaced, so the finally below can put it
        // back. Captured through a holder because the declaration happens
        // inside picocli's execution strategy and the restore has to outlive
        // it. Null until the strategy actually ran: a picocli parse failure
        // never declares anything, and restoring a mode nobody displaced
        // would itself be a write to the global.
        dev.skillmanager.store.HomeScaffold.Access[] displaced = {null};
        boolean[] declared = {false};
        cmd.setExecutionStrategy(pr -> {
            SkillManagerCli root = rootCommand(pr);
            if (root != null) Log.setVerbose(root.verbose);
            // Before anything can touch a store: state whether THIS
            // invocation is allowed to bring a home into existence. Nothing
            // below re-derives it. See CommandHomeAccess for the
            // classification and HomeScaffold for the defect.
            displaced[0] = dev.skillmanager.store.HomeScaffold
                    .declare(CommandHomeAccess.of(pr));
            declared[0] = true;
            tryReconcile();
            int rc = new CommandLine.RunLast().execute(pr);
            return completeExecution(root, pr, rc);
        });
        // Surface auth-expiry + registry-unreachable as stable,
        // agent-parseable banners so the skill-manager-skill wrapper can
        // relay them verbatim to the user. Anything else falls through
        // to picocli's default handler (full stack trace), which is the
        // right diagnostic for unexpected failures.
        cmd.setExecutionExceptionHandler(SkillManagerCli::handleExecutionException);
        try {
            return cmd.execute(args);
        } finally {
            // The access mode is scoped to this invocation, not to the JVM.
            // Without this, an embedded caller (the server, a test harness, an
            // out-of-tree library user) that ran one READ_ONLY command left
            // every later SkillStore.init() in the process a silent no-op.
            if (declared[0]) dev.skillmanager.store.HomeScaffold.restore(displaced[0]);
        }
    }

    static int handleExecutionException(Exception ex, CommandLine c, CommandLine.ParseResult pr)
            throws Exception {
        AuthenticationRequiredException auth = unwrapCause(ex, AuthenticationRequiredException.class);
        if (auth != null) {
            return completeExecution(rootCommand(pr), pr, printAuthBanner(auth.getMessage()));
        }
        RegistryUnavailableException unreachable =
                unwrapCause(ex, RegistryUnavailableException.class);
        if (unreachable != null) {
            return completeExecution(rootCommand(pr), pr, printRegistryUnreachableBanner(unreachable));
        }
        // Match the auth subclass FIRST so its specific banner
        // wins; the generic GitFetcherException catch below is
        // the fall-through for every other git-clone failure
        // (subprocess non-zero, missing git on PATH for SSH,
        // checkout failure, JGit transport not in the auth set).
        GitCloneAuthException gitAuth = unwrapCause(ex, GitCloneAuthException.class);
        if (gitAuth != null) {
            return completeExecution(rootCommand(pr), pr, printGitAuthBanner(gitAuth));
        }
        GitFetcherException gitErr = unwrapCause(ex, GitFetcherException.class);
        if (gitErr != null) {
            return completeExecution(rootCommand(pr), pr, printGitFetcherBanner(gitErr));
        }
        throw ex;
    }

    private static int completeExecution(SkillManagerCli root, CommandLine.ParseResult pr, int rc) {
        tryPrintOutstandingErrors();
        String commandPath = CliAgentContext.commandPath(pr);
        CliObservability.completeCurrent(commandPath, rc);
        if (isAgentContextRequested(root)) {
            CliAgentContext.emit(
                    System.err,
                    commandPath,
                    rc,
                    CliObservability.currentTraceId());
        }
        return rc;
    }

    private static SkillManagerCli rootCommand(CommandLine.ParseResult pr) {
        if (pr == null) return null;
        Object userObject = pr.commandSpec().root().userObject();
        return userObject instanceof SkillManagerCli c ? c : null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T unwrapCause(Throwable t, Class<T> kind) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (kind.isInstance(c)) return (T) c;
        }
        return null;
    }

    /**
     * Reconcile the ambient home, when there is one.
     *
     * <p>This ran on every invocation and opened with {@code store.init()},
     * which is how {@code --version} came to lay out twelve directories in
     * whatever {@code SKILL_MANAGER_HOME} named — the scaffold was a side
     * effect of starting the process rather than of doing work. There is
     * nothing to reconcile in a home that does not exist yet, so this now
     * asks before it acts; the first writing command still creates the home
     * through its own {@code store.init()} and reconciles from then on.
     *
     * <p>"Is there one" is {@link dev.skillmanager.store.SkillStore#isHome()},
     * which is the same predicate {@code exec} and {@code home describe} refuse
     * on rather than a second spelling of it. A <em>partial</em> home therefore
     * no longer self-heals on a read command: it is not a home by that
     * predicate, so nothing here completes its layout. The first writing
     * command does, through {@code init()}.
     */
    private static void tryReconcile() {
        try {
            dev.skillmanager.store.SkillStore store = dev.skillmanager.store.SkillStore.defaultStore();
            if (!store.isHome()) return;
            store.init();
            dev.skillmanager.mcp.GatewayConfig gw = dev.skillmanager.mcp.GatewayConfig.resolve(store, null);
            dev.skillmanager.lifecycle.SkillReconciler.reconcile(store, gw);
        } catch (Throwable ignored) {}
    }

    private static boolean isAgentContextRequested(SkillManagerCli root) {
        if (root != null && root.agentContext) return true;
        String env = System.getenv("SKILL_MANAGER_AGENT_CONTEXT");
        return env != null && (env.equals("1") || env.equalsIgnoreCase("true")
                || env.equalsIgnoreCase("yes"));
    }

    private static void tryPrintOutstandingErrors() {
        // The closing report-as-program runs through ConsoleProgramRenderer:
        // OutstandingError facts are emitted by LoadOutstandingErrors and
        // the renderer's onComplete prints the banner. No extra printer call
        // needed here.
        try {
            dev.skillmanager.store.SkillStore store = dev.skillmanager.store.SkillStore.defaultStore();
            // Same reason as tryReconcile: a home that does not exist holds
            // no outstanding errors, and asking is not worth creating one.
            if (!store.isHome()) return;
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
