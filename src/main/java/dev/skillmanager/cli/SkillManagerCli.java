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
    public static final String RELEASE = "skill-manager 0.23.0";
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
            // After the footer has been printed (completeExecution), and on
            // every path including the ones that never reached it.
            dev.skillmanager.util.RunLog.close();
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
            // Armed as early as the parse allows, so the log holds everything
            // from here on. Lazy: no file exists until something is written.
            dev.skillmanager.util.RunLog.open(CliAgentContext.commandPath(pr));
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
        // relay them verbatim to the user. Anything that carries a message
        // of its own is printed AS that message — see
        // {@link #printFailure}; only a failure with nothing to say still
        // prints a trace.
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
        // Everything else, which is where the REFUSALS live. This used to
        // `throw ex` into picocli's default handler.
        return completeExecution(rootCommand(pr), pr, printFailure(ex, c, pr));
    }

    /**
     * The fall-through printer: <b>a refusal is a message, not a crash.</b>
     *
     * <h2>What this replaced, and why it was wrong</h2>
     *
     * <p>Every exception that was not one of the four banner types above used
     * to be rethrown into picocli's default execution-exception handler, which
     * prints the whole stack. Measured on the onboarding walk:
     *
     * <pre>
     * $ skill-manager uninstall ob-alpha --dry-run
     * java.io.IOException: unit ob-alpha is claimed by skill project(s): … (remove the project lock/binding first)
     *     at dev.skillmanager.app.RemoveUseCase.buildProgram(RemoveUseCase.java:77)
     *     at dev.skillmanager.commands.UninstallCommand.call(UninstallCommand.java:62)
     *     … 13 more frames of picocli internals …
     * </pre>
     *
     * <p>33 frames across two refusals in one graph run. The first line is a
     * good refusal — it names the unit, the claimant and the remedy — and the
     * fifteen lines under it are the reason nobody reads it. These are not
     * crashes: the store was consulted, a rule said no, and the command
     * declined. An onboarding agent hits the first of them within its first
     * few commands.
     *
     * <h2>Why the fix is here rather than at the throw sites</h2>
     *
     * <p>A marker exception type would only cover the sites someone remembered
     * to convert, and this project has now shipped four defects of the shape
     * "an enumeration that was correct for the cases it imagined". It would
     * also miss the third instance the walk found, which is not ours to mark:
     * {@code project register} on a wrong-shaped {@code skill-project.toml}
     * surfaces {@code org.tomlj.TomlInvalidTypeException}, 18 frames, thrown
     * from a library. The structural property that separates the two kinds is
     * not who threw — it is <b>whether the failure has anything to say</b>. A
     * refusal carries a message written for a person; a genuine bug (NPE,
     * IndexOutOfBounds, an assertion trip) carries none, and for those the
     * trace is still the only diagnostic and is still printed unconditionally.
     *
     * <p>The trace is never lost either way: {@code --verbose} prints it, and
     * the message says so.
     *
     * @return the subcommand's {@code exitCodeOnExecutionException} — 1 by
     *         default, i.e. the same non-zero the trace path produced. This
     *         changes what a refusal PRINTS, never what it returns.
     */
    static int printFailure(Exception ex, CommandLine c, CommandLine.ParseResult pr) {
        int rc = c == null ? 1 : c.getCommandSpec().exitCodeOnExecutionException();
        String message = describe(ex);
        if (message == null) {
            // Nothing to say: this is the shape where the trace IS the
            // message. Printed whether or not --verbose was passed, because
            // an operator who cannot see it has nothing at all.
            Log.error("%s failed: %s", CliAgentContext.commandPath(pr), ex.getClass().getName());
            ex.printStackTrace(System.err);
            return rc;
        }
        Log.error("%s", message);
        if (Log.isVerbose()) {
            ex.printStackTrace(System.err);
        } else {
            System.err.println("  (re-run with --verbose for the stack trace)");
        }
        return rc;
    }

    /**
     * The human half of a throwable chain, or {@code null} when it has none.
     *
     * <p>Walks causes because the message that names the actual problem is
     * frequently one or two wraps down — an {@code UncheckedIOException} over
     * the {@code IOException} that carries the refusal, a picocli
     * {@code ExecutionException} over ours. Bounded at three links: past that
     * the chain is describing plumbing rather than the failure, and an
     * unbounded walk over a self-referential cause does not terminate.
     *
     * <p>A link whose message is already quoted by one above it is dropped, so
     * the common wrap-with-the-same-text case reads as one sentence.
     */
    private static String describe(Throwable top) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        Throwable seen = null;
        for (Throwable t = top; t != null && t != seen && parts.size() < 3; t = t.getCause()) {
            seen = t;
            String m = t.getMessage();
            if (m == null || m.isBlank()) continue;
            m = m.strip();
            boolean alreadySaid = false;
            for (String p : parts) {
                if (p.contains(m)) { alreadySaid = true; break; }
            }
            if (!alreadySaid) parts.add(m);
        }
        return parts.isEmpty() ? null : String.join(" — caused by: ", parts);
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
        nameTheLog(pr);
        return rc;
    }

    /**
     * The one line the quiet console spends on the detail it withheld.
     *
     * <p>Printed only when something was actually demoted — a command with
     * nothing behind its verdict advertises no file, because a path to an empty
     * log is a line that costs and says nothing.
     *
     * <p><b>stderr, and never under {@code --json}.</b> This is metadata about
     * the run, not part of its result. Every machine-readable surface this CLI
     * has — {@code --json}, {@code exec --print-env}, the MCP results block —
     * is on stdout, so keeping the footer off stdout means no consumer can be
     * broken by it, including ones nobody re-audited. The {@code --json}
     * suppression is belt and braces on top of that.
     */
    private static void nameTheLog(CommandLine.ParseResult pr) {
        if (dev.skillmanager.util.RunLog.demoted() <= 0) return;
        java.nio.file.Path log = dev.skillmanager.util.RunLog.path();
        if (log == null) return;
        if (jsonRequested(pr)) return;
        System.err.println("  log: " + log);
    }

    /** Whether {@code --json} was matched anywhere in the parsed command chain. */
    private static boolean jsonRequested(CommandLine.ParseResult pr) {
        try {
            for (CommandLine.ParseResult p = pr; p != null; p = p.subcommand()) {
                if (p.hasMatchedOption("--json")) return true;
            }
        } catch (RuntimeException ignored) {
            // A command with no --json option at all: not a JSON consumer.
        }
        return false;
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
