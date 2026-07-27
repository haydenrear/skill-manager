import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The one way a node starts a server that must outlive it.
 *
 * <p><b>Why this exists.</b> The SDK supervisor enforces a process contract: a
 * node may not leave live descendants. It checks two independent things — the
 * process group ({@code OrphanedGroupReaped}) and process <i>parentage</i> via
 * {@code ProcessHandle.descendants()} ("node launcher exited with live
 * descendants"). A plain {@code ProcessBuilder.start()} fails both, so the
 * supervisor reaps the server and errors the node.
 *
 * <p>That is why {@code run.py --all} could not get past {@code smoke}: several
 * TESTBED nodes started long-lived servers with a raw {@code start()}.
 * {@code gateway.up} never tripped it, but only incidentally — it runs a
 * foreground CLI which daemonizes itself, so the daemon lands outside the group
 * and outside the process tree. Two nodes, same {@code Kind.TESTBED}, same
 * intent, two different idioms, one of which happened to work. Knowing one told
 * you nothing about the other, which is exactly the kind of thing that should
 * not be load-bearing knowledge.
 *
 * <p>So: a node that needs a surviving server calls {@link #spawn}. A node that
 * needs a command to finish keeps using {@code Procs.run}. That rule is
 * predictable from either side.
 *
 * <p><b>How.</b> A real double fork, which is what actual daemonization
 * requires — {@code setsid} alone leaves the process a descendant, and
 * {@code setsid(1)} does not exist on macOS anyway. {@code python3} is the
 * portable route to {@code fork(2)}/{@code setsid(2)}. The wrapper exits
 * immediately so the node leaves nothing behind; the grandchild records its own
 * pid, because the wrapper's pid dies with it and would leave a stale value for
 * a teardown node to signal.
 */
final class Daemons {

    private Daemons() {}

    /**
     * Starts {@code pb} as a detached daemon and returns its pid.
     *
     * <p>{@code pb}'s command, environment and output redirection are all
     * honoured — the redirects survive {@code execvp}. On failure this throws
     * rather than returning a sentinel, so a caller cannot accidentally treat a
     * dead server as started.
     *
     * @param pidFile where the daemon records its own pid; the caller keeps
     *                using this for teardown
     */
    static long spawn(ProcessBuilder pb, Path pidFile, Duration timeout)
            throws IOException, InterruptedException {
        Files.deleteIfExists(pidFile);

        String daemonize = String.join("\n",
                "import os, sys",
                "pid_file = sys.argv[1]",
                "cmd = sys.argv[2:]",
                "if os.fork() > 0:",
                "    os._exit(0)",
                "os.setsid()",
                "if os.fork() > 0:",
                "    os._exit(0)",
                "with open(pid_file, 'w') as fh:",
                "    fh.write(str(os.getpid()))",
                "os.execvp(cmd[0], cmd)");

        List<String> wrapped = new ArrayList<>(
                List.of("python3", "-c", daemonize, pidFile.toString()));
        wrapped.addAll(pb.command());
        pb.command(wrapped);

        Process wrapper = pb.start();
        if (!wrapper.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            wrapper.destroyForcibly();
            throw new IOException("daemonize wrapper did not exit within " + timeout);
        }
        if (wrapper.exitValue() != 0) {
            throw new IOException("daemonize wrapper exited " + wrapper.exitValue());
        }

        long pid = awaitPid(pidFile, timeout);
        if (pid < 0) throw new IOException("daemon recorded no pid at " + pidFile);
        return pid;
    }

    /** Signals a daemon started by {@link #spawn}; there is no Process handle. */
    static void stop(long pid) {
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
    }

    private static long awaitPid(Path pidFile, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                String raw = Files.exists(pidFile) ? Files.readString(pidFile).trim() : "";
                if (!raw.isEmpty()) return Long.parseLong(raw);
            } catch (IOException | NumberFormatException retry) {
                // Not written yet, or caught mid-write. Retry.
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return -1;
    }
}
