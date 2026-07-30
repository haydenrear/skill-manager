package dev.skillmanager.commands;

import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.launch.LaunchEnv;
import dev.skillmanager.launch.SeatbeltRefusedException;
import dev.skillmanager.launch.SeatbeltSandbox;
import dev.skillmanager.launch.UnredirectedLaunchException;
import dev.skillmanager.lifecycle.SkillReconciler;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.store.DriftGate;
import dev.skillmanager.store.NotAHomeException;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager exec <command> [args…]} — run something with a Skill
 * Manager home fully bound to it.
 *
 * <p>This is the one place the launch contract is applied, and the generated
 * {@code bin/launch} shims are thin wrappers over it. What "fully bound" means
 * is {@link LaunchEnv}: the descriptor's env block exported, the home's own
 * {@code bin/} ahead of everything on {@code PATH}, every other home's
 * {@code bin/} removed from {@code PATH}, and a refusal rather than a warning
 * when the result would still leave Claude reading the operator's global config
 * directory.
 *
 * <p>It is also the one place the <em>kernel</em> boundary is applied. A home
 * that opted in ({@code SKILL_MANAGER_SANDBOX=1} plus {@code <home>/launch.sb},
 * both written by {@code home shims --sandbox}) has its process tree confined
 * to its worktree by {@code /usr/bin/sandbox-exec}. The two mechanisms are kept
 * together deliberately: {@link LaunchEnv#requireClaudeRedirected()} is the
 * good error message, {@link dev.skillmanager.launch.SeatbeltSandbox} is the
 * guarantee — the env check knows <em>why</em>, the sandbox knows <em>that</em>.
 *
 * <p>Pass {@code --} before the command when it takes options of its own:
 * {@code skill-manager exec -- claude --version}.
 */
@Command(name = "exec",
        description = "Run a command bound to a Skill Manager home: export the home's env, put "
                + "its bin/ first on PATH, and refuse a launch that would still read the "
                + "global agent config. Use `--` before a command with its own options.")
public final class ExecCommand implements Callable<Integer> {

    @Option(names = "--home",
            description = "Skill Manager home to bind. Defaults to $SKILL_MANAGER_HOME.")
    Path home;

    @Option(names = "--home-root",
            description = "Directory holding .claude/.codex/.gemini beside the store. Defaults to "
                    + "whatever the home's descriptor records.")
    Path homeRoot;

    @Option(names = "--print-env",
            description = "Print the launch environment (including the computed PATH) and exit "
                    + "without running anything.")
    boolean printEnv;

    @Option(names = "--no-reconcile",
            description = "Skip refreshing the home's agent symlinks before launching.")
    boolean noReconcile;

    @Option(names = "--ack-drift",
            description = "Acknowledge (and print) any unread change to this home instead of "
                    + "refusing the launch.")
    boolean ackDrift;

    @Option(names = "--init",
            description = "Lay out the home first if it is not one yet. Without this a --home "
                    + "that is not a home is refused rather than created.")
    boolean init;

    @Parameters(arity = "0..*", paramLabel = "COMMAND",
            description = "Command and arguments to run. Required unless --print-env is given.")
    List<String> commandLine = new ArrayList<>();

    private final SkillStore injectedStore;
    private final String inheritedPath;

    public ExecCommand() { this(null, null); }

    /**
     * Test seam. {@code inheritedPath} is injected because a JVM cannot mutate
     * its own environment, and the {@code PATH} sanitizer is the part of this
     * command most worth testing.
     */
    public ExecCommand(SkillStore injectedStore, String inheritedPath) {
        this.injectedStore = injectedStore;
        this.inheritedPath = inheritedPath;
    }

    @Override
    public Integer call() throws Exception {
        SkillStore store = injectedStore != null
                ? injectedStore
                : home != null
                    ? new SkillStore(home.toAbsolutePath().normalize())
                    : SkillStore.defaultStore();
        String path = inheritedPath != null ? inheritedPath : System.getenv("PATH");

        // #33's shape, in the command #33 did not cover. `exec --home <a path
        // that does not exist>` used to SCAFFOLD an eleven-entry home there and
        // exit 0 — LaunchEnv.of(…, bootstrap = true) calls store.init() and
        // writes a descriptor — and `exec` is what every bin/launch shim calls.
        // So one mistyped --home silently created a home plus three agent
        // directories at the typo, and then launched an agent bound to it. The
        // refusal has to come before LaunchEnv.of for exactly that reason.
        try {
            if (init) {
                store.init();
            } else {
                NotAHomeException.require(store.root(), "exec --home");
            }
        } catch (NotAHomeException notAHome) {
            Log.error("%s", notAHome.getMessage());
            return NotAHomeException.EXIT_CODE;
        }

        LaunchEnv launch = LaunchEnv.of(store, homeRoot, path, true);

        // Order matters: the refusal must come before anything is created,
        // written, or spawned. A gate that fires after the reconcile has
        // already re-pointed symlinks has done the damage it exists to
        // prevent.
        try {
            launch.requireClaudeRedirected();
        } catch (UnredirectedLaunchException refused) {
            Log.error("%s", refused.getMessage());
            return UnredirectedLaunchException.EXIT_CODE;
        }

        if (printEnv) {
            launch.exportedEnv().forEach((k, v) -> System.out.println(k + "=" + v));
            // The sandbox decision is part of the launch environment, so
            // --print-env has to be able to answer "would this launch be
            // confined, and to what". A refusal prints as a refusal here for
            // the same reason it does on the launch path: an operator who asks
            // what the environment is must not be told "fine" when a launch
            // would stop.
            try {
                SeatbeltSandbox.planFor(store, launch, System.getenv()).ifPresent(plan -> {
                    System.out.println(SeatbeltSandbox.ACTIVE_VAR + "=" + plan.profile());
                    plan.parameters().forEach(
                            (k, v) -> System.out.println("SKILL_MANAGER_SEATBELT_" + k + "=" + v));
                    plan.env().forEach((k, v) -> {
                        if (!SeatbeltSandbox.ACTIVE_VAR.equals(k)) System.out.println(k + "=" + v);
                    });
                });
            } catch (SeatbeltRefusedException refused) {
                Log.error("%s", refused.getMessage());
                return SeatbeltRefusedException.EXIT_CODE;
            }
            return 0;
        }

        // Change awareness. A sync that moved a skill the agent is following has
        // invalidated whatever it decided on the strength of that skill, and a
        // launch is the only boundary at which that can still be said. Refused
        // rather than warned for the same reason as the Claude check: the failure
        // is invisible from inside the session.
        DriftGate drift = DriftGate.pending(store).orElse(null);
        if (drift != null && !ackDrift) {
            Log.error("refusing to launch: %s changed and the change has not been read.",
                    store.root());
            for (String line : drift.report().render()) Log.error("  %s", line);
            Log.error("  Read it with `skill-manager home drift --show`, then clear the gate with");
            Log.error("  `skill-manager home drift --ack` (or launch with --ack-drift).");
            return DriftGate.EXIT_CODE;
        }
        if (drift != null) {
            DriftGate.acknowledge(store);
            Log.warn("acknowledged drift in %s before launching:", store.root());
            for (String line : drift.report().render()) Log.warn("  %s", line);
        }
        if (commandLine.isEmpty()) {
            Log.error("exec needs a command to run (or --print-env). "
                    + "Example: skill-manager exec -- claude --version");
            return 2;
        }

        if (!noReconcile) refreshHome(store, launch);

        String command = commandLine.get(0);
        Path binary = launch.resolveBinary(command).orElse(null);
        if (binary == null) {
            Log.error("`%s` is not on the launch PATH for the home at %s.", command, store.root());
            Log.error("  The home's own bin/ comes first and other homes' bin/ are excluded on "
                    + "purpose, so a tool installed only in another home is deliberately not "
                    + "reachable here. Install it into this home (`skill-manager install …`) "
                    + "or pass an absolute path.");
            return 127;
        }

        List<String> argv = new ArrayList<>();
        argv.add(binary.toString());
        argv.addAll(commandLine.subList(1, commandLine.size()));

        // THE SEAM. Every bin/launch shim funnels through `skill-manager exec
        // --home <home> --`, and every launch spawns exactly this one process,
        // so wrapping here confines the harness and every grandchild it will
        // ever spawn — a child cannot loosen an inherited sandbox. See
        // SeatbeltSandbox for what that does and does not guarantee: it is a
        // filesystem boundary against accidents, not containment.
        SeatbeltSandbox.Plan sandbox;
        try {
            sandbox = SeatbeltSandbox.planFor(store, launch, System.getenv()).orElse(null);
        } catch (SeatbeltRefusedException refused) {
            Log.error("%s", refused.getMessage());
            return SeatbeltRefusedException.EXIT_CODE;
        }

        ProcessBuilder pb = new ProcessBuilder(sandbox == null ? argv : sandbox.wrap(argv))
                .inheritIO();
        pb.environment().putAll(launch.exportedEnv());
        if (sandbox != null) pb.environment().putAll(sandbox.env());
        Process process = pb.start();
        return process.waitFor();
    }

    /**
     * Refresh the launch home's agent symlinks, so the harness sees the units
     * this home currently holds rather than whatever was projected last time.
     *
     * <p>Two guards. A frozen home is skipped entirely — reconciliation writes.
     * And the {@link AgentHomes} overrides are set from the <em>launch</em>
     * environment first: without them the reconcile would resolve the agent
     * config dirs from this JVM's ambient environment and project the launch
     * home's skills into whatever home the CLI happens to be running under,
     * which is the exact leak the launch is being set up to prevent.
     */
    private static void refreshHome(SkillStore store, LaunchEnv launch) {
        try {
            if (HomePolicy.load(store).frozen()) return;
        } catch (IOException unreadablePolicy) {
            Log.warn("could not read the home policy for %s (%s) — skipping the symlink refresh",
                    store.root(), unreadablePolicy.getMessage());
            return;
        }
        Map<String, String> env = launch.env();
        try {
            applyOverride(env, AgentHomes.CLAUDE_CONFIG_DIR);
            applyOverride(env, AgentHomes.CLAUDE_HOME);
            applyOverride(env, AgentHomes.CODEX_HOME);
            applyOverride(env, AgentHomes.GEMINI_HOME);
            store.init();
            GatewayConfig gateway = GatewayConfig.resolve(store, null);
            SkillReconciler.reconcile(store, gateway);
        } catch (IOException | RuntimeException e) {
            // A stale projection is a degraded launch, not a dangerous one; the
            // dangerous cases (wrong home, frozen home) are handled above.
            Log.warn("could not refresh the agent symlinks for %s (%s)", store.root(), e.getMessage());
        } finally {
            AgentHomes.clearOverrides();
        }
    }

    private static void applyOverride(Map<String, String> env, String key) {
        String value = env.get(key);
        AgentHomes.setOverride(key, value == null || value.isBlank() ? null : Path.of(value));
    }
}
