package com.hayden.testgraphsdk.sdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Subprocess helpers that capture stdout+stderr per (node, label) so failing
 * CLI calls remain diagnosable after the run. Log files live at
 * {@code <reportDir>/node-logs/<nodeId>.<label>.log} — inside
 * {@code build/validation-reports/<runId>/}, so any artifact uploader that
 * grabs the whole report tree picks them up automatically. The file name
 * bakes in the node id so a flat {@code grep -r} or file browse immediately
 * traces any log back to its origin node, without relying on directory
 * context.
 */
public final class Procs {

    private Procs() {}

    /**
     * Resolve (and create the parent dir of) the log file path for one
     * subprocess within the current node's execution. Callers pass a short
     * local label (e.g. {@code "publish"}); the on-disk name is prefixed
     * with {@link NodeContext#nodeId()} (e.g. {@code hello.published.publish.log}).
     */
    public static Path logFile(NodeContext ctx, String label) throws IOException {
        Path dir = ctx.reportDir().resolve("node-logs");
        Files.createDirectories(dir);
        return dir.resolve(ctx.nodeId() + "." + label + ".log");
    }

    /**
     * Start {@code pb} with stdout+stderr merged into {@link #logFile}, wait
     * for it, return the exit code. Replaces the common but opaque pattern
     * {@code pb.inheritIO(); pb.start().waitFor();} — {@code inheritIO} routes
     * subprocess output to the parent (Gradle) console, where it's lost after
     * the live log scrolls.
     */
    public static int runLogged(NodeContext ctx, String label, ProcessBuilder pb)
            throws IOException, InterruptedException {
        Path log = logFile(ctx, label);
        pb.redirectErrorStream(true).redirectOutput(log.toFile());
        return pb.start().waitFor();
    }

    /**
     * Attach the subprocess log to {@code result} as an artifact pointer. On
     * non-zero {@code exitCode}, also tail the last {@code tailLines} lines
     * into {@code result.logs[]} so the envelope itself shows the cause
     * without requiring an artifact download.
     */
    public static NodeResult attach(NodeResult result, NodeContext ctx, String label,
                                    int exitCode, int tailLines) {
        try {
            Path log = logFile(ctx, label);
            result.artifact("log", log.toString());
            if (exitCode != 0 && Files.isRegularFile(log)) {
                List<String> all = Files.readAllLines(log);
                int from = Math.max(0, all.size() - tailLines);
                for (String line : all.subList(from, all.size())) {
                    result.log(line);
                }
            }
        } catch (IOException ignored) {
            // best-effort diagnostics — never fail the node for missing logs
        }
        return result;
    }
}
