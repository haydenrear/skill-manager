package com.hayden.testgraphsdk.sdk;

import java.time.Instant;
import java.util.List;

/**
 * Structured record of one subprocess a node spawned. Attached to
 * {@link NodeResult#process(ProcessRecord)} so the executor and the
 * report renderer can show per-subprocess details (exit code, log
 * link, duration) without having to scrape the node's stdout.
 *
 * <p>Lives in the SDK because both Java and Python node scripts need
 * to construct one; the JSON shape is the contract that build-logic
 * deserializes back into the report.
 *
 * <p>{@code logPath} is stored as a string (not {@link java.nio.file.Path})
 * so the envelope JSON can carry a relative path without forcing a
 * particular filesystem semantics. The convention is: relative to the
 * run's report dir, so it's reproducible across machines and meaningful
 * inside a CI artifact bundle.
 *
 * <p>If the spawn itself fails (binary not found, IOException at
 * {@code start()}), construct a record with {@code pid=null},
 * {@code exitCode=-1}, and a populated {@code error} string. Never
 * throw from the process helper — the node still gets a structured
 * record back so the failure flows through the same reporting path.
 */
public final class ProcessRecord {

    private final String label;
    private final List<String> command;
    private final Instant startedAt;
    private final Instant endedAt;
    private final int exitCode;
    private final Long pid;
    private final String logPath;
    private final String error;

    public ProcessRecord(
            String label,
            List<String> command,
            Instant startedAt,
            Instant endedAt,
            int exitCode,
            Long pid,
            String logPath,
            String error) {
        this.label = label;
        this.command = command == null ? List.of() : List.copyOf(command);
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.exitCode = exitCode;
        this.pid = pid;
        this.logPath = logPath;
        this.error = error;
    }

    public String label()           { return label; }
    public List<String> command()   { return command; }
    public Instant startedAt()      { return startedAt; }
    public Instant endedAt()        { return endedAt; }
    public int exitCode()           { return exitCode; }
    public Long pid()               { return pid; }
    public String logPath()         { return logPath; }
    public String error()           { return error; }

    /** Wall-clock duration in milliseconds, or -1 if either timestamp is missing. */
    public long durationMs() {
        if (startedAt == null || endedAt == null) return -1;
        return endedAt.toEpochMilli() - startedAt.toEpochMilli();
    }

    String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"label\":").append(Json.quote(label));
        sb.append(",\"command\":[");
        for (int i = 0; i < command.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Json.quote(command.get(i)));
        }
        sb.append(']');
        if (startedAt != null) {
            sb.append(",\"startedAt\":").append(Json.quote(startedAt.toString()));
        }
        if (endedAt != null) {
            sb.append(",\"endedAt\":").append(Json.quote(endedAt.toString()));
        }
        sb.append(",\"exitCode\":").append(exitCode);
        sb.append(",\"pid\":").append(pid == null ? "null" : pid.toString());
        sb.append(",\"log\":");
        sb.append(logPath == null ? "null" : Json.quote(logPath));
        sb.append(",\"error\":");
        sb.append(error == null ? "null" : Json.quote(error));
        sb.append('}');
        return sb.toString();
    }
}
