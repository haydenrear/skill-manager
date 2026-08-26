///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Teardown: stop the gateway (via CLI) and the registry + echo-http fixture
 * (via PID files they wrote). Per-graph "wait until X is done" edges are
 * added from the DSL via {@code .dependsOn(...)} — this script declares no
 * graph-specific dependencies so it can be reused across smoke,
 * hyper-experiments, and any future graph that brings up the same
 * registry/gateway pair.
 *
 * <p>The echo-http kill is best-effort: if the pid file isn't present (the
 * graph never spawned the fixture), {@link #killByPidFile} returns true and
 * the corresponding assertion passes trivially.
 */
public class ServersDown {
    static final NodeSpec SPEC = NodeSpec.of("servers.down")
            .kind(NodeSpec.Kind.EVIDENCE)
            .tags("teardown")
            .timeout("90s")
            .retries(2);

    /**
     * A JVM shutting down under SIGTERM on a loaded machine needs more than
     * five seconds; five was the old budget and it is what the graph kept
     * missing. The node timeout above is raised with it so the wait cannot
     * merely move the failure one level out.
     */
    private static final int GRACEFUL_STOP_SECONDS = 20;

    /** After SIGKILL, only the reap is being waited for. */
    private static final int FORCIBLE_STOP_SECONDS = 10;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) return NodeResult.fail("servers.down", "missing env.prepared context");

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(sm.toString(), "gateway", "down");
            SmEnv.apply(ctx, pb, home);
            ProcessRecord gatewayDownProc = Procs.run(ctx, "gateway-down", pb);
            boolean gatewayDown = gatewayDownProc.exitCode() == 0;

            boolean registryDown = killByPidFile(Path.of(home, "test-graph", "registry.pid"));
            boolean echoDown = killByPidFile(Path.of(home, "test-graph", "echo-http.pid"));

            return NodeResult.pass("servers.down")
                    .process(gatewayDownProc)
                    .assertion("gateway_down", gatewayDown)
                    .assertion("registry_down", registryDown)
                    .assertion("echo_fixture_down", echoDown);
        });
    }

    private static boolean killByPidFile(Path pidFile) {
        try {
            if (!Files.isRegularFile(pidFile)) return true;
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            boolean stopped = ProcessHandle.of(pid).map(ServersDown::stop).orElse(true);
            Files.deleteIfExists(pidFile);
            return stopped;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Stop one process and WAIT FOR IT TO BE GONE, rather than asking whether
     * it is gone the instant after asking it to leave.
     *
     * <p>The previous shape raced its own kill and lost, reproducibly, on a
     * loaded machine:
     *
     * <pre>
     *   h.destroy();                                  // SIGTERM
     *   try { h.onExit().get(5, SECONDS); }           // wait
     *   catch (Exception e) { h.destroyForcibly(); }  // SIGKILL
     *   return !h.isAlive();                          // sampled IMMEDIATELY
     * </pre>
     *
     * <p>{@code destroyForcibly} is a request, not a reaping. Nothing waited
     * after it, so {@code isAlive()} was read while SIGKILL was still in
     * flight and the assertion reported a failure to stop a process that did
     * stop. MEASURED 2026-08-21: {@code smoke} failed twice in a row at
     * {@code servers.down / registry_down} on a registry JVM (pid 70183) that
     * was CONFIRMED GONE afterwards — {@code ps -p 70183} empty, no
     * {@code SkillRegistryApp} left. Because Gradle stops the sweep at the
     * first failing task and {@code smoke} is the first graph, one lost race
     * here took the other 23 graphs with it.
     *
     * <p>Two changes, both about waiting: the graceful budget is a JVM
     * shutdown budget rather than a guess, and the forcible path waits too. A
     * process that has genuinely gone reports stopped either way.
     */
    private static boolean stop(ProcessHandle h) {
        h.destroy();
        if (awaitExit(h, GRACEFUL_STOP_SECONDS)) return true;
        h.destroyForcibly();
        // SIGKILL is delivered, not instantaneous. Wait for the reap.
        return awaitExit(h, FORCIBLE_STOP_SECONDS) || !h.isAlive();
    }

    private static boolean awaitExit(ProcessHandle h, int seconds) {
        try {
            h.onExit().get(seconds, java.util.concurrent.TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return !h.isAlive();
        }
    }
}
