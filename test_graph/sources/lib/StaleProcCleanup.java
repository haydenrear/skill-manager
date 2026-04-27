//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.Procs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort cleanup of stale registry / gateway processes that leaked
 * from a previous (cancelled, crashed, mid-failure) test_graph run.
 *
 * <p>Per-run state lives under a randomly-named tmpdir, and the test
 * fixtures' {@code servers.down} only kills processes recorded in the
 * <em>current</em> run's pidfile. If the previous run died before
 * teardown — common when a fixture node fails — the previous python /
 * jbang processes get reparented to PID 1 and stick around forever.
 * Run them and you eventually have a stack of zombie gateways listening
 * on ephemeral ports, contending with the next run for sockets.
 *
 * <p>Strategy: walk every process this user can see via JDK-native
 * {@link ProcessHandle#allProcesses()}, match its full command line
 * against the supplied patterns, and SIGTERM (then SIGKILL on a 5s
 * deadline) any matches. JDK-native means no fork/{@code ps} on macOS or
 * Linux — the command line is read directly from the OS.
 *
 * <p>If any pidfile is supplied via {@link #removeStalePidFiles}, the
 * file is also deleted so the per-run scaffold writes fresh.
 */
public final class StaleProcCleanup {

    private StaleProcCleanup() {}

    /**
     * Send SIGTERM to every running process whose command-line contains
     * any of {@code patterns}, then SIGKILL after a 5 s grace period.
     *
     * <p>Skips this JVM and any of its ancestors (so we don't kill the
     * gradle daemon that's running us).
     *
     * @return ids of processes that were signaled.
     */
    public static List<Long> killByCommandLineMatch(NodeContext ctx, String label, String... patterns) {
        long self = ProcessHandle.current().pid();
        java.util.Set<Long> ancestors = new java.util.HashSet<>();
        ancestors.add(self);
        ProcessHandle.current().parent().ifPresent(p -> {
            ancestors.add(p.pid());
            p.parent().ifPresent(pp -> ancestors.add(pp.pid()));
        });

        List<Long> killed = new ArrayList<>();
        StringBuilder log = new StringBuilder();
        log.append("scanning processes for stale ").append(label)
                .append(" (patterns: ").append(String.join(", ", patterns)).append(")\n");

        ProcessHandle.allProcesses().forEach(ph -> {
            if (ancestors.contains(ph.pid())) return;
            String cmd = ph.info().commandLine().orElse("");
            if (cmd.isEmpty()) return;
            boolean match = false;
            for (String p : patterns) {
                if (cmd.contains(p)) { match = true; break; }
            }
            if (!match) return;

            log.append("  killing pid=").append(ph.pid())
                    .append(" cmd=").append(truncate(cmd, 200)).append("\n");
            ph.destroy();
            try {
                ph.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                if (ph.isAlive()) {
                    log.append("    SIGTERM timed out, SIGKILL pid=").append(ph.pid()).append("\n");
                    ph.destroyForcibly();
                }
            }
            killed.add(ph.pid());
        });

        if (killed.isEmpty()) {
            log.append("  no stale processes matched\n");
        } else {
            log.append("  killed ").append(killed.size()).append(" stale process(es)\n");
        }
        writeLog(ctx, label, log.toString());
        return killed;
    }

    /**
     * Delete pid files left behind by a previous run. Best-effort; missing
     * files are ignored.
     */
    public static void removeStalePidFiles(NodeContext ctx, String label, Path... pidFiles) {
        StringBuilder log = new StringBuilder();
        for (Path p : pidFiles) {
            if (p == null) continue;
            try {
                if (Files.deleteIfExists(p)) {
                    log.append("  removed stale pidfile ").append(p).append("\n");
                }
            } catch (IOException e) {
                log.append("  failed to remove ").append(p).append(": ").append(e.getMessage()).append("\n");
            }
        }
        if (log.length() > 0) writeLog(ctx, label, log.toString());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static void writeLog(NodeContext ctx, String label, String body) {
        try {
            Path log = Procs.logFile(ctx, label);
            Files.writeString(log, body);
        } catch (IOException ignored) {}
    }
}
