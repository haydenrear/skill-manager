package com.hayden.testgraphsdk.sdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Subprocess helpers that produce a structured {@link ProcessRecord}
 * for each child a node spawns. The record carries argv, timestamps,
 * exit code, pid, captured-log path (relative to the run's report
 * dir), and any spawn-time error. Attach it to the result with
 * {@link NodeResult#process(ProcessRecord)} and the report renderer
 * can show per-subprocess sections without scraping stdout.
 *
 * <p>Captured log files live at
 * {@code <reportDir>/node-logs/<nodeId>.<label>.log} — inside
 * {@code build/validation-reports/<runId>/}, so any artifact uploader
 * that grabs the whole report tree picks them up automatically. The
 * file name bakes in the node id so a flat {@code grep -r} or file
 * browse immediately traces any log back to its origin node.
 *
 * <p>Never throws on spawn failure: if {@code pb.start()} blows up
 * (binary not found, IOException), we still return a
 * {@link ProcessRecord} with {@code pid=null}, {@code exitCode=-1},
 * and {@code error} populated. The node body's pass/fail logic stays
 * uniform — it never has to decide between "exception escaped" and
 * "child exited non-zero".
 */
public final class Procs {

    private Procs() {}

    /**
     * Resolve (and create the parent dir of) the log file path for one
     * subprocess within the current node's execution. Callers pass a
     * short local label (e.g. {@code "publish"}); the on-disk name is
     * prefixed with {@link NodeContext#nodeId()} (e.g.
     * {@code hello.published.publish.log}).
     */
    public static Path logFile(NodeContext ctx, String label) throws IOException {
        Path dir = ctx.reportDir().resolve("node-logs");
        Files.createDirectories(dir);
        return dir.resolve(ctx.nodeId() + "." + label + ".log");
    }

    /**
     * Spawn {@code pb} with stdout+stderr captured to
     * {@link #logFile(NodeContext, String)}, wait for it, return a
     * fully-populated {@link ProcessRecord}. Replaces the opaque
     * {@code pb.inheritIO(); pb.start().waitFor();} pattern that lost
     * subprocess output as soon as the live console scrolled.
     *
     * <p>The returned record's {@code logPath} is relative to the
     * run's report dir (so the envelope is reproducible across
     * machines and meaningful inside CI artifact bundles), even though
     * the actual {@link Path} we redirected output to is absolute.
     */
    public static ProcessRecord run(NodeContext ctx, String label, ProcessBuilder pb) {
        List<String> command = new ArrayList<>(pb.command());
        Path log;
        try {
            log = logFile(ctx, label);
        } catch (IOException e) {
            return new ProcessRecord(label, command, null, null,
                    -1, null, null, "could not allocate log file: " + e.getMessage());
        }
        String relativeLog = relativeToReport(ctx, log);

        Instant startedAt = Instant.now();
        Process proc;
        try {
            pb.redirectErrorStream(true).redirectOutput(log.toFile());
            proc = pb.start();
        } catch (IOException e) {
            return new ProcessRecord(label, command, startedAt, Instant.now(),
                    -1, null, relativeLog, "spawn failed: " + e.getMessage());
        }

        long pid = proc.pid();
        int exit;
        try {
            exit = proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessRecord(label, command, startedAt, Instant.now(),
                    -1, pid, relativeLog, "interrupted: " + e.getMessage());
        }
        return new ProcessRecord(
                label, command, startedAt, Instant.now(),
                exit, pid, relativeLog, null);
    }

    /**
     * Render {@code logPath} as a path relative to {@code ctx.reportDir()}
     * if possible, falling back to the absolute string. Keeps the
     * envelope JSON portable across machines without a manual rewrite
     * step in the executor.
     *
     * <p>Exposed for nodes that need to spawn a subprocess with custom
     * IO handling (e.g. reading stdout for parsing) and then
     * hand-construct a {@link ProcessRecord}; they can call
     * {@link #logFile(NodeContext, String)} for the on-disk path and
     * this helper for the canonical relative form to put in the
     * record.
     */
    public static String relativeToReport(NodeContext ctx, Path logPath) {
        try {
            return ctx.reportDir().toAbsolutePath()
                    .relativize(logPath.toAbsolutePath())
                    .toString();
        } catch (IllegalArgumentException e) {
            return logPath.toString();
        }
    }
}
